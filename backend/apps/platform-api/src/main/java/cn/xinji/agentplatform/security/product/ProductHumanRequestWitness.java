package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Current authority is checked independently of historical authorization observations. */
final class ProductHumanRequestWitness {
    private final Clock clock;
    private final ProductHumanCleanupScope cleanup;
    ProductHumanRequestWitness(Clock clock) { this.clock = Objects.requireNonNull(clock); this.cleanup = null; }
    ProductHumanRequestWitness(Clock clock, ProductHumanCleanupScope cleanup) {
        this.clock = Objects.requireNonNull(clock); this.cleanup = Objects.requireNonNull(cleanup);
    }

    void same(ProductHumanRequestReferences original, ProductHumanRequestReferences current, boolean historical) {
        if (!original.cleanup().equals(current.cleanup())) throw changed();
        compare(original, current, historical);
    }

    void historical(ProductHumanRequestStore.Stored original, ProductHumanRequestReferences current) {
        if (cleanup == null) throw changed();
        var manifest = original.manifest();
        cleanup.requireBodyUnchanged(manifest.references().cleanup(), current.cleanup(),
                new ProductHumanCleanupHistory.BodyIdentity(manifest.packageId(), manifest.packageRevision(), original.manifestSha()));
        compare(manifest.references(), current, true);
    }

    private void compare(ProductHumanRequestReferences original, ProductHumanRequestReferences current, boolean historical) {
        if (!historical) {
            if (!original.equals(current)) throw changed();
            return;
        }
        if (!original.facts().equals(current.facts())
                || !original.gaps().equals(current.gaps()) || !Objects.equals(original.comparison(), current.comparison())
                || original.materials().size() != current.materials().size() || original.sources().size() != current.sources().size()) throw changed();
        for (int i = 0; i < original.materials().size(); i++) {
            var old = original.materials().get(i); var now = current.materials().get(i);
            if (!old.type().equals(now.type()) || !old.documentId().equals(now.documentId())
                    || !old.documentVersion().equals(now.documentVersion()) || !old.rawSha().equals(now.rawSha())
                    || !old.fieldLocators().equals(now.fieldLocators()) || !old.source().source().equals(now.source().source())) throw changed();
        }
        for (int i = 0; i < original.sources().size(); i++) {
            var old = original.sources().get(i); var now = current.sources().get(i);
            if (!old.use().equals(now.use()) || !old.source().equals(now.source()) || !old.rights().equals(now.rights())) throw changed();
        }
    }

    void receipt(ProductHumanRequestStore.Lookup original, ProductHumanCleanupScope.Snapshot scope) {
        Instant now = clock.instant();
        if (!now.isBefore(original.receipt().auditUntil()) || !now.isBefore(scope.validUntil())) throw changed();
    }

    void body(ProductHumanRequestStore.Stored stored, ProductHumanRequestReferences current,
              ProductHumanCleanupScope.Snapshot scope) {
        Instant now = clock.instant(); var manifest = stored.manifest();
        new OrdinaryHumanRetention(manifest.policy()).requireReadable(now, manifest.createdAt(), null, manifest.bounds());
        if (!now.isBefore(scope.validUntil()) || !now.isBefore(current.facts().expiresAt())) throw changed();
        for (var source : current.sources())
            if (!now.isBefore(source.rights().useUntil()) || !now.isBefore(source.rights().saveUntil())) throw changed();
        for (var material : current.materials()) if (!now.isBefore(material.source().validUntil())) throw changed();
        if (current.comparison() != null) {
            if (!now.isBefore(current.comparison().expiresAt())) throw changed();
            for (var report : current.comparison().reports()) if (!now.isBefore(report.expiresAt())) throw changed();
        }
    }
    private static IllegalStateException changed() { return new IllegalStateException("Human request witness changed or expired"); }
}
