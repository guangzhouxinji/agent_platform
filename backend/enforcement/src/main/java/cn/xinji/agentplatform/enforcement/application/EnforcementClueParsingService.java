package cn.xinji.agentplatform.enforcement.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class EnforcementClueParsingService {
    public static final String PARSER_VERSION = "STRUCTURED_LOCAL_V1";
    private final Store store;
    private final Clock clock;

    public EnforcementClueParsingService(Store store, Clock clock) {
        this.store = store;
        this.clock = Objects.requireNonNull(clock);
    }

    public ParseTask parse(String actor, String organization, String leadId, String idempotencyKey, String requestId) {
        context(actor, organization);
        Instant now = clock.instant();
        PreparedTask prepared = store().prepare(organization, actor, required(leadId, "leadId"),
                required(idempotencyKey, "idempotencyKey"), required(requestId, "requestId"), PARSER_VERSION, now);
        if (prepared.replay()) return prepared.task();
        Extraction extraction = extract(prepared.input());
        return store().complete(prepared.task().taskId(), organization, actor, requestId, extraction, now);
    }

    public List<ParseTask> tasks(String actor, String organization, String leadId) {
        context(actor, organization);
        return store().tasks(organization, required(leadId, "leadId"));
    }

    public List<ParseResult> results(String actor, String organization, String leadId) {
        context(actor, organization);
        return store().results(organization, required(leadId, "leadId"));
    }

    public ParseResult result(String actor, String organization, String leadId, int version) {
        context(actor, organization);
        if (version < 1) throw failure("INVALID_INPUT", "version");
        return store().result(organization, required(leadId, "leadId"), version)
                .orElseThrow(() -> failure("PARSE_RESULT_NOT_FOUND", "version"));
    }

    public ParseResult revise(String actor, String organization, String leadId, RevisionCommand command, String requestId) {
        context(actor, organization);
        if (command == null || command.expectedVersion() < 1 || blank(command.reason()) || command.fields() == null) {
            throw failure("INVALID_INPUT", "body");
        }
        List<FieldValue> fields = new ArrayList<>();
        int order = 0;
        for (FieldInput input : command.fields()) {
            if (input == null || !FIELD_KINDS.contains(input.kind()) || blank(input.rawValue())) {
                throw failure("INVALID_FIELD", "fields");
            }
            if ("SUBJECT".equals(input.kind()) && blank(input.role())) throw failure("ROLE_REQUIRED", "fields.role");
            fields.add(new FieldValue(input.kind(), ++order, normalized(input.role()), input.rawValue().trim(),
                    normalized(input.normalizedValue()), normalized(input.creditCode()), input.rawValue().trim(),
                    "humanRevision.fields[" + (order - 1) + "]", "HUMAN_REVISION",
                    "EVIDENCE_REFERENCE".equals(input.kind()) ? "PENDING_CONNECTION" : null,
                    null, null, null));
        }
        return store().revise(organization, leadId, command.expectedVersion(), fields, command.reason().trim(),
                actor, required(requestId, "requestId"), clock.instant());
    }

    public ParseResult review(String actor, String organization, String leadId, int version,
                              ReviewCommand command, String requestId) {
        context(actor, organization);
        if (command == null || command.expectedVersion() != version
                || !List.of("CONFIRM", "RETURN").contains(command.action())) {
            throw failure("INVALID_INPUT", "body");
        }
        if ("RETURN".equals(command.action()) && blank(command.reason())) throw failure("REASON_REQUIRED", "reason");
        return store().review(organization, leadId, version, command.action(), normalized(command.reason()),
                actor, required(requestId, "requestId"), clock.instant());
    }

    private static Extraction extract(ParseInput input) {
        List<FieldValue> fields = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        int[] order = {0};
        subjects(input.payload().get("subjects"), fields, issues, order, input);
        simpleValues("FACT", "facts", input.payload().get("facts"), fields, issues, order, input);
        structuredValues("TIME", "eventTimes", input.payload().get("eventTimes"), fields, issues, order, true, input);
        structuredValues("PLACE", "places", input.payload().get("places"), fields, issues, order, false, input);
        evidence(input.payload().get("evidenceRefs"), fields, issues, order, input);
        for (ClassificationInput classification : input.classifications().stream()
                .sorted(Comparator.comparing(ClassificationInput::suspectedType).thenComparing(ClassificationInput::ruleId))
                .toList()) {
            fields.add(new FieldValue("CLASSIFICATION", ++order[0], null, classification.suspectedType(), null, null,
                    classification.suspectedType(), "lead.matches[" + classification.ruleId() + "@"
                    + classification.ruleVersion() + "]", "RULE_MATCH", null, classification.ruleId(),
                    classification.ruleVersion() + "." + classification.ruleRevision(), classification.ruleDigest()));
        }
        return new Extraction(fields, issues, "TO_BE_EVALUATED", "FACTORS_NOT_CONFIRMED", true,
                null, null, "NO_MODEL_USED");
    }

    private static void subjects(Object value, List<FieldValue> fields, List<ParseIssue> issues, int[] order, ParseInput input) {
        if (value == null) { issues.add(issue("MISSING", "subjects", "SUBJECTS_MISSING")); return; }
        if (!(value instanceof List<?> values)) { issues.add(issue("TYPE_ERROR", "subjects", "SUBJECTS_NOT_ARRAY")); return; }
        int index = 0;
        for (Object item : values) {
            String path = "subjects[" + index++ + "]";
            if (!(item instanceof Map<?, ?> map) || blank(text(map.get("role"))) || blank(text(map.get("name")))) {
                issues.add(issue("UNPROCESSED", path, "SUBJECT_ROLE_OR_NAME_MISSING")); continue;
            }
            String name = text(map.get("name")).trim();
            fields.add(new FieldValue("SUBJECT", ++order[0], text(map.get("role")).trim(), name, null,
                    normalized(text(map.get("creditCode"))), name, path, "SOURCE_SNAPSHOT", null,
                    input.snapshotId(), null, input.snapshotDigest()));
        }
        if (values.isEmpty()) issues.add(issue("MISSING", "subjects", "SUBJECTS_EMPTY"));
    }

    private static void simpleValues(String kind, String key, Object value, List<FieldValue> fields,
                                     List<ParseIssue> issues, int[] order, ParseInput input) {
        if (value == null) { issues.add(issue("MISSING", key, key.toUpperCase() + "_MISSING")); return; }
        if (!(value instanceof List<?> values)) { issues.add(issue("TYPE_ERROR", key, key.toUpperCase() + "_NOT_ARRAY")); return; }
        int index = 0;
        for (Object item : values) {
            String path = key + "[" + index++ + "]";
            String raw = item instanceof String text ? text : item instanceof Map<?, ?> map ? text(map.get("rawValue")) : null;
            if (blank(raw)) { issues.add(issue("UNPROCESSED", path, kind + "_VALUE_MISSING")); continue; }
            fields.add(new FieldValue(kind, ++order[0], null, raw.trim(), null, null, raw.trim(), path,
                    "SOURCE_SNAPSHOT", null, input.snapshotId(), null, input.snapshotDigest()));
        }
        if (values.isEmpty()) issues.add(issue("MISSING", key, key.toUpperCase() + "_EMPTY"));
    }

    private static void structuredValues(String kind, String key, Object value, List<FieldValue> fields,
                                         List<ParseIssue> issues, int[] order, boolean validateTime, ParseInput input) {
        if (value == null) { issues.add(issue("MISSING", key, key.toUpperCase() + "_MISSING")); return; }
        if (!(value instanceof List<?> values)) { issues.add(issue("TYPE_ERROR", key, key.toUpperCase() + "_NOT_ARRAY")); return; }
        int index = 0;
        for (Object item : values) {
            String path = key + "[" + index++ + "]";
            if (!(item instanceof Map<?, ?> map) || blank(text(map.get("rawValue")))) {
                issues.add(issue("UNPROCESSED", path, kind + "_RAW_VALUE_MISSING")); continue;
            }
            String raw = text(map.get("rawValue")).trim();
            String normalized = normalized(text(map.get("normalizedValue")));
            if (validateTime && normalized != null && !validIso(normalized)) {
                issues.add(issue("NORMALIZATION_FAILED", path + ".normalizedValue", "TIME_NORMALIZED_VALUE_INVALID"));
                normalized = null;
            }
            fields.add(new FieldValue(kind, ++order[0], null, raw, normalized, null, raw, path,
                    "SOURCE_SNAPSHOT", null, input.snapshotId(), null, input.snapshotDigest()));
        }
        if (values.isEmpty()) issues.add(issue("MISSING", key, key.toUpperCase() + "_EMPTY"));
    }

    private static void evidence(Object value, List<FieldValue> fields, List<ParseIssue> issues, int[] order, ParseInput input) {
        if (value == null) { issues.add(issue("MISSING", "evidenceRefs", "EVIDENCE_REFS_MISSING")); return; }
        if (!(value instanceof List<?> values)) { issues.add(issue("TYPE_ERROR", "evidenceRefs", "EVIDENCE_REFS_NOT_ARRAY")); return; }
        int index = 0;
        for (Object item : values) {
            String path = "evidenceRefs[" + index++ + "]";
            String ref = item instanceof String text ? text : item instanceof Map<?, ?> map ? text(map.get("referenceId")) : null;
            if (blank(ref)) { issues.add(issue("UNPROCESSED", path, "EVIDENCE_REFERENCE_MISSING")); continue; }
            fields.add(new FieldValue("EVIDENCE_REFERENCE", ++order[0], null, ref.trim(), null, null,
                    ref.trim(), path, "SOURCE_SNAPSHOT", "PENDING_CONNECTION",
                    input.snapshotId(), null, input.snapshotDigest()));
        }
        if (values.isEmpty()) issues.add(issue("MISSING", "evidenceRefs", "EVIDENCE_REFS_EMPTY"));
    }

    private static boolean validIso(String value) {
        try { LocalDate.parse(value); return true; } catch (Exception ignored) { }
        try { OffsetDateTime.parse(value); return true; } catch (Exception ignored) { return false; }
    }

    private static ParseIssue issue(String kind, String path, String code) { return new ParseIssue(kind, path, code); }
    private Store store() { if (store == null) throw failure("PARSING_STORE_UNAVAILABLE", null); return store; }
    private static void context(String actor, String organization) { required(actor, "actor"); required(organization, "organization"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalized(String value) { return blank(value) ? null : value.trim(); }
    private static String text(Object value) { return value instanceof String text ? text : null; }
    private static String required(String value, String field) { if (blank(value)) throw failure("INVALID_INPUT", field); return value.trim(); }
    public static ParsingException failure(String code, String field) { return new ParsingException(code, field); }
    private static final List<String> FIELD_KINDS = List.of("SUBJECT", "FACT", "TIME", "PLACE", "EVIDENCE_REFERENCE", "CLASSIFICATION");

    public interface Store {
        PreparedTask prepare(String organization, String actor, String leadId, String idempotencyKey,
                             String requestId, String parserVersion, Instant now);
        ParseTask complete(String taskId, String organization, String actor, String requestId,
                           Extraction extraction, Instant now);
        List<ParseTask> tasks(String organization, String leadId);
        List<ParseResult> results(String organization, String leadId);
        Optional<ParseResult> result(String organization, String leadId, int version);
        ParseResult revise(String organization, String leadId, int expectedVersion, List<FieldValue> fields,
                           String reason, String actor, String requestId, Instant now);
        ParseResult review(String organization, String leadId, int version, String action, String reason,
                           String actor, String requestId, Instant now);
    }

    public record ParseInput(String leadId, int leadVersion, String snapshotId, String snapshotDigest,
                             List<ClassificationInput> classifications, Map<String, Object> payload) {
        public ParseInput { classifications = List.copyOf(classifications); payload = Map.copyOf(payload); }
    }
    public record ClassificationInput(String suspectedType, String ruleId, int ruleVersion,
                                      int ruleRevision, String ruleDigest) {}
    public record PreparedTask(ParseTask task, ParseInput input, boolean replay) {}
    public record Extraction(List<FieldValue> fields, List<ParseIssue> issues, String priorityLevel,
                             String priorityReason, boolean notLegalProbability, String modelId,
                             String modelVersion, String modelUsage) {
        public Extraction { fields = List.copyOf(fields); issues = List.copyOf(issues); }
    }
    public record FieldValue(String kind, int order, String role, String rawValue, String normalizedValue,
                             String creditCode, String sourceFragment, String fieldPath, String sourceKind,
                             String referenceStatus, String sourceRef, String sourceVersion, String sourceDigest) {}
    public record ParseIssue(String kind, String fieldPath, String reasonCode) {}
    public record ParseTask(String taskId, String leadId, String status, int resultVersion, int fieldCount,
                            int issueCount, String errorCode, String parserVersion, String requestId,
                            Instant createdAt, Instant completedAt) {}
    public record ParseResult(String resultId, String leadId, int version, String previousResultId,
                              String revisionKind, String reviewStatus, String parserVersion,
                              String modelId, String modelVersion, String modelUsage,
                              String priorityLevel, String priorityReason, boolean notLegalProbability,
                              boolean reviewRequired, List<FieldValue> fields, List<ParseIssue> issues,
                              String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                              String requestId) {
        public ParseResult { fields = List.copyOf(fields); issues = List.copyOf(issues); }
    }
    public record FieldInput(String kind, String role, String rawValue, String normalizedValue, String creditCode) {}
    public record RevisionCommand(int expectedVersion, String reason, List<FieldInput> fields) {
        public RevisionCommand { fields = fields == null ? null : List.copyOf(fields); }
    }
    public record ReviewCommand(String action, int expectedVersion, String reason) {}
    public static final class ParsingException extends RuntimeException {
        private final String code; private final String field;
        public ParsingException(String code, String field) { super(code); this.code = code; this.field = field; }
        public String code() { return code; } public String field() { return field; }
    }
}
