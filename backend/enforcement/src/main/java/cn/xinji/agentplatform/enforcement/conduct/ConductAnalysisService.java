package cn.xinji.agentplatform.enforcement.conduct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

public final class ConductAnalysisService {
    public interface Access {
        void require(String actor, String org, String type, String id, long version,
                     String action, String purpose);
    }
    public interface LockedEvidence {
        void require(String actor, Input input);
        void require(String actor, Report report);
    }
    public interface CaseScope {
        ScopeVerification verify(String actor, Input input, Rule rule);
    }
    public record Review(long expectedVersion, String reason, String decision) { }
    public record Rerun(long expectedVersion, Input input) { }

    private final ConductStore store;
    private final Access access;
    private final LockedEvidence evidence;
    private final CaseScope scope;
    private final ConductEvaluator evaluator = new ConductEvaluator();

    public ConductAnalysisService(ConductStore store, Access access, LockedEvidence evidence) {
        this(store, access, evidence, (actor, input, rule) ->
                new ScopeVerification(Applicability.UNKNOWN, null, null));
    }

    public ConductAnalysisService(ConductStore store, Access access, LockedEvidence evidence,
                                  CaseScope scope) {
        this.store = store;
        this.access = access;
        this.evidence = evidence;
        this.scope = scope;
    }

    public Receipt analyze(String actor, String key, Input input) {
        validate(actor, key, input);
        var rule = activeRule(actor, input);
        scope.verify(actor, input, rule);
        evidence.require(actor, input);
        var receipt = store.command(key, hash(input.toString()), actor, "ANALYZE_CONDUCT", () -> {
            if (store.latestReportVersion(input.id()) != 0)
                throw new IllegalArgumentException("Analysis already exists");
            var currentRule = activeRule(actor, input);
            var currentScope = scope.verify(actor, input, currentRule);
            evidence.require(actor, input);
            access.require(actor, input.organization(), "CONDUCT_ANALYSIS", input.id(), 1,
                    "ANALYZE", "ANALYZE");
            store.addReport(report(input, currentRule,
                    withScope(evaluator.evaluate(currentRule, input), currentScope), currentScope,
                    1, 0, "UNREVIEWED", null, null));
            return new Receipt(key, "ANALYZE_CONDUCT", input.id(), 1);
        });
        read(actor, receipt.resultId(), receipt.version());
        activeRule(actor, input);
        scope.verify(actor, input, rule);
        evidence.require(actor, input);
        access.require(actor, input.organization(), "CONDUCT_ANALYSIS", input.id(), receipt.version(),
                "ANALYZE", "ANALYZE");
        return receipt;
    }

    public Report read(String actor, String id, long version) {
        if (blank(actor) || blank(id) || version < 1) throw new IllegalArgumentException("Invalid report reference");
        var report = store.report(id, version).orElseThrow(() -> new SecurityException("Analysis unavailable"));
        access.require(actor, report.organization(), "CONDUCT_ANALYSIS", id, version, "READ", "DISPLAY");
        evidence.require(actor, report);
        return report;
    }

    public Receipt review(String actor, String key, String id, Review review) {
        if (blank(key) || blank(id) || review == null || review.expectedVersion() < 1 ||
                blank(review.reason()) || !"REVIEWED".equals(review.decision()) &&
                !"RETURNED".equals(review.decision()))
            throw new IllegalArgumentException("Invalid named review");
        var prior = read(actor, id, review.expectedVersion());
        access.require(actor, prior.organization(), "CONDUCT_ANALYSIS", id,
                review.expectedVersion(), "REVIEW", "HUMAN_REVIEW");
        var receipt = store.command(key, hash(id + review), actor, "REVIEW_CONDUCT", () -> {
            if (store.latestReportVersion(id) != review.expectedVersion())
                throw new IllegalArgumentException("Analysis version conflict");
            read(actor, id, review.expectedVersion());
            access.require(actor, prior.organization(), "CONDUCT_ANALYSIS", id,
                    review.expectedVersion(), "REVIEW", "HUMAN_REVIEW");
            store.addReport(new Report(id, prior.version() + 1, prior.organization(), prior.caseRef(),
                    prior.caseVersion(), prior.evidenceOrganizationId(), prior.evidenceOrganizationVersion(),
                    prior.ruleId(), prior.ruleVersion(), prior.behavior(), prior.scope(), prior.rightRef(),
                    prior.rightVersion(), prior.rightStatus(), prior.facts(), prior.elements(), prior.gaps(),
                    prior.outcome(), review.decision(), actor, review.reason(), prior.version()));
            return new Receipt(key, "REVIEW_CONDUCT", id, prior.version() + 1);
        });
        read(actor, id, review.expectedVersion());
        if (store.report(id, receipt.version()).isEmpty())
            throw new IllegalStateException("Review revision unavailable");
        access.require(actor, prior.organization(), "CONDUCT_ANALYSIS", id,
                review.expectedVersion(), "REVIEW", "HUMAN_REVIEW");
        return receipt;
    }

