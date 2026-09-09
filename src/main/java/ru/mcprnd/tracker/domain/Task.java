package ru.mcprnd.tracker.domain;

/**
 * Задача трекера.
 *
 * @param dueDate срок в формате ISO (yyyy-MM-dd) либо пустая строка, если срок не задан
 */
public record Task(String id, String title, String assignee, TaskStatus status, String dueDate) {

    public Task withPatch(String newTitle, String newAssignee, TaskStatus newStatus, String newDueDate) {
        return new Task(
                id,
                newTitle != null ? newTitle : title,
                newAssignee != null ? newAssignee : assignee,
                newStatus != null ? newStatus : status,
                newDueDate != null ? newDueDate : dueDate);
    }
}
