package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

public record PatentHumanAction(Kind kind, Handoff handoff, Receipt receipt) {
    public PatentHumanAction {
        Objects.requireNonNull(kind);
        if (kind == Kind.PREPARE && (handoff == null || receipt != null)
                || kind == Kind.RECEIVE && (receipt == null || handoff != null)) {
            throw new IllegalArgumentException("human action payload does not match its kind");
        }
    }

    public static PatentHumanAction prepare(Handoff value) { return new PatentHumanAction(Kind.PREPARE, value, null); }
    public static PatentHumanAction receive(Receipt value) { return new PatentHumanAction(Kind.RECEIVE, null, value); }
    public enum Kind { PREPARE, RECEIVE }

    public record Handoff(String id, long version, String branchId, String subTaskId,
                          long taskVersion, String goalReference, List<String> factVersions,
                          List<String> resultReferences, List<String> evidenceReferences,
                          List<String> comparisonReferences, List<String> unresolved,
                          String scopeReference, String requestedAction, String availability) {
        public Handoff {
            required(id); required(branchId); required(subTaskId); required(goalReference);
            required(scopeReference); required(requestedAction);
            if (version < 1 || taskVersion < 1 || !"UNAVAILABLE".equals(availability)) {
                throw new IllegalArgumentException("fixture handoff must be unavailable and versioned");
            }
            factVersions = List.copyOf(Objects.requireNonNull(factVersions));
            resultReferences = List.copyOf(Objects.requireNonNull(resultReferences));
            evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences));
            comparisonReferences = List.copyOf(Objects.requireNonNull(comparisonReferences));
            unresolved = List.copyOf(Objects.requireNonNull(unresolved));
        }
        public String key() { return id + "@" + version; }
    }

    public record Receipt(String authoritativeId, String handoffKey, String actorReference,
                          String scopeReference, String resultReference, String authorityEvidenceReference) {
        public Receipt {
            required(authoritativeId); required(handoffKey); required(actorReference);
            required(scopeReference); required(resultReference); required(authorityEvidenceReference);
        }
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("human reference is required");
    }
}
