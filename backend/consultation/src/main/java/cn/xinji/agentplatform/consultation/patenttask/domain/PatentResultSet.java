package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

public record PatentResultSet(String resultSetId, long version, Long parentVersion, String branchId,
                              String derivationReason, Origin origin, List<Candidate> candidates, boolean delivered,
                              long deliveryOrder, boolean available) {
    public PatentResultSet {
        require(resultSetId, "resultSetId");
        require(branchId, "branchId");
        require(derivationReason, "derivationReason");
        Objects.requireNonNull(origin, "origin");
        if (version < 1 || (parentVersion != null && (parentVersion < 1 || parentVersion >= version))) {
            throw new IllegalArgumentException("invalid result set version lineage");
        }
        candidates = List.copyOf(Objects.requireNonNull(candidates));
        if (candidates.stream().map(Candidate::candidateId).distinct().count() != candidates.size()) {
            throw new IllegalArgumentException("candidate identities must be unique within a snapshot");
        }
        if (deliveryOrder < 0 || (delivered != (deliveryOrder > 0))) {
            throw new IllegalArgumentException("delivery order must identify a delivered snapshot");
        }
    }

    public String key() { return resultSetId + "@" + version; }

    public PatentResultSet deliveredAt(long order) {
        return new PatentResultSet(resultSetId, version, parentVersion, branchId, derivationReason,
                origin, candidates, true, order, available);
    }

    public PatentResultSet withAvailability(boolean next) {
        return new PatentResultSet(resultSetId, version, parentVersion, branchId, derivationReason,
                origin, candidates, delivered, deliveryOrder, next);
    }

    public record Origin(String actualScopeReference, String methodReference,
                         List<String> sourceReferences, List<String> executionReferences,
                         List<String> limitations) {
        public Origin {
            require(actualScopeReference, "actualScopeReference");
            require(methodReference, "methodReference");
            sourceReferences = List.copyOf(Objects.requireNonNull(sourceReferences));
            executionReferences = List.copyOf(Objects.requireNonNull(executionReferences));
            limitations = List.copyOf(Objects.requireNonNull(limitations));
            if (sourceReferences.isEmpty() || executionReferences.isEmpty()) {
                throw new IllegalArgumentException("result set requires source and execution references");
            }
            if (sourceReferences.stream().anyMatch(value -> value == null || value.isBlank())
                    || executionReferences.stream().anyMatch(value -> value == null || value.isBlank())
                    || limitations.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("result set provenance cannot contain blank references");
            }
        }
    }

    public record Candidate(String candidateId, String documentVersionReference) {
        public Candidate {
            require(candidateId, "candidateId");
            require(documentVersionReference, "documentVersionReference");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
