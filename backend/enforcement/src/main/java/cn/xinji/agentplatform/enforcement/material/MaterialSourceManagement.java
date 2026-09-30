package cn.xinji.agentplatform.enforcement.material;

import static cn.xinji.agentplatform.enforcement.material.MaterialDigests.hash;

import java.time.Clock;
import java.util.List;

/** Maintains current enforcement source configuration and its independent admission intent. */
final class MaterialSourceManagement {
    private final MaterialRepository repository;
    private final MaterialAccess access;
    private final Clock clock;

    MaterialSourceManagement(MaterialRepository repository, MaterialAccess access, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.clock = clock;
    }

    MaterialWork.Receipt register(String actor, String requestId, MaterialProductService.SourceInput input) {
        if (input == null || !present(input.id()) || !present(input.organization())
                || !present(input.rightsRef()) || input.epoch() <= 0
                || input.expiresAt() == null || !input.expiresAt().isAfter(clock.instant())
                || input.uses() == null || input.uses().isEmpty()
                || !present(input.credentialRef())
                || !input.credentialRef().matches("[A-Za-z0-9._:-]{1,120}"))
            throw new IllegalArgumentException("Invalid or stale material command");
        String fingerprint = hash("SOURCE", input.toString());
        access.require(actor, input.organization(), "SOURCE", input.id(), 1, "CONFIGURE", "SOURCE_MANAGEMENT");
        access.prepareSource(input.id(), input.epoch());
        var receipt = repository.command(requestId, fingerprint, actor, "REGISTER_SOURCE", () -> {
            access.require(actor, input.organization(), "SOURCE", input.id(), 1, "CONFIGURE", "SOURCE_MANAGEMENT");
            repository.addSource(new MaterialWork.Source(input.id(), input.organization(), input.rightsRef(),
                    input.epoch(), input.expiresAt(), List.copyOf(input.uses()), true, input.credentialRef(), 1));
            return new MaterialWork.Receipt(requestId, "REGISTER_SOURCE", input.id(), 1);
        });
        var saved = repository.source(input.id()).orElseThrow(() -> new IllegalStateException("Source commit missing"));
        if (!saved.organization().equals(input.organization()) || saved.epoch() != input.epoch()
                || !saved.rightsRef().equals(input.rightsRef()))
            throw new IllegalStateException("Source commit differs from independent intent");
        access.activateSource(input.id(), input.epoch());
        access.requireSource(input.id(), "SOURCE_MANAGEMENT", input.epoch());
        access.require(actor, input.organization(), "SOURCE", input.id(), 1, "CONFIGURE", "SOURCE_MANAGEMENT");
        return receipt;
    }

    MaterialWork.Source read(String actor, String id) {
        var source = repository.source(id).orElseThrow(() -> new IllegalArgumentException("Source not found"));
        access.require(actor, source.organization(), "SOURCE", id, 1, "CONFIGURE", "SOURCE_MANAGEMENT");
        access.requireSource(id, "SOURCE_MANAGEMENT", source.epoch());
        return source;
    }

    MaterialWork.Receipt configure(String actor, String requestId, String id,
                                   MaterialProductService.SourceConfigurationInput input) {
        if (input == null || input.expectedVersion() <= 0 || !present(input.credentialRef())
                || !input.credentialRef().matches("[A-Za-z0-9._:-]{1,120}"))
            throw new IllegalArgumentException("Invalid or stale material command");
        var source = repository.source(id).orElseThrow(() -> new IllegalArgumentException("Source not found"));
        access.require(actor, source.organization(), "SOURCE", id, 1, "CONFIGURE", "SOURCE_MANAGEMENT");
        access.requireSource(id, "SOURCE_MANAGEMENT", source.epoch());
        return repository.command(requestId, hash("CONFIGURE_SOURCE", id, String.valueOf(input.expectedVersion()),
                input.credentialRef(), String.valueOf(input.enabled())), actor, "CONFIGURE_SOURCE", () -> {
            var current = repository.source(id).orElseThrow(() -> new IllegalStateException("Source unavailable"));
            if (current.configVersion() != input.expectedVersion())
                throw new IllegalArgumentException("Invalid or stale material command");
            repository.changeSourceConfiguration(id, input.expectedVersion(), input.credentialRef(),
                    input.enabled(), actor);
            return new MaterialWork.Receipt(requestId, "CONFIGURE_SOURCE", id, input.expectedVersion() + 1);
        });
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
