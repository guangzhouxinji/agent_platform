package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owned file-H2 lifecycle, persistent coordinates and explicit port doubles for
 * {@link ProductComparisonStartHandoffRaceTest}; this is not an executable test class.
 * See the test class for the unchanged engineering-only evidence boundary.
 */
abstract class ProductComparisonStartHandoffRaceFixture {
    static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    static final Instant UNTIL = NOW.plusSeconds(60);
    static final String TASK = "10000000-0000-0000-0000-000000000001";
    static final String INPUT = "20000000-0000-0000-0000-000000000001";
    static final String DELEGATION = "30000000-0000-0000-0000-000000000001";
    static final String ATTEMPT = "40000000-0000-0000-0000-000000000001";
    static final String REPLACEMENT = "40000000-0000-0000-0000-000000000002";
    static final String REPORT = "50000000-0000-0000-0000-000000000001";
    static final String CANDIDATE = "a".repeat(64), SHA = "b".repeat(64), DIGEST = "c".repeat(64);
    static final String KEY = "owned-handoff-race-key", ISSUER = "https://identity.invalid";
    final ProductComparisonStartCommands.Scope scope =
            new ProductComparisonStartCommands.Scope(TASK, INPUT, CANDIDATE, SHA, 5, 0);
    final ProductComparisonStartCommands.Actor actor = new ProductComparisonStartCommands.Actor(ISSUER, "human");
    final ProductComparisonStartCommands.ManagedBinding managed =
            new ProductComparisonStartCommands.ManagedBinding("worker", "configuration-version-17");
    final Jwt human = Jwt.withTokenValue("owned-engineering-port-double")
            .header("alg", "RS256").issuer(ISSUER).subject("human").build();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final MutableClock clock = new MutableClock();
    @TempDir Path directory;
    String databaseUrl;
    Connection ownedAnchor;
    JdbcTemplate db;
    ProductComparisonStartCommands commands;
    ProductComparisonStartApplication app;
    ProductComparisonInputApplication inputs;
    ProductComparisonWorkerDelegations delegations;
    ProductWorkerDelegations authority;
    ProductComparisonMatrixReader results;
    ProductTaskDirectory tasks;
    ProductConsultationAccess access;
    ProductComparisonInputs.View view;
    final AtomicInteger claims = new AtomicInteger(), executes = new AtomicInteger(), consumes = new AtomicInteger();
    Instant claimedUntil = UNTIL;
    Runnable duringClaim = () -> {}, duringExecute = () -> {}, beforeClaim = () -> {}, beforeExecute = () -> {};
    boolean claimUnknown, compareAllowed = true, readAllowed = true, sourceAllowed = true;

