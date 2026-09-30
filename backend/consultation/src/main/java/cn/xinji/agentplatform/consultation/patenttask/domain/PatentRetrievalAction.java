package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

public record PatentRetrievalAction(String executionId, String branchId, String actualScopeReference,
                                    String methodReference, String queryReference, String gapReference,
                                    String scopeConfirmationReference, Outcome outcome,
                                    String resultSetId, long resultSetVersion, List<String> sourceReferences,
                                    List<String> limitations, String detail) {
    public PatentRetrievalAction {
        required(executionId); required(branchId); required(actualScopeReference);
        required(methodReference); required(queryReference); required(gapReference);
        Objects.requireNonNull(outcome, "outcome");
        sourceReferences = List.copyOf(Objects.requireNonNull(sourceReferences));
        limitations = List.copyOf(Objects.requireNonNull(limitations));
        detail = Objects.requireNonNullElse(detail, "");
        if (outcome == Outcome.RESULTS && (resultSetId == null || resultSetId.isBlank() || resultSetVersion < 1
                || sourceReferences.isEmpty())) {
            throw new IllegalArgumentException("successful retrieval requires result set and source references");
        }
        if (outcome != Outcome.RESULTS && resultSetId != null) {
            throw new IllegalArgumentException("failed or empty retrieval cannot claim a result set");
        }
    }

    public enum Outcome { RESULTS, NO_RESULT, NO_PERMISSION, SOURCE_INVALID, TECHNICAL_FAILURE, SCOPE_CONFIRMED, STOPPED }
    public enum StopReason { GOAL_AND_EVIDENCE_MET, USER_STOPPED, NO_REASONABLE_NEW_INFORMATION,
        CONFIRMATION_OR_ACCESS_REQUIRED, CONFLICT_NEEDS_HUMAN, TECHNICAL_BLOCKER }

    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("retrieval reference is required");
    }
}
