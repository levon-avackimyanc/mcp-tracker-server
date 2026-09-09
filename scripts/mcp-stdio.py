#!/usr/bin/env python3
"""Минимальный stdio-клиент MCP: поднимает сервер, выполняет запрос и печатает ответ.

Нужен, чтобы посмотреть на контракт и на ответы тулов ровно так, как их видит агент,
без подключения к полноценному хосту.

    python3 scripts/mcp-stdio.py v2 tools/list
    python3 scripts/mcp-stdio.py v1 tools/call query_items '{"filter": "просроченные задачи Алексея"}'
    python3 scripts/mcp-stdio.py v2 tools/call create_task '{"title": "Отчёт", "due_date": "завтра"}'
"""
import json
import os
import subprocess
import sys

PROTOCOL_VERSION = "2025-06-18"
JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "target", "tracker-mcp.jar")


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2

    profile, method = sys.argv[1], sys.argv[2]
    if method == "tools/call":
        if len(sys.argv) < 4:
            print("укажите имя тула: tools/call <name> [json-аргументы]", file=sys.stderr)
            return 2
        arguments = json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}
        params = {"name": sys.argv[3], "arguments": arguments}
    else:
        params = {}

    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java") if os.environ.get("JAVA_HOME") else "java"
    server = subprocess.Popen(
        [java, "-jar", JAR, f"--spring.profiles.active={profile}"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1,
    )

    try:
        send(server, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "mcp-stdio", "version": "1.0.0"},
        }})
        await_response(server, 1)
        send(server, {"jsonrpc": "2.0", "method": "notifications/initialized"})

        send(server, {"jsonrpc": "2.0", "id": 2, "method": method, "params": params})
        response = await_response(server, 2)
        print(json.dumps(response.get("result", response), ensure_ascii=False, indent=2))
        return 0
    finally:
        server.terminate()
        server.wait(timeout=10)


def send(server: subprocess.Popen, message: dict) -> None:
    server.stdin.write(json.dumps(message) + "\n")
    server.stdin.flush()


def await_response(server: subprocess.Popen, request_id: int) -> dict:
    while True:
        line = server.stdout.readline()
        if not line:
            raise RuntimeError("сервер закрыл поток, не ответив на запрос")
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            # Посторонний вывод в stdout — как раз то, что ломает stdio-транспорт.
            print(f"не JSON-RPC в stdout: {line.rstrip()}", file=sys.stderr)
            continue
        if message.get("id") == request_id:
            return message


if __name__ == "__main__":
    sys.exit(main())
