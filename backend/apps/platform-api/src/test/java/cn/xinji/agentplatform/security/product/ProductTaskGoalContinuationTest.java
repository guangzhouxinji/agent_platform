package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Controlled component states are C evidence, not a product identity/source admission or physical I. */
class ProductTaskGoalContinuationTest {
    private List<String> requested=List.of("TITLE");
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void confirmedNewInputPreservesHistoryAndAdvancesFenceWithoutReenablingOldInput(boolean fieldMode) throws Exception {
        requested=fieldMode?List.of("ABSTRACT","CLAIMS"):List.of("TITLE");
        Instant now=Instant.parse("2026-09-30T00:00:00Z");
        MutableClock clock=new MutableClock(now);
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:continue-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        try(var connection=source.getConnection()) {
            for(String name:List.of("V032__product_identity.sql","V034__product_consultation_initial_task.sql",
                    "V041__product_task_authorization_version.sql","V042__product_consultation_goal.sql",
                    "V043__product_task_cancel_epoch.sql","V044__product_task_session_resume.sql",
                    "V045__product_task_execution_fence.sql","V048__product_task_execution_reconciliation.sql",
                    "V046__product_workload_subject.sql","V047__product_task_worker_delegation.sql",
                    "V072__product_search_metadata_filters.sql","V077__product_execution_attempt_history.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8",name)));
        }
        JdbcTemplate db=new JdbcTemplate(source);
        String issuer="https://identity.example.org";
        String sha="a".repeat(64);
        Timestamp start=Timestamp.from(now), expiry=Timestamp.from(now.plusSeconds(3600));
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                + "VALUES(?,'member','member','encoded','ACTIVE',1,?)",issuer,start);
        db.update("INSERT INTO con3_organization VALUES('org','Organization','ACTIVE',1,?)",start);
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,expires_at,"
                + "retention_ref,retention_version,retention_evidence_sha,created_at) "
                + "VALUES('task','org',?,'member','AWAITING_GOAL',1,?,'policy',1,?,?)",issuer,expiry,sha,start);
        db.update("INSERT INTO con3_data_scope VALUES('scope','task','org',1,'TASK_METADATA')");
        db.execute("CREATE TABLE component_commit_receipt(attempt_id VARCHAR(36) PRIMARY KEY)");
        var access=mock(ProductConsultationAccess.class);
        when(access.permits(any(),any(),any())).thenReturn(true);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var directory=new JdbcProductTaskDirectory(db,clock);
        var goals=new ProductTaskGoalService(db,directory,access,tx,
                new ProductRetentionPolicy("policy",1,sha,Duration.ofHours(1)),
                new ObjectMapper().findAndRegisterModules(),clock,16000);
        Jwt jwt=Jwt.withTokenValue("component-token").header("alg","RS256").issuer(issuer)
                .subject("member").issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        assertThatThrownBy(()->goals.confirm("task",jwt,"invalid-query",input(1,"TITLE: battery AND")))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->
                        assertThat(e.getStatusCode().value()).isEqualTo(422));
        for (var invalid : List.of(
                new ProductTaskGoalService.Input(1,"Find titles","QUERY","battery",
                        List.of("SURECHEMBL_PUBLIC_SEARCH"),List.of("TITLE","IPC"),List.of(),true,"policy",1),
                new ProductTaskGoalService.Input(1,"Find titles","QUERY","battery",
                        List.of("SURECHEMBL_PUBLIC_SEARCH"),List.of("TITLE"),List.of(),true,"policy",1,
                        ProductPatentSearchFilters.validated(LocalDate.of(2020,1,1),null,List.of(),List.of()))))
            assertThatThrownBy(()->goals.confirm("task",jwt,"invalid-fields",invalid))
                    .isInstanceOfSatisfying(ResponseStatusException.class,e->
                            assertThat(e.getStatusCode().value()).isEqualTo(422));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_goal_revision",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_search_input",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_message",Integer.class)).isZero();
        assertThat(directory.find("task").orElseThrow().version()).isEqualTo(1);
        var first=goals.confirm("task",jwt,"round-1",input(1,"lithium battery"));
        assertThat(new ObjectMapper().readTree(db.queryForObject(
                "SELECT requested_fields_json FROM con3_search_input WHERE input_id=?",String.class,first.inputId()))
                .toString()).isEqualTo(new ObjectMapper().writeValueAsString(requested));
        assertThat(first.taskVersion()).isEqualTo(2);
        assertThat(goals.confirm("task",jwt,"round-1",input(1,"lithium battery"))).isEqualTo(first);
        assertThatThrownBy(()->goals.confirm("task",jwt,"unchanged",input(2,"lithium battery")))
                .isInstanceOf(ResponseStatusException.class);
        var fence=new ProductTaskExecutionFence(db,tx,clock);
        var lease=fence.acquire("task",first.inputId(),2,"worker",Duration.ofSeconds(30));
        assertThatThrownBy(()->goals.confirm("task",jwt,"pending",input(2,"binder coating")))
                .isInstanceOf(ResponseStatusException.class);
        fence.checkpoint("task",first.inputId(),lease,1,"component/receipt",sha);
        clock.current=now.plusSeconds(60);
        assertThatThrownBy(()->fence.acquire("task",first.inputId(),2,"worker",Duration.ofSeconds(30)))
                .isInstanceOf(ProductTaskExecutionFence.ReconciliationRequired.class);
        var reconciled=tx.execute(status->fence.reconcileNoDispatch("task",first.inputId(),
                new ProductWorkerAccess.Authorized("worker","task",first.inputId(),2),Duration.ofSeconds(30)));
        fence.publish("task",first.inputId(),reconciled,
                ()->db.update("INSERT INTO component_commit_receipt VALUES(?)",reconciled.attemptId()));
        var second=goals.confirm("task",jwt,"round-2",input(2,"binder coating"));
        assertThat(second.goalId()).isEqualTo(first.goalId());
        assertThat(second.inputId()).isNotEqualTo(first.inputId());
        assertThat(second.taskVersion()).isEqualTo(3);
        assertThat(goals.confirm("task",jwt,"round-2",input(2,"binder coating"))).isEqualTo(second);
        assertThatThrownBy(()->goals.confirm("task",jwt,"round-1",input(1,"lithium battery")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_goal_revision",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_search_input",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_session WHERE status='ACTIVE'",Integer.class)).isEqualTo(1);
        assertThat(directory.find("task").orElseThrow().authorizationVersion()).isEqualTo(1);
        var next=fence.acquire("task",second.inputId(),3,"worker",Duration.ofSeconds(30));
        assertThat(next.token()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_execution_attempt",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT input_id FROM con3_execution_attempt WHERE attempt_id=?",
                String.class,lease.attemptId())).isEqualTo(first.inputId());
        assertThatThrownBy(()->fence.publish("task",first.inputId(),lease,
                ()->db.update("DELETE FROM component_commit_receipt"))).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM component_commit_receipt",Integer.class)).isEqualTo(1);
        fence.publish("task",second.inputId(),next,
                ()->db.update("INSERT INTO component_commit_receipt VALUES(?)",next.attemptId()));
        new ProductTaskRecoveryVerifier(db,mock(ProductGrantWatermark.class)).verify();
        when(access.permits(any(),any(),any())).thenReturn(true,true,false);
        assertThatThrownBy(()->goals.confirm("task",jwt,"mid-revoke",input(3,"semiconductor device")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT version FROM con3_task WHERE task_id='task'",Long.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_goal_revision",Integer.class)).isEqualTo(2);
    }
    private ProductTaskGoalService.Input input(long version,String query) {
        return new ProductTaskGoalService.Input(version,"Find patent titles: "+query,"QUERY",query,
                List.of("SURECHEMBL_PUBLIC_SEARCH"),requested,List.of(),true,"policy",1);
    }
    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant now){current=now;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return current;}
    }
}
