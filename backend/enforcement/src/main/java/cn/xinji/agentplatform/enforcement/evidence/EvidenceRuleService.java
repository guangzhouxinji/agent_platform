package cn.xinji.agentplatform.enforcement.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** ENF-06 product rule contract; draft creation never publishes or analyzes a case. */
public final class EvidenceRuleService {
    public interface Access {
        void require(String actor, String organization, String ruleId, long version, String action);
    }
    public record DraftInput(String id, String organization, String caseType,
                             String basisRef, List<EvidenceRuleStore.Matter> matters) { }
    public record Example(String id, List<String> observedMatters, List<String> expectedMissing) { }
    public record Publication(String basisVersion, String scope, String reviewRef,
                              List<Example> positive, List<Example> negative) { }
    public interface Basis {
        void require(String caseType, String basisRef, String basisVersion, String scope);
    }

    private final EvidenceRuleStore store;
    private final Access access;
    private final Basis basis;

    public EvidenceRuleService(EvidenceRuleStore store, Access access, Basis basis) {
        this.store = store;
        this.access = access;
        this.basis = basis;
    }

    public EvidenceRuleStore.Receipt draft(String actor, String key, DraftInput input) {
        if (input == null || blank(actor) || blank(key) || blank(input.id()) ||
                blank(input.organization()) || blank(input.caseType()) || input.matters() == null ||
                input.matters().stream().anyMatch(m -> m == null || blank(m.id())) ||
                input.matters().stream().map(EvidenceRuleStore.Matter::id).distinct().count() != input.matters().size())
            throw new IllegalArgumentException("Invalid evidence rule draft");
        String fingerprint = hash(input.toString());
        var receipt = store.command(key, fingerprint, actor, "DRAFT_EVIDENCE_RULE", () -> {
            var previous = store.latest(input.id());
            if (previous.isPresent() && ("DRAFT".equals(previous.get().state()) ||
                    !previous.get().organization().equals(input.organization()) ||
                    !previous.get().caseType().equals(input.caseType())))
                throw new IllegalArgumentException("Published rule required before new draft");
            long revision = previous.map(rule -> rule.revision() + 1).orElse(1L);
            access.require(actor, input.organization(), input.id(), revision, "EDIT_RULE");
            var rule = new EvidenceRuleStore.Rule(input.id(), revision, input.organization(), input.caseType(),
                    input.basisRef(), "DRAFT", List.copyOf(input.matters()));
            store.add(rule, actor);
            return new EvidenceRuleStore.Receipt(key, "DRAFT_EVIDENCE_RULE", input.id(), revision);
        });
        access.require(actor, input.organization(), input.id(), receipt.revision(), "EDIT_RULE");
        return receipt;
    }

    public EvidenceRuleStore.Rule read(String actor, String id, long revision) {
        if (blank(actor) || blank(id) || revision < 1) throw new IllegalArgumentException("Invalid rule reference");
        var rule = store.rule(id, revision).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, rule.organization(), id, revision, "READ_RULE");
        return rule;
    }

    public EvidenceRuleStore.PublicationReview publication(String actor, String id, long revision) {
        var rule = read(actor, id, revision);
        if ("DRAFT".equals(rule.state())) throw new SecurityException("Publication unavailable");
        return store.publication(id, revision)
                .orElseThrow(() -> new IllegalStateException("Publication metadata unavailable"));
    }

    public EvidenceRuleStore.Receipt publish(String actor, String key, String id, Publication review) {
        if (blank(actor) || blank(key) || blank(id) || review == null || blank(review.basisVersion()) ||
                blank(review.scope()) || blank(review.reviewRef()))
            throw new IllegalArgumentException("Invalid publication review");
        String fingerprint = hash(id + review.toString());
        var receipt = store.command(key, fingerprint, actor, "PUBLISH_EVIDENCE_RULE", () -> {
            var draft = store.latest(id).orElseThrow(() -> new SecurityException("Rule unavailable"));
            if (!"DRAFT".equals(draft.state())) throw new IllegalArgumentException("Draft required");
            access.require(actor, draft.organization(), id, draft.revision(), "PUBLISH_RULE");
            basis.require(draft.caseType(), draft.basisRef(), review.basisVersion(), review.scope());
            verifyExamples(draft, review);
            long publishedRevision = draft.revision() + 1;
            store.add(new EvidenceRuleStore.Rule(id, publishedRevision, draft.organization(), draft.caseType(),
                    draft.basisRef(), "ACTIVE", draft.matters()), actor);
            store.recordReview(id, publishedRevision, review.basisVersion(), review.scope(), review.reviewRef(),
                    fingerprint, actor);
            return new EvidenceRuleStore.Receipt(key, "PUBLISH_EVIDENCE_RULE", id, publishedRevision);
        });
        var published = store.rule(id, receipt.revision()).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, published.organization(), id, receipt.revision() - 1, "PUBLISH_RULE");
        return receipt;
    }

    public EvidenceRuleStore.Receipt disable(String actor, String key, String id, long revision) {
        if (blank(actor) || blank(key) || blank(id) || revision < 2)
            throw new IllegalArgumentException("Invalid published rule");
        var rule = store.rule(id, revision).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, rule.organization(), id, revision, "DISABLE_RULE");
        var receipt = store.command(key, hash(id + ":" + revision + ":DISABLE"), actor,
                "DISABLE_EVIDENCE_RULE", () -> {
                    access.require(actor, rule.organization(), id, revision, "DISABLE_RULE");
                    store.disable(id, revision);
                    return new EvidenceRuleStore.Receipt(key, "DISABLE_EVIDENCE_RULE", id, revision);
                });
        access.require(actor, rule.organization(), id, revision, "DISABLE_RULE");
        return receipt;
    }

    private static void verifyExamples(EvidenceRuleStore.Rule rule, Publication review) {
        if (rule.matters().isEmpty() || rule.matters().stream().noneMatch(EvidenceRuleStore.Matter::required) ||
                rule.matters().stream().anyMatch(m -> blank(m.expectedMaterial()) ||
                        !m.expectedMaterial().startsWith("line-prefix:") ||
                        m.expectedMaterial().length() <= "line-prefix:".length()) ||
                review.positive() == null || review.negative() == null ||
                review.positive().isEmpty() || review.negative().isEmpty())
            throw new IllegalArgumentException("Incomplete rule or examples");
        Set<String> required = new HashSet<>();
        Set<String> known = new HashSet<>();
        rule.matters().forEach(m -> { known.add(m.id()); if (m.required()) required.add(m.id()); });
        Set<String> ids = new HashSet<>();
        for (var example : java.util.stream.Stream.concat(review.positive().stream(),
                review.negative().stream()).toList()) {
            if (example == null || blank(example.id()) || !ids.add(example.id()) ||
                    example.observedMatters() == null || example.expectedMissing() == null ||
                    example.observedMatters().stream().anyMatch(m -> !known.contains(m)) ||
                    example.observedMatters().size() != new HashSet<>(example.observedMatters()).size())
                throw new IllegalArgumentException("Invalid review example");
            var missing = new HashSet<>(required);
            missing.removeAll(example.observedMatters());
            if (!missing.equals(new HashSet<>(example.expectedMissing())) ||
                    example.expectedMissing().size() != missing.size() ||
                    (review.positive().contains(example) ? !missing.isEmpty() : missing.isEmpty()))
                throw new IllegalArgumentException("Review example expectation mismatch");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
