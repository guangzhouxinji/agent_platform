package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.Span;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-candidate lease only. No empty publish callback or model-result upload can mark it COMMITTED. */
final class ProductComparisonCandidateExecution {
    record Scope(String task,String comparison,String candidate,String inputSha,String delegation) { }
    record Lease(String attemptId,long token,Instant until) { }
    static final class ReconciliationRequired extends IllegalStateException {
        ReconciliationRequired() {super("Expired comparison attempt requires external reconciliation");}
    }
    private record Task(long version,long epoch,String state,Instant until) { }
    private record Slot(String attempt,long token,String status,Instant until) { }
    private record Attempt(Scope scope,long taskVersion,long cancelEpoch,String worker,String requestSha,
                           String originalsSha,long token,String status,Instant until) { }
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductComparisonWorkerMaterials materials;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductComparisonCandidateExecution(JdbcTemplate db,TransactionTemplate transactions,
            ProductComparisonWorkerMaterials materials,ProductComparisonJson json,Clock clock) {
        this.db=db;this.transactions=transactions;this.materials=materials;this.json=json;this.clock=clock;
        db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Long.class);
        db.queryForObject("SELECT COUNT(*) FROM con5_candidate_lease",Long.class);
    }
    Lease acquire(HttpServletRequest request,Scope scope,String key,Duration duration) {
        if (key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}") || duration==null
                || duration.isNegative() || duration.isZero() || duration.toMillis()<1) throw invalid();
        var before=consume(request,scope);
        Lease lease=transactions.execute(status->{
            var task=lock(scope.task());requireTask(task,before);
            var loaded=consume(request,scope);if (!fingerprint(before).equals(fingerprint(loaded))) throw stale();
            var slot=slot(scope);
            Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if (slot!=null && "ACTIVE".equals(slot.status()) && !slot.until().isAfter(now)) {
                db.update("UPDATE con5_candidate_attempt SET status='RECONCILE_REQUIRED' WHERE attempt_id=? AND status='ACTIVE'",slot.attempt());
                db.update("UPDATE con5_candidate_lease SET status='RECONCILE_REQUIRED' WHERE comparison_id=? AND candidate_id=? AND status='ACTIVE'",scope.comparison(),scope.candidate());
                progress(scope,"RUNNING","FAILED");consumeStable(request,scope,loaded);return null;
            }
            String requestSha=json.sha(Map.of("purpose","COMPARISON_CANDIDATE_LEASE","scope",scope,
                    "worker",loaded.actor().serviceSubject(),"durationMillis",duration.toMillis(),"originalsSha",fingerprint(loaded)));
            var replay=db.query("SELECT attempt_id,fencing_token,lease_until,request_sha FROM con5_candidate_attempt "
                    + "WHERE comparison_id=? AND candidate_id=? AND worker_subject=? AND request_id=?",(rs,row)->{
                if (!requestSha.equals(rs.getString(4))) throw stale();
                return new Lease(rs.getString(1),rs.getLong(2),rs.getTimestamp(3).toInstant());
            },scope.comparison(),scope.candidate(),loaded.actor().serviceSubject(),key);
            if (!replay.isEmpty()) {
                if (replay.size()!=1) throw stale();requireCurrent(scope,replay.getFirst(),loaded,task);
                consumeStable(request,scope,loaded);return replay.getFirst();
            }
            if (slot!=null && !"FAILED".equals(slot.status())) throw stale();
            if (slot!=null && ProductComparisonFailureProof.inspect(db,slot.attempt(),null)==null) throw new ReconciliationRequired();
            long token=slot==null?1:Math.addExact(slot.token(),1);
            Instant until=now.plus(duration);if(loaded.expiresAt().isBefore(until))until=loaded.expiresAt();
            until=until.truncatedTo(ChronoUnit.MILLIS);if(!until.isAfter(now))throw stale();
            String attempt=UUID.randomUUID().toString();
            db.update("INSERT INTO con5_candidate_attempt VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',NULL,?,?)",
                    attempt,scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),task.version(),task.epoch(),
                    loaded.actor().serviceSubject(),scope.delegation(),key,requestSha,fingerprint(loaded),token,Timestamp.from(now),Timestamp.from(until));
            if(slot==null) db.update("INSERT INTO con5_candidate_lease VALUES(?,?,?,?,'ACTIVE',?)",scope.comparison(),scope.candidate(),attempt,token,Timestamp.from(until));
            else if(db.update("UPDATE con5_candidate_lease SET attempt_id=?,fencing_token=?,status='ACTIVE',lease_until=? "
                    + "WHERE comparison_id=? AND candidate_id=? AND attempt_id=? AND fencing_token=? AND status='FAILED'",
                    attempt,token,Timestamp.from(until),scope.comparison(),scope.candidate(),slot.attempt(),slot.token())!=1)throw stale();
            progress(scope,slot==null?"QUEUED":"FAILED","RUNNING");
            var result=new Lease(attempt,token,until);requireCurrent(scope,result,loaded,task);
            consumeStable(request,scope,loaded);return result;
        });
        if(lease==null)throw new ReconciliationRequired();return lease;
    }
    void require(HttpServletRequest request,Scope scope,Lease lease) {
        transactions.executeWithoutResult(status->{var task=lock(scope.task());var loaded=consume(request,scope);
            requireCurrent(scope,lease,loaded,task);consumeStable(request,scope,loaded);});
    }
    void fail(HttpServletRequest request,Scope scope,Lease lease,String errorCode) {
        if(errorCode==null || !errorCode.matches("[A-Z][A-Z0-9_]{1,79}"))throw invalid();
        transactions.executeWithoutResult(status->{var task=lock(scope.task());var loaded=consume(request,scope);
            requireCurrent(scope,lease,loaded,task);
            if(ProductComparisonFailureProof.beforeFailure(db,lease.attemptId(),null,errorCode)==null) throw new ReconciliationRequired();
            if(db.update("UPDATE con5_candidate_attempt SET status='FAILED',error_code=? WHERE attempt_id=? AND fencing_token=? AND status='ACTIVE'",
                    errorCode,lease.attemptId(),lease.token())!=1)throw stale();
            if(db.update("UPDATE con5_candidate_lease SET status='FAILED' WHERE comparison_id=? AND candidate_id=? "
                    + "AND attempt_id=? AND fencing_token=? AND status='ACTIVE'",scope.comparison(),scope.candidate(),lease.attemptId(),lease.token())!=1)throw stale();
            progress(scope,"RUNNING","FAILED");consumeStable(request,scope,loaded);
        });
    }
    void requireCommitted(HttpServletRequest request,Scope scope,Lease lease) {
        transactions.executeWithoutResult(status->{var task=lock(scope.task());var loaded=consume(request,scope);
            requireCurrent(scope,lease,loaded,task,"COMMITTED");consumeStable(request,scope,loaded);});
    }
    private void requireCurrent(Scope scope,Lease lease,ProductComparisonWorkerMaterials.Loaded loaded,Task task) {
        requireCurrent(scope,lease,loaded,task,"ACTIVE");
    }
    private void requireCurrent(Scope scope,Lease lease,ProductComparisonWorkerMaterials.Loaded loaded,Task task,String state) {
        requireTask(task,loaded);if(lease==null || !lease.until().isAfter(clock.instant()) || lease.until().isAfter(loaded.expiresAt()))throw stale();
        var slot=slot(scope);var attempt=attempt(lease.attemptId());
        if(slot==null || !state.equals(slot.status()) || !slot.attempt().equals(lease.attemptId())
                || slot.token()!=lease.token() || !slot.until().equals(lease.until()) || !slot.until().isAfter(clock.instant())
                || !attempt.scope().equals(scope) || attempt.token()!=lease.token() || !state.equals(attempt.status())
                || !attempt.until().equals(lease.until()) || attempt.taskVersion()!=task.version() || attempt.cancelEpoch()!=task.epoch()
                || !attempt.worker().equals(loaded.actor().serviceSubject()) || !attempt.originalsSha().equals(fingerprint(loaded)))throw stale();
        var progress=db.queryForObject("SELECT status FROM con5_comparison_candidate WHERE comparison_id=? AND candidate_id=?",
                String.class,scope.comparison(),scope.candidate());if(!("COMMITTED".equals(state)?"COMMITTED":"RUNNING").equals(progress))throw stale();
    }
    private void requireTask(Task task,ProductComparisonWorkerMaterials.Loaded loaded) {
        if(!"IN_PROGRESS".equals(task.state()) || !task.until().isAfter(clock.instant())
                || task.version()!=loaded.actor().taskVersion())throw stale();
    }
    private Task lock(String task) {
        var rows=db.query("SELECT version,cancel_epoch,state,expires_at FROM con3_task WHERE task_id=? FOR UPDATE",
                (rs,row)->new Task(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4).toInstant()),task);
        if(rows.size()!=1)throw stale();return rows.getFirst();
    }
    private Slot slot(Scope scope) {
        var rows=db.query("SELECT attempt_id,fencing_token,status,lease_until FROM con5_candidate_lease "
                + "WHERE comparison_id=? AND candidate_id=? FOR UPDATE",
                (rs,row)->new Slot(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4).toInstant()),scope.comparison(),scope.candidate());
        if(rows.size()>1)throw stale();return rows.isEmpty()?null:rows.getFirst();
    }
    private Attempt attempt(String id) {
        var rows=db.query("SELECT task_id,comparison_id,candidate_id,input_sha,delegation_id,task_version,cancel_epoch," 
                + "worker_subject,request_sha,originals_sha,fencing_token,status,lease_until FROM con5_candidate_attempt WHERE attempt_id=?",
                (rs,row)->new Attempt(new Scope(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),
                        rs.getLong(6),rs.getLong(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getLong(11),rs.getString(12),rs.getTimestamp(13).toInstant()),id);
        if(rows.size()!=1)throw stale();return rows.getFirst();
    }
    private void progress(Scope scope,String before,String after) {
        if(db.update("UPDATE con5_comparison_candidate SET status=? WHERE comparison_id=? AND candidate_id=? AND status=?",
                after,scope.comparison(),scope.candidate(),before)!=1)throw stale();
    }
    private ProductComparisonWorkerMaterials.Loaded consume(HttpServletRequest request,Scope scope) {
        if(scope==null)throw invalid();return materials.consume(request,scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),scope.delegation());
    }
    private void consumeStable(HttpServletRequest request,Scope scope,ProductComparisonWorkerMaterials.Loaded before) {
        if(!fingerprint(before).equals(fingerprint(consume(request,scope))))throw stale();
    }
    private String fingerprint(ProductComparisonWorkerMaterials.Loaded loaded) {
        var values=new LinkedHashMap<String,Object>();values.put("inputSha",loaded.input().inputSha());
        values.put("target",Span.of(loaded.target().evidence(0,loaded.target().text().length())));
        values.put("candidate",loaded.candidate().material());values.put("candidateAuthority",loaded.candidate().authority());
        values.put("targetAuthority",loaded.targetPatent()==null?null:loaded.targetPatent().authority());
        return json.sha(values);
    }
    private static IllegalStateException stale() {return new IllegalStateException("Stale comparison candidate lease, input or authority");}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Exact comparison lease request required");}
}
