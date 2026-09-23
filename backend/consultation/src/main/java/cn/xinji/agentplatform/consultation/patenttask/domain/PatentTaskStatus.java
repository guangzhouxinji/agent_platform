package cn.xinji.agentplatform.consultation.patenttask.domain;

public enum PatentTaskStatus {
    IN_PROGRESS,
    WAITING_FOR_USER_INPUT,
    WAITING_FOR_CONFIRMATION,
    PARTIALLY_COMPLETED,
    COMPLETED,
    INSUFFICIENT_EVIDENCE,
    REQUIRES_HUMAN_REVIEW,
    TECHNICAL_FAILURE,
    USER_CANCELLED
}
