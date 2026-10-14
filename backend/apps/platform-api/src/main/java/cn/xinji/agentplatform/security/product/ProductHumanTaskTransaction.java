package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceSettings;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RequestHuman's Task/identity/WRITE stage; package and source guards remain mandatory. */
public final class ProductHumanTaskTransaction {
    public record Context(ProductTaskDirectory.Task task, OrdinaryHumanHandling.Actor actor) { }
    record Verified<T>(T value, Runnable recheck, Runnable deadlines) {
        Verified { Objects.requireNonNull(recheck); Objects.requireNonNull(deadlines); }
    }
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ProductConsultationAccess access;
    private final JwtDecoder decoder;
    private final JdbcProductTaskDirectory directory;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String issuer;

    public ProductHumanTaskTransaction(JdbcTemplate db, PlatformTransactionManager manager,
            ProductGrantWatermark watermark, ProductConsultationAccess access, JwtDecoder decoder,
            String issuer, Clock clock) {
        DataSource main = Objects.requireNonNull(db.getDataSource());
        DataSource authority = Objects.requireNonNull(watermark.independentDatabase().getDataSource());
        if (!(manager instanceof DataSourceTransactionManager jdbcManager)
                || jdbcManager.getDataSource() != main || access.mainDataSource() != main
                || watermark.mainDataSource() != main || main == authority)
            throw new IllegalArgumentException("A matching main transaction manager and independent authority are required");
        requireSeparateScope(main, authority);
        this.db = db;
        this.watermark = watermark;
        this.access = Objects.requireNonNull(access);
        this.decoder = Objects.requireNonNull(decoder);
        this.issuer = Objects.requireNonNull(issuer);
        this.clock = Objects.requireNonNull(clock);
        this.directory = new JdbcProductTaskDirectory(db, clock);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T execute(String rawToken, String taskId, Function<Context, T> operation) {
        Objects.requireNonNull(operation);
        return executeBoundary(rawToken, taskId, null, null, (context, plan) -> operation.apply(context), value -> { }, value -> { });
    }

    <T> T executeRequest(String rawToken, String taskId, ProductHumanRequestLocks locks,
            Function<Context, List<ProductHumanRequestLocks.SourceUse>> planner,
            BiFunction<Context, ProductHumanRequestLocks.Plan, T> operation) {
        Objects.requireNonNull(locks).requireBinding(db.getDataSource(), watermark, access, clock);
        return executeBoundary(rawToken, taskId, locks, Objects.requireNonNull(planner), operation, value -> { }, value -> { });
    }

    <T> T executeVerifiedRequest(String rawToken, String taskId, ProductHumanRequestLocks locks,
            Function<Context, List<ProductHumanRequestLocks.SourceUse>> planner,
            BiFunction<Context, ProductHumanRequestLocks.Plan, Verified<T>> operation) {
        Objects.requireNonNull(locks).requireBinding(db.getDataSource(), watermark, access, clock);
        return executeBoundary(rawToken, taskId, locks, Objects.requireNonNull(planner), operation,
                value -> value.recheck().run(), value -> value.deadlines().run()).value();
    }

    void requireBinding(DataSource main, Clock expectedClock) {
        if (db.getDataSource() != main || clock != expectedClock) throw new IllegalArgumentException("Matching request transaction required");
    }

    private <T> T executeBoundary(String rawToken, String taskId, ProductHumanRequestLocks locks,
            Function<Context, List<ProductHumanRequestLocks.SourceUse>> planner,
            BiFunction<Context, ProductHumanRequestLocks.Plan, T> operation,
            java.util.function.Consumer<T> recheck, java.util.function.Consumer<T> deadlines) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Human request must own its transaction boundary");
        if (rawToken == null || rawToken.isBlank() || taskId == null || taskId.isBlank())
            throw new IllegalArgumentException("Original token and Task are required");
        Objects.requireNonNull(operation);
        Jwt jwt = verified(rawToken);
        return transactions.execute(status -> {
            long generation = watermark.currentGeneration();
            lock("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE", taskId);
            var task = task(taskId);
            lock("SELECT organization_id FROM con3_organization WHERE organization_id=? "
                    + "AND status='ACTIVE' FOR UPDATE", task.organization());
            ProductHumanIdentityLocks.Identity identity = identity(jwt);
            Context context = new Context(task, new OrdinaryHumanHandling.Actor(
                    issuer, jwt.getSubject(), OrdinaryHumanHandling.ActorKind.HUMAN));
            // Planning may decode an existing request body; establish current Task access first.
            // This reads authority only. The sorted epoch locks and all post-lock checks remain below.
            if (locks != null) permitRequestPlanning(jwt, task);
            var plan = locks == null ? null : locks.prepare(context, jwt, planner.apply(context));
            if (plan != null) plan.acquire();
            else lock("SELECT resource_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? "
                    + "AND organization=? AND domain_code='CONSULTATION' AND resource_type='TASK' "
                    + "AND resource_id=? AND action_code='WRITE' AND purpose='TASK_WORK' FOR UPDATE",
                    issuer, jwt.getSubject(), task.organization(), task.id());
            permit(jwt, task);
            Instant grantUntil = grantUntil(jwt, task);
            if (plan != null) plan.assertCurrent(jwt);
            T result = operation.apply(context, plan);
            recheck.accept(result);
            Jwt current = verified(rawToken);
            if (!task.equals(task(taskId)) || !identity.equals(identity(current)))
                throw new IllegalStateException("Human request facts changed before commit");
            permit(current, task);
            if (plan != null) plan.assertCurrent(current);
            watermark.assertUnchanged(generation);
            deadlines.accept(result);
            if (plan != null) plan.assertDeadlines();
            Instant now = clock.instant();
            if (!task.expiresAt().isAfter(now) || !current.getExpiresAt().isAfter(now)
                    || !identity.session().expires().isAfter(now) || !grantUntil.isAfter(now)
                    || "VERIFY_ONLY".equals(identity.keyStatus()) && !identity.verifyUntil().isAfter(now))
                throw new IllegalStateException("Human request deadline reached before commit");
            return result;
        });
    }

