package cn.xinji.agentplatform.enforcement.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EnforcementDiscoveryRuleService {
    public static final String ENGINE = "DETERMINISTIC_V1";
    public static final String USAGE = "DEVELOPMENT_ONLY";
    private static final List<String> CATEGORIES = List.of(
            "COUNTERFEIT_PATENT", "PATENT_INFRINGEMENT", "PUBLICITY_ISSUE");

    private final Store store;
    private final DeterministicRuleInterpreter interpreter;
    private final Clock clock;

    public EnforcementDiscoveryRuleService(Store store, Clock clock) {
        this.store = store;
        this.interpreter = new DeterministicRuleInterpreter();
        this.clock = Objects.requireNonNull(clock);
    }

    public List<RuleVersion> list(String actor, String organization, RuleFilter filter) {
        context(actor, organization);
        return store().list(organization, filter == null ? new RuleFilter(null, null, null, null) : filter);
    }

    public RuleVersion read(String actor, String organization, String ruleId, Integer version) {
        context(actor, organization);
        return store().find(organization, required(ruleId, "ruleId"), version)
                .orElseThrow(() -> failure("RULE_NOT_FOUND", "ruleId"));
    }

    public RuleVersion create(String actor, String organization, SaveCommand command) {
        context(actor, organization);
        validate(command);
        requireSources(organization, command.sourceIds(), false);
        String id = command.ruleId() == null || command.ruleId().isBlank()
                ? "ENF-RULE-" + UUID.randomUUID() : command.ruleId().trim();
        RuleVersion draft = version(id, organization, 1, 1, "DRAFT", command, actor, clock.instant());
        return store().create(draft, actor, clock.instant());
    }

    public RuleVersion update(String actor, String organization, String ruleId, int version, SaveCommand command) {
        context(actor, organization);
        validate(command);
        RuleVersion current = read(actor, organization, ruleId, version);
        if (!"DRAFT".equals(current.status())) throw failure("RULE_VERSION_IMMUTABLE", "status");
        if (command.expectedRevision() == null || command.expectedRevision() != current.revision()) {
            throw failure("VERSION_CONFLICT", "expectedRevision");
        }
        requireSources(organization, command.sourceIds(), false);
        RuleVersion updated = version(current.ruleId(), organization, version, current.revision() + 1,
                "DRAFT", command, actor, clock.instant());
        return store().update(updated, current.revision(), actor, clock.instant());
    }

    public RuleVersion copy(String actor, String organization, String ruleId, int version) {
        RuleVersion source = read(actor, organization, ruleId, version);
        int nextVersion = store().nextVersion(organization, ruleId);
        RuleVersion draft = new RuleVersion(ruleId, organization, nextVersion, 1, "DRAFT", source.ruleName(),
                source.category(), source.description(), ENGINE, USAGE, source.sourceIds(), source.condition(),
                source.contentDigest(), actor, clock.instant(), actor, clock.instant(), null, null);
        return store().createVersion(draft, actor, clock.instant(), version);
    }

    public DryRun dryRun(String actor, String organization, String ruleId, int version,
                         DryRunCommand command, String requestId) {
        context(actor, organization);
        RuleVersion rule = read(actor, organization, ruleId, version);
        if (command == null || blank(command.sampleId()) || blank(command.sampleVersion())
                || !"SYNTHETIC_LOCAL".equals(command.sampleOrigin()) || command.payload() == null) {
            throw failure("SAMPLE_INVALID", "sample");
        }
        requireSources(organization, rule.sourceIds(), true);
        DeterministicRuleInterpreter.Outcome outcome = interpreter.evaluate(rule.condition(), command.payload());
        Instant now = clock.instant();
        DryRun run = new DryRun(UUID.randomUUID().toString(), ruleId, organization, version, rule.revision(),
                rule.contentDigest(), command.sampleId().trim(), command.sampleVersion().trim(), "SYNTHETIC_LOCAL",
                digestPayload(command.payload()), outcome.status(), outcome.matched(), outcome.unhandledReason(),
                actor, now, required(requestId, "requestId"), outcome.results());
        return store().saveRun(run, command.payload(), actor, now);
    }

    public RuleVersion publish(String actor, String organization, String ruleId, int version, int expectedRevision) {
        RuleVersion rule = read(actor, organization, ruleId, version);
        if (!"DRAFT".equals(rule.status())) throw failure("INVALID_STATE_TRANSITION", "status");
        if (rule.revision() != expectedRevision) throw failure("VERSION_CONFLICT", "expectedRevision");
        requireSources(organization, rule.sourceIds(), true);
        if (!store().hasSuccessfulRun(organization, ruleId, version, rule.revision(), rule.contentDigest())) {
            throw failure("SUCCESSFUL_DRY_RUN_REQUIRED", "dryRun");
        }
        return store().transition(organization, ruleId, version, expectedRevision, "DRAFT", "PUBLISHED",
                actor, clock.instant());
    }

    public RuleVersion disable(String actor, String organization, String ruleId, int version, int expectedRevision) {
        RuleVersion rule = read(actor, organization, ruleId, version);
        if (!"PUBLISHED".equals(rule.status())) throw failure("INVALID_STATE_TRANSITION", "status");
        if (rule.revision() != expectedRevision) throw failure("VERSION_CONFLICT", "expectedRevision");
        return store().transition(organization, ruleId, version, expectedRevision, "PUBLISHED", "DISABLED",
                actor, clock.instant());
    }

    public List<RuleVersion> versions(String actor, String organization, String ruleId) {
        read(actor, organization, ruleId, null);
        return store().versions(organization, ruleId);
    }

    public List<DryRun> runs(String actor, String organization, String ruleId, int version) {
        read(actor, organization, ruleId, version);
        return store().runs(organization, ruleId, version);
    }

    private void requireSources(String organization, List<String> sourceIds, boolean healthy) {
        for (int index = 0; index < sourceIds.size(); index++) {
            int sourceIndex = index;
            SourceState source = store().source(organization, sourceIds.get(index))
                    .orElseThrow(() -> failure("SOURCE_NOT_FOUND", "sourceIds[" + sourceIndex + "]"));
            if (healthy && !source.enabled()) throw failure("SOURCE_DISABLED", "sourceIds[" + index + "]");
            if (healthy && !"HEALTHY".equals(source.healthStatus())) {
                throw failure("SOURCE_UNAVAILABLE", "sourceIds[" + index + "]");
            }
        }
    }

    private static RuleVersion version(String id, String organization, int version, int revision, String status,
                                       SaveCommand command, String actor, Instant now) {
        List<String> sources = command.sourceIds().stream().map(String::trim).distinct().sorted().toList();
        String digest = digest(id + "|" + version + "|" + command.ruleName().trim() + "|" + command.category()
                + "|" + Objects.toString(command.description(), "") + "|" + String.join(",", sources)
                + "|" + DeterministicRuleInterpreter.canonical(command.condition()));
        return new RuleVersion(id, organization, version, revision, status, command.ruleName().trim(),
                command.category(), normalized(command.description()), ENGINE, USAGE, sources, command.condition(),
                digest, actor, now, actor, now, null, null);
    }

    private static void validate(SaveCommand command) {
        if (command == null) throw failure("INVALID_INPUT", "body");
        required(command.ruleName(), "ruleName");
        if (!CATEGORIES.contains(command.category())) throw failure("CATEGORY_NOT_ALLOWED", "category");
        if (command.sourceIds() == null || command.sourceIds().isEmpty()
                || command.sourceIds().stream().anyMatch(EnforcementDiscoveryRuleService::blank)) {
            throw failure("INVALID_INPUT", "sourceIds");
        }
        new DeterministicRuleInterpreter().validate(command.condition());
    }

    private Store store() {
        if (store == null) throw failure("RULE_STORE_UNAVAILABLE", null);
        return store;
    }

    private static void context(String actor, String organization) {
        required(actor, "actor"); required(organization, "organization");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String required(String value, String field) {
        if (blank(value)) throw failure("INVALID_INPUT", field);
        return value.trim();
    }
    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String digestPayload(Map<String, Object> payload) {
        return digest(canonicalMap(payload));
    }
    private static String canonicalMap(Map<String, Object> value) {
        StringBuilder result = new StringBuilder("{");
        value.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.append(entry.getKey())
                .append('=').append(canonicalValue(entry.getValue())).append(';'));
        return result.append('}').toString();
    }
    @SuppressWarnings("unchecked")
    private static String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) return canonicalMap((Map<String, Object>) map);
        if (value instanceof List<?> list) return list.stream().map(EnforcementDiscoveryRuleService::canonicalValue).toList().toString();
        return Objects.toString(value, "null");
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public static RuleException failure(String code, String field) { return new RuleException(code, field); }

    public interface Store {
        List<RuleVersion> list(String organization, RuleFilter filter);
        Optional<RuleVersion> find(String organization, String ruleId, Integer version);
        RuleVersion create(RuleVersion version, String actor, Instant now);
        RuleVersion update(RuleVersion version, int expectedRevision, String actor, Instant now);
        int nextVersion(String organization, String ruleId);
        RuleVersion createVersion(RuleVersion version, String actor, Instant now, int copiedFromVersion);
        RuleVersion transition(String organization, String ruleId, int version, int expectedRevision,
                               String from, String to, String actor, Instant now);
        Optional<SourceState> source(String organization, String sourceId);
        DryRun saveRun(DryRun run, Map<String, Object> payload, String actor, Instant now);
        boolean hasSuccessfulRun(String organization, String ruleId, int version, int revision, String contentDigest);
        List<RuleVersion> versions(String organization, String ruleId);
        List<DryRun> runs(String organization, String ruleId, int version);
    }

    public record RuleFilter(String status, String category, String name, String sourceId) {}
    public record SaveCommand(String ruleId, String ruleName, String category, String description,
                              List<String> sourceIds, Condition condition, Integer expectedRevision) {
        public SaveCommand { sourceIds = sourceIds == null ? null : List.copyOf(sourceIds); }
    }
    public record Condition(String nodeId, String operator, String fieldPath, Object value, List<Condition> children) {
        public Condition { children = children == null ? List.of() : List.copyOf(children); }
    }
    public record RuleVersion(String ruleId, String organizationRef, int ruleVersion, int revision, String status,
                              String ruleName, String category, String description, String engineType, String usageScope,
                              List<String> sourceIds, Condition condition, String contentDigest, String createdBy,
                              Instant createdAt, String updatedBy, Instant updatedAt, String publishedBy,
                              Instant publishedAt) {
        public RuleVersion { sourceIds = List.copyOf(sourceIds); }
    }
    public record SourceState(String sourceId, boolean enabled, String healthStatus) {}
    public record DryRunCommand(String sampleId, String sampleVersion, String sampleOrigin,
                                Map<String, Object> payload) {
        public DryRunCommand { payload = payload == null ? null : Map.copyOf(payload); }
    }
    public record ConditionResult(String nodeId, String operator, String fieldPath, boolean matched,
                                  String reasonCode, Object actualValue) {}
    public record DryRun(String runId, String ruleId, String organizationRef, int ruleVersion, int ruleRevision,
                         String contentDigest, String sampleId, String sampleVersion, String sampleOrigin,
                         String inputDigest, String resultStatus, boolean matched, String unhandledReason,
                         String executedBy, Instant executedAt, String requestId, List<ConditionResult> conditionResults) {
        public DryRun { conditionResults = List.copyOf(conditionResults); }
    }
    public static final class RuleException extends RuntimeException {
        private final String code;
        private final String field;
        public RuleException(String code, String field) { super(code); this.code = code; this.field = field; }
        public String code() { return code; }
        public String field() { return field; }
    }
}
