package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Source-only H2 recovery evidence; no physical restore or current business activation. */
class ProductHumanWaitingRecoveryVerifierTest {
    @TempDir Path root;
    private ProductHumanWaitingRecoveryFixture fixture;
    @BeforeEach void setup() throws Exception { fixture=new ProductHumanWaitingRecoveryFixture(); fixture.setup(root); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void realRequestClosesAllNativeTablesAndReferences(boolean material) {
        fixture.packet(material);
        var result=fixture.verifier().verify(fixture.until());
        assertThat(result.packages()).isEqualTo(1); assertThat(result.scopes()).isEqualTo(1);
        assertThat(result.businessActivation()).isFalse(); assertThat(result.closureSha()).matches("[a-f0-9]{64}");
        assertThat(fixture.verifier().verify(fixture.until())).isEqualTo(result);
    }
    @Test void legitimateNewTaskWithoutPackageStillChecksIndependentScope() {
        assertThat(fixture.verifier().verify(fixture.until()).packages()).isZero();
        fixture.request.base.authority.update("UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='unknown'");
        rejects();
    }
    @ParameterizedTest @ValueSource(strings={"receipt","handling","state","actor","sha","time","request","scope","event"})
    void nativeDamageCannotDisappearThroughInnerJoins(String damage) {
        fixture.packet(false); var db=fixture.request.base.db;
        switch(damage) {
            case "receipt" -> db.update("DELETE FROM con6_human_receipt");
            case "handling" -> db.update("DELETE FROM con6_human_handling");
            case "state" -> db.update("UPDATE con6_human_handling SET state='CLAIMED',processing_version=2");
            case "actor" -> db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL");
            case "sha" -> db.update("UPDATE con6_human_package SET manifest_sha=?","b".repeat(64));
            case "time" -> db.update("UPDATE con6_human_handling SET last_change_at=?",Timestamp.from(fixture.request.base.clock.instant().minusSeconds(1)));
            case "request" -> db.update("UPDATE con6_human_receipt SET request_id='different-original-key'");
            case "scope" -> fixture.request.base.authority.update("UPDATE id6_human_cleanup_scope SET history_sha=?","b".repeat(64));
            case "event" -> db.update("UPDATE con6_human_cleanup_event SET event_sha=?","b".repeat(64));
            default -> throw new AssertionError(damage);
        }
        rejects();
    }
    @ParameterizedTest @ValueSource(strings={"con6_human_handling","con6_human_event","con6_human_receipt"})
    void OrphanRowsInDamagedRestorationAreRejected(String table) {
        fixture.packet(false); var db=fixture.request.base.db;
        db.execute("SET REFERENTIAL_INTEGRITY FALSE");
        db.update("UPDATE "+table+" SET package_id='orphan'");
        rejects();
    }
    @ParameterizedTest @ValueSource(strings={"body","null","audit","scope"})
    void everyScalarDeadlineIsCheckedBeforeAnyJsonDecode(String expired) {
        fixture.packet(false); var db=fixture.request.base.db;
        db.update("UPDATE con6_human_package SET manifest_json='invalid JSON'");
        db.update("UPDATE con6_human_cleanup_event SET event_json='invalid JSON'");
        fixture.request.base.clock.now=fixture.request.base.clock.instant().plusSeconds(2);
        switch(expired) {
            case "body" -> db.update("UPDATE con6_human_package SET body_access_until=?",Timestamp.from(fixture.request.base.clock.instant()));
            case "null" -> db.update("UPDATE con6_human_package SET body_access_until=NULL");
            case "audit" -> db.update("UPDATE con6_human_event SET audit_until=?",Timestamp.from(fixture.request.base.clock.instant()));
            case "scope" -> fixture.request.base.authority.update("UPDATE id6_human_cleanup_scope SET valid_until=?",Timestamp.from(fixture.request.base.clock.instant()));
            default -> throw new AssertionError(expired);
        }
        assertThatThrownBy(()->fixture.verifier().verify(fixture.until())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Human WAITING source recovery closure invalid");
    }
    @Test void revokedHistoricalActorDoesNotBecomeAnActiveSessionRequirement() {
        fixture.packet(false);
        fixture.request.base.db.update("UPDATE id2_grant SET status='REVOKED'");
        assertThat(fixture.verifier().verify(fixture.until()).businessActivation()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"withdrawn","save","expired","shortSave"})
    void currentSourceRightsMustSurviveHistoricalPacket(String change) {
        fixture.packet(true); var base=fixture.request.base;
        if ("shortSave".equals(change)) assertThat(fixture.verifier().verify(fixture.until()).packages()).isEqualTo(1);
        var watermark=new ProductSourceWatermark(base.db,base.authority,new org.springframework.transaction.support.TransactionTemplate(base.manager));
        var before=watermark.current("source-1");
        watermark.change("source-1",intent -> {
                    switch(change) {
                        case "withdrawn" -> {
                            base.db.update("UPDATE con4_source_current SET status='WITHDRAWN'");
                            base.db.update("UPDATE con4_source_contract SET status='WITHDRAWN',row_version=row_version+1");
                        }
                        case "save" -> base.db.update("DELETE FROM con4_source_field_use WHERE use_code='SAVE'");
                        case "shortSave" -> base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE use_code='SAVE'",
                                Timestamp.from(base.clock.instant().plusSeconds(60)));
                        case "expired" -> {
                            base.clock.now=base.clock.instant().plusSeconds(2);
                            base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE use_code='SAVE'",Timestamp.from(base.clock.instant()));
                        }
                        default -> throw new AssertionError(change);
                    }
                    assertThat(base.db.update("UPDATE con4_source_current SET source_epoch=?,row_version=? WHERE source_id=? AND source_epoch=? AND row_version=?",
                            before.epoch()+1,before.rowVersion()+1,"source-1",before.epoch(),before.rowVersion())).isEqualTo(1);
                    base.db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,decision_id,operation,"
                                    + "before_epoch,after_epoch,before_version,after_version,intent_id,before_history_sha,actor_ref,request_sha,occurred_at) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",java.util.UUID.randomUUID().toString(),"source-1",
                            before.contractVersion(),before.decisionId(),"withdrawn".equals(change)?"WITHDRAW":"ADMIT",
                            before.epoch(),before.epoch()+1,before.rowVersion(),before.rowVersion()+1,intent.id(),intent.beforeHistorySha(),
                            "component-source-maintainer",ProductComparisonJson.hash("component-source-change:"+change),Timestamp.from(base.clock.instant()));
                    return null;
                });
        assertThat(watermark.current("source-1").epoch()).isEqualTo(before.epoch()+1);
        if ("shortSave".equals(change))
            assertThatThrownBy(()->fixture.verifier().verify(fixture.until())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Recovered human reference closure unavailable");
        else rejects();
    }
    @Test void internallySealedRecoveryWithOverwideHistoricalBoundsIsRejectedAtBoundsCheck() {
        var packet=fixture.packet(true);var base=fixture.request.base;
        var backupUntil=fixture.until();var shortSave=base.clock.instant().plusSeconds(60);
        assertThat(shortSave).isAfter(backupUntil).isBefore(packet.body().manifest().bounds().sourceSaveUntil());
        assertThat(fixture.verifier().verify(backupUntil).packages()).isEqualTo(1);
        // Damaged restoration fixture, not a lawful source transition: simulate internally
        // resealed source rows whose still-current epoch hides a reduced SAVE allowance.
        base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE use_code='SAVE'",Timestamp.from(shortSave));
        String digest=ProductSourceHistoryBatch.digest(base.db,"source-1",true);
        base.db.update("UPDATE con4_source_current SET history_sha=? WHERE source_id='source-1'",digest);
        base.authority.update("UPDATE id4_source_watermark SET history_sha=? WHERE source_id='source-1'",digest);
        new ProductSourceWatermark(base.db,base.authority,new org.springframework.transaction.support.TransactionTemplate(base.manager)).assertAllCurrent();
        assertThatThrownBy(()->fixture.verifier().verify(backupUntil)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Human WAITING source recovery closure invalid");
    }
    @Test void internallySealedSourceCutoffRejectsAuditOnlyOverwideHistoricalBounds() {
        var packet=fixture.packet(true);var base=fixture.request.base;
        var backupUntil=fixture.until();var saveUntil=base.clock.instant().plusSeconds(200);
        var original=packet.body().manifest().bounds();
        assertThat(saveUntil).isAfter(original.sourceSaveUntil()).isAfter(backupUntil).isBefore(original.auditSaveUntil());
        assertThat(fixture.verifier().verify(backupUntil).packages()).isEqualTo(1);
        // Keep the same current source coordinates and a self-consistent seal; isolate the audit cutoff mismatch.
        base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE use_code='SAVE'",Timestamp.from(saveUntil));
        String digest=ProductSourceHistoryBatch.digest(base.db,"source-1",true);
        base.db.update("UPDATE con4_source_current SET history_sha=? WHERE source_id='source-1'",digest);
        base.authority.update("UPDATE id4_source_watermark SET history_sha=? WHERE source_id='source-1'",digest);
        var rebuilt=new ProductHumanWaitingRecoveryReferences(base.db,base.authority,fixture.request.mapper,base.clock,
                fixture.taskPolicy,fixture.factsPolicy,fixture.request.files).verify(packet.body().manifest());
        var allowed=rebuilt.bounds();
        assertThat(allowed.taskWriteUntil()).isEqualTo(original.taskWriteUntil());
        assertThat(allowed.taskSaveUntil()).isEqualTo(original.taskSaveUntil());
        assertThat(allowed.sourceUseUntil()).isEqualTo(original.sourceUseUntil());
        assertThat(allowed.sourceSaveUntil()).isEqualTo(original.sourceSaveUntil());
        assertThat(allowed.backupSaveUntil()).isEqualTo(original.backupSaveUntil());
        assertThat(allowed.auditSaveUntil()).isEqualTo(saveUntil).isBefore(original.auditSaveUntil());
        assertThatThrownBy(()->fixture.verifier().verify(backupUntil)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Human WAITING source recovery closure invalid");
        assertThat(base.db.queryForObject("SELECT manifest_sha FROM con6_human_package",String.class))
                .isEqualTo(packet.body().manifestSha());
    }
    @Test void bodyExpiringBetweenScalarScanAndClobReadIsNeverDecoded() {
        var packet=fixture.packet(false);
        fixture.request.base.db.update("UPDATE con6_human_package SET manifest_json='invalid JSON'");
        var db=new JdbcTemplate(fixture.request.base.source) {
            @Override public <T> java.util.List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                if(sql.startsWith("SELECT body_access_until,body_until,manifest_json"))
                    fixture.request.base.clock.now=ProductHumanRequestStore.bodyAccessUntil(packet.body().manifest());
                return super.query(sql,mapper,args);
            }
        };
        var until=fixture.until();
        assertThatThrownBy(()->fixture.verifier(db).verify(until)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Human WAITING source recovery closure invalid");
    }
    @Test void deadlineAndNativeMutationDuringLastPassRefuseSummary() {
        fixture.packet(false);
        var changed=new AtomicBoolean();
        var db=new JdbcTemplate(fixture.request.base.source) {
            private int reads;
            @Override public <T> java.util.List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                var result=super.query(sql,mapper,args);
                if(sql.startsWith("SELECT package_id,package_revision,task_id") && ++reads==2) {
                    fixture.request.base.db.update("UPDATE con6_human_handling SET state='CLAIMED'"); changed.set(true);
                }
                return result;
            }
        };
        assertThatThrownBy(()->fixture.verifier(db).verify(fixture.until())).isInstanceOf(RuntimeException.class);
        assertThat(changed).isTrue();
    }
    @Test void finiteBackupWindowDoesNotExtendTaskOrReadDeadline() {
        fixture.packet(false);
        assertThatThrownBy(()->fixture.verifier().verify(fixture.request.base.clock.instant())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->fixture.verifier().verify(fixture.request.base.clock.instant().plusSeconds(31L*86400))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->fixture.verifier().verify(fixture.request.base.task.expiresAt().plusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }
    @Test void shortCurrentScopeExpiresBeforeFinalCliReturnWithoutShorteningBackupRetention() {
        fixture.packet(true);
        var clock=fixture.request.base.clock;
        var executionUntil=clock.instant().plusSeconds(2);
        var backupUntil=fixture.until();
        fixture.request.base.db.update("UPDATE con4_task_source_scope SET expires_at=? WHERE task_id=? AND source_id='source-1'",
                Timestamp.from(executionUntil),fixture.request.base.task.id());
        var summary=fixture.verifier().verify(backupUntil);
        assertThat(summary.validUntil()).isEqualTo(backupUntil);
        assertThat(summary.executionUntil()).isEqualTo(executionUntil);
        ProductTaskRecoveryCheck.requireCurrentSourceSummary(summary,clock);
        // Simulate time spent in the CLI's final policy-file/watermark checks after inspection.
        clock.now=executionUntil;
        assertThat(clock.instant()).isBefore(backupUntil);
        assertThatThrownBy(()->ProductTaskRecoveryCheck.requireCurrentSourceSummary(summary,clock))
                .isInstanceOf(IllegalStateException.class).hasMessage("Source inspection deadline expired");
    }
    private void rejects() { assertThatThrownBy(()->fixture.verifier().verify(fixture.until())).isInstanceOf(RuntimeException.class); }
}
