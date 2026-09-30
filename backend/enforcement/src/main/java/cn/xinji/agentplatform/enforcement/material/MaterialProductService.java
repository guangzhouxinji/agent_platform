package cn.xinji.agentplatform.enforcement.material;

import static cn.xinji.agentplatform.enforcement.material.MaterialDigests.hash;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ENF-01..05 first product chain. Every command checks current facts before commit and response. */
public final class MaterialProductService {
    public record SourceInput(String id, String organization, String rightsRef, long epoch,
                              Instant expiresAt, List<String> uses, String credentialRef) { }
    public record SourceConfigurationInput(long expectedVersion, String credentialRef, boolean enabled) { }
    public record ObservationInput(String sourceId, String recordId, String sourceVersion,
                                   String text, String materialMention,
                                   List<MaterialWork.ActorInput> actorMentions) {
        public ObservationInput(String sourceId, String recordId, String sourceVersion,
                                String text, String materialMention) {
            this(sourceId, recordId, sourceVersion, text, materialMention, List.of());
        }
    }
    public record RuleInput(String id, String sourceId, String literal, String basisRef) { }
    public record MaterialInput(String clueId, byte[] bytes, String rightsRef, Instant expiresAt,
                                List<String> uses, String collector, String collectedAt) { }

    private final MaterialRepository repository;
    private final MaterialFiles files;
    private final MaterialAccess access;
    private final Clock clock;
    private final MaterialSourceManagement sources;
    public MaterialProductService(MaterialRepository repository, MaterialFiles files,
                                  MaterialAccess access, Clock clock) {
        this.repository = repository;
        this.files = files;
        this.access = access;
        this.clock = clock;
        this.sources = new MaterialSourceManagement(repository, access, clock);
    }

