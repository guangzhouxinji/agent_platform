package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Record-level witness boundaries only; current Grant and raw-file authentication are separate tests. */
class ProductHumanRequestWitnessTest {
    private final ProductHumanTaskFactsFixture.MutableClock clock = new ProductHumanTaskFactsFixture.MutableClock();
    private final Instant start = Instant.parse("2026-10-13T01:00:00Z");
    private final ProductHumanRequestWitness witness = new ProductHumanRequestWitness(clock);
    private final ProductHumanRequestStore.Manifest manifest;

    ProductHumanRequestWitnessTest() {
        clock.now = start;
        var task = new ProductTaskDirectory.Task("task", "organization", "issuer", "member", "IN_PROGRESS",
                7, 1, "task-scope", 1, start.plusSeconds(3600));
        var actor = new OrdinaryHumanHandling.Actor("issuer", "member", OrdinaryHumanHandling.ActorKind.HUMAN);
        manifest = ProductHumanTaskFactsFixture.manifest(new ProductHumanTaskTransaction.Context(task, actor), clock);
    }

    @Test void historicalIdentitySurvivesNewScopeAndCurrentAuthorizationDeadline() {
        var old = references("scope-old", 1, start.plusSeconds(10), "a".repeat(64), "TITLE:0", source(1), 100, 100);
        var current = references("scope-new", 2, start.plusSeconds(80), "a".repeat(64), "TITLE:0", source(1), 100, 100);
        clock.now = start.plusSeconds(20); // Historical observation expired; current authority is independently valid.
        assertThatCode(() -> witness.same(old, current, true)).doesNotThrowAnyException();
        assertThatCode(() -> witness.body(stored(), current, current.cleanup())).doesNotThrowAnyException();
        assertThatThrownBy(() -> witness.body(stored(), old, old.cleanup())).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"bytes", "locator", "source", "use", "save"})
    void historicalIdentityRejectsChangedEvidenceOrSourceHardDeadline(String change) {
        var original = standard();
        var changed = references("scope-new", 2, start.plusSeconds(90),
                "bytes".equals(change) ? "b".repeat(64) : "a".repeat(64),
                "locator".equals(change) ? "TITLE:1" : "TITLE:0", source("source".equals(change) ? 2 : 1),
                "use".equals(change) ? 90 : 100, "save".equals(change) ? 90 : 100);
        assertThatThrownBy(() -> witness.same(original, changed, true)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"scopeId", "scopeVersion", "authorizationDeadline"})
    void submissionRecheckRejectsAuthorizationObservationDrift(String change) {
        var changed = references("scopeId".equals(change) ? "other-scope" : "scope", "scopeVersion".equals(change) ? 2 : 1,
                start.plusSeconds("authorizationDeadline".equals(change) ? 80 : 90),
                "a".repeat(64), "TITLE:0", source(1), 100, 100);
        assertThatThrownBy(() -> witness.same(standard(), changed, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test void bodyClosesExactlyAtItsBoundWhileReceiptRemainsAvailable() {
        clock.now = start.plusSeconds(100).minusNanos(1);
        var current = references("scope", 1, start.plusSeconds(300), "a".repeat(64), "TITLE:0", source(1), 200, 200);
        assertThatCode(() -> witness.body(stored(), current, current.cleanup())).doesNotThrowAnyException();
        clock.now = start.plusSeconds(100);
        assertThatThrownBy(() -> witness.body(stored(), current, current.cleanup())).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> witness.receipt(lookup(), current.cleanup())).doesNotThrowAnyException();
    }

    @ParameterizedTest @ValueSource(strings = {"audit", "cleanup"})
    void receiptClosesAtAuditOrCleanupDeadline(String deadline) {
        var scope = manifest.references().cleanup();
        if ("cleanup".equals(deadline)) scope = new ProductHumanCleanupScope.Snapshot(scope.scopeId(), scope.organization(),
                scope.taskId(), scope.generation(), scope.state(), scope.intent(), scope.policy(), scope.historySha(),
                scope.createdAt(), start.plusSeconds(50));
        var checked = scope;
        var until = "cleanup".equals(deadline) ? start.plusSeconds(50) : start.plusSeconds(3600);
        clock.now = until.minusNanos(1);
        assertThatCode(() -> witness.receipt(lookup(), checked)).doesNotThrowAnyException();
        clock.now = until;
        assertThatThrownBy(() -> witness.receipt(lookup(), checked)).isInstanceOf(IllegalStateException.class);
    }

    private ProductHumanRequestReferences standard() {
        return references("scope", 1, start.plusSeconds(90), "a".repeat(64), "TITLE:0", source(1), 100, 100);
    }
    private ProductHumanRequestReferences references(String scope, long version, Instant validUntil, String sha,
            String locator, ProductSourceAvailability.Active source, long use, long save) {
        var original = manifest.references();
        var material = new ProductHumanMaterialReferences.Reference("SOURCE_DOCUMENT", "document", "v1", sha,
                Map.of("TITLE", locator), new ProductHumanRequestLocks.SourceReference(source, scope, version, validUntil));
        var pin = new ProductHumanRequestReferences.SourcePin(new ProductHumanRequestLocks.SourceUse("source", Set.of("TITLE"), Set.of()),
                source, scope, version, new ProductHumanRequestLocks.Rights(start.plusSeconds(use), start.plusSeconds(save)));
        return new ProductHumanRequestReferences(original.facts(), List.of(material), List.of(pin), null, original.cleanup(), original.gaps());
    }
    private ProductSourceAvailability.Active source(long version) {
        return new ProductSourceAvailability.Active("source", "contract", "v1", "decision", 1, version,
                "manifest", "b".repeat(64), "c".repeat(64));
    }
    private ProductHumanRequestStore.Stored stored() {
        return new ProductHumanRequestStore.Stored(manifest, "d".repeat(64),
                new ProductHumanRequestStore.Key("request", "e".repeat(64)), start.plusSeconds(3600));
    }
    private ProductHumanRequestStore.Lookup lookup() {
        return new ProductHumanRequestStore.Lookup(new ProductHumanRequestStore.Receipt(manifest.taskId(), manifest.packageId(),
                1, "d".repeat(64), manifest.requester(), 1, "e".repeat(64), start, start.plusSeconds(3600)), start.plusSeconds(100));
    }
}
