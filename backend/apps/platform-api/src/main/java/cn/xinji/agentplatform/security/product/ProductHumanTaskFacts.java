package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current user-provided facts only; external material and save rights require further guards. */
final class ProductHumanTaskFacts {
    record Reference(String type, String id, long revision, String sha) { }
    record Snapshot(String taskId, long taskVersion, String purpose, Reference goal, Reference message,
                    Reference input, ProductRetentionPolicy policy, Instant confirmedAt, Instant expiresAt) {
        OrdinaryHumanHandling.Binding binding() {
            return new OrdinaryHumanHandling.Binding(goal.id() + ":" + goal.revision(),
                    message.id() + ":" + message.sha(), input.id() + ":" + input.sha(), null);
        }
    }
    private final JdbcTemplate db;
    private final ProductConsultationAccess access;
    private final JwtDecoder decoder;
    private final ProductRetentionPolicy policy;
    private final ProductComparisonJson json;
    private final Clock clock;

    ProductHumanTaskFacts(JdbcTemplate db, ProductConsultationAccess access, JwtDecoder decoder,
            ProductRetentionPolicy policy, ObjectMapper mapper, Clock clock) {
        if (db.getDataSource() != access.mainDataSource()) throw new IllegalArgumentException("Task facts must share main storage");
        this.db = db; this.access = access; this.decoder = decoder; this.policy = policy;
        this.json = new ProductComparisonJson(mapper); this.clock = clock;
    }

    Snapshot load(ProductHumanTaskTransaction.Context context, String originalToken) {
        return load(context, originalToken, null);
    }

    Snapshot historical(ProductHumanTaskTransaction.Context context, String originalToken, Snapshot expected) {
        if (!context.task().id().equals(expected.taskId())) throw unavailable();
        Snapshot found = load(context, originalToken, expected);
        if (!found.equals(expected)) throw unavailable();
        return found;
    }

    void requireBinding(javax.sql.DataSource main, Clock expectedClock) {
        if (db.getDataSource() != main || clock != expectedClock) throw new IllegalArgumentException("Matching human facts required");
    }

