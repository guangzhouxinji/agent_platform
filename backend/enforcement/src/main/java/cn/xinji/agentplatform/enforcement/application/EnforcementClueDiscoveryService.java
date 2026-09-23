package cn.xinji.agentplatform.enforcement.application;

import static cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.*;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class EnforcementClueDiscoveryService {
    public static final String USAGE = "DEVELOPMENT_ONLY";
    public static final String CONCLUSION = "NOT_AN_INFRINGEMENT_FINDING";
    private final Store store;
    private final DeterministicRuleInterpreter interpreter = new DeterministicRuleInterpreter();
    private final Clock clock;

    public EnforcementClueDiscoveryService(Store store, Clock clock) {
        this.store = store;
        this.clock = Objects.requireNonNull(clock);
    }

    public DiscoveryTask discover(String actor, String organization, String idempotencyKey,
                                  String requestId, DiscoveryCommand command) {
        context(actor, organization);
        validate(command);
        String inputDigest = digest(command.syncTaskIds().stream().sorted().toList() + "|"
                + command.ruleVersions().stream().sorted(Comparator.comparing(RuleRef::ruleId)
                .thenComparingInt(RuleRef::ruleVersion)).toList());
        Instant now = clock.instant();
        DiscoveryTask task = store().begin(organization, actor, required(idempotencyKey, "idempotencyKey"),
                inputDigest, required(requestId, "requestId"), command, now);
        if (!"queued".equals(task.status())) return task;

        PreparedInput input;
        try {
            input = store().prepare(organization, task.taskId(), command);
        } catch (DiscoveryException problem) {
            return store().complete(task.taskId(), organization,
                    List.of(new TaskItem(1, null, null, null, null, null, null, "FAILED",
                            problem.code(), retryable(problem.code()), null)), List.of(), now);
        }
        List<TaskItem> items = new ArrayList<>(input.upstreamFailures());
        List<String> leadIds = new ArrayList<>();
        int sequence = items.size();
        for (SourceSnapshot snapshot : input.snapshots()) {
            if ("WITHDRAWN".equals(snapshot.withdrawalStatus())) {
                store().propagateWithdrawal(organization, snapshot, actor, task.requestId(), now);
                items.add(new TaskItem(++sequence, snapshot.snapshotId(), snapshot.sourceId(),
                        snapshot.sourceRecordId(), snapshot.sourceVersion(), null, null, "WITHDRAWN",
                        "SOURCE_WITHDRAWN", false, null));
                continue;
            }
            List<RuleVersion> applicable = input.rules().stream()
                    .filter(rule -> rule.sourceIds().contains(snapshot.sourceId())).toList();
            if (applicable.isEmpty()) {
                items.add(new TaskItem(++sequence, snapshot.snapshotId(), snapshot.sourceId(),
                        snapshot.sourceRecordId(), snapshot.sourceVersion(), null, null, "NOT_APPLICABLE",
                        "RULE_NOT_APPLICABLE", false, null));
                continue;
            }
            List<RuleMatch> matches = new ArrayList<>();
            boolean failed = false;
            for (RuleVersion rule : applicable) {
                DeterministicRuleInterpreter.Outcome outcome;
                try { outcome = interpreter.evaluate(rule.condition(), snapshot.payload()); }
                catch (RuntimeException problem) {
                    items.add(item(++sequence, snapshot, rule, "FAILED", "RULE_EVALUATION_FAILED", false, null));
                    failed = true; continue;
                }
                if (!"SUCCEEDED".equals(outcome.status())) {
                    items.add(item(++sequence, snapshot, rule, "FAILED", outcome.unhandledReason(), false, null));
                    failed = true; continue;
                }
                if (outcome.matched()) {
                    matches.add(new RuleMatch(rule.ruleId(), rule.ruleVersion(), rule.revision(), rule.contentDigest(),
                            rule.category(), outcome.results()));
                } else {
                    items.add(item(++sequence, snapshot, rule, "NOT_MATCHED", "CONDITIONS_NOT_MATCHED", false, null));
                }
            }
            if (!matches.isEmpty()) {
                Lead lead = store().upsertLead(organization, actor, task.requestId(), snapshot,
                        excerpt(snapshot.payload()), matches, now);
                leadIds.add(lead.leadId());
                for (RuleMatch match : matches) {
                    items.add(item(++sequence, snapshot, input.rule(match.ruleId(), match.ruleVersion()),
                            "MATCHED", "RULE_MATCHED", false, lead.leadId()));
                }
            }
        }
        return store().complete(task.taskId(), organization, items, leadIds.stream().distinct().toList(), clock.instant());
    }

    public DiscoveryTask task(String actor, String organization, String taskId) {
        context(actor, organization);
        return store().task(organization, required(taskId, "taskId")).orElseThrow(() -> failure("TASK_NOT_FOUND", "taskId"));
    }
    public List<DiscoveryTask> tasks(String actor, String organization) { context(actor, organization); return store().tasks(organization); }
    public LeadPage leads(String actor, String organization, LeadFilter filter) {
        context(actor, organization); return store().leads(organization, filter == null ? new LeadFilter(null, null, null, null, 1, 20) : filter);
    }
    public LeadDetails lead(String actor, String organization, String leadId) {
        context(actor, organization); return store().lead(organization, required(leadId, "leadId"))
                .orElseThrow(() -> failure("LEAD_NOT_FOUND", "leadId"));
    }
    public Lead transition(String actor, String organization, String leadId, StatusCommand command, String requestId) {
        context(actor, organization);
        if (command == null || command.expectedVersion() < 1) throw failure("INVALID_INPUT", "body");
        Lead current = lead(actor, organization, leadId).lead();
        String next = switch (command.action()) {
            case "START_VERIFICATION" -> "VERIFYING"; case "TRANSFER" -> "TRANSFERRED";
            case "EXCLUDE" -> "EXCLUDED"; case "CLOSE" -> "CLOSED";
            default -> throw failure("ACTION_NOT_ALLOWED", "action");
        };
        if (!allowed(current.processingStatus(), next)) throw failure("INVALID_STATE_TRANSITION", "action");
        if (("EXCLUDED".equals(next) || "CLOSED".equals(next)) && blank(command.reason())) {
            throw failure("REASON_REQUIRED", "reason");
        }
        return store().transition(organization, leadId, command.expectedVersion(), current.processingStatus(), next,
                normalized(command.reason()), actor, required(requestId, "requestId"), clock.instant());
    }

    public DuplicateLink markDuplicate(String actor, String organization, DuplicateCommand command, String requestId) {
        context(actor, organization);
        if (command == null || blank(command.leftLeadId()) || blank(command.rightLeadId())
                || command.leftLeadId().equals(command.rightLeadId()) || blank(command.reason())) {
            throw failure("INVALID_INPUT", "duplicate");
        }
        lead(actor, organization, command.leftLeadId()); lead(actor, organization, command.rightLeadId());
        return store().markDuplicate(organization, command, actor, required(requestId, "requestId"), clock.instant());
    }
    public DuplicateLink reviewDuplicate(String actor, String organization, String linkId,
                                         DuplicateReview command, String requestId) {
        context(actor, organization);
        if (command == null || command.expectedVersion() < 1 || !List.of("CONFIRMED", "REJECTED").contains(command.action())) {
            throw failure("INVALID_INPUT", "duplicateReview");
        }
        return store().reviewDuplicate(organization, required(linkId, "linkId"), command, actor,
                required(requestId, "requestId"), clock.instant());
    }

    private static TaskItem item(int sequence, SourceSnapshot snapshot, RuleVersion rule, String status,
                                 String reason, boolean retryable, String leadId) {
        return new TaskItem(sequence, snapshot.snapshotId(), snapshot.sourceId(), snapshot.sourceRecordId(),
                snapshot.sourceVersion(), rule.ruleId(), rule.ruleVersion(), status, reason, retryable, leadId);
    }
    private static boolean allowed(String from, String to) {
        return switch (from) {
            case "PENDING_VERIFICATION" -> List.of("VERIFYING", "EXCLUDED", "CLOSED").contains(to);
            case "VERIFYING" -> List.of("TRANSFERRED", "EXCLUDED", "CLOSED").contains(to);
            case "TRANSFERRED" -> "CLOSED".equals(to); default -> false;
        };
    }
    private static String excerpt(Map<String, Object> payload) {
        Object content = payload.get("content");
        if (!(content instanceof String text)) return null;
        String normalized = text.strip(); return normalized.substring(0, Math.min(1000, normalized.length()));
    }
    private static void validate(DiscoveryCommand command) {
        if (command == null || command.syncTaskIds() == null || command.syncTaskIds().isEmpty()
                || command.ruleVersions() == null || command.ruleVersions().isEmpty()) throw failure("INVALID_INPUT", "body");
        if (command.syncTaskIds().stream().anyMatch(EnforcementClueDiscoveryService::blank)) throw failure("INVALID_INPUT", "syncTaskIds");
        if (command.ruleVersions().stream().anyMatch(ref -> ref == null || blank(ref.ruleId()) || ref.ruleVersion() < 1)) throw failure("INVALID_INPUT", "ruleVersions");
    }
    private Store store() { if (store == null) throw failure("DISCOVERY_STORE_UNAVAILABLE", null); return store; }
    private static void context(String actor, String organization) { required(actor, "actor"); required(organization, "organization"); }
    private static boolean retryable(String code) { return List.of("SOURCE_UNAVAILABLE", "DISCOVERY_STORE_UNAVAILABLE").contains(code); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalized(String value) { return blank(value) ? null : value.trim(); }
    private static String required(String value, String field) { if (blank(value)) throw failure("INVALID_INPUT", field); return value.trim(); }
    public static DiscoveryException failure(String code, String field) { return new DiscoveryException(code, field); }

    public interface Store {
        DiscoveryTask begin(String organization, String actor, String idempotencyKey, String inputDigest,
                            String requestId, DiscoveryCommand command, Instant now);
        PreparedInput prepare(String organization, String taskId, DiscoveryCommand command);
        Lead upsertLead(String organization, String actor, String requestId, SourceSnapshot snapshot,
                        String excerpt, List<RuleMatch> matches, Instant now);
        void propagateWithdrawal(String organization, SourceSnapshot snapshot, String actor, String requestId, Instant now);
        DiscoveryTask complete(String taskId, String organization, List<TaskItem> items, List<String> leadIds, Instant now);
        Optional<DiscoveryTask> task(String organization, String taskId);
        List<DiscoveryTask> tasks(String organization);
        LeadPage leads(String organization, LeadFilter filter);
        Optional<LeadDetails> lead(String organization, String leadId);
        Lead transition(String organization, String leadId, int expectedVersion, String from, String to,
                        String reason, String actor, String requestId, Instant now);
        DuplicateLink markDuplicate(String organization, DuplicateCommand command, String actor, String requestId, Instant now);
        DuplicateLink reviewDuplicate(String organization, String linkId, DuplicateReview command,
                                      String actor, String requestId, Instant now);
    }

    public record DiscoveryCommand(List<String> syncTaskIds, List<RuleRef> ruleVersions) {
        public DiscoveryCommand { syncTaskIds = syncTaskIds == null ? null : List.copyOf(syncTaskIds); ruleVersions = ruleVersions == null ? null : List.copyOf(ruleVersions); }
    }
    public record RuleRef(String ruleId, int ruleVersion) {}
    public record SourceSnapshot(String snapshotId, String sourceId, String sourceRecordId, String sourceVersion,
                                 String contentDigest, String withdrawalStatus, Map<String, Object> payload) {
        public SourceSnapshot { payload = Map.copyOf(payload); }
    }
    public record PreparedInput(List<SourceSnapshot> snapshots, List<RuleVersion> rules, List<TaskItem> upstreamFailures) {
        public PreparedInput { snapshots = List.copyOf(snapshots); rules = List.copyOf(rules); upstreamFailures = List.copyOf(upstreamFailures); }
        RuleVersion rule(String id, int version) { return rules.stream().filter(value -> value.ruleId().equals(id) && value.ruleVersion() == version).findFirst().orElseThrow(); }
    }
    public record RuleMatch(String ruleId, int ruleVersion, int ruleRevision, String ruleDigest,
                            String suspectedType, List<ConditionResult> conditionResults) {
        public RuleMatch { conditionResults = List.copyOf(conditionResults); }
    }
    public record TaskItem(int sequence, String snapshotId, String sourceId, String sourceRecordId, String sourceVersion,
                           String ruleId, Integer ruleVersion, String status, String reasonCode, boolean retryable, String leadId) {}
    public record DiscoveryTask(String taskId, String organizationRef, String requesterRef, String status,
                                int processedCount, int matchedCount, int leadCount, int failedCount,
                                String inputDigest, String requestId, Instant createdAt, Instant startedAt,
                                Instant completedAt, List<TaskItem> items, List<String> leadIds) {
        public DiscoveryTask { items = items == null ? List.of() : List.copyOf(items); leadIds = leadIds == null ? List.of() : List.copyOf(leadIds); }
    }
    public record Lead(String leadId, String organizationRef, String sourceId, String sourceRecordId,
                       String sourceVersion, String snapshotId, String snapshotDigest, String sourceAvailability,
                       String sourceExcerpt, List<String> suspectedTypes, String processingStatus,
                       boolean reviewRequired, String usageScope, String conclusionScope, Instant firstFoundAt,
                       int version, String updatedBy, Instant updatedAt, String requestId) {
        public Lead { suspectedTypes = List.copyOf(suspectedTypes); }
    }
    public record LeadDetails(Lead lead, List<RuleMatch> matches, List<StatusHistory> history,
                              List<DuplicateLink> duplicateLinks, String notificationCapability) {}
    public record LeadFilter(String status, String suspectedType, String sourceId, String keyword, int page, int pageSize) {}
    public record LeadPage(List<Lead> items, long total, int page, int pageSize) { public LeadPage { items = List.copyOf(items); } }
    public record StatusCommand(String action, int expectedVersion, String reason) {}
    public record StatusHistory(int leadVersion, String fromStatus, String toStatus, String reason,
                                String actor, Instant occurredAt, String requestId) {}
    public record DuplicateCommand(String leftLeadId, String rightLeadId, String reason) {}
    public record DuplicateReview(String action, int expectedVersion, String reason) {}
    public record DuplicateLink(String linkId, String leftLeadId, String rightLeadId, String status,
                                int version, String reason, String markedBy, Instant markedAt,
                                String reviewedBy, Instant reviewedAt) {}
    public static final class DiscoveryException extends RuntimeException {
        private final String code; private final String field;
        public DiscoveryException(String code, String field) { super(code); this.code = code; this.field = field; }
        public String code() { return code; } public String field() { return field; }
    }
}
