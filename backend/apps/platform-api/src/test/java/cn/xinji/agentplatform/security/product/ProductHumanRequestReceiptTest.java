package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real H2 native receipt closure; this does not assert application source authorization. */
class ProductHumanRequestReceiptTest {
    private ProductHumanTaskTransactionTest base;
    private ProductHumanRequestStore store;
    private ProductHumanRequestStore.Key key;
    private ProductHumanRequestStore.Stored original;

    @BeforeEach void setup() throws Exception {
        base = new ProductHumanTaskTransactionTest();
        base.setup();
        store = base.requestStore();
        key = base.requestKey();
        original = base.transaction.execute(base.token, base.task.id(), context ->
                store.save(context, base.manifest(context), key));
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "legacyNull"})
    void unreadableBodyIsNeverDecodedOrRewrittenForMinimalReceipt(String condition) {
        if (condition.equals("expired")) base.clock.now = ProductHumanRequestStore.bodyAccessUntil(original.manifest());
        else base.db.update("UPDATE con6_human_package SET body_access_until=NULL");
        base.db.update("UPDATE con6_human_package SET manifest_json='invalid expired JSON'");
        base.db.update("UPDATE con6_human_handling SET state='CLAIMED',processing_version=2");
        var found = locate();
        assertThat(found.bodyAvailable(base.clock.instant())).isFalse();
        assertThat(found.receipt().packageId()).isEqualTo(original.manifest().packageId());
        assertThat(found.receipt().manifestSha()).isEqualTo(original.manifestSha());
        assertThat(found.receipt().auditUntil()).isEqualTo(original.auditUntil());
        assertThatThrownBy(() -> base.transaction.execute(base.token, base.task.id(), context ->
                store.body(context, key, found))).isInstanceOf(IllegalStateException.class);
        assertThat(base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class))
                .isEqualTo("invalid expired JSON");
        assertThat(base.db.queryForObject("SELECT state FROM con6_human_handling", String.class)).isEqualTo("CLAIMED");
        assertThat(base.db.queryForObject("SELECT COUNT(*) FROM con6_human_receipt", Integer.class)).isEqualTo(1);
    }

    @Test void auditDeadlineIsExclusiveEvenWhileTaskAndSessionRemainValid() {
        var until = original.manifest().createdAt().plusSeconds(50);
        base.db.update("UPDATE con6_human_receipt SET audit_until=?", Timestamp.from(until));
        base.db.update("UPDATE con6_human_event SET audit_until=?", Timestamp.from(until));
        base.clock.now = until.minusNanos(1);
        assertThat(locate().receipt().auditUntil()).isEqualTo(until);
        base.clock.now = until;
        assertThatThrownBy(this::locate).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"packageSha", "eventSha", "requestSha", "receiptTime", "eventTime", "scope"})
    void minimalReceiptRejectsCorruptNativeIndexesBeforeParsingBody(String change) {
        base.db.update("UPDATE con6_human_package SET manifest_json='invalid JSON',body_access_until=NULL");
        switch (change) {
            case "packageSha" -> base.db.update("UPDATE con6_human_package SET manifest_sha=?", "f".repeat(64));
            case "eventSha" -> base.db.update("UPDATE con6_human_event SET manifest_sha=?", "f".repeat(64));
            case "requestSha" -> base.db.update("UPDATE con6_human_event SET request_sha=?", "f".repeat(64));
            case "receiptTime" -> base.db.update("UPDATE con6_human_receipt SET created_at=?",
                    Timestamp.from(original.manifest().createdAt().minusSeconds(1)));
            case "eventTime" -> base.db.update("UPDATE con6_human_event SET occurred_at=?",
                    Timestamp.from(original.manifest().createdAt().minusSeconds(1)));
            case "scope" -> base.db.update("UPDATE con6_human_package SET data_scope='other'");
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(this::locate).isInstanceOf(RuntimeException.class);
    }

    @Test void changedStillLiveBodyDeadlineCannotAuthenticateManifest() {
        base.db.update("UPDATE con6_human_package SET body_access_until=?",
                Timestamp.from(original.manifest().createdAt().plusSeconds(50)));
        var found = locate();
        assertThat(found.bodyAvailable(base.clock.instant())).isTrue();
        assertThatThrownBy(() -> base.transaction.execute(base.token, base.task.id(), context ->
                store.body(context, key, found))).isInstanceOf(IllegalStateException.class);
    }

    @Test void oldKeyPayloadConflictIsNotMistakenForANewRequestAfterBodyExpiry() {
        base.clock.now = ProductHumanRequestStore.bodyAccessUntil(original.manifest());
        var changed = new ProductHumanRequestStore.Key(key.requestId(), "f".repeat(64));
        assertThatThrownBy(() -> base.transaction.execute(base.token, base.task.id(), context -> store.locate(context, changed)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("payload conflicts");
    }

    @ParameterizedTest @ValueSource(strings = {"unchanged", "messageChanged", "readRevoked", "expired"})
    void historicalFactsRemainBoundToOriginalCoordinatesAndCurrentRights(String change) {
        var facts = base.taskFacts();
        var expected = base.transaction.execute(base.token, base.task.id(), context -> facts.load(context, base.token));
        base.db.update("DELETE FROM con3_goal_current");
        base.db.update("UPDATE con3_task SET version=8 WHERE task_id=?", base.task.id());
        assertThatThrownBy(() -> base.transaction.execute(base.token, base.task.id(), context -> facts.load(context, base.token)))
                .isInstanceOf(IllegalStateException.class);
        switch (change) {
            case "messageChanged" -> base.db.update("UPDATE con3_message SET raw_text='altered historical statement'");
            case "readRevoked" -> base.db.update("UPDATE id2_grant SET status='REVOKED' WHERE action_code='READ'");
            case "expired" -> base.clock.now = expected.expiresAt();
            case "unchanged" -> { }
            default -> throw new AssertionError(change);
        }
        if (change.equals("unchanged")) {
            var replay = base.transaction.execute(base.token, base.task.id(), context ->
                    facts.historical(context, base.token, expected));
            assertThat(replay).isEqualTo(expected);
            assertThat(replay.taskVersion()).isEqualTo(7);
        } else assertThatThrownBy(() -> base.transaction.execute(base.token, base.task.id(), context ->
                facts.historical(context, base.token, expected))).isInstanceOf(IllegalStateException.class);
    }

    private ProductHumanRequestStore.Lookup locate() {
        return base.transaction.execute(base.token, base.task.id(), context -> store.locate(context, key).orElseThrow());
    }
}
