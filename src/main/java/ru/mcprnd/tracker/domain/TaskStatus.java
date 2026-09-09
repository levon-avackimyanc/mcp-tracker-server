package ru.mcprnd.tracker.domain;

/** Статус задачи. Значения попадают в JSON-схему тулов как enum. */
public enum TaskStatus {
    open,
    in_progress,
    overdue,
    done
}
