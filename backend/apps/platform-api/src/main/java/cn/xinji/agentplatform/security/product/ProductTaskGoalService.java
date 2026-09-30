package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Stores a member-confirmed goal and its search input without executing a source query. */
public final class ProductTaskGoalService {
    public record Input(long expectedTaskVersion, String messageText, String purpose, String queryObject,
                        List<String> sourceIds, List<String> requestedFields, List<String> requiredFeatures,
                        boolean confirmed, String retentionRef, long retentionVersion,
                        ProductPatentSearchFilters filters) {
        public Input(long expectedTaskVersion, String messageText, String purpose, String queryObject,
                List<String> sourceIds, List<String> requestedFields, List<String> requiredFeatures,
                boolean confirmed, String retentionRef, long retentionVersion) {
            this(expectedTaskVersion, messageText, purpose, queryObject, sourceIds, requestedFields,
                    requiredFeatures, confirmed, retentionRef, retentionVersion,
                    ProductPatentSearchFilters.none());
        }
    }
    public record Result(String taskId, String messageId, String goalId, String inputId,
                         long taskVersion, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final ProductTaskDirectory directory;
    private final ProductConsultationAccess access;
    private final TransactionTemplate transactions;
    private final ProductRetentionPolicy retention;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxBytes;

    public ProductTaskGoalService(JdbcTemplate db, ProductTaskDirectory directory, ProductConsultationAccess access,
            TransactionTemplate transactions, ProductRetentionPolicy retention, ObjectMapper mapper,
            Clock clock, int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Goal content limit is required");
        this.db = db;
        this.directory = directory;
        this.access = access;
        this.transactions = transactions;
        this.retention = retention;
        this.mapper = mapper;
        this.clock = clock;
        this.maxBytes = maxBytes;
    }

    public Result confirm(String taskId, Jwt jwt, String requestId, Input input) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (input == null || requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}")
                || input.expectedTaskVersion() < 1 || !input.confirmed()
                || blank(input.messageText()) || blank(input.queryObject())
                || !List.of("QUERY", "SEARCH", "COMPARE").contains(input.purpose())
                || !values(input.sourceIds()) || !values(input.requestedFields())
                || input.requiredFeatures() == null || input.requiredFeatures().stream().anyMatch(ProductTaskGoalService::blank)
                || ("COMPARE".equals(input.purpose()) && input.requiredFeatures().isEmpty())
                || !retention.reference().equals(input.retentionRef())
                || retention.version() != input.retentionVersion())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String sources = json(input.sourceIds());
        String fields = json(input.requestedFields());
        ProductPatentSearchFilters filters = input.filters() == null
                ? ProductPatentSearchFilters.none() : ProductPatentSearchFilters.validated(
                        input.filters().publicationFrom(), input.filters().publicationTo(),
                        input.filters().ipcPrefixes(), input.filters().cpcPrefixes());
        String filterJson = json(filters.evidence());
        String features = json(input.requiredFeatures());
        String payload = json(input);
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String requestSha = sha(payload);
        ProductTaskDirectory.Task task = currentTask(taskId, jwt);
        Result result = transactions.execute(status -> {
            ProductTaskDirectory.Task locked = currentTask(taskId, jwt);
            List<Result> old = db.query("SELECT r.result_id,r.result_version,m.message_id,i.input_id,"
                            + "g.expires_at,r.request_sha FROM con3_command_receipt r "
                            + "JOIN con3_goal_revision g ON g.goal_id=r.result_id AND g.revision=1 "
                            + "JOIN con3_search_input i ON i.task_id=r.task_id AND i.goal_id=g.goal_id "
                            + "AND i.goal_revision=g.revision "
                            + "JOIN con3_message m ON m.task_id=r.task_id AND m.message_id=g.source_message_id "
                            + "WHERE r.task_id=? AND r.actor_issuer=? AND r.actor_subject=? "
                            + "AND r.operation='GOAL_CONFIRMED' AND r.request_id=?",
                    (rs, row) -> {
                        if (!requestSha.equals(rs.getString(6))) throw conflict();
                        return new Result(taskId, rs.getString(3), rs.getString(1), rs.getString(4),
                                rs.getLong(2), rs.getTimestamp(5).toInstant());
                    }, taskId, jwt.getIssuer().toString(), jwt.getSubject(), requestId);
            if (!old.isEmpty()) {
                Result replay = old.getFirst();
                if (locked.version() != replay.taskVersion() || !replay.expiresAt().isAfter(clock.instant())
                        || !"IN_PROGRESS".equals(locked.state())) throw conflict();
                return replay;
            }
            if (!"AWAITING_GOAL".equals(locked.state())
                    || locked.version() != input.expectedTaskVersion()) throw conflict();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Instant expiry = now.plus(retention.maxAge()).isBefore(locked.expiresAt())
                    ? now.plus(retention.maxAge()) : locked.expiresAt();
            if (!expiry.isAfter(now)) throw new ResponseStatusException(HttpStatus.GONE);
            long nextVersion = locked.version() + 1;
            if (db.update("UPDATE con3_task SET state='IN_PROGRESS',version=? "
                    + "WHERE task_id=? AND version=? AND state='AWAITING_GOAL' AND expires_at>?",
                    nextVersion, taskId, locked.version(), Timestamp.from(now)) != 1) throw conflict();
            String sessionId = UUID.randomUUID().toString();
            String messageId = UUID.randomUUID().toString();
            String goalId = UUID.randomUUID().toString();
            String inputId = UUID.randomUUID().toString();
            db.update("INSERT INTO con3_session VALUES(?,?,'ACTIVE',?,?)",
                    sessionId, taskId, Timestamp.from(now), Timestamp.from(expiry));
            db.update("INSERT INTO con3_message VALUES(?,?,?,?,?,?,?,?,?,? ,?,?,?)",
                    messageId, taskId, sessionId, 1, jwt.getIssuer().toString(), jwt.getSubject(),
                    input.messageText(), sha(input.messageText()), Timestamp.from(now), Timestamp.from(expiry),
                    retention.reference(), retention.version(), retention.evidenceSha256());
            db.update("INSERT INTO con3_goal_revision VALUES(?,1,?,?,?,'CONFIRMED',?,?,?,?,?,?,?,?,?,?,?,?)",
                    goalId, taskId, locked.organization(), input.purpose(), input.queryObject(), sources,
                    features, messageId, jwt.getIssuer().toString(), jwt.getSubject(), Timestamp.from(now),
                    Timestamp.from(expiry), requestSha, retention.reference(), retention.version(),
                    retention.evidenceSha256());
            db.update("INSERT INTO con3_goal_current VALUES(?,?,1,?)", taskId, goalId, nextVersion);
            db.update("INSERT INTO con3_search_input(input_id,task_id,goal_id,goal_revision,task_version,"
                            + "query_text,source_scope_json,requested_fields_json,content_sha,confirmed_at,"
                            + "expires_at,filters_json) VALUES(?,?,?,1,?,?,?,?,?,?,?,?)", inputId,
                    taskId, goalId, nextVersion, input.queryObject(), sources, fields, requestSha,
                    Timestamp.from(now), Timestamp.from(expiry), filterJson);
            db.update("INSERT INTO con3_command_receipt VALUES(?,?,?,'GOAL_CONFIRMED',?,?,?,?,?)",
                    taskId, jwt.getIssuer().toString(), jwt.getSubject(), requestId, requestSha,
                    goalId, nextVersion, Timestamp.from(now));
            db.update("INSERT INTO con3_event VALUES(?,?,'GOAL_CONFIRMED',?,?,?,?)",
                    UUID.randomUUID().toString(), taskId, jwt.getSubject(), nextVersion, requestSha,
                    Timestamp.from(now));
            return new Result(taskId, messageId, goalId, inputId, nextVersion, expiry);
        });
        ProductTaskDirectory.Task current = currentTask(taskId, jwt);
        if (current.version() != result.taskVersion() || !"IN_PROGRESS".equals(current.state())
                || current.authorizationVersion() != task.authorizationVersion()) throw conflict();
        return result;
    }

    private ProductTaskDirectory.Task currentTask(String taskId, Jwt jwt) {
        ProductTaskDirectory.Task task = directory.find(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!task.expiresAt().isAfter(clock.instant())
                || !access.permits(jwt, facts(task), ProductAccessPolicy.Action.WRITE))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return task;
    }

    private static ProductConsultationAccess.ResourceFacts facts(ProductTaskDirectory.Task task) {
        return new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(), task.dataScope(),
                "TASK", task.id(), task.authorizationVersion());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException(invalid); }
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static boolean values(List<String> values) {
        return values != null && !values.isEmpty() && values.stream().noneMatch(ProductTaskGoalService::blank)
                && values.stream().distinct().count() == values.size();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