    public MaterialWork.Receipt registerSource(String actor, String requestId, SourceInput input) {
        return sources.register(actor, requestId, input);
    }
    public MaterialWork.Source readSource(String actor, String id) {
        return sources.read(actor, id);
    }
    public MaterialWork.Receipt configureSource(String actor, String requestId, String id,
                                                SourceConfigurationInput input) {
        return sources.configure(actor, requestId, id, input);
    }
    public MaterialWork.Receipt observe(String actor, String requestId, ObservationInput input) {
        require(input != null && present(input.sourceId()) && present(input.recordId())
                && present(input.sourceVersion()) && present(input.text()));
        var source = source(input.sourceId(), "SAVE");
        access.require(actor, source.organization(), "SOURCE", source.id(), 1, "SYNC", "SAVE");
        String id = hash(source.id(), input.recordId(), input.sourceVersion());
        var actors = MaterialActorMentions.locate(id, input.text(), input.actorMentions());
        String fingerprint = hash("OBSERVE", input.sourceId(), input.recordId(), input.sourceVersion(),
                input.text(), String.valueOf(input.materialMention()), actors.toString());
        var receipt = repository.command(requestId, fingerprint, actor, "OBSERVE", () -> {
            currentSource(source, "SAVE");
            access.require(actor, source.organization(), "SOURCE", source.id(), 1, "SYNC", "SAVE");
            repository.addObservation(new MaterialWork.Observation(id, source.id(), input.recordId(), input.sourceVersion(),
                    source.organization(), input.text(), hash(input.text()), input.materialMention(), 1));
            repository.addActors(actors);
            return new MaterialWork.Receipt(requestId, "OBSERVE", id, 1);
        });
        currentSource(source, "DISPLAY");
        access.require(actor, source.organization(), "SOURCE", source.id(), 1, "SYNC", "SAVE");
        return receipt;
    }
    public MaterialWork.Receipt draftRule(String actor, String requestId, RuleInput input) {
        require(input != null && present(input.id()) && present(input.sourceId())
                && present(input.literal()) && present(input.basisRef()));
        var source = source(input.sourceId(), "QUERY");
        access.require(actor, source.organization(), "RULE", input.id(), 1, "EDIT_RULE", "RULE_MANAGEMENT");
        var receipt = repository.command(requestId,
                hash("DRAFT", input.id(), input.sourceId(), input.literal(), input.basisRef()), actor,
                "DRAFT_RULE", () -> {
                    currentSource(source, "QUERY");
                    access.require(actor, source.organization(), "RULE", input.id(), 1, "EDIT_RULE", "RULE_MANAGEMENT");
                    repository.addRule(new MaterialWork.Rule(input.id(), 1, source.organization(), source.id(),
                            input.literal(), input.basisRef(), "DRAFT", null));
                    return new MaterialWork.Receipt(requestId, "DRAFT_RULE", input.id(), 1);
                });
        access.require(actor, source.organization(), "RULE", input.id(), 1, "EDIT_RULE", "RULE_MANAGEMENT");
        return receipt;
    }
    public MaterialRuleTrial.Result trialRule(String actor, String ruleId, String observationId) {
        return new MaterialRuleTrial(repository, access, clock).run(actor, ruleId, observationId);
    }
    public MaterialWork.Receipt ruleState(String actor, String requestId, String id, long revision,
                                          String action, String positiveId, String negativeId) {
        require("PUBLISH_RULE".equals(action) || "DISABLE_RULE".equals(action));
        var rule = repository.rule(id).orElseThrow(() -> new IllegalArgumentException("Rule not found"));
        require(rule.revision() == revision);
        var source = source(rule.sourceId(), "QUERY");
        access.require(actor, rule.organization(), "RULE", id, revision, action, "RULE_MANAGEMENT");
        String fingerprint = hash(action, id, String.valueOf(revision),
                String.valueOf(positiveId), String.valueOf(negativeId));
        var receipt = repository.command(requestId, fingerprint, actor, action, () -> {
            currentSource(source, "QUERY");
            access.require(actor, rule.organization(), "RULE", id, revision, action, "RULE_MANAGEMENT");
            require(("PUBLISH_RULE".equals(action) && "DRAFT".equals(rule.state()))
                    || ("DISABLE_RULE".equals(action) && "ACTIVE".equals(rule.state())));
            var review = "PUBLISH_RULE".equals(action)
                    ? MaterialRuleReview.check(repository, rule, positiveId, negativeId) : null;
            repository.changeRule(new MaterialWork.Rule(id, revision, rule.organization(), rule.sourceId(),
                    rule.literal(), rule.basisRef(), "PUBLISH_RULE".equals(action) ? "ACTIVE" : "DISABLED",
                    "PUBLISH_RULE".equals(action) ? actor : rule.publisher()), actor, review);
            return new MaterialWork.Receipt(requestId, action, id, revision);
        });
        access.require(actor, rule.organization(), "RULE", id, revision, action, "RULE_MANAGEMENT");
        return receipt;
    }
    public MaterialWork.Receipt discover(String actor, String requestId, String observationId, String ruleId) {
        var observation = repository.observation(observationId).orElseThrow(() -> new IllegalArgumentException("Observation not found"));
        var rule = repository.rule(ruleId).orElseThrow(() -> new IllegalArgumentException("Rule not found"));
        require(rule.organization().equals(observation.organization()) && rule.sourceId().equals(observation.sourceId()));
        var source = source(observation.sourceId(), "QUERY");
        access.require(actor, observation.organization(), "OBSERVATION", observation.id(), 1, "CREATE_CLUE", "QUERY");
        String id = hash("CLUE", observation.id());
        var receipt = repository.command(requestId, hash("DISCOVER", observationId, ruleId,
                String.valueOf(rule.revision())), actor, "DISCOVER", () -> {
            currentSource(source, "QUERY");
            access.require(actor, observation.organization(), "OBSERVATION", observation.id(), 1, "CREATE_CLUE", "QUERY");
            require("ACTIVE".equals(repository.rule(ruleId).orElseThrow().state()));
            if (!observation.text().contains(rule.literal()))
                throw new IllegalArgumentException("No rule match; no clue created");
            repository.lockObservation(observation.id());
            var existing = repository.clue(id);
            if (existing.isEmpty()) repository.addClue(new MaterialWork.Clue(id, observation.id(), rule.id(),
                    rule.revision(), observation.organization(), "PENDING_REVIEW", 1));
            if (!repository.clueRules(id).contains(new MaterialWork.RuleRef(rule.id(), rule.revision())))
                repository.addClueRule(id, rule.id(), rule.revision());
            return new MaterialWork.Receipt(requestId, "DISCOVER", id, existing.map(MaterialWork.Clue::version).orElse(1L));
        });
        currentSource(source, "DISPLAY");
        access.require(actor, observation.organization(), "OBSERVATION", observation.id(), 1, "CREATE_CLUE", "QUERY");
        return receipt;
    }
    public MaterialWork.Receipt revise(String actor, String requestId, String clueId, long expected,
                                        String corrected, String reason) {
        var clue = clue(clueId);
        var observation = repository.observation(clue.observationId()).orElseThrow();
        require(clue.version() == expected && present(corrected) && present(reason));
        access.require(actor, clue.organization(), "CLUE", clueId, expected, "REVISE", "SAVE");
        var receipt = repository.command(requestId, hash("REVISE", clueId, String.valueOf(expected), corrected, reason),
                actor, "REVISE", () -> {
                    currentSource(source(observation.sourceId(), "SAVE"), "SAVE");
                    access.require(actor, clue.organization(), "CLUE", clueId, expected, "REVISE", "SAVE");
                    repository.addRevision(new MaterialWork.Revision(clueId, expected + 1, actor, reason,
                            observation.text(), corrected,
                            observation.materialMention() == null ? "NONE_MENTIONED" : "MENTION_ONLY_NO_FILE"));
                    return new MaterialWork.Receipt(requestId, "REVISE", clueId, expected + 1);
                });
        currentSource(source(observation.sourceId(), "SAVE"), "SAVE");
        access.require(actor, clue.organization(), "CLUE", clueId, expected, "REVISE", "SAVE");
        return receipt;
    }
    public MaterialWork.Receipt registerMaterial(String actor, String requestId, MaterialInput input) {
        require(input != null && input.bytes() != null && input.bytes().length > 0
                && input.bytes().length <= 10_000_000 && present(input.collector()) && present(input.collectedAt())
                && present(input.rightsRef()) && input.expiresAt() != null
                && input.expiresAt().isAfter(clock.instant()) && input.uses() != null
                && input.uses().contains("SAVE"));
        var clue = clue(input.clueId());
        access.require(actor, clue.organization(), "CLUE", clue.id(), clue.version(), "REGISTER_MATERIAL", "SAVE");
        var observation = repository.observation(clue.observationId()).orElseThrow();
        var source = source(observation.sourceId(), "SAVE");
        var staged = files.stage(input.bytes());
        String fingerprint = hash("MATERIAL", clue.id(), staged.sha256(), input.rightsRef(),
                input.expiresAt().toString(), String.join(",", input.uses()), input.collector(), input.collectedAt());
        var receipt = repository.command(requestId, fingerprint, actor, "REGISTER_MATERIAL", () -> {
            access.require(actor, clue.organization(), "CLUE", clue.id(), clue.version(), "REGISTER_MATERIAL", "SAVE");
            currentSource(source, "SAVE");
            String id = UUID.randomUUID().toString();
            repository.addMaterial(new MaterialWork.Material(id, clue.id(), clue.organization(), staged.sha256(),
                    staged.key(), input.rightsRef(), input.expiresAt(), List.copyOf(input.uses()),
                    input.collector(), input.collectedAt(), "PENDING_VERIFICATION", 1));
            repository.appendMaterialAction(new MaterialWork.MaterialAction(id, 1, "UPLOAD", actor, null,
                    "FILE_REGISTERED_UNVERIFIED", staged.sha256(), clock.instant()), null);
            return new MaterialWork.Receipt(requestId, "REGISTER_MATERIAL", id, 1);
        });
        currentSource(source, "SAVE");
        access.require(actor, clue.organization(), "CLUE", clue.id(), clue.version(), "REGISTER_MATERIAL", "SAVE");
        return receipt;
    }
    public byte[] preview(String actor, String materialId) {
        var material = repository.material(materialId).orElseThrow(() -> new IllegalArgumentException("Material not found"));
        requireMaterialUse(material, "PREVIEW");
        access.require(actor, material.organization(), "MATERIAL", material.id(), material.version(), "PREVIEW", "PREVIEW");
        var clue = clue(material.clueId());
        var observation = repository.observation(clue.observationId()).orElseThrow();
        var source = source(observation.sourceId(), "DISPLAY");
        byte[] bytes = files.read(material.fileKey());
        require(hash(bytes).equals(material.sha256()));
        requireMaterialUse(material, "PREVIEW");
        currentSource(source, "DISPLAY");
        access.require(actor, material.organization(), "MATERIAL", material.id(), material.version(), "PREVIEW", "PREVIEW");
        return bytes;
    }
    public MaterialWork.Clue readClue(String actor, String id) {
        var clue = clue(id);
        access.require(actor, clue.organization(), "CLUE", id, clue.version(), "READ", "DISPLAY");
        var observation = repository.observation(clue.observationId()).orElseThrow();
        currentSource(source(observation.sourceId(), "DISPLAY"), "DISPLAY");
        access.require(actor, clue.organization(), "CLUE", id, clue.version(), "READ", "DISPLAY");
        return clue;
    }
    public List<MaterialWork.RuleRef> readClueRules(String actor, String id) {
        readClue(actor, id);
        var rules = repository.clueRules(id);
        readClue(actor, id);
        return rules;
    }
    public MaterialWork.Observation readObservation(String actor, String id) {
        var observation = repository.observation(id).orElseThrow(() -> new IllegalArgumentException("Observation not found"));
        access.require(actor, observation.organization(), "OBSERVATION", id, observation.version(), "READ", "DISPLAY");
        currentSource(source(observation.sourceId(), "DISPLAY"), "DISPLAY");
        access.require(actor, observation.organization(), "OBSERVATION", id, observation.version(), "READ", "DISPLAY");
        return observation;
    }
    public List<MaterialWork.ActorMention> readActors(String actor, String observationId) {
        readObservation(actor, observationId);
        var actors = repository.actors(observationId);
        readObservation(actor, observationId);
        return actors;
    }
    public MaterialWork.Rule readRule(String actor, String id) {
        var rule = repository.rule(id).orElseThrow(() -> new IllegalArgumentException("Rule not found"));
        access.require(actor, rule.organization(), "RULE", id, rule.revision(), "READ", "RULE_MANAGEMENT");
        currentSource(source(rule.sourceId(), "QUERY"), "QUERY");
        access.require(actor, rule.organization(), "RULE", id, rule.revision(), "READ", "RULE_MANAGEMENT");
        return rule;
    }
    public MaterialWork.Material readMaterial(String actor, String id) {
        var material = repository.material(id).orElseThrow(() -> new IllegalArgumentException("Material not found"));
        requireMaterialUse(material, "SAVE");
        access.require(actor, material.organization(), "MATERIAL", id, material.version(), "READ", "DISPLAY");
        var clue = clue(material.clueId());
        var observation = repository.observation(clue.observationId()).orElseThrow();
        currentSource(source(observation.sourceId(), "DISPLAY"), "DISPLAY");
        access.require(actor, material.organization(), "MATERIAL", id, material.version(), "READ", "DISPLAY");
        return material;
    }

