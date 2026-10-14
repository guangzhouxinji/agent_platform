package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Configured exact policy rejects old scopes before original-key lookup or expired body decoding. */
class ProductHumanSourceAuditAdmissionTest {
    @TempDir Path root;
    private ProductHumanRequestApplicationFixture fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true);
    }

    @Test void shortOwnedFactsBodyDoesNotShortenSourceFreeAudit() {
        var original = fixture.request();
        var manifest = original.body().manifest();
        assertThat(manifest.references().sources()).isEmpty();
        assertThat(manifest.bounds().sourceSaveUntil()).isEqualTo(manifest.references().facts().expiresAt());
        assertThat(manifest.bounds().auditSaveUntil()).isEqualTo(fixture.base.task.expiresAt())
                .isAfter(manifest.bounds().sourceSaveUntil());
        assertThat(original.receipt().auditUntil()).isEqualTo(fixture.base.task.expiresAt());
        fixture.base.clock.now = manifest.references().facts().expiresAt();
        var lookup = fixture.application.lookup(fixture.base.token, fixture.base.task.id(), "human-original-key");
        assertThat(lookup.body()).isNull();
        assertThat(lookup.receipt()).isEqualTo(original.receipt());
    }

    @ParameterizedTest @ValueSource(strings = {"lookup", "replay"})
    void newExactPolicyRejectsOldScopeBeforeEvenExpiredReceiptLookup(String operation) {
        var original = fixture.request();
        fixture.base.clock.now = original.body().manifest().references().facts().expiresAt();
        fixture.base.db.update("UPDATE con6_human_package SET manifest_json='expired-malformed-body'");
        var base = fixture.base;
        var store = spy(new ProductHumanRequestStore(base.db, fixture.mapper, base.clock));
        // Technical fixture binding only: the product's first-release tuple requires separate inventory admission.
        var newPolicy = new OrdinaryHumanRetention.Policy("human-v3-source-cap-v1", 3, "e".repeat(64));
        var application = new ProductHumanRequestApplication(base.db, base.authority.getDataSource(), base.transaction,
                fixture.lockFixture.locks,
                new ProductHumanTaskFacts(base.db, base.access, base.decoder, fixture.factsPolicy, fixture.mapper, base.clock),
                new ProductHumanMaterialReferences(new ProductCollectedPatentTitles(fixture.files, fixture.mapper)),
                fixture.comparisons, fixture.cleanup, store, base.decoder, new ProductComparisonJson(fixture.mapper),
                newPolicy, fixture.taskPolicy, base.clock);
        clearInvocations(store, fixture.files, fixture.comparisons);
        assertThatThrownBy(() -> {
            if (operation.equals("lookup")) application.lookup(base.token, base.task.id(), "human-original-key");
            else application.request(base.token, base.task.id(), "human-original-key", fixture.command);
        }).isInstanceOf(IllegalStateException.class).hasMessage("Human cleanup authority unavailable").hasNoCause();
        verifyNoInteractions(store, fixture.files, fixture.comparisons);
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(1);
        assertThat(base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class))
                .isEqualTo("expired-malformed-body");
        assertThat(base.db.queryForObject("SELECT policy_id FROM con6_human_cleanup_scope", String.class))
                .isEqualTo(fixture.policy.reference());
        assertThat(base.authority.queryForObject("SELECT policy_id FROM id6_human_cleanup_scope", String.class))
                .isEqualTo(fixture.policy.reference());
    }
}