    @BeforeEach void setup() throws Exception {
        databaseUrl = "jdbc:h2:file:" + directory.resolve("owned-start-handoff").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=Oracle;LOCK_TIMEOUT=10000;WRITE_DELAY=0";
        ownedAnchor = DriverManager.getConnection(databaseUrl,"sa","");
        db = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, "sa", ""));
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY,version BIGINT,authorization_version BIGINT,cancel_epoch BIGINT,state VARCHAR(30),expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_subject(issuer VARCHAR(300),subject_id VARCHAR(200),PRIMARY KEY(issuer,subject_id))");
        db.execute("CREATE TABLE con5_feature_revision(task_id VARCHAR(36),revision_id VARCHAR(36),revision_number BIGINT,PRIMARY KEY(task_id,revision_id,revision_number))");
        db.execute("CREATE TABLE con5_preparation(task_id VARCHAR(36),preparation_id VARCHAR(36),PRIMARY KEY(task_id,preparation_id))");
        db.execute("CREATE TABLE con5_prepared_document(preparation_id VARCHAR(36),document_role VARCHAR(16),document_key VARCHAR(64),PRIMARY KEY(preparation_id,document_role,document_key))");
        db.execute("CREATE TABLE con3_worker_delegation(delegation_id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36),human_issuer VARCHAR(300),human_subject VARCHAR(200),service_subject VARCHAR(200),authorization_version BIGINT,status VARCHAR(20),expires_at TIMESTAMP)");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("db/dm8"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Current workspace migrations required");
        }
        for (String migration : List.of("V095__product_comparison_input.sql", "V098__product_comparison_worker_delegation.sql",
                "V099__product_comparison_candidate_lease.sql", "V100__product_comparison_matrix.sql",
                "V102__product_comparison_start_command.sql", "V103__comparison_start_fence_presence.sql")) {
            String sql = Files.readString(root.resolve("db/dm8").resolve(migration)).replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
            for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        }
        db.update("INSERT INTO con3_task VALUES(?,5,17,0,'IN_PROGRESS',?)", TASK, ts(NOW.plusSeconds(600)));
        db.update("INSERT INTO id2_subject VALUES(?,'human')", ISSUER);
        db.update("INSERT INTO con5_feature_revision VALUES(?,'feature',1)", TASK);
        db.update("INSERT INTO con5_preparation VALUES(?,'prepared')", TASK);
        db.update("INSERT INTO con5_comparison_input VALUES(?,?,'feature',1,'prepared',5,NULL,'{}',?,1,?,'human','input-key',?,?,?)",
                INPUT, TASK, SHA, ISSUER, DIGEST, ts(NOW), ts(NOW.plusSeconds(600)));
        db.update("INSERT INTO con5_prepared_document VALUES('prepared','CANDIDATE',?)", CANDIDATE);
        db.update("INSERT INTO con5_comparison_candidate VALUES(?,'prepared','CANDIDATE',?,0,'QUEUED')", INPUT, CANDIDATE);
        db.update("INSERT INTO con5_comparison_current VALUES(?,?)", TASK, INPUT);
        commands = spy(openCommands());
        inputs = mock(ProductComparisonInputApplication.class);
        delegations = mock(ProductComparisonWorkerDelegations.class);
        authority = mock(ProductWorkerDelegations.class);
        results = mock(ProductComparisonMatrixReader.class);
        tasks = mock(ProductTaskDirectory.class);
        access = mock(ProductConsultationAccess.class);
        var envelope = mock(ProductComparisonInputs.Envelope.class);
        view = mock(ProductComparisonInputs.View.class);
        when(view.input()).thenReturn(envelope);
        when(view.inputSha()).thenReturn(SHA);
        when(envelope.expiresAt()).thenReturn(NOW.plusSeconds(600));
        when(envelope.candidates()).thenReturn(List.of(new ProductComparisonInputs.Candidate(CANDIDATE, 0)));
        when(inputs.consume(TASK, INPUT, human)).thenAnswer(call -> {
            int number = consumes.incrementAndGet();
            if (number == 2) beforeClaim.run();
            if (number == 3) beforeExecute.run();
            if (!sourceAllowed) throw new IllegalStateException("owned source withdrawn");
            return view;
        });
        when(inputs.read(TASK, INPUT, human)).thenAnswer(call -> {
            if (!sourceAllowed) throw new IllegalStateException("owned source withdrawn");
            return view;
        });
        when(tasks.find(TASK)).thenAnswer(call -> Optional.of(db.queryForObject(
                "SELECT version,authorization_version,state,expires_at FROM con3_task WHERE task_id=?",
                (rs, n) -> new ProductTaskDirectory.Task(TASK, "org", ISSUER, "human", rs.getString("state"),
                        rs.getLong("version"), rs.getLong("authorization_version"), "scope", 1,
                        rs.getTimestamp("expires_at").toInstant()), TASK)));
        when(access.permits(eq(human), any(), any())).thenAnswer(call -> readAllowed);
        doAnswer(call -> {
            if (!compareAllowed) throw new IllegalStateException("owned human COMPARE revoked");
            return null;
        }).when(authority).requireComparisonActor(TASK, human);
        when(delegations.issue(eq(TASK), eq(INPUT), any(), eq(human), anyString())).thenAnswer(call -> {
            insertDelegation();
            return new ProductComparisonWorkerDelegations.Receipt(DELEGATION, 1, "ACTIVE", INPUT, SHA, CANDIDATE, UNTIL);
        });
        when(results.results(TASK, INPUT, human)).thenReturn(emptyResults());
        app = new ProductComparisonStartApplication(commands, inputs, delegations, authority, results, tasks, access, db,
                (path, body, key) -> {
                    assertEquals("start:" + original().commandId(), key);
                    if (path.endsWith("/executions")) {
                        claims.incrementAndGet();
                        insertAttempt(ATTEMPT, 1, key, claimedUntil);
                        db.update("INSERT INTO con5_candidate_lease VALUES(?,?,?,1,'ACTIVE',?)", INPUT, CANDIDATE, ATTEMPT, ts(claimedUntil));
                        duringClaim.run();
                        if (claimUnknown) throw new IllegalStateException("owned claim response lost: UNKNOWN");
                        return mapper.valueToTree(new ProductComparisonCandidateExecution.Lease(ATTEMPT, 1, claimedUntil));
                    }
                    assertEquals("/v2/consultation/tasks/" + TASK + "/executions/" + ATTEMPT + "/patent-comparison-result", path);
                    executes.incrementAndGet();
                    duringExecute.run();
                    return mapper.valueToTree(untrustedReceipt());
                }, managed, Duration.ofSeconds(60), clock, mapper);
    }

    @AfterEach void shutdownOwnedDatabase() throws Exception { if(ownedAnchor!=null){ownedAnchor.close();ownedAnchor=null;} if (db != null) db.execute("SHUTDOWN"); }
    ProductComparisonStartCommands openCommands() {
        var ds = new DriverManagerDataSource(databaseUrl, "sa", "");
        return new ProductComparisonStartCommands(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)), clock);
    }
    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    void insertDelegation() {
        db.update("INSERT INTO con3_worker_delegation VALUES(?,?,?,'human','worker',17,'ACTIVE',?)", DELEGATION, TASK, ISSUER, ts(UNTIL));
        db.update("INSERT INTO con5_comparison_delegation VALUES(?,?,?,?,?,?,'worker',?,?)", DELEGATION, TASK, INPUT, CANDIDATE, SHA, DIGEST, ts(NOW), ts(UNTIL));
    }
    void insertAttempt(String id, long token, String key, Instant until) {
        db.update("INSERT INTO con5_candidate_attempt VALUES(?,?,?,?,?,5,0,'worker',?,?,?, ?,?,'ACTIVE',NULL,?,?)",
                id, TASK, INPUT, CANDIDATE, SHA, DELEGATION, key, DIGEST, SHA, token, ts(NOW), ts(until));
    }
    ProductComparisonStartCommands.Command original() {
        return commands.lookupOriginalKey(TASK, actor, KEY).orElseThrow();
    }
    ProductComparisonMatrixReader.Results emptyResults() {
        return new ProductComparisonMatrixReader.Results(TASK, INPUT, SHA, "IN_PROGRESS", List.of(), List.of());
    }
    ProductComparisonMatrixResults.Receipt untrustedReceipt() {
        return new ProductComparisonMatrixResults.Receipt(TASK, INPUT, CANDIDATE, SHA, ATTEMPT, 1, REPORT, DIGEST, 1, "COMMITTED");
    }
    ProductComparisonStartApplication.Receipt start() {
        return app.start(TASK, INPUT, CANDIDATE, new ProductComparisonStartApplication.Start(5, SHA), KEY, human);
    }
    String boundCommand() {
        var command = commands.reserveExact(scope, actor, KEY, DIGEST, managed, UNTIL).command();
        assertTrue(commands.dispatchOnce(command.commandId()).isPresent());
        insertDelegation();
        commands.bindDelegation(command.commandId(), DELEGATION);
        insertAttempt(ATTEMPT, 1, "start:" + command.commandId(), UNTIL);
        db.update("INSERT INTO con5_candidate_lease VALUES(?,?,?,1,'ACTIVE',?)", INPUT, CANDIDATE, ATTEMPT, ts(UNTIL));
        commands.bindAttempt(command.commandId(), new ProductComparisonStartCommands.AttemptBinding(ATTEMPT, 1, UNTIL));
        return command.commandId();
    }
    void noClosure() {
        verify(commands, never()).commitFromExactReceipt(anyString(), anyString(), anyString());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM con5_matrix_report", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM con5_matrix_receipt", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM con5_start_command WHERE status='COMMITTED'", Integer.class));
    }
    List<Map<String, Object>> snapshot() {
        var rows = new ArrayList<Map<String, Object>>();
        for (String table : List.of("con3_task", "con5_comparison_input", "con5_comparison_current", "con5_start_command",
                "con3_worker_delegation", "con5_comparison_delegation", "con5_candidate_attempt", "con5_candidate_lease"))
            rows.addAll(db.query("SELECT * FROM " + table, (rs, number) -> {
                var row = new LinkedHashMap<String, Object>();
                var metadata = rs.getMetaData();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object value = rs.getObject(column);
                    // H2 CLOB wrappers have connection-specific identity; compare their stored bytes/text.
                    row.put(metadata.getColumnLabel(column), value instanceof java.sql.Clob ? rs.getString(column) : value);
                }
                return row;
            }));
        return rows;
    }
    void invalidate(String change) {
        switch (change) {
            case "cancelEpoch" -> db.update("UPDATE con3_task SET cancel_epoch=1 WHERE task_id=?", TASK);
            case "taskVersion" -> db.update("UPDATE con3_task SET version=6 WHERE task_id=?", TASK);
            case "taskState" -> db.update("UPDATE con3_task SET state='CANCELLED' WHERE task_id=?", TASK);
            case "taskExpiry" -> db.update("UPDATE con3_task SET expires_at=? WHERE task_id=?", ts(NOW), TASK);
            case "inputExpiry" -> {
                db.update("UPDATE con5_comparison_input SET expires_at=? WHERE comparison_id=?", ts(NOW.plusMillis(1)), INPUT);
                clock.now = NOW.plusMillis(1);
            }
            case "inputSha" -> db.update("UPDATE con5_comparison_input SET input_sha=? WHERE comparison_id=?", DIGEST, INPUT);
            case "currentInput" -> db.update("DELETE FROM con5_comparison_current WHERE task_id=?", TASK);
            case "delegationWithdrawn" -> db.update("UPDATE con3_worker_delegation SET status='REVOKED' WHERE delegation_id=?", DELEGATION);
            case "authorizationVersion" -> db.update("UPDATE con3_task SET authorization_version=18 WHERE task_id=?", TASK);
            case "delegationWindow" -> db.update("UPDATE con3_worker_delegation SET expires_at=? WHERE delegation_id=?", ts(NOW.plusSeconds(30)), DELEGATION);
            case "bindingWindow" -> db.update("UPDATE con5_comparison_delegation SET expires_at=? WHERE delegation_id=?", ts(NOW.plusSeconds(30)), DELEGATION);
            case "attemptWorker" -> db.update("UPDATE con5_candidate_attempt SET worker_subject='other-worker' WHERE attempt_id=?", ATTEMPT);
            case "attemptInput" -> db.update("UPDATE con5_candidate_attempt SET input_sha=? WHERE attempt_id=?", DIGEST, ATTEMPT);
            case "attemptUnknown" -> db.update("UPDATE con5_candidate_attempt SET status='RECONCILE_REQUIRED' WHERE attempt_id=?", ATTEMPT);
            case "slotUnknown" -> db.update("UPDATE con5_candidate_lease SET status='RECONCILE_REQUIRED' WHERE attempt_id=?", ATTEMPT);
            case "slotReplacement" -> {
                insertAttempt(REPLACEMENT, 2, "owned-replacement", UNTIL);
                db.update("UPDATE con5_candidate_lease SET attempt_id=?,fencing_token=2 WHERE comparison_id=? AND candidate_id=?", REPLACEMENT, INPUT, CANDIDATE);
            }
            case "slotLeaseMismatch" -> db.update("UPDATE con5_candidate_lease SET lease_until=? WHERE attempt_id=?", ts(UNTIL.minusSeconds(1)), ATTEMPT);
            case "commandUnknown" -> commands.markReconcile(original().commandId(), "OWNED_UNKNOWN");
            case "commandDeadline" -> clock.now = UNTIL;
            default -> throw new IllegalArgumentException(change);
        }
    }

    static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
