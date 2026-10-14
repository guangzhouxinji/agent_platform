package cn.xinji.agentplatform.consultation.prompt.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record PromptRevision(Reference reference, Kind kind, String name, String body,
                             Set<String> scenarioIds, Set<String> intentIds, String audienceRef,
                             String language, int displayOrder, List<VariableDefinition> variables,
                             Provenance provenance) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public enum Kind { GUIDE, SYSTEM }
    public record Reference(String promptId, String revisionId, long version, String contentSha256) {
        public Reference {
            requireText(promptId); requireText(revisionId);
            if (version < 1 || contentSha256 == null || !contentSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("reference");
        }
    }
    public record Provenance(String generationSourceRef, String createdBy, Instant createdAt,
                             String modifiedBy, Instant modifiedAt, String changeDescription,
                             List<String> testRecordRefs, String contentApprovalRef, String retentionPolicyRef) {
        public Provenance {
            requireText(generationSourceRef); requireText(createdBy); requireText(modifiedBy);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(modifiedAt);
            if (modifiedAt.isBefore(createdAt)) throw new IllegalArgumentException("timestamps");
            requireText(changeDescription);
            testRecordRefs = List.copyOf(testRecordRefs);
        }
    }

    public PromptRevision {
        Objects.requireNonNull(reference); Objects.requireNonNull(kind); Objects.requireNonNull(provenance);
        requireText(name); requireText(body); requireText(audienceRef); requireText(language);
        scenarioIds = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(scenarioIds));
        intentIds = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(intentIds));
        variables = List.copyOf(variables);
        if (variables.stream().map(VariableDefinition::name).distinct().count() != variables.size())
            throw new PromptFailure("INVALID_TEMPLATE");
        if (!reference.contentSha256().equals(contentSha256(kind, name, body, scenarioIds, intentIds,
                audienceRef, language, displayOrder, variables))) throw new PromptFailure("REVISION_CONTENT_MISMATCH");
    }

    public static Reference referenceFor(String promptId, String revisionId, long version, Kind kind,
                                         String name, String body, Set<String> scenarioIds, Set<String> intentIds,
                                         String audienceRef, String language, int displayOrder,
                                         List<VariableDefinition> variables) {
        return new Reference(promptId, revisionId, version, contentSha256(kind, name, body, scenarioIds,
                intentIds, audienceRef, language, displayOrder, variables));
    }

    public static String contentSha256(Kind kind, String name, String body, Set<String> scenarios,
                                        Set<String> intents, String audience, String language, int order,
                                        List<VariableDefinition> variables) {
        var content = new LinkedHashMap<String, Object>();
        content.put("encoding", "PROMPT_CONTENT_JSON_V1");
        content.put("kind", kind); content.put("name", name); content.put("body", body);
        content.put("scenarioIds", new TreeSet<>(scenarios)); content.put("intentIds", new TreeSet<>(intents));
        content.put("audienceRef", audience); content.put("language", language); content.put("displayOrder", order);
        content.put("variables", variables.stream().map(v -> {
            var definition = new LinkedHashMap<String, Object>();
            definition.put("name", v.name()); definition.put("type", v.type());
            definition.put("required", v.required()); definition.put("sourceRef", v.sourceRef());
            definition.put("allowDefault", v.allowDefault()); definition.put("defaultValue", v.defaultValue());
            definition.put("allowEmpty", v.allowEmpty());
            return definition;
        }).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(content)));
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Prompt content encoding unavailable", failure);
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required text");
    }
}
