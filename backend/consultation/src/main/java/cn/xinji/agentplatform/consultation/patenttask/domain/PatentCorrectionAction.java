package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

/** Fixture-level dependency declarations and explicit user corrections for one patent task. */
public record PatentCorrectionAction(Kind kind, Artifact artifact, String replacedArtifactId,
                                     long replacedVersion, String reason) {
    public PatentCorrectionAction {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(artifact, "artifact");
        if (kind == Kind.CORRECT && (replacedArtifactId == null || replacedArtifactId.isBlank()
                || replacedVersion < 1 || reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("correction requires the replaced artifact and reason");
        }
    }

    public enum Kind { REGISTER, CORRECT }

    public record Artifact(String id, long version, Type type, String branchId,
                           List<String> dependsOn) {
        public Artifact {
            if (id == null || id.isBlank() || version < 1 || branchId == null || branchId.isBlank()) {
                throw new IllegalArgumentException("artifact identity, version and branch are required");
            }
            Objects.requireNonNull(type, "type");
            dependsOn = List.copyOf(Objects.requireNonNull(dependsOn));
            if (dependsOn.stream().anyMatch(value -> value == null || value.isBlank())
                    || dependsOn.stream().distinct().count() != dependsOn.size()) {
                throw new IllegalArgumentException("dependencies must be distinct artifact keys");
            }
        }

        public String key() { return id + "@" + version; }
    }

    public enum Type {
        FEATURE, SCOPE, CANDIDATE, REFERENCE, GOAL, OBJECT,
        QUERY, RESULT_SET, SUFFICIENCY, SELECTION_REASON, COMPARISON_ITEM, ANSWER_CLAIM, SOURCE_FACT
    }
}
