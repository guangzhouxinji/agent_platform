package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

/** Task-local evidence records; professional sufficiency is not inferred from citations. */
public record PatentEvidenceAction(Kind kind, Evidence evidence, EvidenceSet evidenceSet,
                                   Assessment assessment) {
    public PatentEvidenceAction {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.RECORD_EVIDENCE && (evidence == null || evidenceSet != null || assessment != null))
                || (kind == Kind.RECORD_SET && (evidence != null || evidenceSet == null || assessment != null))
                || (kind == Kind.ASSESS && (evidence != null || evidenceSet != null || assessment == null))) {
            throw new IllegalArgumentException("evidence action must carry exactly its own payload");
        }
    }

    public static PatentEvidenceAction record(Evidence evidence) {
        return new PatentEvidenceAction(Kind.RECORD_EVIDENCE, evidence, null, null);
    }

    public static PatentEvidenceAction set(EvidenceSet set) {
        return new PatentEvidenceAction(Kind.RECORD_SET, null, set, null);
    }

    public static PatentEvidenceAction assess(Assessment assessment) {
        return new PatentEvidenceAction(Kind.ASSESS, null, null, assessment);
    }

    public enum Kind { RECORD_EVIDENCE, RECORD_SET, ASSESS }
    public enum Status { SUFFICIENT, INSUFFICIENT, CONFLICT, NOT_EVALUABLE, PENDING_EVIDENCE }

    public record Evidence(String id, long version, String branchId, String sourceId,
                           String sourceVersion, String locator, String supportsArtifactKey,
                           boolean currentlyAccessible, List<String> limitations) {
        public Evidence {
            required(id); required(branchId); required(sourceId); required(sourceVersion);
            required(locator); required(supportsArtifactKey);
            if (version < 1) throw new IllegalArgumentException("evidence version must be positive");
            limitations = List.copyOf(Objects.requireNonNull(limitations));
        }
        public String key() { return id + "@" + version; }
    }

    public record EvidenceSet(String id, long version, String branchId, List<String> evidenceKeys) {
        public EvidenceSet {
            required(id); required(branchId);
            if (version < 1) throw new IllegalArgumentException("set version must be positive");
            evidenceKeys = List.copyOf(Objects.requireNonNull(evidenceKeys));
            if (evidenceKeys.stream().distinct().count() != evidenceKeys.size()) {
                throw new IllegalArgumentException("duplicate evidence in set");
            }
        }
        public String key() { return id + "@" + version; }
    }

    public record Assessment(String id, long version, String branchId, String targetArtifactKey,
                             String evidenceSetKey, String matrixReference, Status status,
                             String reason, List<String> missingOrConflicting,
                             List<String> limitations, String responsibilityReference) {
        public Assessment {
            required(id); required(branchId); required(targetArtifactKey); required(evidenceSetKey);
            required(reason); required(responsibilityReference);
            Objects.requireNonNull(status, "status");
            if (version < 1) throw new IllegalArgumentException("assessment version must be positive");
            missingOrConflicting = List.copyOf(Objects.requireNonNull(missingOrConflicting));
            limitations = List.copyOf(Objects.requireNonNull(limitations));
        }
        public String key() { return id + "@" + version; }
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("evidence reference is required");
    }
}
