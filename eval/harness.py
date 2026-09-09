#!/usr/bin/env python3
"""Eval-харнесс: гоняет сценарии агентом против каждой версии контракта и считает success rate.

Агентом выступает Claude Code в headless-режиме (`claude -p`), поэтому внешний
API-ключ не нужен — используется уже настроенная аутентификация. Каждый прогон это
отдельный процесс с чистым контекстом, то есть независимое испытание: агент не может
научиться формату фильтра на предыдущей попытке.

    python3 eval/harness.py                      все профили, 10 прогонов на сценарий
    python3 eval/harness.py --profiles v1 v1b    только две версии
    python3 eval/harness.py -n 3 --jobs 4        быстрый черновой прогон

Сырые данные каждого прогона (вызовы тулов с аргументами, финальный ответ, стоимость)
пишутся в eval/results/<таймстамп>.jsonl — из них снимаются скрины для доклада.
"""
import argparse
import concurrent.futures
import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "server-java", "target", "tracker-mcp.jar")
RESULTS_DIR = os.path.join(ROOT, "eval", "results")

PROFILES = ["v1", "v1err", "v1b", "v2"]

# Тулы обеих версий контракта: имена в v1/v1b и v2 не совпадают, поэтому разрешаем все.
ALLOWED_TOOLS = ",".join(
    "mcp__tracker__" + name
    for name in ["query_items", "create_item", "get_report",
                 "search_tasks", "create_task", "update_task", "get_task_report"]
)

RUN_TIMEOUT_SEC = 300


def today():
    return datetime.date.today()


def iso(days):
    return (today() + datetime.timedelta(days=days)).isoformat()


# --- Сценарии -------------------------------------------------------------
#
# Формулировки заданий одинаковы для всех профилей — меняется только контракт сервера.
# Требование к формату ответа («списком id», «одним числом») намеренно жёсткое:
# иначе проверка результата превращается в разбор свободного текста и сама начинает
# ошибаться. Проверяем то, что видит пользователь, плюс аргументы вызовов там,
# где важно именно как агент вызвал тул.


def check_overdue_alexey(run):
    """Успех: перечислены ровно просроченные задачи Алексея — T-1, T-2, T-3."""
    text = run["result"]
    wanted = ["T-1", "T-2", "T-3"]
    # T-4 — задача Алексея, но не просроченная; T-5 — просроченная, но чужая.
    unwanted = ["T-4", "T-5"]
    if not all(t in text for t in wanted):
        return False, "в ответе не все три задачи"
    if any(t in text for t in unwanted):
        return False, "в ответ попали лишние задачи"
    return True, ""


def check_create_date(run):
    """Успех: задача создана со сроком 2026-08-05.

    Срок задан со временем суток. Обе проверенные модели уверенно приводят к ISO
    любую чистую дату («завтра», «05.08.2026»), и ошибка формата не возникает вовсе;
    а вот время суток девать некуда — агент отправляет «2026-08-05T18:00», получает
    отказ, и дальше всё решает текст ошибки: сможет ли он понять нужный формат
    и исправиться сам. Поле в трекере хранит только дату, поэтому 18:00 отбрасывается.
    """
    wanted = "2026-08-05"
    for call in run["tool_calls"]:
        if call["name"] not in ("mcp__tracker__create_item", "mcp__tracker__create_task"):
            continue
        if call["input"].get("due_date") == wanted and not call["is_error"]:
            return True, ""
    attempts = [c["input"].get("due_date") for c in run["tool_calls"]
                if c["name"].endswith(("create_item", "create_task"))]
    if not attempts:
        return False, "тул создания не вызывался"
    return False, "срок не приведён к %s: %s" % (wanted, attempts)


def check_tasks_per_assignee(run):
    """Успех: верно названо число задач у каждого из трёх исполнителей.

    Сценарий на различимость похожих тулов: ответ целиком лежит в get_report,
    но взять его можно и перебором поиска по каждому имени. В v2 описания
    разводят тулы перекрёстными ссылками («For aggregated statistics use
    get_task_report instead»), в v1 — нет.
    """
    expected = {"Алексей": 4, "Мария": 2, "Иван": 2}
    got = {}
    for line in run["result"].splitlines():
        for name in expected:
            if name in line:
                digits = "".join(c if c.isdigit() else " " for c in line).split()
                if len(digits) == 1:
                    got[name] = int(digits[0])
    if got == expected:
        return True, ""
    return False, "ожидалось %s, получено %s" % (expected, got)


