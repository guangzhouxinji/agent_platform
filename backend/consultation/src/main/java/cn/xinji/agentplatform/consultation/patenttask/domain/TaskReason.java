package cn.xinji.agentplatform.consultation.patenttask.domain;

import cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange;

import java.util.Objects;

public record TaskReason(Code code, String detail, SubTaskChange.ResponsibilitySource responsibilitySource,
                         String responsibilityReference) {
    public TaskReason {
        Objects.requireNonNull(code, "code");
        detail = Objects.requireNonNullElse(detail, "");
        Objects.requireNonNull(responsibilitySource, "responsibilitySource");
        if (responsibilityReference == null || responsibilityReference.isBlank()) {
            throw new IllegalArgumentException("responsibilityReference is required");
        }
    }

    public enum Code {
        NO_RESULT,
        NO_PERMISSION,
        SOURCE_INVALID,
        INSUFFICIENT_EVIDENCE,
        TECHNICAL_FAILURE
    }
}
