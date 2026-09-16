package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;

public record HumanReviewRecord(
        String reviewRecordId,
        String subjectType,
        String subjectId,
        int subjectVersion,
        String action,
        String reason,
        String actorRef,
        Instant actedAt,
        String scopeLabel,
        boolean notOfficialDecision) {

    public HumanReviewRecord {
        if (!ListHolder.ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported review action");
        if ("RETURN".equals(action) && (reason == null || reason.isBlank())) throw new IllegalArgumentException("A return reason is required");
        if (!"SYNTHETIC_PROCESS_TEST".equals(scopeLabel) || !notOfficialDecision) {
            throw new IllegalArgumentException("V0 review must remain a synthetic non-official action");
        }
    }

    private static final class ListHolder {
        private static final java.util.Set<String> ACTIONS = java.util.Set.of("REVISE", "SUBMIT", "CONFIRM", "RETURN");
    }
}
