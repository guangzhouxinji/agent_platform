package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

public record ResultFocusAction(Kind kind, String branchId, String resultSetId, long resultSetVersion,
                                Long parentVersion, String reason, PatentResultSet.Origin origin,
                                List<PatentResultSet.Candidate> candidates,
                                int ordinal, String referenceId, boolean available,
                                List<String> ambiguityCandidates) {
    public ResultFocusAction {
        Objects.requireNonNull(kind, "kind");
        if (branchId == null || branchId.isBlank()) throw new IllegalArgumentException("branchId is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates));
        ambiguityCandidates = List.copyOf(Objects.requireNonNull(ambiguityCandidates));
    }

    public static ResultFocusAction produce(String branch, String id, long version, Long parent,
                                            String reason, PatentResultSet.Origin origin,
                                            List<PatentResultSet.Candidate> candidates) {
        return new ResultFocusAction(Kind.PRODUCE, branch, id, version, parent, reason,
                origin, candidates, 0, null, true, List.of());
    }

    public static ResultFocusAction onResult(Kind kind, String branch, String id, long version) {
        return new ResultFocusAction(kind, branch, id, version, null, null, null,
                List.of(), 0, null, true, List.of());
    }

    public static ResultFocusAction availability(String branch, String id, long version, boolean available) {
        return new ResultFocusAction(Kind.AVAILABILITY, branch, id, version, null, null, null,
                List.of(), 0, null, available, List.of());
    }

    public static ResultFocusAction bind(String branch, String id, long version, int ordinal, String referenceId) {
        return new ResultFocusAction(Kind.BIND_EXPLICIT, branch, id, version, null, null, null,
                List.of(), ordinal, referenceId, true, List.of());
    }

    public static ResultFocusAction bindCurrent(String branch, int ordinal, String referenceId) {
        return new ResultFocusAction(Kind.BIND_CURRENT, branch, null, 0, null, null, null,
                List.of(), ordinal, referenceId, true, List.of());
    }

    public static ResultFocusAction bindLatestDelivered(String branch, int ordinal, String referenceId) {
        return new ResultFocusAction(Kind.BIND_LATEST_DELIVERED, branch, null, 0, null, null, null,
                List.of(), ordinal, referenceId, true, List.of());
    }

    public static ResultFocusAction switchBranch(String branch) {
        return new ResultFocusAction(Kind.SWITCH_BRANCH, branch, null, 0, null, null, null,
                List.of(), 0, null, true, List.of());
    }

    public static ResultFocusAction ambiguous(String branch, String referenceId, List<String> candidates) {
        return new ResultFocusAction(Kind.AMBIGUOUS, branch, null, 0, null, null, null,
                List.of(), 0, referenceId, true, candidates);
    }

    public enum Kind {
        PRODUCE, DELIVER, DISPLAY, VIEW, SELECT, BIND_EXPLICIT, BIND_CURRENT, BIND_LATEST_DELIVERED,
        AVAILABILITY, SWITCH_BRANCH, AMBIGUOUS
    }
}
