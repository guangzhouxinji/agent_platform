package cn.xinji.agentplatform.enforcement.conduct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

public final class ConductRuleService {
    public interface Access {
        void require(String actor, String org, String id, long version, String action);
    }
    public interface VerifiedBasis {
        void require(Basis basis, Behavior behavior);
    }
    public record Draft(String id, String organization, Behavior behavior, Basis basis,
                        List<Element> elements, List<String> defenses,
                        boolean noDefensesConfirmed, String noDefensesSource) { }
    public record Example(Input input, Outcome outcome, List<String> gaps) { }
    public record Publication(String reviewRef, List<Example> positive, List<Example> negative) { }

    private final ConductStore store;
    private final Access access;
    private final VerifiedBasis basis;
    private final ConductEvaluator evaluator = new ConductEvaluator();

    public ConductRuleService(ConductStore store, Access access, VerifiedBasis basis) {
        this.store = store;
        this.access = access;
        this.basis = basis;
    }

    public Receipt draft(String actor, String key, Draft input) {
        if (blank(actor) || blank(key) || input == null || blank(input.id()) ||
                blank(input.organization()) || input.behavior() == null ||
                input.elements() == null || input.elements().isEmpty() ||
                input.elements().stream().anyMatch(e -> e == null || blank(e.id()) || blank(e.description())) ||
                input.elements().stream().map(Element::id).distinct().count() != input.elements().size() ||
                input.defenses() == null || input.defenses().stream().anyMatch(ConductRuleService::blank) ||
                input.noDefensesConfirmed() && !input.defenses().isEmpty() ||
                input.noDefensesConfirmed() && blank(input.noDefensesSource()))
            throw new IllegalArgumentException("Invalid conduct rule draft");
        var receipt = store.command(key, hash(input.toString()), actor, "DRAFT_CONDUCT_RULE", () -> {
            var previous = store.latestRule(input.id());
            if (previous.isPresent() && (!"ACTIVE".equals(previous.get().state()) &&
                    !"DISABLED".equals(previous.get().state()) ||
                    !previous.get().organization().equals(input.organization()) ||
                    previous.get().behavior() != input.behavior()))
                throw new IllegalArgumentException("Prior published revision required");
            long version = previous.map(rule -> rule.version() + 1).orElse(1L);
            access.require(actor, input.organization(), input.id(), version, "EDIT_RULE");
            store.addRule(new Rule(input.id(), version, input.organization(), input.behavior(),
                    "DRAFT", input.basis(), List.copyOf(input.elements()), List.copyOf(input.defenses()),
                    input.noDefensesConfirmed(), input.noDefensesSource(), null, actor));
            return new Receipt(key, "DRAFT_CONDUCT_RULE", input.id(), version);
        });
        read(actor, receipt.resultId(), receipt.version());
        access.require(actor, input.organization(), input.id(), receipt.version(), "EDIT_RULE");
        return receipt;
    }

