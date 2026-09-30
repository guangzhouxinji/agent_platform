package cn.xinji.agentplatform.enforcement.material;

import java.time.Clock;

/** Read-only rule explanation; no candidate or receipt is produced by a trial. */
public final class MaterialRuleTrial {
    public record Result(String ruleId, long ruleRevision, String observationId,
                         String sourceVersion, String textSha256, String outcome,
                         String explanation) { }

    private final MaterialRepository repository;
    private final MaterialAccess access;
    private final Clock clock;

    public MaterialRuleTrial(MaterialRepository repository, MaterialAccess access, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.clock = clock;
    }

    public Result run(String actor, String ruleId, String observationId) {
        var rule = repository.rule(ruleId).orElseThrow(() -> new IllegalArgumentException("Rule not found"));
        var observation = repository.observation(observationId)
                .orElseThrow(() -> new IllegalArgumentException("Observation not found"));
        verify(actor, rule, observation);
        String outcome = !rule.sourceId().equals(observation.sourceId())
                || !rule.organization().equals(observation.organization()) ? "NOT_APPLICABLE"
                : observation.text() == null || observation.text().isBlank() ? "UNKNOWN"
                : observation.text().contains(rule.literal()) ? "MATCH" : "NO_MATCH";
        verify(actor, rule, observation);
        return new Result(rule.id(), rule.revision(), observation.id(), observation.sourceVersion(),
                observation.textSha256(), outcome, "Literal condition on stored source text; no legal finding");
    }

    private void verify(String actor, MaterialWork.Rule rule, MaterialWork.Observation observation) {
        access.require(actor, rule.organization(), "RULE", rule.id(), rule.revision(),
                "EDIT_RULE", "RULE_MANAGEMENT");
        access.require(actor, observation.organization(), "OBSERVATION", observation.id(),
                observation.version(), "READ", "DISPLAY");
        source(rule.sourceId(), "QUERY");
        source(observation.sourceId(), "DISPLAY");
    }

    private void source(String id, String use) {
        var current = repository.source(id).orElseThrow(() -> new IllegalStateException("Source unavailable"));
        if (!current.active() || !current.expiresAt().isAfter(clock.instant()) || !current.uses().contains(use))
            throw new IllegalStateException("Source use unavailable");
        access.requireSource(id, use, current.epoch());
    }
}
