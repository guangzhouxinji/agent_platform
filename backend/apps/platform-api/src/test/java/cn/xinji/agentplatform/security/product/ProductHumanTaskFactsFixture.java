package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing real-table user-fact test preparation, shared with the transaction assertions. */
final class ProductHumanTaskFactsFixture {
    // Storage-component input only; application tests must authenticate real facts and cleanup rows.
    static ProductHumanRequestStore.Manifest manifest(ProductHumanTaskTransaction.Context context, Clock clock) {
        var task = context.task();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var policy = new cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention.Policy(
                "approved-human-v3", 3, "e".repeat(64));
        var facts = new ProductHumanTaskFacts.Snapshot(task.id(), task.version(), "QUERY",
                new ProductHumanTaskFacts.Reference("GOAL", "goal", 1, "a".repeat(64)),
                new ProductHumanTaskFacts.Reference("MESSAGE", "message", 1, "b".repeat(64)),
                new ProductHumanTaskFacts.Reference("SEARCH_INPUT", "input", 1, "c".repeat(64)),
                new ProductRetentionPolicy("goal-policy", 1, "a".repeat(64), Duration.ofHours(1)),
                now, now.plusSeconds(100));
        var cleanup = new ProductHumanCleanupScope.Snapshot("cleanup", task.organization(), task.id(), 1,
                "READY", null, new ProductHumanCleanupScope.Policy(policy.reference(), policy.version(), policy.evidenceSha256()),
                "d".repeat(64), now, task.expiresAt());
        var references = new ProductHumanRequestReferences(facts, java.util.List.of(), java.util.List.of(), null,
                cleanup, java.util.List.of(ProductHumanRequestReferences.Gap.NO_COMPARISON_SELECTED,
                        ProductHumanRequestReferences.Gap.NO_REPORT_SELECTED));
        return new ProductHumanRequestStore.Manifest(task.id(), task.organization(), task.dataScope(), task.version(),
                UUID.randomUUID().toString(), 1, context.actor(), references.binding(), references,
                "ordinary assistance requested", "ORDINARY_ASSISTANCE", policy,
                new cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention.Bounds(
                        task.expiresAt(), task.expiresAt(), now.plusSeconds(100), now.plusSeconds(100),
                        task.expiresAt(), now.plusSeconds(100)), now);
    }
    static void migrate(javax.sql.DataSource source, String... paths) throws Exception {
        try (var connection = source.getConnection()) {
            for (String path : paths) ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db", path)));
        }
    }

    static ProductHumanTaskFacts create(JdbcTemplate db, DataSourceTransactionManager manager,
            ProductGrantWatermark watermark, ProductConsultationAccess access, JwtDecoder decoder,
            ProductTaskDirectory.Task task, Clock clock, String issuer) {
        var grants = new ProductGrants(db, new TransactionTemplate(manager), clock);
        watermark.change(() -> grants.issue(new ProductGrants.Scope(issuer, "member", task.organization(), "CONSULTATION",
                task.id(), task.dataScope(), "TASK", task.id(), 1, "READ", "TASK_WORK"), task.expiresAt(), 0,
                "maintainer", "read", "e".repeat(64)));
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Timestamp start = Timestamp.from(now), until = Timestamp.from(now.plusSeconds(100));
        String session = UUID.randomUUID().toString(), message = UUID.randomUUID().toString(), goal = UUID.randomUUID().toString();
        db.update("INSERT INTO con3_session VALUES(?,?,'ACTIVE',?,?)", session, task.id(), start, until);
        db.update("INSERT INTO con3_message VALUES(?,?,?,1,?,? ,?,?,?,?,'goal-policy',1,?)", message, task.id(), session,
                issuer, "member", "original user statement", ProductComparisonJson.hash("original user statement"),
                start, until, "a".repeat(64));
        db.update("INSERT INTO con3_goal_revision VALUES(?,1,?,?,'QUERY','CONFIRMED','battery',"
                + "'[\"source-not-read\"]','[]',?,?,?, ?,?,?,'goal-policy',1,?)", goal, task.id(), task.organization(),
                message, issuer, "member", start, until, "d".repeat(64), "a".repeat(64));
        db.update("INSERT INTO con3_goal_current VALUES(?, ?,1,7)", task.id(), goal);
        db.update("INSERT INTO con3_search_input VALUES(?,?,?,1,7,'battery','[\"source-not-read\"]',"
                + "'[\"TITLE\"]',?,?,?)", UUID.randomUUID().toString(), task.id(), goal, "d".repeat(64), start, until);
        db.update("INSERT INTO con3_command_receipt VALUES(?,?,?,'GOAL_CONFIRMED','goal-request',?,?,7,?)", task.id(),
                issuer, "member", "d".repeat(64), goal, start);
        return new ProductHumanTaskFacts(db, access, decoder, new ProductRetentionPolicy("goal-policy", 1,
                "a".repeat(64), Duration.ofHours(1)), new ObjectMapper().findAndRegisterModules(), clock);
    }
    static final class MutableClock extends Clock {
        Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

