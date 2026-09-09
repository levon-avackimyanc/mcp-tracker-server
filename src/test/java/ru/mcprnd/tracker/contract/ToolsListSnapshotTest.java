package ru.mcprnd.tracker.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.mcprnd.tracker.domain.TaskStore;
import ru.mcprnd.tracker.tools.v1.LegacyTools;
import ru.mcprnd.tracker.tools.v1b.DocumentedLegacyTools;
import ru.mcprnd.tracker.tools.v1err.LegacyToolsWithGoodErrors;
import ru.mcprnd.tracker.tools.v2.TrackerTools;

/**
 * Контракт сервера под контролем версий.
 *
 * <p>Описания тулов и схемы параметров — то, на основании чего модель принимает решения,
 * поэтому их изменение меняет поведение системы. Тест фиксирует контракт целиком:
 * правка описания роняет сборку и попадает в code review отдельным диффом.
 *
 * <p>Обновление эталона после осознанного изменения: {@code mvn test -Dsnapshot.update=true}.
 */
class ToolsListSnapshotTest {

    @Test
    @DisplayName("контракт v1 совпадает с эталоном")
    void v1ContractMatchesSnapshot() {
        assertContract("tools-v1.json", new LegacyTools(new TaskStore()));
    }

    @Test
    @DisplayName("контракт v1b совпадает с эталоном")
    void v1bContractMatchesSnapshot() {
        assertContract("tools-v1b.json", new DocumentedLegacyTools(new TaskStore()));
    }

    @Test
    @DisplayName("контракт v1err неотличим от v1: разница только в текстах ошибок")
    void v1errIsIndistinguishableFromV1InToolsList() {
        String v1 = ToolsContractSnapshot.render(new LegacyTools(new TaskStore()));
        String v1err = ToolsContractSnapshot.render(new LegacyToolsWithGoodErrors(new TaskStore()));

        // Тексты ошибок не попадают в tools/list — модель узнаёт их только во время
        // выполнения сценария. Именно поэтому snapshot-тест контракта их не страхует,
        // и остаётся только eval: об этом слайд 16.
        assertThat(v1err).isEqualTo(v1);
    }

    @Test
    @DisplayName("контракт v1b отличается от v1 ровно одним описанием")
    void v1bDiffersFromV1InOneDescriptionOnly() {
        String v1 = ToolsContractSnapshot.render(new LegacyTools(new TaskStore()));
        String v1b = ToolsContractSnapshot.render(new DocumentedLegacyTools(new TaskStore()));

        List<String> differing = differingLines(v1, v1b);

        assertThat(differing)
                .as("v1b задуман как правка одного описания: всё остальное в контракте "
                        + "должно совпадать с v1, иначе демонстрация теряет смысл")
                .hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("\"description\""));
    }

    /** Номера строк, в которых снимки расходятся; длина снимков должна совпадать. */
    private static List<String> differingLines(String left, String right) {
        List<String> leftLines = left.lines().toList();
        List<String> rightLines = right.lines().toList();

        assertThat(rightLines).hasSameSizeAs(leftLines);

        return IntStream.range(0, leftLines.size())
                .filter(i -> !leftLines.get(i).equals(rightLines.get(i)))
                .mapToObj(rightLines::get)
                .toList();
    }

    @Test
    @DisplayName("контракт v2 совпадает с эталоном")
    void v2ContractMatchesSnapshot() {
        assertContract("tools-v2.json", new TrackerTools(new TaskStore()));
    }

    private static void assertContract(String snapshotFile, Object tools) {
        String actual = ToolsContractSnapshot.render(tools);
        String expected = ToolsContractSnapshot.read(snapshotFile);

        if (expected == null || ToolsContractSnapshot.updateRequested()) {
            ToolsContractSnapshot.write(snapshotFile, actual);
            return;
        }

        assertThat(actual)
                .as("контракт tools/list изменился; проверьте дифф и обновите эталон "
                        + "командой mvn test -Dsnapshot.update=true, если изменение осознанное")
                .isEqualTo(expected);
    }
}
