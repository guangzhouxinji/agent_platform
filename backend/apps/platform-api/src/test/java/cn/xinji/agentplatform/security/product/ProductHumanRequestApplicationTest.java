package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Complete internal no-comparison requests, not HTTP, DM8 or actual personnel admission. */
class ProductHumanRequestApplicationTest {
    @TempDir Path root;
    private ProductHumanRequestApplicationFixture fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true);
    }

    @Test void noReportRequestAuthenticatesRealFactsAndAtomicallyPersistsFourTables() {
        var result = fixture.request();
        var refs = result.body().manifest().references();
        assertThat(refs.facts().taskId()).isEqualTo(fixture.base.task.id());
        assertThat(refs.facts().message().sha()).isEqualTo(ProductComparisonJson.hash("original user statement"));
        assertThat(refs.facts().goal().id()).isEqualTo(fixture.base.db.queryForObject("SELECT goal_id FROM con3_goal_current", String.class));
        assertThat(refs.gaps()).containsExactly(ProductHumanRequestReferences.Gap.NO_COMPARISON_SELECTED,
                ProductHumanRequestReferences.Gap.NO_REPORT_SELECTED);
        assertThat(refs.comparison()).isNull();
        assertThat(refs.materials()).isEmpty();
        assertThat(result.receipt().requester().subject()).isEqualTo("member");
        assertThat(fixture.base.db.queryForObject("SELECT state FROM con6_human_handling", String.class)).isEqualTo("WAITING");
        rows(1);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void originalKeyReturnsSameResultWithoutNewRowsAfterTaskVersionChanges() {
        var original = fixture.request();
        assertThat(fixture.request()).isEqualTo(original);
        fixture.base.db.update("UPDATE con3_task SET version=8 WHERE task_id=?", fixture.base.task.id());
        fixture.base.db.update("DELETE FROM con3_goal_current");
        assertThat(fixture.request()).isEqualTo(original);
        assertThat(original.body().manifest().taskVersion()).isEqualTo(7);
        rows(1);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void originalKeyCannotAcceptDifferentPayload() {
        fixture.request();
        var changed = new ProductHumanRequestApplication.Command(7, null, List.of(), "different reason", "ORDINARY_ASSISTANCE");
        assertThatThrownBy(() -> fixture.application.request(fixture.base.token, fixture.base.task.id(), "human-original-key", changed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("payload conflicts");
        rows(1);
    }

    @Test void factsExpiryReturnsOnlyOriginalReceiptWithoutParsingBadBodyOrRenewal() {
        var original = fixture.request();
        fixture.base.clock.now = original.body().manifest().references().facts().expiresAt();
        fixture.base.db.update("UPDATE con6_human_package SET manifest_json='expired invalid JSON'");
        fixture.base.db.update("UPDATE con6_human_handling SET state='CLAIMED',processing_version=2");
        var receipt = fixture.request();
        assertThat(receipt.body()).isNull();
        assertThat(receipt.receipt()).isEqualTo(original.receipt());
        assertThat(fixture.base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class)).isEqualTo("expired invalid JSON");
        assertThat(fixture.base.db.queryForObject("SELECT state FROM con6_human_handling", String.class)).isEqualTo("CLAIMED");
        rows(1);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void missingCleanupScopeNeverAutoInitializesOrWritesRequest() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, false);
        assertThatThrownBy(fixture::request).isInstanceOf(IllegalStateException.class);
        rows(0);
        assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_scope", Integer.class)).isZero();
        assertThat(fixture.base.authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Integer.class)).isZero();
    }

    @Test void independentCleanupDriftAfterInsertRollsBackAllRequestTables() {
        var drifted = new AtomicBoolean();
        doAnswer(call -> {
            if (!drifted.get() && fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_package", Integer.class) == 1) {
                fixture.base.authority.update("UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='intervening-cleanup'");
                drifted.set(true);
            }
            return call.callRealMethod();
        }).when(fixture.cleanup).assertUnchanged(any());
        assertThatThrownBy(fixture::request).isInstanceOf(IllegalStateException.class);
        assertThat(drifted).isTrue();
        rows(0);
        assertThat(fixture.base.authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class)).isEqualTo("PENDING");
    }

    @ParameterizedTest @ValueSource(strings = {"reference", "version", "sha"})
    void taskRetentionPolicyTamperingCannotCreateHumanObjects(String change) {
        switch (change) {
            case "reference" -> fixture.base.db.update("UPDATE con3_task SET retention_ref='unapproved'");
            case "version" -> fixture.base.db.update("UPDATE con3_task SET retention_version=2");
            case "sha" -> fixture.base.db.update("UPDATE con3_task SET retention_evidence_sha=?", "f".repeat(64));
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(fixture::request).isInstanceOf(IllegalStateException.class);
        rows(0);
    }

    private void rows(int expected) {
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(expected);
    }
}
