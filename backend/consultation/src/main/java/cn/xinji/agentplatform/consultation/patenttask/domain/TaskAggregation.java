package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;

public record TaskAggregation(PatentTaskStatus status, int matchedRule, List<TaskReason> reasons) {
    public TaskAggregation {
        reasons = List.copyOf(reasons);
    }
}
