package ru.mcprnd.tracker.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Снятие снимка контракта — того, что сервер отдаёт в ответе {@code tools/list}.
 *
 * <p>В снимок попадают ровно те поля, которые видит языковая модель: имя тула,
 * его описание и схема входных параметров.
 */
public final class ToolsContractSnapshot {

    private static final Path SNAPSHOT_DIR = Path.of("src", "test", "resources", "snapshots");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ToolsContractSnapshot() {
    }

    /** Контракт набора тулов в виде стабильного JSON. */
    public static String render(Object... toolObjects) {
        List<SyncToolSpecification> specs = new SyncMcpToolProvider(List.of(toolObjects)).getToolSpecifications();
        List<ToolView> tools = specs.stream()
                .map(SyncToolSpecification::tool)
                .sorted(Comparator.comparing(Tool::name))
                .map(ToolsContractSnapshot::describe)
                .toList();
        return MAPPER.writeValueAsString(tools);
    }

    private static ToolView describe(Tool tool) {
        return new ToolView(tool.name(), tool.description(), tool.inputSchema(), tool.outputSchema());
    }

    /**
     * Поля контракта в фиксированном порядке. Вложенные схемы — обычные map'ы,
     * их ключи сортируются, поэтому снимок стабилен от прогона к прогону.
     */
    private record ToolView(
            String name,
            String description,
            Map<String, Object> inputSchema,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> outputSchema) {
    }

    public static String read(String fileName) {
        Path file = SNAPSHOT_DIR.resolve(fileName);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(String fileName, String content) {
        try {
            Files.createDirectories(SNAPSHOT_DIR);
            Files.writeString(SNAPSHOT_DIR.resolve(fileName), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Обновление эталонов: {@code mvn test -Dsnapshot.update=true}. */
    public static boolean updateRequested() {
        return Boolean.getBoolean("snapshot.update");
    }
}
