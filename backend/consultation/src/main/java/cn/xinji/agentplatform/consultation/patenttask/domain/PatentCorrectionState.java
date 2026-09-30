package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.*;

/** Immutable, task-local dependency history. Keys identify artifact versions, never display ordinals. */
public record PatentCorrectionState(Map<String, PatentCorrectionAction.Artifact> artifacts,
                                    Set<String> invalidated, List<Impact> impacts) {
    public PatentCorrectionState {
        artifacts = Map.copyOf(artifacts);
        invalidated = Set.copyOf(invalidated);
        impacts = List.copyOf(impacts);
    }

    public static PatentCorrectionState empty() {
        return new PatentCorrectionState(Map.of(), Set.of(), List.of());
    }

    public PatentCorrectionState apply(PatentCorrectionAction action) {
        PatentCorrectionAction.Artifact next = action.artifact();
        Map<String, PatentCorrectionAction.Artifact> records = new LinkedHashMap<>(artifacts);
        if (records.containsKey(next.key())) throw new IllegalArgumentException("artifact version already exists");
        for (String dependency : next.dependsOn()) {
            PatentCorrectionAction.Artifact parent = records.get(dependency);
            if (parent == null || !parent.branchId().equals(next.branchId()) || invalidated.contains(dependency)) {
                throw new IllegalArgumentException("dependency is missing, invalid or belongs to another branch");
            }
        }
        if (action.kind() == PatentCorrectionAction.Kind.REGISTER) {
            records.put(next.key(), next);
            return new PatentCorrectionState(records, invalidated, impacts);
        }
        String replacedKey = action.replacedArtifactId() + "@" + action.replacedVersion();
        PatentCorrectionAction.Artifact previous = records.get(replacedKey);
        if (previous == null || invalidated.contains(replacedKey) || previous.type() != next.type()
                || !previous.branchId().equals(next.branchId())
                || !previous.id().equals(next.id()) || next.version() <= previous.version()
                || next.dependsOn().contains(replacedKey)
                || !Set.of(PatentCorrectionAction.Type.FEATURE, PatentCorrectionAction.Type.SCOPE,
                    PatentCorrectionAction.Type.CANDIDATE, PatentCorrectionAction.Type.REFERENCE,
                    PatentCorrectionAction.Type.GOAL, PatentCorrectionAction.Type.OBJECT).contains(next.type())) {
            throw new IllegalArgumentException("correction must replace an active input version in the same branch");
        }
        records.put(next.key(), next);
        Set<String> affected = new LinkedHashSet<>();
        affected.add(replacedKey);
        boolean changed;
        do {
            changed = false;
            for (var entry : records.entrySet()) {
                if (!affected.contains(entry.getKey()) && entry.getValue().dependsOn().stream().anyMatch(affected::contains)) {
                    changed |= affected.add(entry.getKey());
                }
            }
        } while (changed);
        Set<String> currentInvalid = new LinkedHashSet<>(invalidated);
        currentInvalid.addAll(affected);
        Set<String> retained = new LinkedHashSet<>(records.keySet());
        retained.removeAll(currentInvalid);
        List<String> actions = affected.stream().filter(key -> !key.equals(replacedKey))
                .map(key -> records.get(key).type().name() + ":" + key).toList();
        List<Impact> history = new ArrayList<>(impacts);
        history.add(new Impact(replacedKey, next.key(), action.reason(), List.copyOf(affected),
                List.copyOf(retained), actions));
        return new PatentCorrectionState(records, currentInvalid, history);
    }

    public boolean usable(String key) { return artifacts.containsKey(key) && !invalidated.contains(key); }

    public record Impact(String replaced, String replacement, String reason, List<String> invalidated,
                         List<String> retained, List<String> recomputeActions) {
        public Impact {
            invalidated = List.copyOf(invalidated);
            retained = List.copyOf(retained);
            recomputeActions = List.copyOf(recomputeActions);
        }
    }
}
