package cn.xinji.agentplatform.enforcement.evidence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Read-only extraction of configured line prefixes; not a fact or relation decision. */
public final class EvidenceRuleTrial {
    public interface Access {
        void require(String actor, String organization, String type, String id, long version,
                     String action, String purpose);
    }
    public record Request(String caseRef, String caseVersion, List<EvidenceTrialSource.Ref> materials) { }
    public record Candidate(String matterId, String materialId, String version, String sha256,
                            String locator, String value, String state) { }
    public record MatterResult(String matterId, String coverage, List<Candidate> candidates) { }
    public record Result(String caseRef, String caseVersion, String ruleId, long ruleRevision,
                         List<MatterResult> matters, List<String> unrelatedMaterials,
                         List<String> unprocessedMaterials) { }

    private final EvidenceRuleStore rules;
    private final EvidenceTrialSource source;
    private final Access access;

    public EvidenceRuleTrial(EvidenceRuleStore rules, EvidenceTrialSource source, Access access) {
        this.rules = rules;
        this.source = source;
        this.access = access;
    }

    public Result run(String actor, String ruleId, long revision, Request request) {
        if (actor == null || actor.isBlank() || ruleId == null || ruleId.isBlank() || revision < 1 ||
                request == null || request.caseRef() == null || request.caseRef().isBlank() ||
                request.caseVersion() == null || request.caseVersion().isBlank() ||
                request.materials() == null || request.materials().isEmpty() ||
                request.materials().stream().anyMatch(ref -> ref == null || ref.id() == null ||
                        ref.version() == null || ref.sha256() == null) ||
                request.materials().stream().map(EvidenceTrialSource.Ref::id).distinct().count()
                        != request.materials().size())
            throw new IllegalArgumentException("Invalid trial references");
        var rule = rules.rule(ruleId, revision).orElseThrow(() -> new SecurityException("Rule unavailable"));
        require(actor, rule, request);
        var snapshot = source.read(request.caseRef(), request.caseVersion(), request.materials());
        if (!rule.caseType().equals(snapshot.caseType()) ||
                !rule.organization().equals(snapshot.organization()) ||
                !request.caseRef().equals(snapshot.caseRef()) ||
                !request.caseVersion().equals(snapshot.version()) || snapshot.materials().size() != request.materials().size())
            throw new SecurityException("Trial scope changed");
        var unrelated = new ArrayList<String>();
        var unprocessed = new ArrayList<String>();
        Map<String, List<Candidate>> candidates = new HashMap<>();
        for (var material : snapshot.materials()) {
            if (material.parseIssue() != null) {
                unprocessed.add(material.ref().id() + ":" + material.parseIssue());
                continue;
            }
            if (!snapshot.eventRef().equals(material.eventRef())) {
                unrelated.add(material.ref().id());
                continue;
            }
            var lines = material.text().split("\\R", -1);
            for (var matter : rule.matters()) {
                if (!matter.expectedMaterial().startsWith("line-prefix:")) continue;
                String prefix = matter.expectedMaterial().substring("line-prefix:".length());
                if (prefix.isBlank()) continue;
                for (int line = 0; line < lines.length; line++) {
                    if (!lines[line].startsWith(prefix)) continue;
                    String value = lines[line].substring(prefix.length()).trim();
                    if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
                    if (!value.isBlank()) candidates.computeIfAbsent(matter.id(), ignored -> new ArrayList<>())
                            .add(new Candidate(matter.id(), material.ref().id(), material.ref().version(),
                                    material.ref().sha256(), "line " + (line + 1), value, "EXTRACTED_CANDIDATE"));
                }
            }
        }
        if (unprocessed.size() == snapshot.materials().size())
            throw new SecurityException("No parseable evidence in locked set");
        var results = new ArrayList<MatterResult>();
        for (var matter : rule.matters()) {
            var found = List.copyOf(candidates.getOrDefault(matter.id(), List.of()));
            int distinct = new HashSet<>(found.stream().map(Candidate::value).toList()).size();
            results.add(new MatterResult(matter.id(), found.isEmpty() ? "MISSING" :
                    distinct > 1 ? "CONFLICT" : "PRESENT", found));
        }
        require(actor, rule, request);
        return new Result(snapshot.caseRef(), snapshot.version(), rule.id(), rule.revision(),
                List.copyOf(results), List.copyOf(unrelated), List.copyOf(unprocessed));
    }

    private void require(String actor, EvidenceRuleStore.Rule rule, Request request) {
        access.require(actor, rule.organization(), "EVIDENCE_RULE", rule.id(), rule.revision(),
                "READ_RULE", "RULE_MANAGEMENT");
        access.require(actor, rule.organization(), "CASE", request.caseRef(),
                Long.parseLong(request.caseVersion()), "READ", "ANALYZE");
        for (var ref : request.materials()) {
            long version = Long.parseLong(ref.version());
            access.require(actor, rule.organization(), "EVIDENCE", ref.id(), version, "READ", "ANALYZE");
        }
    }
}
