package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed old container deadlines over the existing real SAVE/identity SQL fixture. */
class ProductPromptSaveContainerExpiryTest {
    @TempDir Path root;
    ProductPromptSaveApplicationTest saves;
    ProductPromptCreateApplicationTest.Fixture f;
    Instant cutoff;
    List<List<Map<String,Object>>> mainBefore;

    @BeforeEach void setup()throws Exception{
        saves=new ProductPromptSaveApplicationTest();saves.root=root;saves.setup();f=saves.f;
        cutoff=f.base.db.queryForObject("SELECT container_until FROM conp_prompt WHERE prompt_id=?",
                Timestamp.class,saves.created.promptId()).toInstant();
        mainBefore=mainScalars();f.mainSource.statements.clear();f.authoritySource.statements.clear();
    }

    @ParameterizedTest @CsvSource({"false,0","false,1","true,0","true,1"})
    void closedContainerCannotAppendOrResumeEvenWhileNewIntentAndAuditRemainCurrent(boolean recovering,int secondsAfter)throws Exception{
        f.base.now=recovering?cutoff.minusSeconds(10):cutoff.plusSeconds(secondsAfter);
        var command=saves.permit(saves.created,"member","Fresh full draft {{ratio}}");
        Map<String,Object> original=null;
        if(recovering){failMain(command);original=saves.savedPrepared();assertPendingDeadlinesRemainAfter(cutoff.plusSeconds(secondsAfter));}
        f.base.now=cutoff.plusSeconds(secondsAfter);
        f.mainSource.statements.clear();f.authoritySource.statements.clear();
        assertThatThrownBy(()->saves.app().request(f.base.token,saves.created.promptId(),"closed-container",command))
                .isInstanceOf(IllegalStateException.class);
        assertMainUnchanged();assertNoWrites(f.mainSource);assertNoWrites(f.authoritySource);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(recovering?2:1);
        assertThat(controlState()).isEqualTo(recovering?"PENDING":"READY");
        if(recovering)assertThat(saves.savedPrepared()).isEqualTo(original);
        if(!recovering)assertThat(f.app().lookup(f.base.token,f.base.workspaceId,"same-key")).isEqualTo(saves.created);
    }

    @Test void reserveCrossingOldContainerBeforeCommitRollsBackRequestAndControl()throws Exception{
        f.base.now=cutoff.minusSeconds(10);
        var command=saves.permit(saves.created,"member","Reserve full draft {{ratio}}");
        var oldControl=f.base.independent.queryForMap("SELECT * FROM idp_prompt_control WHERE prompt_id=?",saves.created.promptId());
        var injected=new AtomicBoolean();
        f.authoritySource.afterSql=sql->{
            if(sql.startsWith("INSERT INTO idp_prompt_request")&&injected.compareAndSet(false,true))advanceAtCommit(cutoff);
        };
        assertThatThrownBy(()->saves.app().request(f.base.token,saves.created.promptId(),"closed-container",command))
                .isInstanceOf(IllegalStateException.class);
        assertThat(injected).isTrue();assertThat(f.base.now).isEqualTo(cutoff);
        assertMainUnchanged();assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);
        assertThat(f.base.independent.queryForMap("SELECT * FROM idp_prompt_control WHERE prompt_id=?",saves.created.promptId())).isEqualTo(oldControl);
        assertNoWrites(f.mainSource);
        assertThat(f.authoritySource.statements).anyMatch(sql->sql.startsWith("INSERT INTO idp_prompt_request"));
        assertThat(f.app().lookup(f.base.token,f.base.workspaceId,"same-key")).isEqualTo(saves.created);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldContainerExpiresAfterFinalAuthorizationBeforeMainCommitAndKeepsOriginalPending(boolean recovering)throws Exception{
        f.base.now=cutoff.minusSeconds(10);
        var command=saves.permit(saves.created,"member","Commit full draft {{ratio}}");
        Map<String,Object> original=null;
        if(recovering){failMain(command);original=saves.savedPrepared();}
        var injected=new AtomicBoolean();
        f.mainSource.afterSql=sql->{
            if(sql.startsWith("INSERT INTO conp_receipt")&&injected.compareAndSet(false,true))advanceAtCommit(cutoff);
        };
        assertThatThrownBy(()->saves.app().request(f.base.token,saves.created.promptId(),"closed-container",command))
                .isInstanceOf(IllegalStateException.class);
        assertThat(injected).isTrue();assertThat(f.base.now).isEqualTo(cutoff);
        assertMainUnchanged();assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(2);
        assertThat(controlState()).isEqualTo("PENDING");assertPendingDeadlinesRemainAfter(cutoff);
        var prepared=saves.savedPrepared();
        if(recovering)assertThat(prepared).isEqualTo(original);
        assertThat(f.base.independent.queryForObject("SELECT state FROM idp_prompt_request WHERE operation='SAVE'",String.class)).isEqualTo("PENDING");
        f.mainSource.statements.clear();f.authoritySource.statements.clear();
        assertThatThrownBy(()->saves.app().request(f.base.token,saves.created.promptId(),"closed-container",command))
                .isInstanceOf(IllegalStateException.class);
        assertMainUnchanged();assertNoWrites(f.mainSource);assertNoWrites(f.authoritySource);
        assertThat(saves.savedPrepared()).isEqualTo(prepared);
    }

    private void failMain(ProductPromptSaveCommand command){
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->saves.app().request(f.base.token,saves.created.promptId(),"closed-container",command)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainSource.faultTriggered).isTrue();assertMainUnchanged();assertThat(controlState()).isEqualTo("PENDING");
    }
    private void advanceAtCommit(Instant until){
        // Run after application authorization returns but before the production fixed deadline fence.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public int getOrder(){return Integer.MIN_VALUE;}
            @Override public void beforeCommit(boolean readOnly){f.base.now=until;}
        });
    }
    private void assertPendingDeadlinesRemainAfter(Instant at){
        var pending=f.base.independent.queryForMap("SELECT intent_until,body_until,audit_until FROM idp_prompt_request WHERE operation='SAVE'");
        for(Object deadline:pending.values())assertThat(((Timestamp)deadline).toInstant()).isAfter(at);
    }
    private String controlState(){return f.base.independent.queryForObject("SELECT state FROM idp_prompt_control WHERE prompt_id=?",String.class,saves.created.promptId());}
    private void assertMainUnchanged(){
        assertThat(mainScalars()).isEqualTo(mainBefore);assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(1);
        assertThat(f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
    }
    private List<List<Map<String,Object>>> mainScalars(){
        return List.of(f.base.db.queryForList("SELECT * FROM conp_prompt"),
                f.base.db.queryForList("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state FROM conp_revision"),
                f.base.db.queryForList("SELECT * FROM conp_event"),f.base.db.queryForList("SELECT * FROM conp_receipt"));
    }
    private static void assertNoWrites(ProductPromptCreateApplicationTest.TrackingSource source){
        assertThat(source.statements).noneMatch(sql->sql.startsWith("INSERT ")||sql.startsWith("UPDATE ")||sql.startsWith("DELETE "));
    }
}