    private void requireMaterialUse(MaterialWork.Material material, String use) {
        if (!material.expiresAt().isAfter(clock.instant()) || !material.uses().contains(use))
            throw new SecurityException("Material use unavailable");
    }

    public List<MaterialWork.Revision> revisions(String actor, String clueId) {
        var clue = readClue(actor, clueId);
        var revisions = repository.revisions(clueId);
        readClue(actor, clueId);
        return revisions;
    }

    public int cleanOrphans() { return cleanOrphans(() -> { }); }

    public int cleanOrphans(Runnable currentJobGrant) {
        return new MaterialOrphanCleanup(repository, files).run(currentJobGrant);
    }

    private MaterialWork.Clue clue(String id) {
        return repository.clue(id).orElseThrow(() -> new IllegalArgumentException("Clue not found"));
    }

    private MaterialWork.Source source(String id, String use) {
        var source = repository.source(id).orElseThrow(() -> new IllegalStateException("Source unavailable"));
        currentSource(source, use);
        return source;
    }

    private void currentSource(MaterialWork.Source source, String use) {
        var current = repository.source(source.id()).orElseThrow(() -> new IllegalStateException("Source unavailable"));
        if (!source.equals(current) || !current.active() || !current.expiresAt().isAfter(clock.instant())
                || !current.uses().contains(use)) throw new IllegalStateException("Source use unavailable");
        access.requireSource(source.id(), use, source.epoch());
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid or stale material command");
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

}
