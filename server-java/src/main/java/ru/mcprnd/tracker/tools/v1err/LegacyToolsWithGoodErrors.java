package ru.mcprnd.tracker.tools.v1err;

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
 * Версия контракта v1err: описания как в v1, тексты ошибок как в v2.
 *
 * <p>Профиль изолирует вклад одного слоя контракта — сообщений об ошибках.
 * В v2 хорошее описание параметра не даёт агенту ошибиться, и до текста ошибки
 * дело не доходит; здесь описания остались плохими, поэтому агент ошибается —
 * и видно, помогает ли ему выбраться текст ошибки.
 *
 * <p>Важно, что улучшены только сообщения с {@code isError}. Пустой результат
 * поиска, как и в v1, возвращается молчаливым {@code []}: это не ошибка, и никакой
 * текст ошибки такой отказ не лечит.
 */
@Component
@Profile("v1err")
public class LegacyToolsWithGoodErrors {

    private final TaskStore store;

    public LegacyToolsWithGoodErrors(TaskStore store) {
        this.store = store;
    }

    @McpTool(name = "query_items", description = "Queries items using filter")
    public CallToolResult queryItems(String filter) {
        List<Task> found = LegacyTools.matchByFilter(store, filter);
        return CallToolResult.builder().addTextContent(Json.write(found)).build();
    }

    @McpTool(name = "create_item", description = "Creates an item")
    public CallToolResult createItem(String title, String assignee, String due_date) {
        if (due_date != null && !Dates.isIsoDate(due_date)) {
            return error("Invalid due_date: expected ISO date like %s, got \"%s\". Today is %s"
                    .formatted(Dates.isoDaysFromNow(5), due_date, Dates.today()));
        }
        try {
            Task task = store.create(title, assignee, due_date, null);
            return CallToolResult.builder().addTextContent(Json.write(task)).build();
        } catch (IllegalArgumentException e) {
            return error("Invalid title: must be a non-empty string");
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
