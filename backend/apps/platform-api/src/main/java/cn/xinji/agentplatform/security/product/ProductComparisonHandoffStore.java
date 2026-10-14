package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

/** V105 coordinate-only proof of receipt, not a computation lease or model approval. */
final class ProductComparisonHandoffStore {
    static final String ERROR="COMPARISON_MODEL_ADMISSION_REQUIRED";
    record Proof(String commandId,String taskId,String comparisonId,String candidateId,String inputSha,
                 String requestId,String requestSha,String configurationVersion,String status,Instant receivedAt) { }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ProductComparisonStartCommands commands;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductComparisonHandoffStore(JdbcTemplate db,TransactionTemplate tx,ProductComparisonStartCommands commands,ProductComparisonJson json,Clock clock) {
        this.db=db;this.tx=tx;this.commands=commands;this.json=json;this.clock=clock;
        db.queryForList("SELECT command_id,task_id,comparison_id,candidate_id,input_sha,request_id,request_sha,configuration_version,status,receipt_json,receipt_sha,received_at FROM con5_start_handoff_receipt WHERE 1=0");
    }
    Proof accept(ProductComparisonStartCommands.Command expected,Runnable authorize) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw conflict();
        return tx.execute(status->{
            authorize.run();
            var scope=expected.scope();
            var tasks=db.queryForList("SELECT version,cancel_epoch,state,expires_at FROM con3_task WHERE task_id=? FOR UPDATE",scope.task());
            if(tasks.size()!=1)throw conflict();
            var t=tasks.getFirst();
            var locks=db.queryForList("SELECT command_id FROM con5_start_command WHERE command_id=? FOR UPDATE",expected.commandId());
            if(locks.size()!=1)throw conflict();
            var c=commands.metadata(expected.commandId());
            if(!c.equals(expected)||!c.managed().configurationVersion().startsWith("HANDOFF_ONLY:")||c.attemptId()!=null||c.fencingToken()!=null
                    ||c.leaseUntil()!=null||!c.expiresAt().isAfter(clock.instant())
                    ||((Number)t.get("version")).longValue()!=scope.taskVersion()||((Number)t.get("cancel_epoch")).longValue()!=scope.cancelEpoch()
                    ||!"IN_PROGRESS".equals(t.get("state"))||!((Timestamp)t.get("expires_at")).toInstant().isAfter(clock.instant()))throw conflict();
            Long inputs=db.queryForObject("SELECT COUNT(*) FROM con5_comparison_current p JOIN con5_comparison_input i ON i.task_id=p.task_id AND i.comparison_id=p.comparison_id WHERE i.task_id=? AND i.comparison_id=? AND i.input_sha=? AND i.task_version=? AND i.expires_at>?",Long.class,
                    scope.task(),scope.comparison(),scope.inputSha(),scope.taskVersion(),Timestamp.from(clock.instant()));
            if(inputs==null||inputs!=1)throw conflict();
            ProductComparisonStartHandoffGuard.require(db,c,clock.instant());
            noComputation(c);
            var old=read(c);
            if(old.isPresent()){authorize.run();return old.get();}
            if(!Set.of("DISPATCHING","RECONCILE_REQUIRED").contains(c.status()))throw conflict();
            var proof=new Proof(c.commandId(),scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),c.requestId(),c.requestSha(),c.managed().configurationVersion(),"ADMISSION_REQUIRED",clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            String body=json.canonical(proof);
            db.update("INSERT INTO con5_start_handoff_receipt(command_id,task_id,comparison_id,candidate_id,input_sha,request_id,request_sha,configuration_version,status,receipt_json,receipt_sha,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    c.commandId(),scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),c.requestId(),c.requestSha(),c.managed().configurationVersion(),proof.status(),body,json.sha(proof),Timestamp.from(proof.receivedAt()));
            if(db.update("UPDATE con5_start_command SET status='FAILED',error_code=? WHERE command_id=? AND status IN ('DISPATCHING','RECONCILE_REQUIRED') AND attempt_id IS NULL AND fencing_token IS NULL AND lease_until IS NULL",ERROR,c.commandId())!=1)throw conflict();
            authorize.run();
            if(!c.expiresAt().isAfter(clock.instant()))throw conflict();
            Long terminal=db.queryForObject("SELECT COUNT(*) FROM con3_task t JOIN con5_comparison_current p ON p.task_id=t.task_id WHERE t.task_id=? AND t.version=? AND t.cancel_epoch=? AND t.state='IN_PROGRESS' AND t.expires_at>? AND p.comparison_id=?",Long.class,scope.task(),scope.taskVersion(),scope.cancelEpoch(),Timestamp.from(clock.instant()),scope.comparison());
            if(terminal==null||terminal!=1)throw conflict();
            ProductComparisonStartHandoffGuard.require(db,c,clock.instant());
            noComputation(c);
            return proof;
        });
    }
    Optional<Proof> read(ProductComparisonStartCommands.Command c) { return readProof(db,json,c); }
    static boolean proven(JdbcTemplate db,ProductComparisonStartCommands.Command c) {
        return readProof(db,new ProductComparisonJson(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),c).isPresent();
    }
    private static Optional<Proof> readProof(JdbcTemplate db,ProductComparisonJson json,ProductComparisonStartCommands.Command c) {
        var rows=db.queryForList("SELECT * FROM con5_start_handoff_receipt WHERE command_id=?",c.commandId());
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1||!"FAILED".equals(c.status())||!ERROR.equals(c.errorCode())||c.attemptId()!=null||c.fencingToken()!=null||c.leaseUntil()!=null)throw conflict();
        var r=rows.getFirst();String body=db.queryForObject("SELECT receipt_json FROM con5_start_handoff_receipt WHERE command_id=?",String.class,c.commandId());
        var proof=json.stored(body,Proof.class);
        if(!json.sha(proof).equals(r.get("receipt_sha"))||!body.equals(json.canonical(proof))||!proof.commandId().equals(c.commandId())
                ||!proof.taskId().equals(c.scope().task())||!proof.comparisonId().equals(c.scope().comparison())||!proof.candidateId().equals(c.scope().candidate())
                ||!proof.inputSha().equals(c.scope().inputSha())||!proof.requestId().equals(c.requestId())||!proof.requestSha().equals(c.requestSha())
                ||!proof.configurationVersion().equals(c.managed().configurationVersion())||!"ADMISSION_REQUIRED".equals(proof.status())
                ||!proof.configurationVersion().startsWith("HANDOFF_ONLY:")||proof.receivedAt().isBefore(c.createdAt())||!proof.receivedAt().isBefore(c.expiresAt()))throw conflict();
        Object[] exact={proof.taskId(),proof.comparisonId(),proof.candidateId(),proof.inputSha(),proof.requestId(),proof.requestSha(),proof.configurationVersion(),proof.status()};
        String[] columns={"task_id","comparison_id","candidate_id","input_sha","request_id","request_sha","configuration_version","status"};
        for(int i=0;i<columns.length;i++)if(!exact[i].equals(r.get(columns[i])))throw conflict();
        if(!proof.receivedAt().equals(((Timestamp)r.get("received_at")).toInstant()))throw conflict();
        noAttempt(db,c);return Optional.of(proof);
    }
    private void noComputation(ProductComparisonStartCommands.Command c) {
        noAttempt(db,c);
        Long active=db.queryForObject("SELECT COUNT(*) FROM con5_candidate_lease WHERE comparison_id=? AND candidate_id=? AND status='ACTIVE'",Long.class,c.scope().comparison(),c.scope().candidate());
        if(active==null||active!=0)throw conflict();
    }
    private static void noAttempt(JdbcTemplate db,ProductComparisonStartCommands.Command c) {
        Long attempts=db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt WHERE task_id=? AND comparison_id=? AND candidate_id=? AND input_sha=? AND delegation_id=?",Long.class,c.scope().task(),c.scope().comparison(),c.scope().candidate(),c.scope().inputSha(),c.delegationId());
        if(attempts==null||attempts!=0)throw conflict();
    }
    private static IllegalStateException conflict(){return new IllegalStateException("COMPARISON_HANDOFF_RECEIPT_UNAVAILABLE");}
}
