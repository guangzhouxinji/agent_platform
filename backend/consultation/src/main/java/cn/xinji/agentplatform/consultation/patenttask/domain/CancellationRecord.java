package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;

public record CancellationRecord(long cancelledFromTaskVersion, List<String> activityPointReferences,
                                 String stopRequestReference, String toolStopResultReference) {
    public CancellationRecord {
        if (cancelledFromTaskVersion < 1) {
            throw new IllegalArgumentException("cancelledFromTaskVersion must be positive");
        }
        activityPointReferences = List.copyOf(activityPointReferences);
        if (stopRequestReference == null || stopRequestReference.isBlank()) {
            throw new IllegalArgumentException("stopRequestReference is required");
        }
    }

    public boolean toolStopResultRecorded() {
        return toolStopResultReference != null && !toolStopResultReference.isBlank();
    }
}