    private Snapshot load(ProductHumanTaskTransaction.Context context, String originalToken, Snapshot historical) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(db.getDataSource()))) throw unavailable();
        permitted(context, originalToken);
        Snapshot before = read(context, historical);
        permitted(context, originalToken);
        Snapshot after = read(context, historical);
        if (!before.equals(after) || !before.expiresAt().isAfter(clock.instant())) throw unavailable();
        return before;
    }

    private Snapshot read(ProductHumanTaskTransaction.Context context, Snapshot historical) {
        var task = context.task();
        long taskVersion = historical == null ? task.version() : historical.taskVersion();
        Object[] coordinates = historical == null ? new Object[]{task.id()}
                : new Object[]{task.id(), historical.goal().id(), historical.goal().revision(), historical.input().id()};
        var rows = db.query("SELECT g.goal_id,g.revision,i.task_version,g.organization_id,g.purpose,g.object_text,"
                + "g.scope_json,g.content_sha,g.confirmed_issuer,g.confirmed_subject,g.confirmed_at,g.expires_at,"
                + "g.retention_ref,g.retention_version,g.retention_evidence_sha,i.input_id,i.task_version,i.query_text,"
                + "i.source_scope_json,i.content_sha,i.confirmed_at,i.expires_at,m.message_id,m.actor_issuer,m.actor_subject,"
                + "m.raw_text,m.content_sha,m.received_at,m.expires_at,m.retention_ref,m.retention_version,m.retention_evidence_sha "
                + "FROM con3_goal_revision g JOIN con3_search_input i ON i.task_id=g.task_id "
                + "AND i.goal_id=g.goal_id AND i.goal_revision=g.revision "
                + (historical == null ? "JOIN con3_goal_current c ON c.task_id=g.task_id AND c.goal_id=g.goal_id "
                    + "AND c.revision=g.revision AND c.task_version=i.task_version " : "")
                + "JOIN con3_message m ON m.task_id=g.task_id AND m.message_id=g.source_message_id "
                + "JOIN con3_command_receipt r ON r.task_id=g.task_id "
                + "AND r.operation='GOAL_CONFIRMED' AND r.result_id=g.goal_id AND r.result_version=i.task_version "
                + "AND r.actor_issuer=g.confirmed_issuer AND r.actor_subject=g.confirmed_subject "
                + "AND r.request_sha=g.content_sha AND r.created_at=g.confirmed_at "
                + "WHERE g.task_id=? AND g.status='CONFIRMED'"
                + (historical == null ? "" : " AND g.goal_id=? AND g.revision=? AND i.input_id=?"), (rs, n) -> {
            Instant confirmed = rs.getTimestamp(11).toInstant();
            Instant goalUntil = rs.getTimestamp(12).toInstant();
            Instant inputUntil = rs.getTimestamp(22).toInstant();
            Instant messageAt = rs.getTimestamp(28).toInstant();
            Instant messageUntil = rs.getTimestamp(29).toInstant();
            if (rs.getLong(3) != taskVersion || rs.getLong(17) != taskVersion
                    || !task.organization().equals(rs.getString(4)) || !rs.getString(6).equals(rs.getString(18))
                    || !List.of(json.stored(rs.getString(7), String[].class)).equals(List.of(json.stored(rs.getString(19), String[].class)))
                    || !rs.getString(8).equals(rs.getString(20)) || !rs.getString(8).matches("[a-f0-9]{64}")
                    || !rs.getString(9).equals(rs.getString(24)) || !rs.getString(10).equals(rs.getString(25))
                    || !confirmed.equals(rs.getTimestamp(21).toInstant()) || messageAt.isAfter(confirmed)
                    || !ProductComparisonJson.hash(rs.getString(26)).equals(rs.getString(27))) throw unavailable();
            requirePolicy(rs.getString(13), rs.getLong(14), rs.getString(15), confirmed, goalUntil);
            requirePolicy(rs.getString(30), rs.getLong(31), rs.getString(32), messageAt, messageUntil);
            Instant expiry = List.of(task.expiresAt(), goalUntil, inputUntil, messageUntil).stream().min(Instant::compareTo).orElseThrow();
            if (confirmed.isAfter(clock.instant()) || !expiry.isAfter(clock.instant()) || !inputUntil.isAfter(confirmed)) throw unavailable();
            return new Snapshot(task.id(), taskVersion, rs.getString(5),
                    new Reference("GOAL", rs.getString(1), rs.getLong(2), rs.getString(8)),
                    new Reference("USER_MESSAGE", rs.getString(23), 1, rs.getString(27)),
                    new Reference("SEARCH_INPUT", rs.getString(16), 1, rs.getString(20)), policy, confirmed, expiry);
        }, coordinates);
        if (rows.size() != 1) throw unavailable();
        return rows.getFirst();
    }

    private void requirePolicy(String ref, long version, String sha, Instant from, Instant until) {
        if (!policy.reference().equals(ref) || policy.version() != version || !policy.evidenceSha256().equals(sha)
                || !until.isAfter(from) || until.isAfter(from.plus(policy.maxAge()))) throw unavailable();
    }

    private void permitted(ProductHumanTaskTransaction.Context context, String originalToken) {
        var jwt = decoder.decode(originalToken);
        var task = context.task();
        var current = new JdbcProductTaskDirectory(db, clock).find(task.id()).orElseThrow(ProductHumanTaskFacts::unavailable);
        if (!current.equals(task) || jwt.getIssuer() == null || !context.actor().issuer().equals(jwt.getIssuer().toString())
                || !context.actor().subject().equals(jwt.getSubject()) || jwt.getExpiresAt() == null
                || !jwt.getExpiresAt().isAfter(clock.instant()) || !task.expiresAt().isAfter(clock.instant())
                || !access.permits(jwt, new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(),
                    task.dataScope(), "TASK", task.id(), task.authorizationVersion()), ProductAccessPolicy.Action.READ)) throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Current human Task facts unavailable"); }
}
