package cn.xinji.agentplatform.enforcement.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Candidate relations are persisted separately from official facts and human review. */
public final class EvidenceOrganizationService {
    public interface Access extends EvidenceRuleTrial.Access { }
    public record Input(String id, String ruleId, long ruleRevision,
                        EvidenceRuleTrial.Request evidenceSet) { }
    public record Correction(long expectedVersion, String matterId, String evidenceId,
                             String locator, String relation, String reason) { }
    public record Review(long expectedVersion, String decision, String reason) { }
    public record Exclude(long expectedVersion, String evidenceId, String reason) { }

    private final EvidenceRuleStore rules;
    private final EvidenceOrganizationStore store;
    private final EvidenceTrialSource source;
    private final Access access;

    public EvidenceOrganizationService(EvidenceRuleStore rules, EvidenceOrganizationStore store,
                                       EvidenceTrialSource source, Access access) {
        this.rules = rules;
        this.store = store;
        this.source = source;
        this.access = access;
    }

    public EvidenceOrganizationStore.Receipt organize(String actor, String key, Input input) {
        if (actor == null || actor.isBlank() || key == null || key.isBlank() || input == null ||
                input.id() == null || input.id().isBlank() || input.ruleId() == null ||
                input.evidenceSet() == null) throw new IllegalArgumentException("Invalid organization");
        var rule = rules.rule(input.ruleId(), input.ruleRevision())
                .orElseThrow(() -> new SecurityException("Rule unavailable"));
        require(actor, rule, input);
        if (!"ACTIVE".equals(rule.state())) throw new SecurityException("Active rule required");
        var candidate = new EvidenceRuleTrial(rules, source, access).run(actor, rule.id(),
                rule.revision(), input.evidenceSet());
        var snapshot = source.read(input.evidenceSet().caseRef(), input.evidenceSet().caseVersion(),
                input.evidenceSet().materials());
        List<EvidenceOrganizationStore.Link> links = new ArrayList<>();
        List<EvidenceOrganizationStore.Matter> matters = new ArrayList<>();
        for (var matter : candidate.matters()) {
            matters.add(new EvidenceOrganizationStore.Matter(matter.matterId(), matter.coverage()));
            String firstValue = null;
            for (var value : matter.candidates()) {
                String relation = firstValue == null ? "SUPPORT_CANDIDATE" :
                        firstValue.equals(value.value()) ? "CORROBORATE_CANDIDATE" : "CONTRADICT_CANDIDATE";
                if (firstValue == null) firstValue = value.value();
                links.add(new EvidenceOrganizationStore.Link(matter.matterId(), value.materialId(),
                        Long.parseLong(value.version()), value.sha256(), value.locator(), value.value(),
                        relation, "MACHINE_CANDIDATE", "Configured document consistency mapping"));
            }
            for (var unrelatedId : candidate.unrelatedMaterials()) {
                var material = snapshot.materials().stream().filter(m -> m.ref().id().equals(unrelatedId))
                        .findFirst().orElseThrow();
                links.add(new EvidenceOrganizationStore.Link(matter.matterId(), unrelatedId,
                        Long.parseLong(material.ref().version()), material.ref().sha256(),
                        "line 2", material.eventRef(), "IRRELEVANT_CANDIDATE",
                        "MACHINE_CANDIDATE", "Event reference outside case snapshot"));
            }
        }
        var times = new ArrayList<EvidenceOrganizationStore.Time>();
        for (var material : snapshot.materials()) {
            String eventTime = candidate.matters().stream().filter(m -> "EVENT_TIME".equals(m.matterId()))
                    .flatMap(m -> m.candidates().stream())
                    .filter(c -> c.materialId().equals(material.ref().id()))
                    .map(EvidenceRuleTrial.Candidate::value).findFirst().orElse(null);
            times.add(new EvidenceOrganizationStore.Time(material.ref().id(), eventTime,
                    material.collectionTime(), material.uploadTime(),
                    material.parseIssue() != null ? "UNPARSEABLE_ORIGINAL" :
                            eventTime == null ? "NOT_IN_ORIGINAL" : null,
                    material.collectionTime() == null ? "NOT_RECORDED" : null,
                    material.uploadTime() == null ? "NOT_RECORDED" : null));
        }
        var revision = new EvidenceOrganizationStore.Revision(input.id(), 1, rule.organization(),
                snapshot.caseRef(), snapshot.version(), rule.id(), rule.revision(), "PENDING_REVIEW",
                List.copyOf(input.evidenceSet().materials()), List.copyOf(matters), List.copyOf(links),
                List.copyOf(times), candidate.unrelatedMaterials(), candidate.unprocessedMaterials(),
                actor, null, null, List.of());
        String fingerprint = hash(revision.toString());
        var receipt = store.command(key, fingerprint, actor, "ORGANIZE_EVIDENCE", () -> {
            require(actor, rule, input);
            if (!"ACTIVE".equals(rules.rule(rule.id(), rule.revision()).orElseThrow().state()))
                throw new SecurityException("Rule disabled");
            source.read(snapshot.caseRef(), snapshot.version(), input.evidenceSet().materials());
            store.append(revision, actor);
            return new EvidenceOrganizationStore.Receipt(key, input.id(), 1);
        });
        require(actor, rule, input);
        source.read(snapshot.caseRef(), snapshot.version(), input.evidenceSet().materials());
        return receipt;
    }

