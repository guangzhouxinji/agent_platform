package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptCopyRequestFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptCopyReconciliationTest {
    private ProductPromptCopyRequestFixture f;
    @BeforeEach void setup()throws Exception{f=new ProductPromptCopyRequestFixture();}

    @Test void freshAndReservedCopyRemainAllBeforeWithoutChangingSource(){
        var copy=f.copy("copy");var sourceRows=f.base.main.queryForMap("SELECT * FROM conp_prompt WHERE prompt_id=?",f.source.request().pending().promptId());
        assertThat(f.observe(copy.request()).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        f.reserve(copy);
        assertThat(f.observe(f.find(copy)).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        assertThat(f.base.main.queryForMap("SELECT * FROM conp_prompt WHERE prompt_id=?",f.source.request().pending().promptId())).isEqualTo(sourceRows);
        assertThat(f.base.mainCount("conp_revision")).isEqualTo(1);
    }

    @Test void rolledBackCopyCanRebuildOnlyItsOriginalPreparedTargetAndSource(){
        var copy=f.copy("rollback");f.reserve(copy);
        assertThatThrownBy(()->f.base.mainTx.executeWithoutResult(status->{
            f.base.store.append(copy.prepared());throw new IllegalStateException("copy main commit not reached");
        })).hasMessage("copy main commit not reached");
        var original=f.find(copy);
        assertThat(f.observe(original).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        f.base.now=f.base.now.plusSeconds(1);
        f.base.mainTx.executeWithoutResult(status->{
            var rebuilt=f.base.store.prepare(copy.prepared().write(),original.receipt().id());
            f.base.requests.requirePrepared(original,rebuilt);
            f.base.store.append(rebuilt);
        });
        assertThat(f.finish(copy)).isEqualTo(original.receipt());
        assertThat(f.find(copy).copySource()).isEqualTo(original.copySource());
        assertThat(f.base.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.base.mainCount("conp_event")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"source-pending","source-body-expired"})
    void exactCommittedCopySettlesWithoutRequiringSourceHeadOrBodyAgain(String sourceState){
        var copy=f.copy("settle");f.reserve(copy);f.append(copy);
        if("source-pending".equals(sourceState)){
            var sourceSave=f.save(f.source,"source-save");f.reserve(sourceSave);
            assertThat(f.base.controls.control(f.source.request().pending().promptId()).state()).isEqualTo("PENDING");
        }else f.base.now=copy.request().copySource().sourceBodyUntil();
        assertThat(f.finish(copy)).isEqualTo(copy.request().receipt());
        assertThat(f.find(copy).state()).isEqualTo("READY");
        assertThat(f.finish(copy)).isEqualTo(copy.request().receipt());
        assertThat(f.base.mainCount("conp_revision")).isEqualTo(2);
        if("source-body-expired".equals(sourceState))assertThatThrownBy(()->f.base.mainTx.execute(status->
                f.base.store.readRevision(copy.request().pending().promptId(),copy.request().header().revisionId(),f.base.controls)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void oldCopyReceiptSurvivesReadyTargetSaveButNotPendingTargetSuccessor(){
        var copy=f.copy("old-copy");f.reserve(copy);f.append(copy);var receipt=f.finish(copy);var ready=f.find(copy);
        var save=f.save(copy,"target-save");f.reserve(save);
        assertThat(f.observe(ready).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        assertThatThrownBy(()->f.find(copy)).isInstanceOf(IllegalStateException.class);
        f.append(save);f.finish(save);
        assertThat(f.finish(copy)).isEqualTo(receipt);assertThat(f.find(copy).copySource()).isEqualTo(ready.copySource());
        assertThat(f.base.controls.control(copy.request().pending().promptId()).generation()).isEqualTo(2L);
    }

    @Test void foreignPromptWithExactCopyKeyIsNotMistakenForAllBefore(){
        var copy=f.copy("foreign-key");var receipt=copy.request().receipt();
        f.base.main.update("INSERT INTO conp_receipt SELECT ?,workspace_id,issuer,?,'COPY',?,request_sha,prompt_id,revision_id,generation,"
                        +"content_sha,result_sha,submitted_at,audit_until FROM conp_receipt WHERE receipt_id=?",
                id(),receipt.subject(),receipt.keySha(),f.source.request().receipt().id());
        assertThat(f.observe(copy.request()).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        assertThat(f.base.count("idp_prompt_request")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"revision","event","receipt"})
    void partialCommittedTargetNeverProducesExactAfterProof(String missing){
        var copy=f.copy("partial");f.reserve(copy);f.append(copy);var receipt=copy.request().receipt();
        if("revision".equals(missing))f.base.main.update("UPDATE conp_revision SET record_sha=? WHERE revision_id=?",hash("corrupt"),receipt.revisionId());
        else f.base.main.update("DELETE FROM conp_"+missing+" WHERE "+("event".equals(missing)?"event_id":"receipt_id")+"=?",receipt.id());
        assertThat(f.observe(f.find(copy)).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        assertThat(f.base.count("idp_revision_witness")).isEqualTo(1);
    }

    @Test void changedSourceScalarsAfterObservationCannotConsumeOldExactAfterProof(){
        var copy=f.copy("changed-proof");f.reserve(copy);f.append(copy);
        var original=f.find(copy);
        f.base.mainTx.executeWithoutResult(status->{
            var proof=f.reconciliation.observe(f.base.workspace,original).exactAfter();
            f.base.authority.update("UPDATE idp_prompt_request SET source_record_sha=? WHERE request_id=?",hash("changed"),copy.request().pending().intentId());
            assertThatThrownBy(()->f.suspended.execute(outer->f.base.authorityTx.execute(inner->f.base.requests.complete(proof))))
                    .isInstanceOf(IllegalStateException.class);
        });
        assertThat(f.base.authority.queryForObject("SELECT state FROM idp_prompt_request WHERE request_id=?",String.class,copy.request().pending().intentId())).isEqualTo("PENDING");
        assertThat(f.base.count("idp_revision_witness")).isEqualTo(1);
    }
}