SCENARIOS = [
    {
        "id": "overdue-alexey",
        "prompt": "Найди все просроченные задачи Алексея. "
                  "Ответь только списком id, без пояснений.",
        "check": check_overdue_alexey,
    },
    {
        "id": "create-date",
        "prompt": "Создай в трекере задачу «Отчёт по инциденту» для Алексея "
                  "со сроком 5 августа 2026 к 18:00.",
        "check": check_create_date,
    },
    {
        "id": "tasks-per-person",
        "prompt": "Сколько задач в трекере у каждого исполнителя? "
                  "Ответь строками вида «Имя: число», без пояснений.",
        "check": check_tasks_per_assignee,
    },
]


# --- Запуск ---------------------------------------------------------------


def mcp_config(profile, directory):
    """Конфиг MCP под конкретный профиль; путь к jar подставляется, а не хранится в репозитории."""
    path = os.path.join(directory, "mcp-%s.json" % profile)
    config = {"mcpServers": {"tracker": {
        "command": "java",
        "args": ["-jar", JAR, "--spring.profiles.active=" + profile],
    }}}
    with open(path, "w") as f:
        json.dump(config, f)
    return path


def run_once(profile, scenario, model, config_path):
    """Один прогон: свежая сессия агента против сервера в заданном профиле."""
    command = [
        "claude", "-p", scenario["prompt"],
        "--mcp-config", config_path,
        "--strict-mcp-config",
        # Пользовательские настройки не грузим: включённые там плагины добавляют
        # десятки тулов, из-за чего хост переходит на отложенную загрузку схем,
        # и слабая модель начинает спотыкаться об неё, а не об наш контракт.
        "--setting-sources", "",
        # Из встроенных тулов оставляем только поиск по отложенным схемам — иначе
        # агент считает даты через Bash и уходит в сабагентов, и это попадает в метрику.
        "--tools", "ToolSearch",
        "--allowedTools", ALLOWED_TOOLS,
        "--model", model,
        "--output-format", "stream-json",
        "--verbose",
    ]
    started = datetime.datetime.now()
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=RUN_TIMEOUT_SEC, cwd=ROOT)
    except subprocess.TimeoutExpired:
        return {"profile": profile, "scenario": scenario["id"], "result": "",
                "tool_calls": [], "cost_usd": 0.0, "error": "таймаут %s с" % RUN_TIMEOUT_SEC,
                "started": started.isoformat()}

    run = parse_stream(completed.stdout)
    run.update(profile=profile, scenario=scenario["id"], started=started.isoformat())
    if run.get("error") is None and completed.returncode != 0:
        run["error"] = "claude завершился с кодом %s: %s" % (
            completed.returncode, completed.stderr.strip()[:300])
    return run


