package ru.mcprnd.tracker.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Разбор ответа тула так, как его увидит агент: текст плюс признак ошибки. */
public final class ToolResults {

    private ToolResults() {
    }

    public static String text(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    public static boolean isError(CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }
}
