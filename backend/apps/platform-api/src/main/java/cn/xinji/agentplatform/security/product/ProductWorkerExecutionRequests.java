package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Current worker proof and one durable receipt for a lease acquisition. */
final class ProductWorkerExecutionRequests {
    record Receipt(String attemptId, long fencingToken, java.time.Instant leaseUntil) { }
    private record Stored(String sha, String inputId, String delegationId, Receipt lease) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductWorkerAccess access;
    private final ProductTaskWorkerExecution execution;
    private final Clock clock;

    ProductWorkerExecutionRequests(JdbcTemplate db, TransactionTemplate transactions,
            ProductWorkerAccess access, ProductTaskWorkerExecution execution, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.access = access;
        this.execution = execution;
        this.clock = clock;
    }

    Receipt acquire(HttpServletRequest request, String taskId, String inputId,
            String delegationId, String requestId) {
        return issue(request, taskId, inputId, delegationId, requestId, false);
    }

    Receipt reconcile(HttpServletRequest request, String taskId, String inputId,
            String delegationId, String requestId) {
        return issue(request, taskId, inputId, delegationId, requestId, true);
    }

    private Receipt issue(HttpServletRequest request, String taskId, String inputId,
            String delegationId, String requestId, boolean reconciliation) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact worker request identity required");
        Receipt issued = transactions.execute(status -> {
            var worker = access.require(request, taskId, inputId, delegationId);
            String sha = reconciliation ? digest("RECONCILE", taskId, inputId, delegationId)
                    : digest(taskId, inputId, delegationId);
            List<Stored> prior = db.query("SELECT request_sha,input_id,delegation_id,attempt_id,"
                            + "fencing_token,lease_until FROM con3_execution_request "
                            + "WHERE task_id=? AND service_subject=? AND request_id=?",
                    (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                            new Receipt(rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant())),
                    taskId, worker.serviceSubject(), requestId);
            if (!prior.isEmpty()) {
                Stored old = prior.getFirst();
                if (prior.size() != 1 || !sha.equals(old.sha()) || !inputId.equals(old.inputId())
                        || !delegationId.equals(old.delegationId()))
                    throw new IllegalStateException("Worker request conflicts with prior input");
                Receipt receipt = old.lease();
                Integer current = db.queryForObject("SELECT COUNT(*) FROM con3_execution_lease "
                        + "WHERE task_id=? AND attempt_id=? AND input_id=? AND worker_ref=? "
                        + "AND fencing_token=? AND status='ACTIVE' AND lease_until>?",
                        Integer.class, taskId, receipt.attemptId(), inputId, worker.serviceSubject(),
                        receipt.fencingToken(), Timestamp.from(clock.instant()));
                if (current != 1 || !receipt.leaseUntil().isAfter(clock.instant()))
                    throw new IllegalStateException("Old worker receipt is not current");
                access.require(request, taskId, inputId, delegationId);
                return receipt;
            }
            ProductTaskExecutionFence.Lease lease;
            try {
                lease = reconciliation
                        ? execution.reconcile(request, taskId, inputId, delegationId, Duration.ofSeconds(30))
                        : execution.acquire(request, taskId, inputId, delegationId,
                                Duration.ofSeconds(30));
            } catch (ProductTaskExecutionFence.ReconciliationRequired pending) {
                return null;
            }
            db.update("INSERT INTO con3_execution_request(task_id,service_subject,request_id,"
                    + "request_sha,input_id,delegation_id,attempt_id,fencing_token,lease_until,created_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?)", taskId, worker.serviceSubject(), requestId, sha,
                    inputId, delegationId, lease.attemptId(), lease.token(),
                    Timestamp.from(lease.until()), Timestamp.from(clock.instant()));
            access.require(request, taskId, inputId, delegationId);
            return new Receipt(lease.attemptId(), lease.token(), lease.until());
        });
        if (issued == null) throw new ProductTaskExecutionFence.ReconciliationRequired();
        return issued;
    }

    private static String digest(String... fields) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String value : fields)
                hash.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