    private Jwt verified(String raw) {
        Jwt jwt = decoder.decode(raw);
        Instant now = clock.instant();
        if (jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || blank(jwt.getSubject()) || blank(jwt.getId()) || blank(jwt.getClaimAsString("sid"))
                || !(jwt.getHeaders().get("kid") instanceof String kid) || blank(kid)
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))
            throw new IllegalStateException("Current human token required");
        return jwt;
    }

    private ProductTaskDirectory.Task task(String id) {
        var task = directory.find(id).orElseThrow(() -> new IllegalStateException("Task unavailable"));
        if (!"IN_PROGRESS".equals(task.state()) || !task.expiresAt().isAfter(clock.instant()))
            throw new IllegalStateException("An unexpired confirmed Task is required");
        return task;
    }

    private ProductHumanIdentityLocks.Identity identity(Jwt jwt) {
        return new ProductHumanIdentityLocks(db, issuer, clock).read(jwt, true);
    }

    private void permitRequestPlanning(Jwt jwt, ProductTaskDirectory.Task task) {
        permit(jwt, task);
        var facts = new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(),
                task.dataScope(), "TASK", task.id(), task.authorizationVersion());
        if (!access.permits(jwt, facts, ProductAccessPolicy.Action.READ))
            throw new IllegalStateException("Current Task READ permission required");
    }

    private void permit(Jwt jwt, ProductTaskDirectory.Task task) {
        var facts = new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(),
                task.dataScope(), "TASK", task.id(), task.authorizationVersion());
        if (!access.permits(jwt, facts, ProductAccessPolicy.Action.WRITE))
            throw new IllegalStateException("Current Task WRITE permission required");
    }

    private Instant grantUntil(Jwt jwt, ProductTaskDirectory.Task task) {
        return single(db.query("SELECT g.expires_at FROM id2_current_epoch c "
                        + "JOIN id2_grant g ON g.grant_id=c.current_grant_id WHERE c.issuer=? "
                        + "AND c.subject_id=? AND c.organization=? AND c.domain_code='CONSULTATION' "
                        + "AND c.resource_type='TASK' AND c.resource_id=? AND c.action_code='WRITE' "
                        + "AND c.purpose='TASK_WORK'", (rs, n) -> rs.getTimestamp(1).toInstant(),
                issuer, jwt.getSubject(), task.organization(), task.id()));
    }

    private void lock(String sql, Object... args) {
        single(db.query(sql, (rs, n) -> rs.getString(1), args));
    }

    private static <T> T single(List<T> rows) {
        if (rows.size() != 1) throw new IllegalStateException("Current human request facts unavailable");
        return rows.getFirst();
    }

    private static void requireSeparateScope(DataSource main, DataSource authority) {
        try (var primary = main.getConnection(); var independent = authority.getConnection()) {
            String primaryUrl = primary.getMetaData().getURL();
            String authorityUrl = independent.getMetaData().getURL();
            if (primaryUrl.equals(authorityUrl)) {
                AuthorityDataSourceSettings.resolve("shared-instance", primaryUrl, primary.getSchema(),
                        primary.getMetaData().getUserName(), authorityUrl, independent.getSchema(),
                        independent.getMetaData().getUserName(), "");
            }
        }
        catch (java.sql.SQLException unavailable) {
            throw new IllegalStateException("Cannot establish transaction database binding");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