    public Receipt rerun(String actor, String key, String id, Rerun rerun) {
        if (blank(key) || blank(id) || rerun == null || rerun.input() == null ||
                rerun.expectedVersion() < 1 || !id.equals(rerun.input().id()))
            throw new IllegalArgumentException("Invalid rerun");
        var prior = read(actor, id, rerun.expectedVersion());
        var input = rerun.input();
        if (!prior.organization().equals(input.organization()) ||
                !prior.caseRef().equals(input.caseRef()) || !prior.caseVersion().equals(input.caseVersion()))
            throw new SecurityException("Analysis target changed");
        var rule = activeRule(actor, input);
        scope.verify(actor, input, rule);
        evidence.require(actor, input);
        var receipt = store.command(key, hash(id + rerun), actor, "RERUN_CONDUCT", () -> {
            if (store.latestReportVersion(id) != rerun.expectedVersion())
                throw new IllegalArgumentException("Analysis version conflict");
            read(actor, id, rerun.expectedVersion());
            var currentRule = activeRule(actor, input);
            var currentScope = scope.verify(actor, input, currentRule);
            evidence.require(actor, input);
            access.require(actor, prior.organization(), "CONDUCT_ANALYSIS", id,
                    rerun.expectedVersion(), "RERUN", "ANALYZE");
            store.addReport(report(input, currentRule,
                    withScope(evaluator.evaluate(currentRule, input), currentScope), currentScope,
                    prior.version() + 1, prior.version(),
                    "REVIEW_REQUIRED", null, null));
            return new Receipt(key, "RERUN_CONDUCT", id, prior.version() + 1);
        });
        read(actor, id, rerun.expectedVersion());
        if (store.report(id, receipt.version()).isEmpty())
            throw new IllegalStateException("Rerun revision unavailable");
        activeRule(actor, input);
        scope.verify(actor, input, rule);
        evidence.require(actor, input);
        access.require(actor, prior.organization(), "CONDUCT_ANALYSIS", id,
                rerun.expectedVersion(), "RERUN", "ANALYZE");
        return receipt;
    }

    private Rule activeRule(String actor, Input input) {
        var rule = store.rule(input.ruleId(), input.ruleVersion())
                .orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, rule.organization(), "CONDUCT_RULE", rule.id(), rule.version(),
                "USE_RULE", "ANALYZE");
        if (!rule.organization().equals(input.organization()) ||
                rule.behavior() != input.behavior() || !"ACTIVE".equals(rule.state()) ||
                rule.basis() == null || !"VERIFIED".equals(rule.basis().effectStatus()))
            throw new SecurityException("Active verified rule required");
        return rule;
    }

    private static Report report(Input input, Rule rule, Trial trial, ScopeVerification scope,
                                 long version, long previous,
                                 String reviewState, String reviewer, String reason) {
        return new Report(input.id(), version, input.organization(), input.caseRef(), input.caseVersion(),
                input.evidenceOrganizationId(), input.evidenceOrganizationVersion(), rule.id(),
                rule.version(), rule.behavior(), scope, input.right() == null ? null : input.right().ref(),
                input.right() == null ? null : input.right().version(),
                input.right() == null ? "UNKNOWN_AT_BEHAVIOR_TIME" : input.right().status(),
                List.copyOf(input.facts()), trial.elements(), trial.gaps(), trial.outcome(),
                reviewState, reviewer, reason, previous);
    }

    private static Trial withScope(Trial trial, ScopeVerification verification) {
        if (verification == null || verification.state() == null)
            throw new IllegalStateException("Case scope verification unavailable");
        if (verification.state() == Applicability.MATCH) return trial;
        var gaps = new java.util.ArrayList<>(trial.gaps());
        gaps.add(verification.state() == Applicability.UNKNOWN
                ? "APPLICABILITY_SCOPE_UNKNOWN" : "APPLICABILITY_SCOPE_MISMATCH");
        return new Trial(trial.elements(), List.copyOf(gaps), Outcome.INSUFFICIENT);
    }

    private static void validate(String actor, String key, Input input) {
        if (blank(actor) || blank(key) || input == null || blank(input.id()) ||
                blank(input.organization()) || blank(input.caseRef()) || blank(input.caseVersion()) ||
                blank(input.evidenceOrganizationId()) || input.evidenceOrganizationVersion() < 1 ||
                blank(input.ruleId()) || input.ruleVersion() < 1)
            throw new IllegalArgumentException("Invalid analysis input");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
