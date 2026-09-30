package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Rebuilds only the current confirmed input from persisted task facts. */
public final class ProductTaskGoalReader {
    public record GoalView(String taskId, long taskVersion, String goalId, long goalRevision,
                           String inputId, String purpose, String messageText, String queryObject,
                           List<String> sourceIds, List<String> requestedFields,
                           List<String> requiredFeatures, Instant confirmedAt, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final ProductTaskDirectory directory;
    private final ProductConsultationAccess access;
    private final ProductRetentionPolicy retention;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProductTaskGoalReader(JdbcTemplate db, ProductTaskDirectory directory, ProductConsultationAccess access,
            ProductRetentionPolicy retention, ObjectMapper mapper, Clock clock) {
        this.db = db;
        this.directory = directory;
        this.access = access;
        this.retention = retention;
        this.mapper = mapper;
        this.clock = clock;
    }

    public GoalView current(String taskId, Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        ProductTaskDirectory.Task task = permitted(taskId, jwt);
        if (!"IN_PROGRESS".equals(task.state())) throw notFound();
        List<GoalView> rows = db.query("SELECT c.goal_id,c.revision,c.task_version,i.input_id,g.purpose,"
                        + "m.raw_text,g.object_text,g.scope_json,i.requested_fields_json,g.features_json,"
                        + "g.confirmed_at,g.expires_at,g.retention_ref,g.retention_version,"
                        + "g.retention_evidence_sha,i.expires_at,m.expires_at "
                        + "FROM con3_goal_current c JOIN con3_goal_revision g ON g.task_id=c.task_id "
                        + "AND g.goal_id=c.goal_id AND g.revision=c.revision "
                        + "JOIN con3_search_input i ON i.task_id=c.task_id AND i.goal_id=c.goal_id "
                        + "AND i.goal_revision=c.revision AND i.task_version=c.task_version "
                        + "JOIN con3_message m ON m.task_id=c.task_id "
                        + "AND m.message_id=g.source_message_id "
                        + "WHERE c.task_id=? AND g.status='CONFIRMED'",
                (rs, row) -> {
                    Instant expiry = rs.getTimestamp(12).toInstant();
                    if (!retention.reference().equals(rs.getString(13))
                            || retention.version() != rs.getLong(14)
                            || !retention.evidenceSha256().equals(rs.getString(15)))
                        throw new IllegalStateException("Goal retention policy changed");
                    if (rs.getLong(3) != task.version() || !expiry.isAfter(clock.instant())
                            || !rs.getTimestamp(16).toInstant().isAfter(clock.instant())
                            || !rs.getTimestamp(17).toInstant().isAfter(clock.instant()))
                        throw notFound();
                    return new GoalView(taskId, rs.getLong(3), rs.getString(1), rs.getLong(2),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            parse(rs.getString(8)), parse(rs.getString(9)), parse(rs.getString(10)),
                            rs.getTimestamp(11).toInstant(), expiry);
                }, taskId);
        if (rows.size() != 1) throw notFound();
        ProductTaskDirectory.Task after = permitted(taskId, jwt);
        if (!task.equals(after)) throw notFound();
        return rows.getFirst();
    }

    private ProductTaskDirectory.Task permitted(String taskId, Jwt jwt) {
        ProductTaskDirectory.Task task = directory.find(taskId).orElseThrow(ProductTaskGoalReader::notFound);
        if (!task.expiresAt().isAfter(clock.instant()) || !access.permits(jwt,
                new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(), task.dataScope(),
                        "TASK", task.id(), task.authorizationVersion()), ProductAccessPolicy.Action.READ))
            throw notFound();
        return task;
    }

    private List<String> parse(String json) {
        try { return mapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (Exception corrupt) { throw new IllegalStateException("Confirmed goal input is corrupt", corrupt); }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
