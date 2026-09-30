package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

public record PatentComparisonAction(String id, long version, String branchId, String subTaskId,
                                     String selectionReference, String purposeReference,
                                     List<Item> items, List<String> limitations) {
    public PatentComparisonAction {
        required(id); required(branchId); required(subTaskId); required(selectionReference);
        required(purposeReference);
        if (version < 1) throw new IllegalArgumentException("comparison version must be positive");
        items = List.copyOf(Objects.requireNonNull(items));
        limitations = List.copyOf(Objects.requireNonNull(limitations));
        if (items.isEmpty() || items.stream().map(Item::featureKey).distinct().count() != items.size()) {
            throw new IllegalArgumentException("comparison needs distinct feature items");
        }
    }

    public String key() { return id + "@" + version; }

    public record Item(String featureKey, String evidenceKey, Relationship relationship,
                       String reason, List<String> missing, String responsibilityReference) {
        public Item {
            required(featureKey); required(reason); required(responsibilityReference);
            Objects.requireNonNull(relationship, "relationship");
            missing = List.copyOf(Objects.requireNonNull(missing));
        }
    }

    public enum Relationship { SAME, DIFFERENT, UNKNOWN }

    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("comparison reference is required");
    }
}
