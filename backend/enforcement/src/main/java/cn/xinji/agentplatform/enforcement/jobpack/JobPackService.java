package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class JobPackService {
    public interface Access { void require(String actor, String organization, String id, String action); }
    public interface EvidenceVerification { void require(String packageId, Evidence evidence); }
    public record Create(String id, String organization, String title, String purpose) { }
    public record CreatePublic(String id, String organization, String title, String purpose,
                               PublicInput publicInput) { }
    public record FactInput(Map<String, String> fields, List<Evidence> evidence) { }
    public record VerificationInput(long expectedRevision, boolean accepted, String reason) { }
    public record Completion(long expectedRevision, String result, String receiptRef, String detail) { }

    private static final Map<FactType, List<String>> REQUIRED = Map.of(
            FactType.TASK, List.of("externalTaskRef", "externalTaskVersion", "originalTaskSha256", "currentProcess", "expectedOutput"),
            FactType.SOURCE_RIGHTS, List.of("sourceRef", "sourceVersion", "rightsHolder", "allowedUses", "scope", "validFrom", "validTo"),
            FactType.ACTOR_POSITION, List.of("personRef", "organizationRef", "positionTitle", "appointmentRef", "validFrom", "validTo"),
            FactType.APPROVAL, List.of("decision", "method", "reason", "targetContentRevision"),
            FactType.HUMAN_RECEIPT, List.of("personRef", "positionTitle", "text", "targetContentRevision"),
            FactType.BUSINESS_EVALUATION, List.of("completeness", "traceability", "operability", "accuracy", "humanEffort", "basis", "conclusion", "targetContentRevision"));

    private final JobPackStore store;
    private final Access access;
    private final EvidenceVerification evidenceVerification;
    private final Clock clock;

    public JobPackService(JobPackStore store, Access access, EvidenceVerification evidenceVerification, Clock clock) {
        this.store = store; this.access = access; this.evidenceVerification = evidenceVerification; this.clock = clock;
    }

    public static Design design() {
        return new Design("ENF-JOB-PACK-V1", State.DESIGN_READY,
                List.of("TASK", "SOURCE_RIGHTS", "ACTOR_POSITION", "APPROVAL", "HUMAN_RECEIPT", "BUSINESS_EVALUATION"),
                List.of("完整性", "可追溯性", "可操作性", "事实准确性", "人工投入"),
                List.of("原始任务与来源", "材料与证据引用", "程序与行为辅助结果", "人工批准、回执与评价", "缺口和限制"),
                List.of("缺许可", "虚构岗位", "本人核验", "本人批准", "系统生成回执", "过期或撤回", "摘要漂移", "旧版批准", "失权触发"));
    }

    public Receipt create(String actor, String key, Create input) {
        if (input == null || blank(key) || blank(input.id()) || blank(input.organization()) ||
                blank(input.title()) || blank(input.purpose())) throw new IllegalArgumentException("Complete package identity required");
        access.require(actor, input.organization(), input.id(), "CREATE");
        return store.command(key, hash(input.toString()), actor, "CREATE_JOB_PACK", () -> {
            if (store.latest(input.id()).isPresent()) throw new IllegalArgumentException("Package already exists");
            var snapshot = build(input.id(), 1, 0, input.organization(), input.title(), input.purpose(),
                    List.of(), null, null, null);
            store.add(snapshot);
            return receipt(key, "CREATE_JOB_PACK", snapshot);
        });
    }

    public Receipt createPublic(String actor, String key, CreatePublic input) {
        if (input == null || blank(key) || blank(input.id()) || blank(input.organization())
                || blank(input.title()) || blank(input.purpose()) || invalid(input.publicInput())
                || !actor.equals(input.publicInput().claimedBy()))
            throw new IllegalArgumentException("Complete claimed public package required");
        access.require(actor, input.organization(), input.id(), "CREATE");
        return store.command(key, hash(input.toString()), actor, "CREATE_PUBLIC_JOB_PACK", () -> {
            if (store.latest(input.id()).isPresent()) throw new IllegalArgumentException("Package already exists");
            var snapshot = build(input.id(), 1, 1, input.organization(), input.title(), input.purpose(),
                    List.of(), null, null, input.publicInput());
            store.add(snapshot);
            return receipt(key, "CREATE_PUBLIC_JOB_PACK", snapshot);
        });
    }

    public Receipt submit(String actor, String key, String id, FactType type, FactInput input) {
        if (blank(key) || input == null || input.fields() == null || input.evidence() == null)
            throw new IllegalArgumentException("Fact payload required");
        var current = required(id);
        access.require(actor, current.organization(), id, "COLLECT");
        var trustedFields = new TreeMap<>(input.fields());
        if (type == FactType.ACTOR_POSITION) {
            trustedFields.put("personRef", actor);
            trustedFields.put("organizationRef", current.organization());
        } else if (type == FactType.HUMAN_RECEIPT) {
            trustedFields.put("personRef", actor);
        }
        var fields = Map.copyOf(trustedFields);
        validate(type, fields, input.evidence(), actor, current);
        input.evidence().forEach(evidence -> evidenceVerification.require(id, evidence));
        return store.command(key, hash(type + fields.toString() + input.evidence()), actor, "SUBMIT_JOB_PACK_FACT", () -> {
            var latest = required(id);
            if (latest.revision() != current.revision()) throw new IllegalArgumentException("Package revision changed");
            var facts = latestFacts(latest);
            facts.put(type, new Fact(type, fields, List.copyOf(input.evidence()), actor, clock.instant(),
                    Verification.PENDING, null, null, null));
            long content = latest.contentRevision() + (contentFact(type) ? 1 : 0);
            var next = build(id, latest.revision() + 1, content, latest.organization(), latest.title(),
                    latest.purpose(), ordered(facts), null, null, latest.publicInput());
            store.add(next);
            return receipt(key, "SUBMIT_JOB_PACK_FACT", next);
        });
    }

    public Receipt verify(String actor, String key, String id, FactType type, VerificationInput input) {
        if (blank(key) || input == null || blank(input.reason())) throw new IllegalArgumentException("Verification reason required");
        var current = required(id);
        access.require(actor, current.organization(), id, "VERIFY");
        return store.command(key, hash(type + input.toString()), actor, "VERIFY_JOB_PACK_FACT", () -> {
            var latest = required(id);
            if (latest.revision() != input.expectedRevision()) throw new IllegalArgumentException("Package revision changed");
            var facts = latestFacts(latest);
            var fact = facts.get(type);
            if (fact == null) throw new IllegalArgumentException("Fact is missing");
            if (actor.equals(fact.submittedBy())) throw new IllegalArgumentException("Independent verifier required");
            facts.put(type, new Fact(type, fact.fields(), fact.evidence(), fact.submittedBy(), fact.submittedAt(),
                    input.accepted() ? Verification.VERIFIED : Verification.REJECTED,
                    actor, clock.instant(), input.reason()));
            var candidate = build(id, latest.revision() + 1, latest.contentRevision(), latest.organization(),
                    latest.title(), latest.purpose(), ordered(facts), null, null, latest.publicInput());
            if (candidate.state() == State.READY) {
                access.require(actor, latest.organization(), id, "TRIGGER");
                var trigger = new Trigger(UUID.randomUUID().toString(), "NEXT-V2-INT-ENF-01",
                        candidate.revision(), candidate.sha256(), clock.instant());
                candidate = build(id, candidate.revision(), candidate.contentRevision(), candidate.organization(),
                        candidate.title(), candidate.purpose(), candidate.facts(), trigger, null, candidate.publicInput());
            }
            store.add(candidate);
            return receipt(key, "VERIFY_JOB_PACK_FACT", candidate);
        });
    }

    public Snapshot read(String actor, String id) {
        var snapshot = required(id);
        access.require(actor, snapshot.organization(), id, "READ");
        return snapshot;
    }

    public Receipt complete(String actor, String key, String id, Completion input) {
        if (blank(key) || input == null || !("PASSED".equals(input.result()) || "FAILED".equals(input.result())) ||
                blank(input.receiptRef()) || blank(input.detail())) throw new IllegalArgumentException("Actual integration result required");
        var current = required(id);
        access.require(actor, current.organization(), id, "COMPLETE");
        return store.command(key, hash(input.toString()), actor, "COMPLETE_JOB_PACK_RUN", () -> {
            var latest = required(id);
            if (latest.revision() != input.expectedRevision() || latest.state() != State.RUNNING || latest.trigger() == null)
                throw new IllegalArgumentException("Running package revision required");
            var execution = new Execution(input.result(), input.receiptRef(), input.detail(), actor, clock.instant());
            var next = build(id, latest.revision() + 1, latest.contentRevision(), latest.organization(), latest.title(),
                    latest.purpose(), latest.facts(), latest.trigger(), execution, latest.publicInput());
            store.add(next);
            return receipt(key, "COMPLETE_JOB_PACK_RUN", next);
        });
    }

    private Snapshot build(String id, long revision, long contentRevision, String organization, String title,
                           String purpose, List<Fact> facts, Trigger trigger, Execution execution,
                           PublicInput publicInput) {
        var findings = findings(contentRevision, purpose, facts, publicInput);
        State state = execution == null ? (trigger == null ? state(facts, findings, publicInput) : State.RUNNING)
                : State.valueOf(execution.result());
        String digest = hash(id + "|" + revision + "|" + contentRevision + "|" + organization + "|" + title +
                "|" + purpose + "|" + facts + "|" + findings + "|" + publicInput);
        return new Snapshot(id, revision, contentRevision, organization, title, purpose, state,
                List.copyOf(facts), List.copyOf(findings), digest, trigger, execution, publicInput);
    }

    private List<String> findings(long contentRevision, String purpose, List<Fact> facts, PublicInput publicInput) {
        var byType = new EnumMap<FactType, Fact>(FactType.class);
        facts.forEach(f -> byType.put(f.type(), f));
        var result = new ArrayList<String>();
        var requiredTypes = requiredTypes(purpose, publicInput);
        for (var type : requiredTypes) {
            var fact = byType.get(type);
            if (fact == null) { result.add("MISSING:" + type); continue; }
            if (fact.verification() != Verification.VERIFIED) result.add("UNVERIFIED:" + type);
            for (String key : requiredFields(type, purpose))
                if (blank(fact.fields().get(key))) result.add("MISSING_FIELD:" + type + ":" + key);
            if ((type == FactType.SOURCE_RIGHTS || type == FactType.ACTOR_POSITION) && expired(fact.fields().get("validTo")))
                result.add("EXPIRED:" + type);
            String requiredUse = functional(purpose) ? "FUNCTIONAL_VALIDATION" : "REAL_INTEGRATION";
            if (type == FactType.SOURCE_RIGHTS && !fact.fields().getOrDefault("allowedUses", "").contains(requiredUse))
                result.add("USE_NOT_ALLOWED:SOURCE_RIGHTS");
            if ((type == FactType.APPROVAL || type == FactType.HUMAN_RECEIPT || type == FactType.BUSINESS_EVALUATION)
                    && !Long.toString(contentRevision).equals(fact.fields().get("targetContentRevision")))
                result.add("STALE_TARGET:" + type);
            if (type == FactType.APPROVAL && !"APPROVED".equals(fact.fields().get("decision"))) result.add("NOT_APPROVED");
            if (type == FactType.BUSINESS_EVALUATION) for (String metric : List.of("completeness", "traceability", "operability", "accuracy", "humanEffort"))
                if (!score(fact.fields().get(metric))) result.add("INVALID_SCORE:" + metric);
        }
        return result;
    }

    private static State state(List<Fact> facts, List<String> findings, PublicInput publicInput) {
        if (publicInput == null && facts.isEmpty()) return State.COLLECTING;
        if (findings.isEmpty()) return State.READY;
        return publicInput == null ? State.WAITING_DATA : State.WAITING_REAL_HUMAN_ACTION;
    }

    private static void validate(FactType type, Map<String, String> fields, List<Evidence> evidence,
                                 String actor, Snapshot current) {
        for (String key : requiredFields(type, current.purpose()))
            if (blank(fields.get(key))) throw new IllegalArgumentException("Missing field: " + key);
        if (evidence.isEmpty() && type != FactType.HUMAN_RECEIPT && type != FactType.BUSINESS_EVALUATION)
            throw new IllegalArgumentException("Evidence reference required");
        for (var item : evidence) if (item == null || blank(item.ref()) || !item.sha256().matches("[0-9a-f]{64}") || blank(item.fileKey()))
            throw new IllegalArgumentException("Exact evidence digest required");
        if ((type == FactType.ACTOR_POSITION || type == FactType.HUMAN_RECEIPT) && !actor.equals(fields.get("personRef")))
            throw new IllegalArgumentException("Logged-in person must submit own position or receipt");
        var task = current.facts().stream().filter(f -> f.type() == FactType.TASK).findFirst().orElse(null);
        if (type == FactType.APPROVAL && ((task != null && actor.equals(task.submittedBy()))
                || (current.publicInput() != null && actor.equals(current.publicInput().claimedBy()))))
            throw new IllegalArgumentException("Self approval is forbidden");
    }

    private static boolean invalid(PublicInput input) {
        return input == null || blank(input.candidateId()) || blank(input.sourceUrl()) || blank(input.sourceName())
                || blank(input.capturedAt()) || input.rawSha256() == null
                || !input.rawSha256().matches("[0-9a-f]{64}") || blank(input.rawFileKey())
                || blank(input.originalName()) || blank(input.collectionBatchId())
                || !"COLLECTION_ALLOWED".equals(input.collectionDecision())
                || input.publicFacts() == null || input.publicFacts().isEmpty() || blank(input.claimedBy());
    }

    private Snapshot required(String id) { return store.latest(id).orElseThrow(() -> new SecurityException("Package unavailable")); }
    private static EnumMap<FactType, Fact> latestFacts(Snapshot snapshot) {
        var facts = new EnumMap<FactType, Fact>(FactType.class); snapshot.facts().forEach(f -> facts.put(f.type(), f)); return facts;
    }
    private static List<Fact> ordered(Map<FactType, Fact> facts) {
        return facts.values().stream().sorted(Comparator.comparing(Fact::type)).toList();
    }
    private static boolean contentFact(FactType type) {
        return type == FactType.TASK || type == FactType.SOURCE_RIGHTS || type == FactType.ACTOR_POSITION;
    }
    private static List<FactType> requiredTypes(String purpose, PublicInput publicInput) {
        if (functional(purpose)) return List.of(FactType.TASK, FactType.SOURCE_RIGHTS, FactType.APPROVAL,
                FactType.HUMAN_RECEIPT, FactType.BUSINESS_EVALUATION);
        return publicInput == null ? List.of(FactType.values()) : List.of(FactType.ACTOR_POSITION,
                FactType.APPROVAL, FactType.HUMAN_RECEIPT, FactType.BUSINESS_EVALUATION);
    }
    private static List<String> requiredFields(FactType type, String purpose) {
        if (functional(purpose) && type == FactType.HUMAN_RECEIPT)
            return List.of("text", "targetContentRevision");
        return REQUIRED.get(type);
    }
    private static boolean functional(String purpose) { return "FUNCTIONAL_VALIDATION".equals(purpose); }
    private static boolean expired(String value) {
        try { return LocalDate.parse(value).isBefore(LocalDate.now()); } catch (RuntimeException invalid) { return true; }
    }
    private static boolean score(String value) {
        try { int parsed = Integer.parseInt(value); return parsed >= 1 && parsed <= 5; } catch (RuntimeException invalid) { return false; }
    }
    private static Receipt receipt(String key, String operation, Snapshot snapshot) {
        return new Receipt(key, operation, snapshot.id(), snapshot.revision(), snapshot.state());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