    public Rule read(String actor, String id, long version) {
        if (blank(actor) || blank(id) || version < 1) throw new IllegalArgumentException("Invalid rule reference");
        var rule = store.rule(id, version).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, rule.organization(), id, version, "READ_RULE");
        return rule;
    }

    public Trial trial(String actor, String id, long version, Input input) {
        var rule = read(actor, id, version);
        access.require(actor, rule.organization(), id, version, "TRIAL_RULE");
        if (input == null || !rule.id().equals(input.ruleId()) || version != input.ruleVersion() ||
                !rule.organization().equals(input.organization()) || rule.behavior() != input.behavior())
            throw new IllegalArgumentException("Trial rule mismatch");
        var result = evaluator.evaluate(rule, input);
        read(actor, id, version);
        return result;
    }

    public Receipt publish(String actor, String key, String id, long version, Publication review) {
        if (blank(actor) || blank(key) || blank(id) || version < 1 || review == null ||
                blank(review.reviewRef())) throw new IllegalArgumentException("Invalid publication");
        var receipt = store.command(key, hash(id + version + review), actor, "PUBLISH_CONDUCT_RULE", () -> {
            var draft = read(actor, id, version);
            if (!"DRAFT".equals(draft.state()) || store.latestRule(id).orElseThrow().version() != version)
                throw new IllegalArgumentException("Current draft required");
            access.require(actor, draft.organization(), id, version, "PUBLISH_RULE");
            verifyComplete(draft, review);
            basis.require(draft.basis(), draft.behavior());
            var active = new Rule(id, version + 1, draft.organization(), draft.behavior(), "ACTIVE",
                    draft.basis(), draft.elements(), draft.defenses(), draft.noDefensesConfirmed(),
                    draft.noDefensesSource(), review.reviewRef(), draft.author());
            store.addRule(active);
            return new Receipt(key, "PUBLISH_CONDUCT_RULE", id, active.version());
        });
        var result = store.rule(id, receipt.version()).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, result.organization(), id, version, "PUBLISH_RULE");
        return receipt;
    }

    public Receipt disable(String actor, String key, String id, long version) {
        if (blank(key)) throw new IllegalArgumentException("Idempotency key required");
        var rule = read(actor, id, version);
        access.require(actor, rule.organization(), id, version, "DISABLE_RULE");
        var receipt = store.command(key, hash(id + version + "DISABLE"), actor, "DISABLE_CONDUCT_RULE", () -> {
            access.require(actor, rule.organization(), id, version, "DISABLE_RULE");
            store.disable(id, version);
            return new Receipt(key, "DISABLE_CONDUCT_RULE", id, version);
        });
        read(actor, id, version);
        return receipt;
    }

    private void verifyComplete(Rule rule, Publication review) {
        Basis b = rule.basis();
        if (b == null || blank(b.ref()) || blank(b.version()) || blank(b.locator()) ||
                !"VERIFIED".equals(b.effectStatus()) || blank(b.scope()) ||
                blank(b.jurisdiction()) || blank(b.procedure()) ||
                rule.elements().stream().noneMatch(Element::required) ||
                (rule.noDefensesConfirmed() && !rule.defenses().isEmpty()) ||
                (rule.defenses().isEmpty() && (!rule.noDefensesConfirmed() || blank(rule.noDefensesSource()))) ||
                review.positive() == null || review.positive().isEmpty() ||
                review.negative() == null || review.negative().isEmpty())
            throw new IllegalArgumentException("Publication contract incomplete");
        var fingerprints = new HashSet<String>();
        for (var example : review.positive()) verifyExample(rule, example, true, fingerprints);
        for (var example : review.negative()) verifyExample(rule, example, false, fingerprints);
    }

    private void verifyExample(Rule rule, Example example, boolean positive, HashSet<String> seen) {
        if (example == null || example.input() == null || example.gaps() == null ||
                !seen.add(hash(example.input().toString())) ||
                !rule.id().equals(example.input().ruleId()) ||
                rule.version() != example.input().ruleVersion() ||
                rule.behavior() != example.input().behavior())
            throw new IllegalArgumentException("Invalid publication example");
        var actual = evaluator.evaluate(rule, example.input());
        // Publication tests deterministic coverage, not a final defense decision.
        // Keep the evaluator's complete gaps/outcome in the example and in all case reports.
        boolean configuredDefenses = !rule.noDefensesConfirmed() && !rule.defenses().isEmpty();
        boolean coverageGap = actual.gaps().stream().anyMatch(gap -> !configuredDefenses ||
                (!"DEFENSE_UNASSESSED".equals(gap) && !"DEFENSE_REVIEW_REQUIRED".equals(gap)));
        if (actual.outcome() != example.outcome() ||
                !new HashSet<>(actual.gaps()).equals(new HashSet<>(example.gaps())) ||
                (positive && coverageGap) || (!positive && !coverageGap))
            throw new IllegalArgumentException("Publication example mismatch");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