    public EvidenceOrganizationStore.Revision read(String actor, String id, long revision) {
        if (actor == null || actor.isBlank() || id == null || id.isBlank() || revision < 1)
            throw new IllegalArgumentException("Invalid organization reference");
        var saved = store.revision(id, revision)
                .orElseThrow(() -> new SecurityException("Organization unavailable"));
        access.require(actor, saved.organization(), "EVIDENCE_ORGANIZATION", id, revision,
                "READ", "DISPLAY");
        access.require(actor, saved.organization(), "EVIDENCE_RULE", saved.ruleId(), saved.ruleVersion(),
                "READ_RULE", "RULE_MANAGEMENT");
        access.require(actor, saved.organization(), "CASE", saved.caseRef(),
                Long.parseLong(saved.caseVersion()), "READ", "ANALYZE");
        for (var ref : saved.evidenceSet())
            access.require(actor, saved.organization(), "EVIDENCE", ref.id(),
                    Long.parseLong(ref.version()), "READ", "ANALYZE");
        source.read(saved.caseRef(), saved.caseVersion(), saved.evidenceSet());
        return saved;
    }

    public EvidenceOrganizationStore.Receipt correct(String actor, String key, String id, Correction input) {
        if (key == null || key.isBlank() || input == null || input.expectedVersion() < 1 ||
                input.matterId() == null || input.evidenceId() == null ||
                input.locator() == null || input.relation() == null ||
                input.reason() == null || input.reason().isBlank() ||
                !List.of("SUPPORT", "CORROBORATE", "CONTRADICT", "IRRELEVANT", "UNVERIFIED")
                        .contains(input.relation()))
            throw new IllegalArgumentException("Invalid correction");
        var prior = read(actor, id, input.expectedVersion());
        access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id, input.expectedVersion(),
                "CORRECT", "ANALYZE");
        if (prior.matters().stream().noneMatch(m -> m.id().equals(input.matterId())))
            throw new IllegalArgumentException("Matter unavailable");
        var ref = prior.evidenceSet().stream().filter(r -> r.id().equals(input.evidenceId()))
                .findFirst().orElseThrow(() -> new SecurityException("Evidence outside locked set"));
        var snapshot = source.read(prior.caseRef(), prior.caseVersion(), prior.evidenceSet());
        var original = snapshot.materials().stream().filter(m -> m.ref().equals(ref))
                .findFirst().orElseThrow();
        int line = parseLine(input.locator());
        var lines = original.text().split("\\R", -1);
        if (line > lines.length || lines[line - 1].isBlank())
            throw new IllegalArgumentException("Locator unavailable");
        var revisedLinks = new ArrayList<>(prior.links());
        revisedLinks.add(new EvidenceOrganizationStore.Link(input.matterId(), ref.id(),
                Long.parseLong(ref.version()), ref.sha256(), input.locator(), lines[line - 1],
                input.relation(), "HUMAN_CORRECTION", input.reason()));
        var next = new EvidenceOrganizationStore.Revision(id, prior.version() + 1,
                prior.organization(), prior.caseRef(), prior.caseVersion(), prior.ruleId(),
                prior.ruleVersion(), "PENDING_REVIEW", prior.evidenceSet(), prior.matters(),
                List.copyOf(revisedLinks), prior.times(), prior.unrelated(), prior.unprocessed(),
                actor, null, null, prior.exclusions());
        var receipt = store.command(key, hash(prior.toString() + input), actor, "CORRECT_EVIDENCE", () -> {
            if (store.latest(id) != prior.version()) throw new IllegalArgumentException("Stale revision");
            access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id,
                    prior.version(), "CORRECT", "ANALYZE");
            source.read(prior.caseRef(), prior.caseVersion(), prior.evidenceSet());
            store.append(next, actor);
            return new EvidenceOrganizationStore.Receipt(key, id, next.version());
        });
        access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id,
                prior.version(), "CORRECT", "ANALYZE");
        source.read(prior.caseRef(), prior.caseVersion(), prior.evidenceSet());
        return receipt;
    }

    public EvidenceOrganizationStore.Receipt review(String actor, String key, String id, Review input) {
        if (key == null || key.isBlank() || input == null || input.expectedVersion() < 1 ||
                input.decision() == null ||
                !List.of("CONFIRMED", "RETURNED").contains(input.decision()) ||
                input.reason() == null || input.reason().isBlank())
            throw new IllegalArgumentException("Invalid review");
        var prior = read(actor, id, input.expectedVersion());
        access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id, prior.version(),
                "REVIEW", "REVIEW");
        if (actor.equals(prior.lastActor()) || !"PENDING_REVIEW".equals(prior.state()))
            throw new SecurityException("Independent pending review required");
        var next = new EvidenceOrganizationStore.Revision(id, prior.version() + 1,
                prior.organization(), prior.caseRef(), prior.caseVersion(), prior.ruleId(),
                prior.ruleVersion(), input.decision(), prior.evidenceSet(), prior.matters(),
                prior.links(), prior.times(), prior.unrelated(), prior.unprocessed(),
                prior.lastActor(), actor, input.reason(), prior.exclusions());
        var receipt = store.command(key, hash(prior.toString() + input), actor, "REVIEW_EVIDENCE", () -> {
            if (store.latest(id) != prior.version()) throw new IllegalArgumentException("Stale revision");
            access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id,
                    prior.version(), "REVIEW", "REVIEW");
            source.read(prior.caseRef(), prior.caseVersion(), prior.evidenceSet());
            store.append(next, actor);
            return new EvidenceOrganizationStore.Receipt(key, id, next.version());
        });
        access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id,
                prior.version(), "REVIEW", "REVIEW");
        source.read(prior.caseRef(), prior.caseVersion(), prior.evidenceSet());
        return receipt;
    }

    public EvidenceOrganizationStore.Receipt exclude(String actor, String key, String id, Exclude input) {
        if (actor == null || actor.isBlank() || key == null || key.isBlank() || id == null || id.isBlank() ||
                input == null || input.expectedVersion() < 1 || input.evidenceId() == null ||
                input.evidenceId().isBlank() || input.reason() == null || input.reason().isBlank())
            throw new IllegalArgumentException("Invalid exclusion");
        var prior = store.revision(id, input.expectedVersion())
                .orElseThrow(() -> new SecurityException("Organization unavailable"));
        access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id, prior.version(),
                "CORRECT", "ANALYZE");
        var remaining = prior.evidenceSet().stream()
                .filter(ref -> !ref.id().equals(input.evidenceId())).toList();
        if (remaining.size() == prior.evidenceSet().size() || remaining.isEmpty())
            throw new IllegalArgumentException("Excluded evidence unavailable or last");
        var rule = rules.rule(prior.ruleId(), prior.ruleVersion())
                .orElseThrow(() -> new SecurityException("Rule unavailable"));
        var request = new EvidenceRuleTrial.Request(prior.caseRef(), prior.caseVersion(), remaining);
        require(actor, rule, new Input(id, rule.id(), rule.revision(), request));
        var candidate = new EvidenceRuleTrial(rules, source, access)
                .run(actor, rule.id(), rule.revision(), request);
        var snapshot = source.read(prior.caseRef(), prior.caseVersion(), remaining);
        var matters = candidate.matters().stream()
                .map(m -> new EvidenceOrganizationStore.Matter(m.matterId(), m.coverage())).toList();
        var links = new ArrayList<EvidenceOrganizationStore.Link>();
        for (var matter : candidate.matters()) {
            String firstValue = null;
            for (var value : matter.candidates()) {
                String relation = firstValue == null ? "SUPPORT_CANDIDATE" :
                        firstValue.equals(value.value()) ? "CORROBORATE_CANDIDATE" : "CONTRADICT_CANDIDATE";
                if (firstValue == null) firstValue = value.value();
                links.add(new EvidenceOrganizationStore.Link(matter.matterId(), value.materialId(),
                        Long.parseLong(value.version()), value.sha256(), value.locator(), value.value(),
                        relation, "MACHINE_CANDIDATE", "Configured document consistency mapping"));
            }
            for (var unrelatedId : candidate.unrelatedMaterials()) {
                var material = snapshot.materials().stream()
                        .filter(m -> m.ref().id().equals(unrelatedId)).findFirst().orElseThrow();
                links.add(new EvidenceOrganizationStore.Link(matter.matterId(), unrelatedId,
                        Long.parseLong(material.ref().version()), material.ref().sha256(),
                        "line 2", material.eventRef(), "IRRELEVANT_CANDIDATE",
                        "MACHINE_CANDIDATE", "Event reference outside case snapshot"));
            }
        }
        prior.links().stream().filter(link -> "HUMAN_CORRECTION".equals(link.originKind()) &&
                remaining.stream().anyMatch(ref -> ref.id().equals(link.evidenceId())))
                .forEach(links::add);
        var exclusions = new ArrayList<>(prior.exclusions());
        exclusions.add(new EvidenceOrganizationStore.Exclusion(input.evidenceId(), input.reason(), actor));
        var next = new EvidenceOrganizationStore.Revision(id, prior.version() + 1,
                prior.organization(), prior.caseRef(), prior.caseVersion(), prior.ruleId(),
                prior.ruleVersion(), "REVIEW_REQUIRED", remaining, matters, List.copyOf(links),
                prior.times().stream().filter(time -> remaining.stream()
                        .anyMatch(ref -> ref.id().equals(time.evidenceId()))).toList(),
                candidate.unrelatedMaterials(), candidate.unprocessedMaterials(), actor, null, null, exclusions);
        var receipt = store.command(key, hash(prior.toString() + input), actor, "EXCLUDE_EVIDENCE", () -> {
            if (store.latest(id) != prior.version()) throw new IllegalArgumentException("Stale revision");
            access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id,
                    prior.version(), "CORRECT", "ANALYZE");
            require(actor, rule, new Input(id, rule.id(), rule.revision(), request));
            source.read(prior.caseRef(), prior.caseVersion(), remaining);
            store.append(next, actor);
            return new EvidenceOrganizationStore.Receipt(key, id, next.version());
        });
        access.require(actor, prior.organization(), "EVIDENCE_ORGANIZATION", id,
                prior.version(), "CORRECT", "ANALYZE");
        require(actor, rule, new Input(id, rule.id(), rule.revision(), request));
        source.read(prior.caseRef(), prior.caseVersion(), remaining);
        return receipt;
    }

    private static int parseLine(String locator) {
        if (!locator.matches("line [1-9][0-9]*")) throw new IllegalArgumentException("Invalid locator");
        return Integer.parseInt(locator.substring(5));
    }

    private void require(String actor, EvidenceRuleStore.Rule rule, Input input) {
        access.require(actor, rule.organization(), "CASE", input.evidenceSet().caseRef(),
                Long.parseLong(input.evidenceSet().caseVersion()), "ORGANIZE", "ANALYZE");
        access.require(actor, rule.organization(), "EVIDENCE_RULE", rule.id(), rule.revision(),
                "READ_RULE", "RULE_MANAGEMENT");
        for (var ref : input.evidenceSet().materials())
            access.require(actor, rule.organization(), "EVIDENCE", ref.id(),
                    Long.parseLong(ref.version()), "ANALYZE", "ANALYZE");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
