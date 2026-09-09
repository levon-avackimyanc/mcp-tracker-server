package ru.mcprnd.tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Хранилище задач в памяти — общая логика для обеих версий контракта.
 *
 * <p>Сроки задач задаются относительно текущей даты, поэтому просроченные задачи
 * остаются просроченными в любой день демонстрации.
 */
@Component
public class TaskStore {

    private final List<Task> tasks = new ArrayList<>();
    private int nextId;

    public TaskStore() {
        reset();
    }

    public final void reset() {
        tasks.clear();
        tasks.addAll(List.of(
                new Task("T-1", "Согласовать договор с Airtech", "Алексей", TaskStatus.overdue, Dates.isoDaysFromNow(-5)),
                new Task("T-2", "Обновить сертификаты TLS", "Алексей", TaskStatus.overdue, Dates.isoDaysFromNow(-2)),
                new Task("T-3", "Ответить аудиторам по SOC 2", "Алексей", TaskStatus.overdue, Dates.isoDaysFromNow(-1)),
                new Task("T-4", "Подготовить план миграции", "Алексей", TaskStatus.open, Dates.isoDaysFromNow(7)),
                new Task("T-5", "Починить экспорт CSV", "Мария", TaskStatus.overdue, Dates.isoDaysFromNow(-3)),
                new Task("T-6", "Ревью PR #482", "Мария", TaskStatus.in_progress, Dates.isoDaysFromNow(2)),
                new Task("T-7", "Настроить CI", "Иван", TaskStatus.done, Dates.isoDaysFromNow(-10)),
                new Task("T-8", "Черновик роадмапа Q3", "Иван", TaskStatus.open, Dates.isoDaysFromNow(14))));
        nextId = tasks.size() + 1;
    }

    public List<Task> all() {
        return List.copyOf(tasks);
    }

    public Optional<Task> byId(String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** Поиск по точным фильтрам; null-фильтры игнорируются, остальные объединяются по И. */
    public List<Task> search(String assignee, TaskStatus status, String dueBefore) {
        return tasks.stream()
                .filter(t -> assignee == null || t.assignee().equalsIgnoreCase(assignee))
                .filter(t -> status == null || t.status() == status)
                .filter(t -> dueBefore == null || (!t.dueDate().isEmpty() && t.dueDate().compareTo(dueBefore) < 0))
                .toList();
    }

    public Task create(String title, String assignee, String dueDate, TaskStatus status) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        if (dueDate != null && !dueDate.isEmpty() && !Dates.isIsoDate(dueDate)) {
            throw new IllegalArgumentException("invalid date: " + dueDate);
        }
        Task task = new Task(
                "T-" + nextId++,
                title.trim(),
                assignee != null ? assignee : "",
                status != null ? status : TaskStatus.open,
                dueDate != null ? dueDate : "");
        tasks.add(task);
        return task;
    }

    public Task update(String id, String title, String assignee, TaskStatus status, String dueDate) {
        if (dueDate != null && !Dates.isIsoDate(dueDate)) {
            throw new IllegalArgumentException("invalid date: " + dueDate);
        }
        int index = -1;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new NoSuchTaskException(id);
        }
        Task updated = tasks.get(index).withPatch(title, assignee, status, dueDate);
        tasks.set(index, updated);
        return updated;
    }

    /** Сводная статистика: всего задач, разрезы по статусу и по исполнителю. */
    public Report report() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byAssignee = new LinkedHashMap<>();
        for (Task t : tasks) {
            byStatus.merge(t.status().name(), 1, Integer::sum);
            byAssignee.merge(t.assignee(), 1, Integer::sum);
        }
        return new Report(tasks.size(), byStatus, byAssignee);
    }

    public record Report(int total, Map<String, Integer> byStatus, Map<String, Integer> byAssignee) {
    }

    public static class NoSuchTaskException extends RuntimeException {
        public NoSuchTaskException(String id) {
            super("task not found: " + id);
        }
    }
}
