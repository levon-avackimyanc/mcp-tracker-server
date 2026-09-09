package ru.mcprnd.tracker.tools.v1;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.mcprnd.tracker.Json;
import ru.mcprnd.tracker.domain.Dates;
import ru.mcprnd.tracker.domain.Task;
import ru.mcprnd.tracker.domain.TaskStore;

/**
 * Версия контракта v1: описания тулов написаны для человека, читающего исходники.
 *
 * <p>Логика полностью совпадает с {@link ru.mcprnd.tracker.tools.v2.TrackerTools} —
 * отличаются только тексты описаний, схемы параметров и формулировки ошибок.
 *
 * <p>Имена параметров попадают в JSON-схему как есть, поэтому здесь используется
 * snake_case: контракт задаётся именами параметров Java-метода.
 */
@Component
@Profile("v1")
public class LegacyTools {

    private final TaskStore store;

    public LegacyTools(TaskStore store) {
        this.store = store;
    }

    @McpTool(name = "query_items", description = "Queries items using filter")
    public CallToolResult queryItems(String filter) {
        List<Task> found = matchByFilter(store, filter);
        return CallToolResult.builder().addTextContent(Json.write(found)).build();
    }

    @McpTool(name = "create_item", description = "Creates an item")
    public CallToolResult createItem(String title, String assignee, String due_date) {
        if (due_date != null && !Dates.isIsoDate(due_date)) {
            return error("Error: Invalid date");
        }
        try {
            Task task = store.create(title, assignee, due_date, null);
            return CallToolResult.builder().addTextContent(Json.write(task)).build();
        } catch (IllegalArgumentException e) {
            return error("Error: Invalid input");
        }
    }

    @McpTool(name = "get_report", description = "Gets report")
    public CallToolResult getReport(String period) {
        return CallToolResult.builder().addTextContent(Json.write(store.report())).build();
    }

    /**
     * Подстрочный поиск по всем полям задачи: совпасть должны все слова фильтра.
     *
     * <p>Работает для запроса «сертификаты», но не для того, как формулирует агент:
     * «просроченные задачи Алексея» не находит ничего.
     */
    public static List<Task> matchByFilter(TaskStore store, String filter) {
        if (filter == null || filter.isBlank()) {
            return List.of();
        }
        List<String> tokens = Arrays.stream(filter.toLowerCase().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .toList();
        return store.all().stream()
                .filter(task -> {
                    String haystack = (task.title() + " " + task.assignee() + " " + task.status()).toLowerCase();
                    return tokens.stream().allMatch(haystack::contains);
                })
                .toList();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
