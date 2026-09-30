package cn.xinji.agentplatform.enforcement.material;

import static cn.xinji.agentplatform.enforcement.material.MaterialDigests.hash;

import java.time.Clock;
import java.util.List;

/** Human collection and independent byte verification, separate from an upload claim. */
public final class MaterialCollectionService {
    private final MaterialRepository repository;
    private final MaterialFiles files;
    private final MaterialAccess access;
    private final Clock clock;

    public MaterialCollectionService(MaterialRepository repository, MaterialFiles files,
                                     MaterialAccess access, Clock clock) {
        this.repository = repository;
        this.files = files;
        this.access = access;
        this.clock = clock;
    }

    public MaterialWork.Receipt attest(String actor, String key, String id, long expected,
                                       String sourceRef, String reason) {
        if (sourceRef == null || sourceRef.isBlank() || reason == null || reason.isBlank())
            throw new IllegalArgumentException("Collection location and method required");
        current(actor, id, expected, "COLLECT_ATTEST");
        var receipt = repository.command(key, hash("COLLECT_ATTEST", id, String.valueOf(expected), sourceRef, reason),
                actor, "COLLECT_ATTEST", () -> {
                    var material = current(actor, id, expected, "COLLECT_ATTEST");
                    if (material.version() != expected || !actor.equals(material.collector()) || expected != 1
                            || !"PENDING_VERIFICATION".equals(material.state()))
                        throw new IllegalArgumentException("Collector must attest the uploaded original");
                    if (repository.materialActions(id).stream().anyMatch(a -> "COLLECT_ATTEST".equals(a.action())))
                        throw new IllegalStateException("Collection already attested");
                    repository.appendMaterialAction(new MaterialWork.MaterialAction(id, expected + 1,
                            "COLLECT_ATTEST", actor, sourceRef, reason, material.sha256(), clock.instant()),
                            "PENDING_VERIFICATION");
                    return new MaterialWork.Receipt(key, "COLLECT_ATTEST", id, expected + 1);
                });
        current(actor, id, expected, "COLLECT_ATTEST");
        return receipt;
    }

    public MaterialWork.Receipt verify(String actor, String key, String id, long expected, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Verification reason required");
        current(actor, id, expected, "VERIFY");
        var receipt = repository.command(key, hash("VERIFY", id, String.valueOf(expected), reason), actor,
                "VERIFY", () -> {
                    var material = current(actor, id, expected, "VERIFY");
                    var collection = repository.materialActions(id).stream()
                            .filter(a -> "COLLECT_ATTEST".equals(a.action())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Collection attestation missing"));
                    if (material.version() != expected || expected != collection.revision()
                            || actor.equals(collection.actor())
                            || !"PENDING_VERIFICATION".equals(material.state()))
                        throw new IllegalArgumentException("Independent current verification required");
                    repository.appendMaterialAction(new MaterialWork.MaterialAction(id, expected + 1,
                            "VERIFY", actor, collection.sourceRef(), reason, material.sha256(), clock.instant()),
                            "VERIFIED");
                    return new MaterialWork.Receipt(key, "VERIFY", id, expected + 1);
                });
        current(actor, id, expected, "VERIFY");
        return receipt;
    }

    public List<MaterialWork.MaterialAction> actions(String actor, String id) {
        var material = current(actor, id, -1, "READ");
        var result = repository.materialActions(id);
        current(actor, material.id(), -1, "READ");
        return result;
    }

    private MaterialWork.Material current(String actor, String id, long version, String action) {
        var material = repository.material(id).orElseThrow(() -> new IllegalArgumentException("Material not found"));
        if (!material.expiresAt().isAfter(clock.instant()) || !material.uses().contains("SAVE"))
            throw new SecurityException("Material use unavailable");
        access.require(actor, material.organization(), "MATERIAL", id,
                version > 0 ? version : material.version(), action,
                "READ".equals(action) ? "DISPLAY" : "SAVE");
        var clue = repository.clue(material.clueId()).orElseThrow();
        var observation = repository.observation(clue.observationId()).orElseThrow();
        var source = repository.source(observation.sourceId()).orElseThrow();
        if (!source.active() || !source.expiresAt().isAfter(clock.instant())
                || !source.uses().contains("SAVE")) throw new SecurityException("Source use unavailable");
        access.requireSource(source.id(), "SAVE", source.epoch());
        if (!hash(files.read(material.fileKey())).equals(material.sha256()))
            throw new IllegalStateException("Original bytes changed");
        return material;
    }
}