def parse_stream(stdout):
    """Разбор stream-json: вызовы тулов с аргументами, их результаты и финальный ответ."""
    calls = []
    pending = {}
    result_text = ""
    cost = 0.0
    error = None

    for line in stdout.splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue

        kind = message.get("type")
        if kind == "assistant":
            for block in message.get("message", {}).get("content", []):
                if block.get("type") == "tool_use":
                    call = {"name": block.get("name", ""), "input": block.get("input", {}),
                            "is_error": False}
                    calls.append(call)
                    pending[block.get("id")] = call
        elif kind == "user":
            content = message.get("message", {}).get("content") or []
            for block in content:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    call = pending.get(block.get("tool_use_id"))
                    if call is not None:
                        call["is_error"] = bool(block.get("is_error"))
                        call["result"] = str(block.get("content"))[:500]
        elif kind == "result":
            result_text = message.get("result") or ""
            cost = message.get("total_cost_usd") or 0.0
            if message.get("subtype") != "success":
                error = "агент завершился со статусом %s" % message.get("subtype")

    # Обращения агента к служебным тулам хоста в метрику контракта не идут.
    calls = [c for c in calls if c["name"].startswith("mcp__tracker__")]
    return {"result": result_text, "tool_calls": calls, "cost_usd": cost, "error": error}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profiles", nargs="+", default=PROFILES, choices=PROFILES)
    parser.add_argument("-n", "--runs", type=int, default=10,
                        help="прогонов на сценарий (по умолчанию 10)")
    parser.add_argument("--model", default="sonnet")
    parser.add_argument("--jobs", type=int, default=3, help="параллельных прогонов")
    parser.add_argument("--scenarios", nargs="+", default=[s["id"] for s in SCENARIOS])
    args = parser.parse_args()

    if not os.path.exists(JAR):
        sys.exit("нет собранного сервера: %s\nсоберите его: cd server-java && mvn package" % JAR)
    if shutil.which("claude") is None:
        sys.exit("в PATH нет claude — харнесс запускает агента через него")

    scenarios = [s for s in SCENARIOS if s["id"] in args.scenarios]
    os.makedirs(RESULTS_DIR, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = os.path.join(RESULTS_DIR, "%s-%s.jsonl" % (stamp, args.model))

    tasks = [(profile, scenario)
             for profile in args.profiles
             for scenario in scenarios
             for _ in range(args.runs)]

    print("Профили: %s · сценарии: %d · прогонов на сценарий: %d · модель: %s" % (
        ", ".join(args.profiles), len(scenarios), args.runs, args.model))
    print("Всего прогонов: %d, параллельно: %d\n" % (len(tasks), args.jobs))

    lock = threading.Lock()
    done = [0]
    outcomes = []

    with tempfile.TemporaryDirectory() as tmp:
        configs = {p: mcp_config(p, tmp) for p in args.profiles}

        def work(task):
            profile, scenario = task
            run = run_once(profile, scenario, args.model, configs[profile])
            # Сбой запуска — это не провал сценария. Если агент не стартовал
            # (недоступна модель, упал хост, таймаут), прогон не даёт информации
            # о контракте, и засчитывать его как FAIL значит портить метрику.
            if run.get("error"):
                run["passed"], run["note"], run["broken"] = False, run["error"], True
            else:
                passed, note = scenario["check"](run)
                run["passed"], run["note"], run["broken"] = passed, note, False

            with lock:
                done[0] += 1
                outcomes.append(run)
                with open(log_path, "a") as f:
                    f.write(json.dumps(run, ensure_ascii=False) + "\n")
                print("[%3d/%3d] %-4s %-16s %s %s" % (
                    done[0], len(tasks), profile, scenario["id"],
                    "OK  " if passed else "FAIL", note[:60]))
            return run

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            list(pool.map(work, tasks))

    report(outcomes, args.profiles, scenarios, args.runs)
    print("\nСырые прогоны: %s" % log_path)


def report(outcomes, profiles, scenarios, runs):
    print("\n" + "=" * 64)
    print("SUCCESS RATE (успехов из %d)" % runs)
    print("=" * 64)

    print("%-16s" % "сценарий" + "".join("%8s" % p for p in profiles))
    print("-" * 64)

    for scenario in scenarios:
        row = "%-16s" % scenario["id"]
        calls_row = "%-16s" % "  вызовов"
        for profile in profiles:
            matching = [o for o in outcomes
                        if o["profile"] == profile and o["scenario"] == scenario["id"]
                        and not o.get("broken")]
            passed = [o for o in matching if o["passed"]]
            row += "%8s" % ("%d/%d" % (len(passed), len(matching)))
            # Цена успеха: сколько обращений к серверу понадобилось там, где сценарий прошёл.
            # На сильной модели плохой контракт не роняет сценарий, а дорожает — видно здесь.
            if passed:
                average = sum(len(o["tool_calls"]) for o in passed) / len(passed)
                calls_row += "%8s" % ("%.1f" % average)
            else:
                calls_row += "%8s" % "—"
        print(row)
        print(calls_row)

    print("-" * 64)
    total_row = "%-16s" % "ИТОГО"
    for profile in profiles:
        matching = [o for o in outcomes if o["profile"] == profile and not o.get("broken")]
        passed = sum(1 for o in matching if o["passed"])
        share = 100.0 * passed / len(matching) if matching else 0.0
        total_row += "%8s" % ("%d%%" % round(share) if matching else "—")
    print(total_row)

    print("\nСтоимость прогона: $%.2f" % sum(o.get("cost_usd") or 0.0 for o in outcomes))

    broken = [o for o in outcomes if o.get("broken")]
    if broken:
        print("\nНЕ СОСТОЯЛОСЬ: %d из %d прогонов — агент не запустился, в метрику не вошли."
              % (len(broken), len(outcomes)))
        for reason in sorted({o["note"][:90] for o in broken}):
            print("  %s" % reason)
        print("Таблица построена на оставшихся прогонах; при большом числе сбоев перезапустите.")


if __name__ == "__main__":
    sys.exit(main())
