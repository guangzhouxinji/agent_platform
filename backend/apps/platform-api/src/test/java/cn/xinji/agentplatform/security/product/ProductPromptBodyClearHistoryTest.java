package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptBodyClearHistoryTest {
    @TempDir Path root;
    ProductPromptBodyClearHistoryFixture fixture()throws Exception{return new ProductPromptBodyClearHistoryFixture(root);}

    @Test void sameGenerationTwoClearsPreservePresentHeadAndAllOriginalReceiptsWithoutBodyReads()throws Exception {
        var h=fixture();h.at(10);Receipt second=h.save(h.created(),"second");h.at(20);Receipt third=h.save(second,"third");
        String original=h.originalPrepared("third");h.at(610);h.clear(h.created());h.clear(second);h.clearStatements();
        assertThat(h.lookup("third")).isEqualTo(third);assertThat(h.lookup("second")).isEqualTo(second);
        assertThat(h.f.app().lookup(h.f.base.token,h.f.base.workspaceId,"same-key")).isEqualTo(h.created());
        assertThat(h.originalPrepared("third")).isEqualTo(original);assertNoBodyRead(h);
        assertThat(h.f.mainSource.statements).noneMatch(sql->sql.startsWith("UPDATE ")||sql.startsWith("INSERT ")||sql.startsWith("DELETE "));
    }
    @Test void inverseProjectionRetainsEarlierGenerationClearedRevisionAndOriginSupportsAnotherSave()throws Exception {
        var h=fixture();h.at(10);Receipt second=h.save(h.created(),"second");h.at(600);h.clear(h.created());
        h.at(601);Receipt third=h.save(second,"third");String original=h.originalPrepared("third");
        h.at(610);h.clear(second);h.clearStatements();
        assertThat(h.lookup("third")).isEqualTo(third);assertThat(h.lookup("second")).isEqualTo(second);
        Receipt fourth=h.save(third,"fourth");assertThat(fourth.generation()).isEqualTo(4);
        assertThat(h.originalPrepared("third")).isEqualTo(original);assertNoBodyRead(h);
        assertThat(h.f.base.db.queryForList("SELECT body_state FROM conp_revision ORDER BY revision_number",String.class))
                .isEqualTo(List.of("CLEARED","CLEARED","PRESENT","PRESENT"));
    }
    @Test void clearedOriginAllowsOnlyItsExactPendingSaveToResumeOriginalPreparedBytes()throws Exception {
        var h=fixture();h.at(10);Receipt second=h.save(h.created(),"second");h.at(600);h.clear(h.created());h.at(601);
        var command=h.saves.permit(second,"member","Owned resumed draft {{ratio}}");
        h.f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->h.saves.app().request(h.f.base.token,second.promptId(),"resume",command)).isInstanceOf(RuntimeException.class);
        assertThat(h.f.mainSource.faultTriggered).isTrue();String original=h.originalPrepared("resume");h.at(602);h.clearStatements();
        Receipt resumed=h.saves.app().request(h.f.base.token,second.promptId(),"resume",command);
        assertThat(resumed.generation()).isEqualTo(3);assertThat(h.originalPrepared("resume")).isEqualTo(original);assertNoBodyRead(h);
    }
    @Test void clearedHeadReceiptSurvivesExpiredBodyAndIntentButNotOriginalAudit()throws Exception {
        var h=fixture();h.at(10);Receipt second=h.save(h.created(),"second");h.at(610);h.clear(second);h.clearStatements();
        assertThat(h.lookup("second")).isEqualTo(second);assertNoBodyRead(h);
        var command=h.saves.permit(second,"member","Cannot revive {{ratio}}");
        assertThatThrownBy(()->h.saves.app().request(h.f.base.token,second.promptId(),"revive",command)).isInstanceOf(IllegalStateException.class);
        h.at(1210);assertThatThrownBy(()->h.lookup("second")).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"missing-main","missing-independent","fake-null","restored-body","witness-present","column-drift","bad-json","marker"})
    void mixedOrForgedCleanupFactsCannotAuthenticateOriginalReceipt(String fault)throws Exception {
        var h=fixture();h.at(600);
        String backup=h.f.base.db.queryForObject("SELECT revision_json FROM conp_revision WHERE revision_id=?",String.class,h.created().revisionId());
        var cleared=h.clear(h.created());
        switch(fault) {
            case "missing-main"->h.f.base.db.update("DELETE FROM conp_prompt_cleanup_receipt WHERE request_id=?",cleared.requestId());
            case "missing-independent"->h.f.base.independent.update("DELETE FROM idp_prompt_cleanup_request WHERE request_id=?",cleared.requestId());
            case "fake-null"->{h.f.base.db.execute("ALTER TABLE conp_revision DROP CONSTRAINT ck_conp_revision");
                h.f.base.db.update("UPDATE conp_revision SET revision_json='null' WHERE revision_id=?",h.created().revisionId());}
            case "restored-body"->h.f.base.db.update("UPDATE conp_revision SET revision_json=?,body_state='PRESENT' WHERE revision_id=?",backup,h.created().revisionId());
            case "witness-present"->h.f.base.independent.update("UPDATE idp_revision_witness SET body_state='PRESENT' WHERE revision_id=?",h.created().revisionId());
            case "column-drift"->h.f.base.independent.update("UPDATE idp_prompt_cleanup_request SET prepared_sha=? WHERE request_id=?","f".repeat(64),cleared.requestId());
            case "bad-json"->h.f.base.db.update("UPDATE conp_prompt_cleanup_receipt SET manifest_json='{}' WHERE request_id=?",cleared.requestId());
            case "marker"->{h.f.base.independent.execute("ALTER TABLE idp_prompt_control DROP CONSTRAINT ck_idp_prompt");
                h.f.base.independent.update("UPDATE idp_prompt_control SET cleanup_request_id=? WHERE prompt_id=?",cleared.requestId(),h.created().promptId());}
        }
        h.clearStatements();
        assertThatThrownBy(()->h.f.app().lookup(h.f.base.token,h.f.base.workspaceId,"same-key")).isInstanceOf(RuntimeException.class);
        assertNoBodyRead(h);
    }
    @ParameterizedTest @ValueSource(strings={"broken-chain","future-generation"})
    void validDigestsCannotAuthenticateAnUnanchoredOrFutureConversion(String fault)throws Exception {
        var h=fixture();h.at(600);var request=h.clear(h.created());Head before=request.before();
        Head forged=new Head(before.promptId(),before.workspaceId(),before.kind(),before.revisionId(),
                before.generation()+(fault.equals("future-generation")?1:0),"e".repeat(64),before.containerUntil(),before.controlUntil());
        h.replace(request,forged,request.afterSha());h.clearStatements();
        assertThatThrownBy(()->h.f.app().lookup(h.f.base.token,h.f.base.workspaceId,"same-key")).isInstanceOf(IllegalStateException.class);
        assertNoBodyRead(h);
    }
    @Test void inverseProjectionRejectsOtherScalarMutationEvenWithCurrentClosureAndMatchingChainEndpoints()throws Exception {
        var h=fixture();h.at(10);Receipt second=h.save(h.created(),"second");h.at(20);Receipt third=h.save(second,"third");
        h.at(600);var request=h.clear(h.created());
        h.f.base.db.update("UPDATE conp_revision SET record_sha=? WHERE revision_id=?","e".repeat(64),second.revisionId());
        String forged=h.mainTx.execute(status->h.f.store.closureSha(third.promptId()));
        h.f.base.db.update("UPDATE conp_prompt SET scalar_sha=? WHERE prompt_id=?",forged,third.promptId());
        h.f.base.independent.update("UPDATE idp_prompt_control SET scalar_sha=? WHERE prompt_id=?",forged,third.promptId());
        h.replace(request,request.before(),forged);h.clearStatements();
        assertThatThrownBy(()->h.lookup("third")).isInstanceOf(IllegalStateException.class);assertNoBodyRead(h);
    }
    @Test void scalarProjectionIsImmutableAndPredictsOnlyTheNativeBodyStateDelta()throws Exception {
        var h=fixture();h.at(600);
        h.mainTx.executeWithoutResult(status->{
            Head head=h.f.store.head(h.created().promptId(),true);Header original=h.f.store.header(head.promptId(),h.created().revisionId());
            var snapshot=h.f.store.scalarSnapshot(head);var cleared=snapshot.clear(original);
            assertThat(snapshot.sha()).isEqualTo(head.scalarSha());assertThat(cleared.sha()).isNotEqualTo(snapshot.sha());
            assertThat(cleared.restorePresent(original).sha()).isEqualTo(snapshot.sha());
            assertThatThrownBy(()->cleared.clear(original)).isInstanceOf(IllegalStateException.class);
            h.f.main.update("UPDATE conp_revision SET revision_json=NULL,body_state='CLEARED' WHERE revision_id=?",original.revisionId());
            assertThat(h.f.store.closureSha(head.promptId())).isEqualTo(cleared.sha());
            status.setRollbackOnly();
        });
    }
    private static void assertNoBodyRead(ProductPromptBodyClearHistoryFixture h) {
        assertThat(h.f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
    }
}
