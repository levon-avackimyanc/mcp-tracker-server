package ru.mcprnd.tracker.tools.v2;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.mcprnd.tracker.Json;
import ru.mcprnd.tracker.domain.Dates;
import ru.mcprnd.tracker.domain.Task;
import ru.mcprnd.tracker.domain.TaskStatus;
import ru.mcprnd.tracker.domain.TaskStore;

/**
 * Версия контракта v2: те же операции, но описания написаны для языковой модели.
 *
 * <p>Отличия от {@link ru.mcprnd.tracker.tools.v1.LegacyTools} — только в контракте:
 * описание отвечает на вопрос «когда вызывать этот тул», параметры описаны с форматом
 * и примером, статусы сужены до перечисления, тексты ошибок содержат подсказку,
 * достаточную для самостоятельного исправления вызова.
 *
 * <p>Примеры дат в описаниях намеренно статичны: описания попадают в snapshot-тест
 * контракта, и «плавающая» дата ломала бы его каждый день.
 */
@Component
@Profile("v2")
public class TrackerTools {

    private static final String EXAMPLE_DATE = "2026-09-30";

    private final TaskStore store;

    public TrackerTools(TaskStore store) {
        this.store = store;
    }

    @McpTool(name = "search_tasks", description = """
            Search tasks in the tracker by assignee and/or status. \
            Use this to find, count or list tasks — e.g. all overdue tasks for a person. \
            All filters are optional and combined with AND; call without filters to list every task. \
            For aggregated statistics use get_task_report instead.""")
    public CallToolResult searchTasks(
            @McpToolParam(description = "Exact assignee name, e.g. \"Алексей\"", required = false)
            String assignee,
            @McpToolParam(description = "Task status; \"overdue\" means the due date has passed", required = false)
            TaskStatus status,
            @McpToolParam(description = "Only tasks due strictly before this ISO date, e.g. " + EXAMPLE_DATE, required = false)
            String due_before) {

        if (due_before != null && !Dates.isIsoDate(due_before)) {
            return error("Invalid due_before: expected ISO date like %s, got \"%s\""
                    .formatted(Dates.isoDaysFromNow(5), due_before));
        }
        List<Task> found = store.search(assignee, status, due_before);
        if (found.isEmpty()) {
            return ok("No tasks match the given filters.");
        }
        String text = found.stream()
                .map(t -> "%s · %s · assignee: %s · status: %s · due: %s"
                        .formatted(t.id(), t.title(), t.assignee(), t.status(), t.dueDate()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return ok(text);
    }

    @McpTool(name = "create_task", description = """
            Create a new task in the tracker. Use this when the user asks to add, schedule or assign work. \
            For changing an existing task use update_task; for finding tasks use search_tasks.""")
    public CallToolResult createTask(
            @McpToolParam(description = "Short task title", required = true)
            String title,
            @McpToolParam(description = "Assignee name, e.g. \"Мария\"", required = false)
            String assignee,
            @McpToolParam(description = "Due date as ISO date, e.g. " + EXAMPLE_DATE
                    + ". Convert relative dates (\"завтра\") to ISO first", required = false)
            String due_date) {

        if (due_date != null && !Dates.isIsoDate(due_date)) {
            return error("Invalid due_date: expected ISO date like %s, got \"%s\". Today is %s"
                    .formatted(Dates.isoDaysFromNow(5), due_date, Dates.today()));
        }
        if (title == null || title.isBlank()) {
            return error("Invalid title: must be a non-empty string");
        }
        Task task = store.create(title, assignee, due_date, null);
        return ok("Created %s: %s (assignee: %s, due: %s)".formatted(
                task.id(),
                task.title(),
                task.assignee().isEmpty() ? "—" : task.assignee(),
                task.dueDate().isEmpty() ? "—" : task.dueDate()));
    }

    @McpTool(name = "update_task", description = """
            Update fields of an existing task by its id (e.g. change status, due date or assignee). \
            Find the task id via search_tasks first. For creating new tasks use create_task.""")
    public CallToolResult updateTask(
            @McpToolParam(description = "Task id, e.g. \"T-3\"", required = true)
            String id,
            @McpToolParam(description = "New task title", required = false)
            String title,
            @McpToolParam(description = "New assignee name", required = false)
            String assignee,
            @McpToolParam(description = "New status", required = false)
            TaskStatus status,
            @McpToolParam(description = "New due date as ISO date, e.g. " + EXAMPLE_DATE, required = false)
            String due_date) {

        if (due_date != null && !Dates.isIsoDate(due_date)) {
            return error("Invalid due_date: expected ISO date like %s, got \"%s\". Today is %s"
                    .formatted(Dates.isoDaysFromNow(5), due_date, Dates.today()));
        }
        try {
            Task task = store.update(id, title, assignee, status, due_date);
            return ok("Updated %s: %s (assignee: %s, status: %s, due: %s)".formatted(
                    task.id(), task.title(), task.assignee(), task.status(), task.dueDate()));
        } catch (TaskStore.NoSuchTaskException e) {
            return error("Task not found: \"%s\". Use search_tasks to find valid task ids (they look like \"T-3\")"
                    .formatted(id));
        }
    }

    @McpTool(name = "get_task_report", description = """
            Get aggregated task statistics: totals by status and by assignee. \
            Use this for overview questions ('how many tasks are overdue?'). \
            For finding individual tasks use search_tasks.""")
    public CallToolResult getTaskReport() {
        return ok(Json.writePretty(store.report()));
    }

    private static CallToolResult ok(String text) {
        return CallToolResult.builder().addTextContent(text).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
