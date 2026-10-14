package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ProductPromptCopyApplicationTest {
    @TempDir Path root;
    ProductPromptCopyApplicationFixture c;
    ProductPromptCreateApplicationTest.Fixture f;
    @BeforeEach void setup()throws Exception{c=new ProductPromptCopyApplicationFixture(root);f=c.f;}

    @Test void copiesExactFullContentOnceWithoutTargetReadAndLeavesSourceUnchanged()throws Exception{
        var before=f.base.db.queryForMap("SELECT * FROM conp_prompt WHERE prompt_id=?",c.source.promptId());
        var source=f.base.mapper.readTree(json(c.source));f.mainSource.statements.clear();
        Receipt copied=c.copy("normal");
        assertThat(f.mainSource.statements.stream().filter(s->s.startsWith("SELECT revision_json")).count()).isEqualTo(1);
        assertThat(copied.operation()).isEqualTo(Operation.COPY);assertThat(copied.generation()).isEqualTo(1);
        assertThat(copied.workspaceId()).isEqualTo(c.target);assertThat(copied.promptId()).isNotEqualTo(c.source.promptId());
        assertThat(copied.contentSha()).isEqualTo(c.source.contentSha());
        var target=f.base.mapper.readTree(json(copied));
        for(String field:List.of("kind","name","body","scenarioIds","intentIds","audienceRef","language","displayOrder","variables"))
            assertThat(target.get(field)).as(field).isEqualTo(source.get(field));
        assertThat(target.path("provenance").path("createdBy").asText()).isEqualTo("member");
        assertThat(target.path("provenance").path("changeDescription").asText()).isEqualTo("COPY_DRAFT");
        assertThat(target.path("provenance").path("testRecordRefs").size()).isZero();
        assertThat(f.base.db.queryForMap("SELECT * FROM conp_prompt WHERE prompt_id=?",c.source.promptId())).isEqualTo(before);
        assertThat(c.lookup("normal")).isEqualTo(copied);assertThat(c.copy("normal")).isEqualTo(copied);
        assertThat(f.base.receipts).doesNotContainKey(c.target+":READ").doesNotContainKey(c.target+":CREATE");
    }

    @Test void reservationSurvivesMainFailureAndRestoresOriginalIdsTimeDeadlinesAndSource(){
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->c.copy("before")).isInstanceOf(RuntimeException.class);assertThat(f.mainSource.faultTriggered).isTrue();
        assertThat(f.mainCount("conp_revision")).isEqualTo(1);var before=fixedPrepared();
        f.base.now=f.base.now.plusSeconds(20);Receipt result=c.copy("before");
        assertThat(fixedPrepared()).isEqualTo(before);assertThat(result.id()).isEqualTo(before.get("RECEIPT_ID"));
        assertThat(f.mainCount("conp_revision")).isEqualTo(2);
    }

    @Test void committedTargetSettlesAfterSourceBodyAndIntentExpiryWithoutSourceText(){
        failReady("after");f.base.now=f.base.now.plusSeconds(601);f.mainSource.statements.clear();
        Receipt result=c.lookup("after");assertThat(result.operation()).isEqualTo(Operation.COPY);
        assertThat(f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
        assertThat(f.base.independent.queryForObject("SELECT state FROM idp_prompt_request WHERE operation='COPY'",String.class)).isEqualTo("READY");
        assertThat(c.copy("after")).isEqualTo(result);
    }

    @Test void committedTargetSettlesWhileSourceHasPendingSuccessor()throws Exception{
        failReady("after-pending");c.pendingSource();f.mainSource.statements.clear();f.authoritySource.statements.clear();
        assertThat(c.lookup("after-pending").operation()).isEqualTo(Operation.COPY);
        assertThat(f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
        assertThat(f.base.independent.queryForObject("SELECT state FROM idp_prompt_control WHERE prompt_id=?",String.class,c.source.promptId())).isEqualTo("PENDING");
    }

    @Test void freshSourcePendingRefusesBeforeAnyCopyReservation()throws Exception{
        c.pendingSource();assertThatThrownBy(()->c.copy("source-pending")).isInstanceOf(RuntimeException.class);
        assertThat(copyRequests()).isZero();
    }

    @Test void allBeforeCannotResumeWhenSourceHasPendingSuccessor()throws Exception{
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->c.copy("before-pending")).isInstanceOf(RuntimeException.class);
        c.pendingSource();var original=fixedPrepared();
        assertThatThrownBy(()->c.copy("before-pending")).isInstanceOf(RuntimeException.class);
        assertThat(fixedPrepared()).isEqualTo(original);assertThat(f.mainCount("conp_revision")).isEqualTo(1);
    }

    @Test void expiredAllBeforeDoesNotExtendTimeOrRebuildTarget(){
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->c.copy("expired")).isInstanceOf(RuntimeException.class);var original=fixedPrepared();
        f.base.now=f.base.now.plusSeconds(601);
        assertThatThrownBy(()->c.copy("expired")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->c.lookup("expired")).isInstanceOf(RuntimeException.class);
        assertThat(fixedPrepared()).isEqualTo(original);assertThat(f.mainCount("conp_revision")).isEqualTo(1);
    }

    @Test void freshSourceBodyAtExactCutoffCannotBeCopied(){
        f.base.now=f.base.now.plusSeconds(600);
        assertThatThrownBy(()->c.copy("body-cutoff")).isInstanceOf(RuntimeException.class);assertThat(copyRequests()).isZero();
    }

    @Test void selectedOldSourceStillCopiesAfterNewReadyVersion()throws Exception{
        f.base.grant(Action.SAVE);var next=c.permitSave(c.source,"member","Successor {{ratio}}");
        Receipt saved=c.save().request(f.base.token,c.source.promptId(),"next-source",next);
        assertThat(saved.generation()).isEqualTo(2);Receipt result=c.copy("old-source");
        assertThat(result.contentSha()).isEqualTo(c.source.contentSha()).isNotEqualTo(saved.contentSha());
        assertThat(c.prepared().get("SOURCE_REVISION_NUMBER")).isEqualTo(1L);
    }

    @Test void anotherSaveOnlyWriterKeepsCopierProvenanceWithoutSourceReadAndOldCopyReceiptSurvives()throws Exception{
        Receipt copied=c.copy("copy-first");String token=c.writer("editor-b");
        f.base.now=f.base.now.plusSeconds(1);var next=c.permitSave(copied,"editor-b","Editor complete draft {{ratio}}");
        f.mainSource.statements.clear();Receipt saved=c.save().request(token,copied.promptId(),"b-save",next);
        assertThat(f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
        var provenance=f.base.mapper.readTree(json(saved)).path("provenance");
        assertThat(provenance.path("createdBy").asText()).isEqualTo("member");
        assertThat(provenance.path("createdAt").asText()).isEqualTo(copied.submittedAt().toString());
        assertThat(provenance.path("modifiedBy").asText()).isEqualTo("editor-b");
        assertThat(c.lookup("copy-first")).isEqualTo(copied);
        assertThat(f.base.independent.queryForObject("SELECT generation FROM idp_prompt_control WHERE prompt_id=?",Long.class,copied.promptId())).isEqualTo(2);
    }

    @Test void mismatchedFirstEventOperationClosesSave()throws Exception{
        Receipt copied=c.copy("tamper-first");String token=c.writer("editor-b");var next=c.permitSave(copied,"editor-b","Editor {{ratio}}");
        f.base.db.update("UPDATE conp_event SET operation='CREATE' WHERE event_id=?",copied.id());
        assertThatThrownBy(()->c.save().request(token,copied.promptId(),"denied-save",next)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.base.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request WHERE operation='SAVE'",Long.class)).isZero();
    }

    @Test void copyKeyConflictDoesNotReopenSourceBody(){
        c.copy("same-key");c.f.mainSource.statements.clear();var original=c.command;
        var other=new ProductPromptCopyCommand(0,id(),original.sourceContentSha(),original.targetWorkspaceId(),original.sourceId());
        assertThatThrownBy(()->c.app().request(f.base.token,c.source.workspaceId(),c.source.promptId(),"same-key",other)).isInstanceOf(ProductPromptCreateConflict.class);
        assertThat(f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));assertThat(copyRequests()).isEqualTo(1);
    }

    @Test void lookupWrongButAuthorizedSourceWorkspaceIsUniformlyDenied()throws Exception{
        c.copy("lookup-scope");String other=id();f.base.addWorkspace(other,f.base.organization);f.base.grant(other,Action.READ);c.refreshRegistry();
        assertThatThrownBy(()->c.app().lookup(f.base.token,other,c.target,"lookup-scope")).isInstanceOf(ProductPromptAccessDenied.class);
    }

    @Test void targetCreateDoesNotReplaceCopyAndSourceReadIsRequired(){
        var copyGrant=f.base.receipts.get(c.target+":COPY");
        f.base.watermark.change(()->f.base.grants.revoke(copyGrant.grantId(),1,"component-maintainer",id(),ProductComparisonJson.hash(id())));
        f.base.grant(c.target,Action.CREATE);
        assertThatThrownBy(()->c.copy("only-create")).isInstanceOf(ProductPromptAccessDenied.class);assertThat(copyRequests()).isZero();
    }

    @Test void revokedSourceReadClosesExistingReceiptLookup(){
        c.copy("revoke-read");f.base.revoke(Action.READ);
        assertThatThrownBy(()->c.lookup("revoke-read")).isInstanceOf(ProductPromptAccessDenied.class);
    }

    @Test void crossOrganizationAndWrongSourceOwnershipCannotReserve()throws Exception{
        String other=id();f.base.addWorkspace(other,id());f.base.grant(other,Action.COPY);c.refreshRegistry();
        var cross=c.permitCopy(c.source,other);
        assertThatThrownBy(()->c.app().request(f.base.token,c.source.workspaceId(),c.source.promptId(),"cross-org",cross))
                .isInstanceOf(ProductPromptAccessDenied.class);
        f.base.grant(c.target,Action.READ);
        assertThatThrownBy(()->c.app().request(f.base.token,c.target,c.source.promptId(),"wrong-owner",c.command))
                .isInstanceOf(ProductPromptAccessDenied.class);
        assertThat(copyRequests()).isZero();
    }

    @Test void pendingTargetSuccessorClosesOldCopyLookupWithoutRestoringOldControl()throws Exception{
        Receipt copied=c.copy("target-next");f.base.grant(c.target,Action.SAVE);
        var next=c.permitSave(copied,"member","Pending target {{ratio}}");f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->c.save().request(f.base.token,copied.promptId(),"pending-target",next)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainSource.faultTriggered).isTrue();
        assertThatThrownBy(()->c.lookup("target-next")).isInstanceOf(IllegalStateException.class);
        assertThat(f.base.independent.queryForObject("SELECT state FROM idp_prompt_control WHERE prompt_id=?",String.class,copied.promptId())).isEqualTo("PENDING");
    }

    @Test void copyAndCreateNamespacesRemainDistinctForSameTextKey()throws Exception{
        f.base.grant(Action.COPY);var sameWorkspace=c.permitCopy(c.source,c.source.workspaceId());
        Receipt result=c.app().request(f.base.token,c.source.workspaceId(),c.source.promptId(),"source-create",sameWorkspace);
        assertThat(result.id()).isNotEqualTo(c.source.id());assertThat(result.workspaceId()).isEqualTo(c.source.workspaceId());
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(2);
    }

    @Test void grantCutoffDuringReadyRollsBackIndependentCompletion(){
        var cutoff=f.base.now.plusSeconds(10);var grant=f.base.receipts.get(c.target+":COPY");
        f.base.watermark.change(()->f.base.db.update("UPDATE id2_grant SET expires_at=? WHERE grant_id=?",Timestamp.from(cutoff),grant.grantId()));
        f.authoritySource.afterSql=sql->{if(sql.startsWith("INSERT INTO idp_revision_witness"))f.base.now=cutoff;};
        assertThatThrownBy(()->c.copy("grant-cutoff")).isInstanceOf(RuntimeException.class);
        assertThat(f.base.now).isEqualTo(cutoff);assertThat(f.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(1);
        assertThat(c.prepared().get("STATE")).isEqualTo("PENDING");
    }

    @Test void outerTransactionIsRejectedBeforeSourceRead(){
        f.mainSource.statements.clear();new TransactionTemplate(f.mainManager).executeWithoutResult(status->{
            assertThatThrownBy(()->c.copy("outer")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->c.lookup("outer")).isInstanceOf(IllegalStateException.class);
        });assertThat(f.mainSource.statements).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void intentExpiresAfterFinalAuthorizationBeforeMainCommitRollsBackBodyButKeepsPending(boolean recovering){
        if(recovering){
            f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
            assertThatThrownBy(()->c.copy("main-commit-cutoff")).isInstanceOf(RuntimeException.class);
            assertThat(f.mainSource.faultTriggered).isTrue();
        }
        var cutoff=f.base.now.plusSeconds(300);var injected=new java.util.concurrent.atomic.AtomicBoolean();
        // Time advances only after all application/authorization SQL has returned, immediately
        // before the production commit fence. Earlier snapshot checks alone cannot catch this.
        f.mainSource.afterSql=sql->{
            if(sql.startsWith("INSERT INTO conp_receipt")&&injected.compareAndSet(false,true))
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public int getOrder(){return Integer.MIN_VALUE;}
                    @Override public void beforeCommit(boolean readOnly){f.base.now=cutoff;}
                });
        };
        assertThatThrownBy(()->c.copy("main-commit-cutoff")).isInstanceOf(IllegalStateException.class);
        assertThat(injected).isTrue();assertThat(f.base.now).isEqualTo(cutoff);
        assertThat(f.mainCount("conp_revision")).isEqualTo(1);assertThat(copyRequests()).isEqualTo(1);
        assertThat(c.prepared().get("STATE")).isEqualTo("PENDING");
        assertThatThrownBy(()->c.copy("main-commit-cutoff")).isInstanceOf(IllegalStateException.class);
        assertThat(f.mainCount("conp_revision")).isEqualTo(1);
    }

    private void failReady(String key){
        f.authoritySource.failOnce(sql->sql.startsWith("INSERT INTO idp_revision_witness"));
        assertThatThrownBy(()->c.copy(key)).isInstanceOf(RuntimeException.class);assertThat(f.authoritySource.faultTriggered).isTrue();
        assertThat(f.mainCount("conp_revision")).isEqualTo(2);assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(1);
    }
    private String json(Receipt receipt){return f.base.db.queryForObject("SELECT revision_json FROM conp_revision WHERE revision_id=?",String.class,receipt.revisionId());}
    private long copyRequests(){return f.base.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request WHERE operation='COPY'",Long.class);}
    private Map<String,Object> fixedPrepared(){return f.base.independent.queryForMap("SELECT prompt_id,target_revision_id,receipt_id,prepared_at,body_until,audit_until,prepared_sha,"
            +"source_workspace_id,source_prompt_id,source_revision_id,source_revision_number,source_record_sha,source_body_until,source_audit_until FROM idp_prompt_request WHERE operation='COPY'");}
}
