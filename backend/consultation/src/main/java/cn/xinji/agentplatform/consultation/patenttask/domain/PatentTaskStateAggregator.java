package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;

public final class PatentTaskStateAggregator {
    private PatentTaskStateAggregator() {}

    public static TaskAggregation aggregate(List<PatentSubTask> allSubTasks, boolean cancelled) {
        List<PatentSubTask> subTasks = allSubTasks.stream().filter(PatentSubTask::primary).toList();
        if (subTasks.isEmpty()) throw new IllegalArgumentException("at least one primary subtask is required");
        List<TaskReason> reasons = subTasks.stream().flatMap(task -> task.reasons().stream()).toList();

        if (cancelled) return result(PatentTaskStatus.USER_CANCELLED, 1, reasons);
        if (subTasks.stream().allMatch(task -> task.status() == PatentTaskStatus.COMPLETED)) {
            return result(PatentTaskStatus.COMPLETED, 2, reasons);
        }
        boolean completed = subTasks.stream().anyMatch(task -> task.status() == PatentTaskStatus.COMPLETED);
        if (completed && subTasks.stream().anyMatch(task -> task.status() != PatentTaskStatus.COMPLETED)) {
            return result(PatentTaskStatus.PARTIALLY_COMPLETED, 3, reasons);
        }
        if (hasStatus(subTasks, PatentTaskStatus.REQUIRES_HUMAN_REVIEW)) {
            return result(PatentTaskStatus.REQUIRES_HUMAN_REVIEW, 4, reasons);
        }
        if (hasStatus(subTasks, PatentTaskStatus.WAITING_FOR_CONFIRMATION)) {
            return result(PatentTaskStatus.WAITING_FOR_CONFIRMATION, 5, reasons);
        }
        if (hasStatus(subTasks, PatentTaskStatus.WAITING_FOR_USER_INPUT)) {
            return result(PatentTaskStatus.WAITING_FOR_USER_INPUT, 6, reasons);
        }
        if (subTasks.stream().anyMatch(task -> task.status() == PatentTaskStatus.IN_PROGRESS || task.hasLegalContinuation())) {
            return result(PatentTaskStatus.IN_PROGRESS, 7, reasons);
        }
        if (hasStatus(subTasks, PatentTaskStatus.TECHNICAL_FAILURE)) {
            return result(PatentTaskStatus.TECHNICAL_FAILURE, 8, reasons);
        }
        if (subTasks.stream().allMatch(task -> task.status() == PatentTaskStatus.INSUFFICIENT_EVIDENCE)) {
            return result(PatentTaskStatus.INSUFFICIENT_EVIDENCE, 9, reasons);
        }
        throw new IllegalStateException("subtask snapshot does not match the approved aggregation rules");
    }

    private static boolean hasStatus(List<PatentSubTask> tasks, PatentTaskStatus status) {
        return tasks.stream().anyMatch(task -> task.status() == status);
    }

    private static TaskAggregation result(PatentTaskStatus status, int rule, List<TaskReason> reasons) {
        return new TaskAggregation(status, rule, reasons);
    }
}
