package ru.mcprnd.tracker.tools.v1b;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.mcprnd.tracker.Json;
import ru.mcprnd.tracker.domain.Dates;
import ru.mcprnd.tracker.domain.Task;
import ru.mcprnd.tracker.domain.TaskStore;
import ru.mcprnd.tracker.tools.v1.LegacyTools;

/**
 * Версия контракта v1b: v1, в котором переписано одно описание.
 *
 * <p>От {@link LegacyTools} отличается ровно одной строкой — описанием тула
 * {@code query_items}. Логика поиска не тронута: она и в v1 умеет находить
 * просроченные задачи исполнителя, агент лишь не знал, что фильтр — это
 * набор ключевых слов. Схемы параметров, имена тулов и тексты ошибок
 * совпадают с v1 посимвольно.
 *
 * <p>Профиль существует ради одного измерения: насколько сдвигается success rate
 * от правки одного описания, при полностью неизменном коде. Дифф снимков контракта
 * {@code tools-v1.json} и {@code tools-v1b.json} состоит из одной строки.
 */
@Component
@Profile("v1b")
public class DocumentedLegacyTools {

    private final TaskStore store;

    public DocumentedLegacyTools(TaskStore store) {
        this.store = store;
    }

    @McpTool(name = "query_items", description = "Searches tasks. The filter is a set of space-separated "
            + "keywords matched against task title, assignee and status; a task matches when every keyword "
            + "matches. Statuses: open, in_progress, done, overdue. Example: \"Алексей overdue\" finds "
            + "overdue tasks assigned to Алексей.")
    public CallToolResult queryItems(String filter) {
        List<Task> found = LegacyTools.matchByFilter(store, filter);
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

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
