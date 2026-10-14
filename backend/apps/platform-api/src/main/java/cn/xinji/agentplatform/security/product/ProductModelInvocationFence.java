package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable single-dispatch ownership. An unfinished claim is NOT permission to retry the provider. */
final class ProductModelInvocationFence {
    record Claim(String turnId, boolean dispatch, Instant expiresAt) { }
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductRetentionPolicy retention;
    private final Clock clock;

    ProductModelInvocationFence(JdbcTemplate db, TransactionTemplate transactions,
            ProductRetentionPolicy retention, Clock clock) {
        this.db=db; this.transactions=transactions; this.retention=retention; this.clock=clock;
    }

    Claim reserve(ProductTaskDirectory.Task task, String actor, String requestId, String requestSha,
            String kind, String resultId, String resultSha, Runnable current) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model dispatch cannot run inside an outer transaction");
        return transactions.execute(status -> {
            var locked=db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",(rs,n)->rs.getString(1),task.id());
            if (locked.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            current.run();
            // Recheck after acquiring the lock: another caller may have finished since the initial read.
            var turns=db.query("SELECT turn_id,request_sha FROM con3_model_turn WHERE task_id=? AND actor_subject=? AND request_id=?",
                    (rs,n)->Map.entry(rs.getString(1),rs.getString(2)),task.id(),actor,requestId);
            if (!turns.isEmpty()) {
                if (!requestSha.equals(turns.getFirst().getValue())) throw conflict();
                return new Claim(turns.getFirst().getKey(),false,null);
            }
            var prior=db.queryForList("SELECT request_sha FROM con3_model_invocation WHERE task_id=? AND actor_subject=? AND request_id=?",
                    String.class,task.id(),actor,requestId);
            if (!prior.isEmpty()) {
                if (!requestSha.equals(prior.getFirst())) throw conflict();
                throw new ResponseStatusException(HttpStatus.CONFLICT,"MODEL_INVOCATION_UNRESOLVED");
            }
            Instant now=clock.instant();
            Instant expiry=now.plus(retention.maxAge()).isBefore(task.expiresAt()) ? now.plus(retention.maxAge()) : task.expiresAt();
            if (!expiry.isAfter(now)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO con3_model_invocation(invocation_id,task_id,task_version,actor_subject,kind,request_id,request_sha,"
                    + "result_set_id,result_sha,created_at,expires_at,retention_ref,retention_version,retention_evidence_sha)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,task.id(),task.version(),actor,kind,requestId,requestSha,
                    resultId,resultSha,Timestamp.from(now),Timestamp.from(expiry),retention.reference(),retention.version(),retention.evidenceSha256());
            current.run(); // A failed last authorization/source check rolls the claim back; no provider was called.
            return new Claim(id,true,expiry);
        });
    }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
