package ru.mcprnd.tracker.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.mcprnd.tracker.domain.TaskStatus;
import ru.mcprnd.tracker.domain.TaskStore;
import ru.mcprnd.tracker.tools.v1.LegacyTools;
import ru.mcprnd.tracker.tools.v2.TrackerTools;

/**
 * Поведение тулов на уровне протокола: что именно получает агент в ответе.
 *
 * <p>Тесты обеих версий проходят одинаково успешно — работоспособность кода
 * не зависит от качества описаний, а вот работоспособность сценария зависит.
 */
class ToolBehaviourTest {

    @Nested
    @DisplayName("v1 — описания как javadoc")
    class V1 {

        private final TaskStore store = new TaskStore();
        private final LegacyTools tools = new LegacyTools(store);

        @Test
        @DisplayName("вызов в ожидаемом формате — пробельные ключевые слова — находит все три задачи")
        void findsTasksByKeywordFilter() {
            CallToolResult result = tools.queryItems("Алексей overdue");

            assertThat(ToolResults.text(result)).contains("T-1", "T-2", "T-3").doesNotContain("T-4");
        }

        @Test
        @DisplayName("формат, который агент придумывает по описанию, не находит ничего — но это не ошибка")
        void agentGuessedFilterFindsNothing() {
            CallToolResult result = tools.queryItems("assignee=Алексей AND status=overdue");

            assertThat(ToolResults.isError(result)).isFalse();
            assertThat(ToolResults.text(result)).isEqualTo("[]");
        }

        @Test
        @DisplayName("ошибка даты не содержит ни формата, ни примера")
        void returnsUnhelpfulDateError() {
            CallToolResult result = tools.createItem("Отчёт", null, "завтра");

            assertThat(ToolResults.isError(result)).isTrue();
            assertThat(ToolResults.text(result)).isEqualTo("Error: Invalid date");
        }
    }

    @Nested
    @DisplayName("v2 — описания для модели")
    class V2 {

        private final TaskStore store = new TaskStore();
        private final TrackerTools tools = new TrackerTools(store);

        @Test
        @DisplayName("поиск по исполнителю и статусу возвращает три просроченные задачи")
        void findsOverdueTasksByAssignee() {
            CallToolResult result = tools.searchTasks("Алексей", TaskStatus.overdue, null);

            assertThat(ToolResults.isError(result)).isFalse();
            assertThat(ToolResults.text(result)).contains("T-1", "T-2", "T-3").doesNotContain("T-4");
        }

        @Test
        @DisplayName("пустая выборка описывается словами, а не пустым списком")
        void describesEmptyResult() {
            CallToolResult result = tools.searchTasks("Пётр", null, null);

            assertThat(ToolResults.text(result)).isEqualTo("No tasks match the given filters.");
        }

        @Test
        @DisplayName("ошибка даты содержит формат, пример и текущую дату")
        void returnsSelfCorrectableDateError() {
            CallToolResult result = tools.createTask("Отчёт", null, "завтра");

            assertThat(ToolResults.isError(result)).isTrue();
            assertThat(ToolResults.text(result))
                    .matches("Invalid due_date: expected ISO date like \\d{4}-\\d{2}-\\d{2}, "
                            + "got \"завтра\"\\. Today is \\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("после подсказки повторный вызов с ISO-датой проходит")
        void acceptsIsoDateOnRetry() {
            CallToolResult result = tools.createTask("Отчёт", "Мария", "2026-08-01");

            assertThat(ToolResults.isError(result)).isFalse();
            assertThat(ToolResults.text(result)).contains("Created T-9", "due: 2026-08-01");
        }

        @Test
        @DisplayName("ошибка неизвестного идентификатора подсказывает, где взять верный")
        void explainsUnknownTaskId() {
            CallToolResult result = tools.updateTask("T-404", null, null, TaskStatus.done, null);

            assertThat(ToolResults.isError(result)).isTrue();
            assertThat(ToolResults.text(result)).contains("Task not found", "search_tasks");
        }

        @Test
        @DisplayName("перенос срока просроченной задачи меняет её состояние")
        void postponesOverdueTask() {
            tools.updateTask("T-1", null, null, TaskStatus.open, "2026-12-01");

            assertThat(store.byId("T-1")).get()
                    .satisfies(task -> {
                        assertThat(task.status()).isEqualTo(TaskStatus.open);
                        assertThat(task.dueDate()).isEqualTo("2026-12-01");
                    });
        }

        @Test
        @DisplayName("отчёт отдаётся человекочитаемым JSON")
        void returnsReport() {
            CallToolResult result = tools.getTaskReport();

            assertThat(ToolResults.text(result)).contains("\"total\" : 8", "overdue");
        }
    }
}
