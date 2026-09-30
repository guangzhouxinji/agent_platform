package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ProcedureService {
    public interface Access {
        void require(String actor, String organization, String type, String id, long version,
                     String action, String purpose);
    }
    public interface BasisVerification { void require(Basis basis, Rule rule); }
    public record TrialRequest(String caseRef, String caseVersion, String calendarRef, String calendarVersion) { }
    public record CheckRequest(String caseRef, String caseVersion, String ruleId, long ruleVersion,
                               String calendarRef, String calendarVersion) { }
    public record Rectification(String reason, List<String> evidenceRefs, long expectedVersion) { }
    public record Publication(List<TrialRequest> positive, List<TrialRequest> negative,
                              String reviewRef, String reviewer) { }

    private final ProcedureStore store;
    private final ProcedureSource source;
    private final Access access;
    private final BasisVerification basis;
    private final ProcedureEvaluator evaluator = new ProcedureEvaluator();

    public ProcedureService(ProcedureStore store, ProcedureSource source, Access access,
                            BasisVerification basis) {
        this.store = store;
        this.source = source;
        this.access = access;
        this.basis = basis;
    }

    public Receipt draft(String actor, String key, Rule input) {
        if (blank(actor) || blank(key) || input == null || blank(input.id()) ||
                blank(input.organization()) || blank(input.procedure()) || blank(input.stage()) ||
                blank(input.region()) || input.requiredEvents() == null || input.requiredMaterials() == null)
            throw new IllegalArgumentException("Invalid procedure draft");
        var receipt = store.command(key, hash(input.toString()), actor, "DRAFT_PROCEDURE_RULE", () -> {
            var prior = store.latestRule(input.id());
            if (prior.isPresent() && (!"DISABLED".equals(prior.get().state()) &&
                    !"ACTIVE".equals(prior.get().state())))
                throw new IllegalArgumentException("Previous draft must be resolved");
            if (prior.isPresent() && !prior.get().organization().equals(input.organization()))
                throw new SecurityException("Organization mismatch");
            long version = prior.map(r -> r.version() + 1).orElse(1L);
            access.require(actor, input.organization(), "PROCEDURE_RULE", input.id(), version,
                    "EDIT_RULE", "RULE_MANAGEMENT");
            store.addRule(new Rule(input.id(), version, input.organization(), input.region(),
                    input.procedure(), input.stage(), "DRAFT", input.basis(), input.clock(),
                    List.copyOf(input.requiredEvents()), List.copyOf(input.requiredMaterials()),
                    actor, null, null));
            return new Receipt(key, "DRAFT_PROCEDURE_RULE", input.id(), version);
        });
        access.require(actor, input.organization(), "PROCEDURE_RULE", input.id(), receipt.version(),
                "EDIT_RULE", "RULE_MANAGEMENT");
        return receipt;
    }

    public Rule readRule(String actor, String id, long version) {
        var rule = store.rule(id, version).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, rule.organization(), "PROCEDURE_RULE", id, version,
                "READ_RULE", "RULE_MANAGEMENT");
        return rule;
    }

    public Trial trial(String actor, String id, long version, TrialRequest request) {
        var rule = readRule(actor, id, version);
        access.require(actor, rule.organization(), "PROCEDURE_RULE", id, version,
                "TRIAL_RULE", "RULE_MANAGEMENT");
        var snapshot = snapshot(request);
        if (!rule.organization().equals(snapshot.organization())) throw new SecurityException("Case scope mismatch");
        access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                "READ_CASE", "PROCEDURE_CHECK");
        return evaluator.trial(rule, snapshot, calendar(request));
    }

    public Receipt publish(String actor, String key, String id, long version, Publication publication) {
        if (blank(key) || publication == null || blank(publication.reviewRef()) ||
                blank(publication.reviewer()) || publication.positive() == null ||
                publication.negative() == null || publication.positive().isEmpty() ||
                publication.negative().isEmpty()) throw new IllegalArgumentException("Boundary review required");
        var draft = readRule(actor, id, version);
        var receipt = store.command(key, hash(id + version + publication), actor, "PUBLISH_PROCEDURE_RULE", () -> {
            var current = store.latestRule(id).orElseThrow();
            if (!"DRAFT".equals(current.state()) || current.version() != version)
                throw new IllegalArgumentException("Exact current draft required");
            access.require(actor, draft.organization(), "PROCEDURE_RULE", id, version,
                    "PUBLISH_RULE", "RULE_MANAGEMENT");
            verifyComplete(draft);
            basis.require(draft.basis(), draft);
            if (store.activeRules(draft.organization(), draft.region(), draft.procedure(), draft.stage())
                    .stream().anyMatch(other -> !other.id().equals(draft.id()) &&
                            !other.basis().effectiveTo().isBefore(draft.basis().effectiveFrom()) &&
                            !draft.basis().effectiveTo().isBefore(other.basis().effectiveFrom())))
                throw new IllegalArgumentException("Overlapping applicable rule requires resolution");
            if (actor.equals(draft.author()) || !actor.equals(publication.reviewer()))
                throw new IllegalArgumentException("Independent named reviewer required");
            for (var example : publication.positive())
                if (!"CALCULATED".equals(reviewTrial(actor, draft, example).state()))
                    throw new IllegalArgumentException("Positive boundary failed");
            for (var example : publication.negative())
                if ("CALCULATED".equals(reviewTrial(actor, draft, example).state()))
                    throw new IllegalArgumentException("Negative boundary failed");
            long published = version + 1;
            store.addRule(new Rule(id, published, draft.organization(), draft.region(), draft.procedure(),
                    draft.stage(), "ACTIVE", draft.basis(), draft.clock(), draft.requiredEvents(),
                    draft.requiredMaterials(), draft.author(), publication.reviewer(), publication.reviewRef()));
            return new Receipt(key, "PUBLISH_PROCEDURE_RULE", id, published);
        });
        access.require(actor, draft.organization(), "PROCEDURE_RULE", id, version,
                "PUBLISH_RULE", "RULE_MANAGEMENT");
        return receipt;
    }

    public Receipt disable(String actor, String key, String id, long version) {
        var rule = readRule(actor, id, version);
        var receipt = store.command(key, hash(id + version + "DISABLE"), actor,
                "DISABLE_PROCEDURE_RULE", () -> {
                    access.require(actor, rule.organization(), "PROCEDURE_RULE", id, version,
                            "DISABLE_RULE", "RULE_MANAGEMENT");
                    store.disableRule(id, version);
                    return new Receipt(key, "DISABLE_PROCEDURE_RULE", id, version);
                });
        access.require(actor, rule.organization(), "PROCEDURE_RULE", id, version,
                "DISABLE_RULE", "RULE_MANAGEMENT");
        return receipt;
    }

    public Receipt check(String actor, String key, String id, CheckRequest request) {
        if (blank(key) || blank(id) || request == null) throw new IllegalArgumentException("Check required");
        var rule = readRule(actor, request.ruleId(), request.ruleVersion());
        if (!"ACTIVE".equals(rule.state())) throw new IllegalArgumentException("Active rule required");
        var snapshot = snapshot(new TrialRequest(request.caseRef(), request.caseVersion(),
                request.calendarRef(), request.calendarVersion()));
        access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                "CHECK_CASE", "PROCEDURE_CHECK");
        if (!snapshot.organization().equals(rule.organization())) throw new SecurityException("Organization mismatch");
        var calendar = calendar(new TrialRequest(request.caseRef(), request.caseVersion(),
                request.calendarRef(), request.calendarVersion()));
        var items = evaluator.check(rule, snapshot, calendar);
        var receipt = store.command(key, hash(id + request + snapshot + calendar), actor,
                "CHECK_PROCEDURE", () -> {
                    if (store.latestReport(id).isPresent()) throw new IllegalArgumentException("Report already exists");
                    access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                            "CHECK_CASE", "PROCEDURE_CHECK");
                    store.addReport(new Report(id, 1, snapshot.organization(), snapshot.ref(), snapshot.version(),
                            rule.id(), rule.version(), calendar == null ? null : calendar.ref(),
                            calendar == null ? null : calendar.version(), "COMPLETE", items, List.of(),
                            null, null, 0));
                    return new Receipt(key, "CHECK_PROCEDURE", id, 1);
                });
        access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                "CHECK_CASE", "PROCEDURE_CHECK");
        return receipt;
    }

    public Report readReport(String actor, String id, long version) {
        var report = store.report(id, version).orElseThrow(() -> new SecurityException("Report unavailable"));
        access.require(actor, report.organization(), "PROCEDURE_REPORT", id, version,
                "READ_REPORT", "PROCEDURE_CHECK");
        var current = source.caseSnapshot(report.caseRef(), report.caseVersion());
        if (!report.organization().equals(current.organization()))
            throw new SecurityException("Report source scope changed");
        var calendar = report.calendarRef() == null || report.calendarVersion() == null ? null :
                source.calendar(report.calendarRef(), report.calendarVersion());
        var rule = store.rule(report.ruleId(), report.ruleVersion())
                .orElseThrow(() -> new SecurityException("Report rule unavailable"));
        if (!report.items().equals(evaluator.check(rule, current, calendar)))
            throw new SecurityException("Report source references changed");
        return report;
    }

    public Receipt rectify(String actor, String key, String id, Rectification input) {
        if (blank(key) || input == null || blank(input.reason()) || input.evidenceRefs() == null)
            throw new IllegalArgumentException("Reason required");
        var target = store.report(id, input.expectedVersion())
                .orElseThrow(() -> new SecurityException("Report unavailable"));
        var receipt = store.command(key, hash(id + input), actor, "RECTIFY_PROCEDURE", () -> {
            var latest = store.latestReport(id).orElseThrow();
            if (latest.version() != input.expectedVersion()) throw new IllegalArgumentException("Report version conflict");
            access.require(actor, latest.organization(), "PROCEDURE_REPORT", id, latest.version(),
                    "RECTIFY_REPORT", "PROCEDURE_CHECK");
            store.addReport(new Report(id, latest.version() + 1, latest.organization(), latest.caseRef(),
                    latest.caseVersion(), latest.ruleId(), latest.ruleVersion(), latest.calendarRef(),
                    latest.calendarVersion(), "RECTIFIED_PENDING_RECHECK", latest.items(), latest.unprocessed(),
                    input.reason() + " [" + String.join(",", input.evidenceRefs()) + "]", actor, latest.version()));
            return new Receipt(key, "RECTIFY_PROCEDURE", id, latest.version() + 1);
        });
        access.require(actor, target.organization(), "PROCEDURE_REPORT", id, target.version(),
                "RECTIFY_REPORT", "PROCEDURE_CHECK");
        return receipt;
    }

    public Receipt recheck(String actor, String key, String id, long expectedVersion, CheckRequest request) {
        var current = store.report(id, expectedVersion).orElseThrow(() -> new SecurityException("Report unavailable"));
        if (!"RECTIFIED_PENDING_RECHECK".equals(current.executionState()))
            throw new IllegalArgumentException("Rectification version required");
        var rule = readRule(actor, request.ruleId(), request.ruleVersion());
        if (!"ACTIVE".equals(rule.state())) throw new IllegalArgumentException("Active rule required");
        var snapshot = snapshot(new TrialRequest(request.caseRef(), request.caseVersion(),
                request.calendarRef(), request.calendarVersion()));
        if (!current.caseRef().equals(snapshot.ref()) || !current.organization().equals(snapshot.organization()))
            throw new SecurityException("Recheck case mismatch");
        access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                "CHECK_CASE", "PROCEDURE_CHECK");
        var calendar = calendar(new TrialRequest(request.caseRef(), request.caseVersion(),
                request.calendarRef(), request.calendarVersion()));
        var items = evaluator.check(rule, snapshot, calendar);
        var receipt = store.command(key, hash(id + expectedVersion + request + snapshot + calendar), actor,
                "RECHECK_PROCEDURE", () -> {
                    if (store.latestReport(id).orElseThrow().version() != expectedVersion)
                        throw new IllegalArgumentException("Report version conflict");
                    access.require(actor, current.organization(), "PROCEDURE_REPORT", id, expectedVersion,
                            "RECHECK_REPORT", "PROCEDURE_CHECK");
                    access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                            "CHECK_CASE", "PROCEDURE_CHECK");
                    store.addReport(new Report(id, expectedVersion + 1, snapshot.organization(), snapshot.ref(),
                            snapshot.version(), rule.id(), rule.version(), calendar == null ? null : calendar.ref(),
                            calendar == null ? null : calendar.version(), "COMPLETE", items, List.of(),
                            current.rectification(), current.rectifiedBy(), expectedVersion));
                    return new Receipt(key, "RECHECK_PROCEDURE", id, expectedVersion + 1);
                });
        access.require(actor, current.organization(), "PROCEDURE_REPORT", id, expectedVersion,
                "RECHECK_REPORT", "PROCEDURE_CHECK");
        access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                "CHECK_CASE", "PROCEDURE_CHECK");
        return receipt;
    }

    private CaseSnapshot snapshot(TrialRequest request) {
        if (request == null || blank(request.caseRef()) || blank(request.caseVersion()))
            throw new IllegalArgumentException("Exact case reference required");
        return source.caseSnapshot(request.caseRef(), request.caseVersion());
    }
    private Trial reviewTrial(String actor, Rule rule, TrialRequest example) {
        var snapshot = snapshot(example);
        if (!rule.organization().equals(snapshot.organization())) throw new SecurityException("Example scope mismatch");
        access.require(actor, snapshot.organization(), "CASE", snapshot.ref(), exactVersion(snapshot.version()),
                "READ_CASE", "RULE_MANAGEMENT");
        return evaluator.trial(rule, snapshot, calendar(example));
    }
    private Calendar calendar(TrialRequest request) {
        return blank(request.calendarRef()) || blank(request.calendarVersion()) ? null :
                source.calendar(request.calendarRef(), request.calendarVersion());
    }
    private static void verifyComplete(Rule rule) {
        var clock = rule.clock();
        if (rule.basis() == null || blank(rule.basis().ref()) || blank(rule.basis().version()) ||
                blank(rule.basis().locator()) || blank(rule.basis().scope()) ||
                rule.basis().effectiveFrom() == null || rule.basis().effectiveTo() == null ||
                clock == null || clock.unit() == null || clock.days() < 1 || blank(clock.startEvent()) ||
                blank(clock.deadlineAdjustment()) || blank(clock.pausePolicy()) ||
                !"NONE".equals(clock.interruptionPolicy()) ||
                (clock.unit() == Unit.WORKDAY && (blank(clock.calendarRef()) || blank(clock.calendarVersion()))) ||
                rule.requiredEvents().isEmpty()) throw new IllegalArgumentException("Incomplete procedure rule");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static long exactVersion(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Numeric exact source version required", invalid);
        }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
