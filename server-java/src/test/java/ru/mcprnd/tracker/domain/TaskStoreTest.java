package ru.mcprnd.tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Классические юнит-тесты доменной логики: она одинакова для обеих версий контракта. */
class TaskStoreTest {

    private TaskStore store;

    @BeforeEach
    void setUp() {
        store = new TaskStore();
    }

    @Test
    @DisplayName("в трекере восемь задач, три просроченные у Алексея")
    void seedsTasks() {
        assertThat(store.all()).hasSize(8);
        assertThat(store.search("Алексей", TaskStatus.overdue, null))
                .extracting(Task::id)
                .containsExactly("T-1", "T-2", "T-3");
    }

    @Test
    @DisplayName("поиск без фильтров возвращает все задачи")
    void searchWithoutFilters() {
        assertThat(store.search(null, null, null)).hasSize(8);
    }

    @Test
    @DisplayName("фильтры объединяются по И")
    void combinesFilters() {
        assertThat(store.search("Мария", TaskStatus.overdue, null))
                .extracting(Task::id)
                .containsExactly("T-5");
    }

    @Test
    @DisplayName("имя исполнителя сравнивается без учёта регистра")
    void assigneeIsCaseInsensitive() {
        assertThat(store.search("алексей", null, null)).hasSize(4);
    }

    @Test
    @DisplayName("due_before отбирает задачи со сроком строго раньше даты")
    void filtersByDueBefore() {
        assertThat(store.search(null, null, Dates.isoDaysFromNow(-4)))
                .extracting(Task::id)
                .containsExactly("T-1", "T-7");
    }

    @Test
    @DisplayName("создание задачи присваивает следующий идентификатор")
    void createsTask() {
        Task created = store.create("Написать пост", "Мария", "2026-08-01", null);

        assertThat(created.id()).isEqualTo("T-9");
        assertThat(created.status()).isEqualTo(TaskStatus.open);
        assertThat(store.all()).hasSize(9);
    }

    @Test
    @DisplayName("создание с некорректной датой отклоняется")
    void rejectsInvalidDate() {
        assertThatThrownBy(() -> store.create("Задача", null, "завтра", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("создание без названия отклоняется")
    void rejectsEmptyTitle() {
        assertThatThrownBy(() -> store.create("  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("обновление меняет только переданные поля")
    void updatesOnlyGivenFields() {
        Task updated = store.update("T-1", null, null, TaskStatus.done, null);

        assertThat(updated.status()).isEqualTo(TaskStatus.done);
        assertThat(updated.title()).isEqualTo("Согласовать договор с Airtech");
        assertThat(updated.assignee()).isEqualTo("Алексей");
    }

    @Test
    @DisplayName("обновление несуществующей задачи отклоняется")
    void rejectsUnknownId() {
        assertThatThrownBy(() -> store.update("T-404", null, null, TaskStatus.done, null))
                .isInstanceOf(TaskStore.NoSuchTaskException.class);
    }

    @Test
    @DisplayName("отчёт считает задачи по статусам и исполнителям")
    void buildsReport() {
        TaskStore.Report report = store.report();

        assertThat(report.total()).isEqualTo(8);
        assertThat(report.byStatus()).containsEntry("overdue", 4).containsEntry("open", 2);
        assertThat(report.byAssignee()).containsEntry("Алексей", 4).containsEntry("Мария", 2);
    }
}
