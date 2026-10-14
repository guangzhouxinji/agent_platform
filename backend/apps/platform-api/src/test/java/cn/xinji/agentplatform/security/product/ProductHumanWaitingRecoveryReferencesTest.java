package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProductHumanWaitingRecoveryReferencesTest {
    @TempDir Path root;
    ProductHumanWaitingRecoveryFixture fixture;
    ProductHumanRequestStore.Manifest manifest;
    @BeforeEach void setup() throws Exception {fixture=new ProductHumanWaitingRecoveryFixture();fixture.setup(root);manifest=fixture.packet(true).body().manifest();}
    ProductHumanWaitingRecoveryReferences reader() {
        var request=fixture.request;return new ProductHumanWaitingRecoveryReferences(request.base.db,request.base.authority,request.mapper,request.base.clock,fixture.taskPolicy,fixture.factsPolicy,request.files);
    }
    @Test void nativeOriginalClosureDoesNotNeedHistoricalHumanGrantToRemainActive() {
        var before=reader().verify(manifest);
        fixture.request.base.db.update("UPDATE id2_grant SET status='REVOKED'");
        assertThat(reader().verify(manifest)).isEqualTo(before);
    }
    @Test void currentSaveUpperBoundAlsoLimitsAuditWithoutUsingShorterFactsBodyLimit() {
        var before=reader().verify(manifest);
        var base=fixture.request.base;
        var saveUntil=base.clock.instant().plusSeconds(200);
        assertThat(saveUntil).isAfter(before.bounds().sourceSaveUntil()).isBefore(before.bounds().auditSaveUntil());
        // Controlled resealed restoration input, not a new source authorization or a runtime mutation API.
        base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE use_code='SAVE'",java.sql.Timestamp.from(saveUntil));
        String digest=ProductSourceHistoryBatch.digest(base.db,"source-1",true);
        base.db.update("UPDATE con4_source_current SET history_sha=? WHERE source_id='source-1'",digest);
        base.authority.update("UPDATE id4_source_watermark SET history_sha=? WHERE source_id='source-1'",digest);
        var after=reader().verify(manifest);
        assertThat(after.bounds().auditSaveUntil()).isEqualTo(saveUntil);
        assertThat(after.bounds().sourceSaveUntil()).isEqualTo(before.bounds().sourceSaveUntil());
        assertThat(after.bounds().backupSaveUntil()).isEqualTo(before.bounds().backupSaveUntil());
        assertThat(after.executionUntil()).isEqualTo(before.executionUntil());
        assertThat(after.closureSha()).isNotEqualTo(before.closureSha());
        assertThat(manifest.bounds().auditSaveUntil()).isEqualTo(base.task.expiresAt());
    }
    @Test void changedOriginalIsRejectedEvenWhenManifestAndNativeRowsRemainUntouched() throws Exception {
        reader().verify(manifest);Files.writeString(root.resolve("capture/raw.json"),"tampered original");
        assertThatThrownBy(()->reader().verify(manifest)).isInstanceOf(RuntimeException.class);
    }
    @Test void currentSourceScopeCannotBeReplacedByHistoricalManifestCoordinates() {
        reader().verify(manifest);fixture.request.base.db.update("UPDATE con4_task_source_current SET status='REVOKED'");
        assertThatThrownBy(()->reader().verify(manifest)).isInstanceOf(RuntimeException.class);
    }
    @Test void selectedMaterialCannotHideItsSourcePin() {
        var old=manifest.references();
        var references=new ProductHumanRequestReferences(old.facts(),old.materials(),java.util.List.of(),old.comparison(),old.cleanup(),old.gaps());
        var altered=new ProductHumanRequestStore.Manifest(manifest.taskId(),manifest.organization(),manifest.dataScope(),manifest.taskVersion(),manifest.packageId(),manifest.packageRevision(),manifest.requester(),references.binding(),references,manifest.reason(),manifest.purpose(),manifest.policy(),manifest.bounds(),manifest.createdAt());
        assertThatThrownBy(()->reader().verify(altered)).isInstanceOf(IllegalStateException.class).hasMessage("Recovered human reference closure unavailable");
    }
    @Test void withdrawalDuringSecondInspectionOriginalReadIsRejectedByThatInspection() {
        var checked=reader();checked.verify(manifest);
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            Object original=call.callRealMethod();
            if("capture/raw.json".equals(call.getArgument(0))&&changed.compareAndSet(false,true)) {
                fixture.request.base.db.update("UPDATE con4_source_current SET status='WITHDRAWN'");
                fixture.request.base.db.update("UPDATE con4_source_contract SET status='WITHDRAWN'");
            }
            return original;
        }).when(fixture.request.files).verified(anyString(),anyString());
        assertThatThrownBy(()->checked.verify(manifest)).isInstanceOf(RuntimeException.class);
        assertThat(changed).isTrue();
    }
    @Test void unrelatedNativeFactsChangeCannotProduceTheSameClosureDigest() {
        var before=reader().verify(manifest);
        fixture.request.base.db.update("UPDATE con3_search_input SET requested_fields_json='[\"TITLE\",\"ABSTRACT\"]'");
        assertThat(reader().verify(manifest).closureSha()).isNotEqualTo(before.closureSha());
    }
}
