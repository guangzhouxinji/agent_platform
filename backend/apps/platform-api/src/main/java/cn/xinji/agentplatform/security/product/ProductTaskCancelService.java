package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Terminal local cancellation; external actions remain separately reconcilable. */
public final class ProductTaskCancelService {
    public record Result(String taskId, long taskVersion, long cancelEpoch) { }

    private final JdbcTemplate db;
    private final ProductTaskDirectory directory;
    private final ProductConsultationAccess access;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProductTaskCancelService(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, TransactionTemplate transactions, Clock clock) {
        this.db = db;
        this.directory = directory;
        this.access = access;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Result cancel(String taskId, Jwt jwt, String requestId, long expectedVersion) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (expectedVersion < 1 || requestId == null
                || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String digest = sha(taskId + ":" + expectedVersion);
        permitted(taskId, jwt);
        Result result = transactions.execute(status -> {
            ProductTaskDirectory.Task task = permitted(taskId, jwt);
            List<Result> replay = db.query("SELECT request_sha,result_version FROM con3_command_receipt "
                            + "WHERE task_id=? AND actor_issuer=? AND actor_subject=? "
                            + "AND operation='USER_CANCELLED' AND request_id=?",
                    (rs, row) -> {
                        if (!digest.equals(rs.getString(1))) throw conflict();
                        return new Result(taskId, rs.getLong(2), currentEpoch(taskId));
                    }, taskId, jwt.getIssuer().toString(), jwt.getSubject(), requestId);
            if (!replay.isEmpty()) {
                if (!"USER_CANCELLED".equals(task.state())
                        || replay.getFirst().taskVersion() != task.version()) throw conflict();
                return replay.getFirst();
            }
            if ("USER_CANCELLED".equals(task.state())) throw conflict();
            if (task.version() != expectedVersion) throw conflict();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            long next = task.version() + 1;
            if (db.update("UPDATE con3_task SET state='USER_CANCELLED',version=?,cancel_epoch=cancel_epoch+1 "
                    + "WHERE task_id=? AND version=? AND state IN ('AWAITING_GOAL','IN_PROGRESS') "
                    + "AND expires_at>?", next, taskId, expectedVersion, Timestamp.from(now)) != 1)
                throw conflict();
            db.update("UPDATE con3_session SET status='ENDED' WHERE task_id=? AND status='ACTIVE'", taskId);
            db.update("UPDATE con3_execution_lease SET status='CANCELLED' WHERE task_id=? AND status='ACTIVE'",
                    taskId);
            db.update("INSERT INTO con3_command_receipt VALUES(?,?,?,'USER_CANCELLED',?,?,?,?,?)",
                    taskId, jwt.getIssuer().toString(), jwt.getSubject(), requestId,
                    digest, taskId, next, Timestamp.from(now));
            db.update("INSERT INTO con3_event VALUES(?,?,'USER_CANCELLED',?,?,?,?)",
                    UUID.randomUUID().toString(), taskId, jwt.getSubject(), next, digest, Timestamp.from(now));
            return new Result(taskId, next, currentEpoch(taskId));
        });
        ProductTaskDirectory.Task current = permitted(taskId, jwt);
        if (!"USER_CANCELLED".equals(current.state()) || current.version() != result.taskVersion())
            throw conflict();
        return result;
    }

    private long currentEpoch(String taskId) {
        return db.queryForObject("SELECT cancel_epoch FROM con3_task WHERE task_id=?", Long.class, taskId);
    }

    private ProductTaskDirectory.Task permitted(String taskId, Jwt jwt) {
        ProductTaskDirectory.Task task = directory.find(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!task.expiresAt().isAfter(clock.instant()) || !access.permits(jwt,
                new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(), task.dataScope(),
                        "TASK", task.id(), task.authorizationVersion()), ProductAccessPolicy.Action.WRITE))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return task;
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT);
    }
}
