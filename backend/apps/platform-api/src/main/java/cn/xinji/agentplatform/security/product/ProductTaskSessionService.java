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

/** Re-enters a confirmed task through a new session, without inheriting the old session. */
public final class ProductTaskSessionService {
    public record Result(String sessionId, String taskId, String inputId, long taskVersion, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final ProductTaskDirectory directory;
    private final ProductConsultationAccess access;
    private final ProductTaskGoalReader goals;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProductTaskSessionService(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, ProductTaskGoalReader goals,
            TransactionTemplate transactions, Clock clock) {
        this.db = db;
        this.directory = directory;
        this.access = access;
        this.goals = goals;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Result resume(String taskId, Jwt jwt, String requestId, long expectedVersion) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (expectedVersion < 1 || requestId == null
                || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String digest = sha(taskId + ":" + expectedVersion);
        permitted(taskId, jwt);
        Result result = transactions.execute(status -> {
            db.queryForObject("SELECT version FROM con3_task WHERE task_id=? FOR UPDATE", Long.class, taskId);
            ProductTaskDirectory.Task task = permitted(taskId, jwt);
            ProductTaskGoalReader.GoalView goal = goals.current(taskId, jwt);
            List<Result> old = db.query("SELECT r.request_sha,r.result_id,r.result_version,s.expires_at "
                            + "FROM con3_command_receipt r JOIN con3_session s ON s.task_id=r.task_id "
                            + "AND s.session_id=r.result_id WHERE r.task_id=? AND r.actor_issuer=? "
                            + "AND r.actor_subject=? AND r.operation='SESSION_RESUMED' AND r.request_id=?",
                    (rs, row) -> {
                        if (!digest.equals(rs.getString(1))) throw conflict();
                        return new Result(rs.getString(2), taskId, goal.inputId(), rs.getLong(3),
                                rs.getTimestamp(4).toInstant());
                    }, taskId, jwt.getIssuer().toString(), jwt.getSubject(), requestId);
            if (!old.isEmpty()) {
                Result replay = old.getFirst();
                Integer active = db.queryForObject("SELECT COUNT(*) FROM con3_session "
                                + "WHERE task_id=? AND session_id=? AND status='ACTIVE' AND expires_at>?",
                        Integer.class, taskId, replay.sessionId(), Timestamp.from(clock.instant()));
                if (active != 1 || replay.taskVersion() != task.version()) throw conflict();
                return replay;
            }
            if (task.version() != expectedVersion || !"IN_PROGRESS".equals(task.state())) throw conflict();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Instant expiry = goal.expiresAt().isBefore(task.expiresAt()) ? goal.expiresAt() : task.expiresAt();
            if (!expiry.isAfter(now)) throw new ResponseStatusException(HttpStatus.GONE);
            db.update("UPDATE con3_session SET status='ENDED' WHERE task_id=? AND status='ACTIVE'",
                    taskId);
            String sessionId = UUID.randomUUID().toString();
            db.update("INSERT INTO con3_session VALUES(?,?,'ACTIVE',?,?)", sessionId, taskId,
                    Timestamp.from(now), Timestamp.from(expiry));
            db.update("INSERT INTO con3_command_receipt VALUES(?,?,?,'SESSION_RESUMED',?,?,?,?,?)",
                    taskId, jwt.getIssuer().toString(), jwt.getSubject(), requestId, digest,
                    sessionId, task.version(), Timestamp.from(now));
            db.update("INSERT INTO con3_event VALUES(?,?,'SESSION_RESUMED',?,?,?,?)",
                    UUID.randomUUID().toString(), taskId, jwt.getSubject(), task.version(), digest,
                    Timestamp.from(now));
            return new Result(sessionId, taskId, goal.inputId(), task.version(), expiry);
        });
        ProductTaskDirectory.Task current = permitted(taskId, jwt);
        if (!"IN_PROGRESS".equals(current.state()) || current.version() != result.taskVersion()
                || db.queryForObject("SELECT COUNT(*) FROM con3_session WHERE task_id=? AND session_id=? "
                        + "AND status='ACTIVE' AND expires_at>?", Integer.class, taskId, result.sessionId(),
                        Timestamp.from(clock.instant())) != 1) throw conflict();
        goals.current(taskId, jwt);
        return result;
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
