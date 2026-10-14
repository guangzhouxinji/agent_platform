package cn.xinji.agentplatform.security.product;
import static cn.xinji.agentplatform.security.product.ProductComparisonStartReceiptChecks.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
/**
 * V102 persistent handoff coordinates only: no identity, grant, source, model or HTTP authority.
 * Caller must authorize every action, select managed service/configuration and bound the window.
 * A replay is read-only; only the original newlyReserved path may call dispatchOnce.
 */
final class ProductComparisonStartCommands {
    record Scope(String task, String comparison, String candidate, String inputSha, long taskVersion, long cancelEpoch) { }
    record Actor(String issuer, String subject) { }
    record ManagedBinding(String serviceSubject, String configurationVersion) { }
    record AttemptBinding(String attemptId, long fencingToken, Instant leaseUntil) { }
    record Command(String commandId, Scope scope, Actor actor, String requestId, String requestSha,
                   ManagedBinding managed, String delegationId, String attemptId, Long fencingToken,
                   Instant leaseUntil, String status, String errorCode, Instant createdAt, Instant expiresAt) { }
    record Reservation(Command command, boolean newlyReserved) { }
    private record Task(long version, long cancelEpoch, String state, Instant expiresAt) { }
    private record Locked(Command command, Task task) { }
    private static final String COLUMNS = "command_id,task_id,comparison_id,candidate_id,input_sha,task_version,cancel_epoch,"
            + "actor_issuer,actor_subject,request_id,request_sha,service_subject,configuration_version,"
            + "delegation_id,attempt_id,fencing_token,lease_until,status,error_code,created_at,expires_at";
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;
    ProductComparisonStartCommands(JdbcTemplate db, TransactionTemplate transactions, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()), transactions);
        // Includes configuration_version; do not treat the actual 21-column migration as 20 columns.
        db.query("SELECT " + COLUMNS + " FROM con5_start_command WHERE 1=0", ProductComparisonStartCommands::row);
    }
    Reservation reserveExact(Scope scope, Actor actor, String key, String digest, ManagedBinding managed, Instant until) {
        validate(scope); validate(actor); validate(managed); key(key); hash(digest);
        Objects.requireNonNull(until, "Exact bounded expiration required");
        return transaction(() -> {
            var task = lockTask(scope.task());
            var old = original(scope.task(), actor, key);
            if (old.isPresent()) {
                var value = old.get();
                if (!value.scope().equals(scope) || !value.actor().equals(actor) || !value.requestSha().equals(digest)
                        || !value.managed().equals(managed)) throw conflict();
                // Server-derived windows may shorten between reads; never rewrite the original deadline.
                return new Reservation(observe(value, task), false);
            }
            current(scope, task, true);
            var now = now();
            var expires = until.truncatedTo(ChronoUnit.MILLIS);
            Instant inputUntil = db.queryForObject("SELECT expires_at FROM con5_comparison_input WHERE task_id=? AND comparison_id=?",
                    Timestamp.class, scope.task(), scope.comparison()).toInstant();
            if (!expires.isAfter(now) || expires.isAfter(task.expiresAt()) || expires.isAfter(inputUntil)) throw conflict();
            available(scope, null);
            String id = UUID.randomUUID().toString();
            db.update("INSERT INTO con5_start_command (" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,'ACCEPTED',NULL,?,?)",
                    id, scope.task(), scope.comparison(), scope.candidate(), scope.inputSha(), scope.taskVersion(), scope.cancelEpoch(),
                    actor.issuer(), actor.subject(), key, digest, managed.serviceSubject(), managed.configurationVersion(),
                    Timestamp.from(now), Timestamp.from(expires));
            return new Reservation(command(id), true);
        });
    }
    /** Durable CAS before external I/O. Empty means no permission to send/re-send. No I/O runs here. */
    Optional<Command> dispatchOnce(String commandId) {
        id(commandId);
        return transaction(() -> {
            var locked = lockCommand(commandId); var command = locked.command();
            if (!command.status().equals("ACCEPTED")) return Optional.empty();
            current(command.scope(), locked.task(), true); live(command);
            available(command.scope(), commandId);
            changed(db.update("UPDATE con5_start_command SET status='DISPATCHING' WHERE command_id=? AND status='ACCEPTED'", commandId));
            return Optional.of(command(commandId));
        });
    }
    /** New external I/O only. GET reconciliation deliberately keeps its separate historical contract. */
    void requireLiveHandoff(String commandId) {
        id(commandId);
        transaction(() -> {
            var locked = lockCommand(commandId); var c = locked.command();
            current(c.scope(), locked.task(), true); live(c);
            if (!"DISPATCHING".equals(c.status()) || c.delegationId() == null) throw conflict();
            ProductComparisonStartHandoffGuard.require(db, c, now());
            return null;
        });
    }
    Command bindDelegation(String commandId, String delegationId) {
        id(commandId); id(delegationId);
        return transaction(() -> {
            var locked = lockCommand(commandId); var c = locked.command();
            if (c.delegationId() != null && !c.delegationId().equals(delegationId)) throw conflict();
            current(c.scope(), locked.task(), true); live(c);
            ProductComparisonStartHandoffGuard.requireDelegation(db, c, delegationId);
            if (c.delegationId() == null) {
                writable(c);
                changed(db.update("UPDATE con5_start_command SET delegation_id=? WHERE command_id=? AND delegation_id IS NULL", delegationId, commandId));
            }
            return command(commandId);
        });
    }
    Command bindAttempt(String commandId, AttemptBinding binding) {
        id(commandId); Objects.requireNonNull(binding); id(binding.attemptId());
        if (binding.fencingToken() <= 0 || binding.leaseUntil() == null) throw invalid();
        return transaction(() -> {
            var locked = lockCommand(commandId); var c = locked.command();
            current(c.scope(), locked.task(), false);
            if (c.delegationId() == null || binding.leaseUntil().isAfter(c.expiresAt())) throw conflict();
            if (c.attemptId() != null && (!c.attemptId().equals(binding.attemptId()) || c.fencingToken() != binding.fencingToken()
                    || !c.leaseUntil().equals(binding.leaseUntil()))) throw conflict();
            Long count = db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt a JOIN con5_candidate_lease l ON l.comparison_id=a.comparison_id "
                    + "AND l.candidate_id=a.candidate_id AND l.attempt_id=a.attempt_id AND l.fencing_token=a.fencing_token "
                    + "WHERE a.attempt_id=? AND a.task_id=? AND a.comparison_id=? AND a.candidate_id=? AND a.input_sha=? AND a.task_version=? "
                    + "AND a.cancel_epoch=? AND a.worker_subject=? AND a.delegation_id=? AND a.fencing_token=? AND a.lease_until=? AND l.lease_until=a.lease_until",
                    Long.class, binding.attemptId(), c.scope().task(), c.scope().comparison(), c.scope().candidate(), c.scope().inputSha(),
                    c.scope().taskVersion(), c.scope().cancelEpoch(), c.managed().serviceSubject(), c.delegationId(), binding.fencingToken(), Timestamp.from(binding.leaseUntil()));
            if (count == null || count != 1) throw conflict();
            if (c.attemptId() == null) {
                writable(c);
                changed(db.update("UPDATE con5_start_command SET attempt_id=?,fencing_token=?,lease_until=? WHERE command_id=? AND attempt_id IS NULL",
                        binding.attemptId(), binding.fencingToken(), Timestamp.from(binding.leaseUntil()), commandId));
            }
            return command(commandId);
        });
    }
    /** A transport error is UNKNOWN, never an inferred definitive failure or a retry license. */
    Command markReconcile(String commandId, String errorCode) {
        id(commandId); text(errorCode, 80);
        return transaction(() -> {
            var locked = lockCommand(commandId); var c = locked.command();
            if (Set.of("ACCEPTED", "DISPATCHING", "RECONCILE_REQUIRED").contains(c.status())) {
                changed(db.update("UPDATE con5_start_command SET status='RECONCILE_REQUIRED',error_code=COALESCE(error_code,?) WHERE command_id=?",
                        errorCode, commandId));
            }
            return observe(command(commandId), locked.task());
        });
    }

    /** Caller must separately check current READ and source purposes. No protected originals returned. */
    Command readExact(Scope scope, Actor actor, String commandId) {
        validate(scope); validate(actor); id(commandId);
        return transaction(() -> {
            var locked = lockCommand(commandId); var c = locked.command();
            if (!c.scope().equals(scope) || !c.actor().equals(actor)) throw conflict();
            return observe(c, locked.task());
        });
    }

    Optional<Command> lookupOriginalKey(String task, Actor actor, String key) { return lookupOriginalKey(task,actor,key,true); }
    /** Coordinates/cancellation only, before callers have checked source READ; never hashes a model body. */
    Optional<Command> lookupOriginalKeyMetadata(String task, Actor actor, String key) { return lookupOriginalKey(task,actor,key,false); }
    private Optional<Command> lookupOriginalKey(String task, Actor actor, String key,boolean inspectFailure) {
        id(task); validate(actor); key(key);
        return transaction(() -> {
            var locked = lockTask(task);
            return original(task, actor, key).map(value -> observe(value, locked,inspectFailure));
        });
    }

    /** Only an existing exact native report/receipt closure can advance the command, never client output. */
    Command commitFromExactReceipt(String commandId, String reportId, String reportSha) {
        id(commandId); id(reportId); hash(reportSha);
        return transaction(() -> {
            var locked = lockCommand(commandId); var c = locked.command();
            current(c.scope(), locked.task(), false);
            if (c.attemptId() == null || c.delegationId() == null || c.fencingToken() == null) throw conflict();
            var closure = db.query("SELECT r.package_json,m.receipt_json,m.receipt_sha,i.response_json,i.response_sha,i.approval_sha "
                    + "FROM con5_matrix_receipt m JOIN con5_matrix_report r ON r.report_id=m.report_id AND r.attempt_id=m.attempt_id AND r.report_sha=m.report_sha "
                    + "JOIN con5_candidate_attempt a ON a.attempt_id=r.attempt_id JOIN con5_candidate_lease l ON l.attempt_id=a.attempt_id AND l.fencing_token=a.fencing_token "
                    + "AND l.comparison_id=a.comparison_id AND l.candidate_id=a.candidate_id JOIN con5_matrix_invocation i ON i.attempt_id=a.attempt_id "
                    + "JOIN con5_comparison_candidate s ON s.comparison_id=a.comparison_id AND s.candidate_id=a.candidate_id "
                    + "WHERE r.report_id=? AND r.report_sha=? AND r.task_id=? AND r.comparison_id=? AND r.candidate_id=? AND r.input_sha=? "
                    + "AND r.attempt_id=? AND r.fencing_token=? AND a.task_id=r.task_id AND a.comparison_id=r.comparison_id AND a.candidate_id=r.candidate_id "
                    + "AND a.input_sha=r.input_sha AND a.task_version=? AND a.cancel_epoch=? AND a.worker_subject=? AND a.delegation_id=? "
                    + "AND a.fencing_token=r.fencing_token AND l.lease_until=a.lease_until AND i.configuration_version=? "
                    + "AND a.status='COMMITTED' AND l.status='COMMITTED' AND i.status='COMMITTED' AND s.status='COMMITTED'",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)},
                    reportId, reportSha, c.scope().task(), c.scope().comparison(), c.scope().candidate(), c.scope().inputSha(),
                    c.attemptId(), c.fencingToken(), c.scope().taskVersion(), c.scope().cancelEpoch(), c.managed().serviceSubject(),
                    c.delegationId(), c.managed().configurationVersion());
            if (closure.size() != 1) throw conflict();
            var value = closure.getFirst();
            if (!digest(value[0]).equals(reportSha) || !digest(value[1]).equals(value[2]) || !digest(value[3]).equals(value[4])) throw conflict();
            var receipt = tree(value[1]); var report = tree(value[0]);
            jsonCoordinates(receipt, c, reportId); jsonCoordinates(report, c, reportId);
            if (!reportSha.equals(receipt.path("reportSha").asText()) || !"COMMITTED".equals(receipt.path("status").asText())
                    || !c.managed().configurationVersion().equals(report.path("configurationVersion").asText())
                    || !c.managed().serviceSubject().equals(report.path("workerSubject").asText())
                    || !value[4].equals(report.path("responseSha").asText()) || !value[5].equals(report.path("approvalSha").asText())) throw conflict();
            if (!c.status().equals("COMMITTED")) {
                writable(c);
                changed(db.update("UPDATE con5_start_command SET status='COMMITTED' WHERE command_id=? AND status IN ('DISPATCHING','RECONCILE_REQUIRED')", commandId));
            }
            return command(commandId);
        });
    }

    private void available(Scope scope, String ownCommand) {
        var candidates = db.queryForList("SELECT status FROM con5_comparison_candidate WHERE comparison_id=? AND candidate_id=?", String.class,
                scope.comparison(), scope.candidate());
        if (candidates.size() != 1 || !Set.of("QUEUED", "FAILED").contains(candidates.getFirst())) throw conflict();
        var leases = db.queryForList("SELECT status FROM con5_candidate_lease WHERE comparison_id=? AND candidate_id=? FOR UPDATE", String.class,
                scope.comparison(), scope.candidate());
        if (leases.size() > 1 || (!leases.isEmpty() && !leases.getFirst().equals("FAILED"))) throw conflict();
        var commands = db.query("SELECT " + COLUMNS + " FROM con5_start_command WHERE task_id=? AND comparison_id=? AND candidate_id=?",
                ProductComparisonStartCommands::row, scope.task(), scope.comparison(), scope.candidate());
        for (var c : commands) if (!c.commandId().equals(ownCommand)
                && !"CANCELLED".equals(c.status()) && ProductComparisonFailureProof.inspect(db,c) == null) throw conflict();
    }

    private Command observe(Command c, Task task) { return observe(c,task,true); }
    private Command observe(Command c, Task task,boolean inspectFailure) {
        // Projection only: never erase prior COMMITTED/unknown history or the external attempt.
        var failure = inspectFailure && task.cancelEpoch() == c.scope().cancelEpoch() ? ProductComparisonFailureProof.inspect(db,c) : null;
        if (inspectFailure && task.cancelEpoch() == c.scope().cancelEpoch() && "FAILED".equals(c.status()) && failure == null) throw conflict();
        String status = task.cancelEpoch() != c.scope().cancelEpoch() ? "CANCELLED" : failure != null ? "FAILED" : c.status();
        String code = failure == null ? c.errorCode() : failure.code();
        return status.equals(c.status()) && Objects.equals(code, c.errorCode()) ? c : new Command(c.commandId(), c.scope(), c.actor(), c.requestId(), c.requestSha(), c.managed(),
                c.delegationId(), c.attemptId(), c.fencingToken(), c.leaseUntil(), status, code, c.createdAt(), c.expiresAt());
    }
    private void current(Scope scope, Task task, boolean fresh) {
        if (task.version() != scope.taskVersion() || task.cancelEpoch() != scope.cancelEpoch() || !task.state().equals("IN_PROGRESS")
                || (fresh && !task.expiresAt().isAfter(now()))) throw conflict();
        Long count = db.queryForObject("SELECT COUNT(*) FROM con5_comparison_input i JOIN con5_comparison_current p ON p.task_id=i.task_id AND p.comparison_id=i.comparison_id "
                + "WHERE i.task_id=? AND i.comparison_id=? AND i.input_sha=? AND i.task_version=?" + (fresh ? " AND i.expires_at>?" : ""), Long.class,
                fresh ? new Object[]{scope.task(), scope.comparison(), scope.inputSha(), scope.taskVersion(), Timestamp.from(now())}
                      : new Object[]{scope.task(), scope.comparison(), scope.inputSha(), scope.taskVersion()});
        if (count == null || count != 1) throw conflict();
    }
    private Task lockTask(String task) {
        var rows = db.query("SELECT version,cancel_epoch,state,expires_at FROM con3_task WHERE task_id=? FOR UPDATE",
                (rs, row) -> new Task(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()), task);
        if (rows.size() != 1) throw conflict(); return rows.getFirst();
    }
    private Locked lockCommand(String id) {
        var first = command(id); var task = lockTask(first.scope().task());
        var rows = db.query("SELECT " + COLUMNS + " FROM con5_start_command WHERE command_id=? FOR UPDATE", ProductComparisonStartCommands::row, id);
        if (rows.size() != 1 || !rows.getFirst().scope().task().equals(first.scope().task())) throw conflict();
        return new Locked(rows.getFirst(), task);
    }
    Command metadata(String id) { id(id); return command(id); }
    private Command command(String id) {
        var rows = db.query("SELECT " + COLUMNS + " FROM con5_start_command WHERE command_id=?", ProductComparisonStartCommands::row, id);
        if (rows.size() != 1) throw conflict(); return rows.getFirst();
    }
    private Optional<Command> original(String task, Actor actor, String key) {
        var rows = db.query("SELECT " + COLUMNS + " FROM con5_start_command WHERE task_id=? AND actor_issuer=? AND actor_subject=? AND request_id=?",
                ProductComparisonStartCommands::row, task, actor.issuer(), actor.subject(), key);
        if (rows.size() > 1) throw conflict(); return rows.stream().findFirst();
    }
    private <T> T transaction(Supplier<T> operation) {
        // Sending before an ambient transaction commits would violate durable-before-dispatch.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("COMPARISON_START_REQUIRES_OWN_TRANSACTION");
        return tx.execute(status -> operation.get());
    }
    private static Command row(ResultSet rs, int ignored) throws SQLException {
        long token = rs.getLong("fencing_token"); Long nullableToken = rs.wasNull() ? null : token;
        var lease = rs.getTimestamp("lease_until");
        return new Command(rs.getString("command_id"), new Scope(rs.getString("task_id"), rs.getString("comparison_id"), rs.getString("candidate_id"),
                rs.getString("input_sha"), rs.getLong("task_version"), rs.getLong("cancel_epoch")), new Actor(rs.getString("actor_issuer"), rs.getString("actor_subject")),
                rs.getString("request_id"), rs.getString("request_sha"), new ManagedBinding(rs.getString("service_subject"), rs.getString("configuration_version")),
                rs.getString("delegation_id"), rs.getString("attempt_id"), nullableToken, lease == null ? null : lease.toInstant(), rs.getString("status"),
                rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
    }
    private void live(Command c) { if (!c.expiresAt().isAfter(now())) throw conflict(); }
    private static void writable(Command c) { if (!Set.of("DISPATCHING", "RECONCILE_REQUIRED").contains(c.status())) throw conflict(); }
    private static void changed(int count) { if (count != 1) throw conflict(); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static void validate(Scope s) { if (s == null) throw invalid(); id(s.task()); id(s.comparison()); text(s.candidate(), 64); hash(s.inputSha()); if (s.taskVersion() <= 0 || s.cancelEpoch() < 0) throw invalid(); }
    private static void validate(Actor a) { if (a == null) throw invalid(); text(a.issuer(), 300); text(a.subject(), 200); }
    private static void validate(ManagedBinding b) { if (b == null) throw invalid(); text(b.serviceSubject(), 200); text(b.configurationVersion(), 200); }
    private static void id(String value) { text(value, 36); }
    private static void hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid(); }
    private static void key(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}")) throw invalid(); }
    private static void text(String value, int length) { if (value == null || value.isBlank() || value.length() > length || value.chars().anyMatch(c -> c < 32)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("EXACT_COMPARISON_START_COORDINATES_REQUIRED"); }
    private static IllegalStateException conflict() { return new IllegalStateException("COMPARISON_START_BINDING_OR_STATE_CONFLICT"); }
}
