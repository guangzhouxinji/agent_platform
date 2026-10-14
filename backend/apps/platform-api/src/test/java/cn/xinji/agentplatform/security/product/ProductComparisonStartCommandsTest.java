package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 storage-protocol engineering only; real V095/98/99/100/102 DDL, no business/model acceptance. */
class ProductComparisonStartCommandsTest {
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(600);
    private static final String SHA = "a".repeat(64), REQUEST = "b".repeat(64);
    private final ProductComparisonStartCommands.Scope scope = new ProductComparisonStartCommands.Scope("task", "input", "A", SHA, 5, 0);
    private final ProductComparisonStartCommands.Actor actor = new ProductComparisonStartCommands.Actor("issuer", "human");
    // Deliberately a version, NOT the model configuration id.
    private final ProductComparisonStartCommands.ManagedBinding managed = new ProductComparisonStartCommands.ManagedBinding("worker", "configuration-version-17");
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private ProductComparisonStartCommands store;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:start_" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        db = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY, version BIGINT, authorization_version BIGINT, cancel_epoch BIGINT, state VARCHAR(30), expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_subject(issuer VARCHAR(300),subject_id VARCHAR(200),PRIMARY KEY(issuer,subject_id))");
        db.execute("CREATE TABLE con5_feature_revision(task_id VARCHAR(36),revision_id VARCHAR(36),revision_number BIGINT,PRIMARY KEY(task_id,revision_id,revision_number))");
        db.execute("CREATE TABLE con5_preparation(task_id VARCHAR(36),preparation_id VARCHAR(36),PRIMARY KEY(task_id,preparation_id))");
        db.execute("CREATE TABLE con5_prepared_document(preparation_id VARCHAR(36),document_role VARCHAR(16),document_key VARCHAR(64),PRIMARY KEY(preparation_id,document_role,document_key))");
        db.execute("CREATE TABLE con3_worker_delegation(delegation_id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36),human_issuer VARCHAR(300),human_subject VARCHAR(200),service_subject VARCHAR(200),authorization_version BIGINT,status VARCHAR(20),expires_at TIMESTAMP)");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("db/dm8"))) { root = root.getParent(); if(root == null) throw new IllegalStateException("Repository migrations required"); }
        for (var file : List.of("V095__product_comparison_input.sql", "V098__product_comparison_worker_delegation.sql", "V099__product_comparison_candidate_lease.sql", "V100__product_comparison_matrix.sql", "V102__product_comparison_start_command.sql")) {
            String sql = Files.readString(root.resolve("db/dm8").resolve(file)).replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
            for (var statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        }
        db.update("INSERT INTO con3_task VALUES('task',5,17,0,'IN_PROGRESS',?)", ts(UNTIL.plusSeconds(300)));
        db.update("INSERT INTO id2_subject VALUES('issuer','human')");
        db.update("INSERT INTO con5_feature_revision VALUES('task','feature',1)");
        db.update("INSERT INTO con5_preparation VALUES('task','prepared')");
        db.update("INSERT INTO con5_comparison_input VALUES('input','task','feature',1,'prepared',5,NULL,'{}',?,2,'issuer','human','input-key',?,?,?)", SHA, REQUEST, ts(NOW), ts(UNTIL.plusSeconds(100)));
        for (var candidate : List.of("A","B")) {
            db.update("INSERT INTO con5_prepared_document VALUES('prepared','CANDIDATE',?)", candidate);
            db.update("INSERT INTO con5_comparison_candidate VALUES('input','prepared','CANDIDATE',?,?,'QUEUED')", candidate, candidate.equals("A") ? 0 : 1);
        }
        db.update("INSERT INTO con5_comparison_current VALUES('task','input')");
        store = new ProductComparisonStartCommands(db, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private ProductComparisonStartCommands.Reservation reserve(String key) { return store.reserveExact(scope, actor, key, REQUEST, managed, UNTIL); }
    private String dispatch() { var c = reserve("start").command(); assertTrue(store.dispatchOnce(c.commandId()).isPresent()); return c.commandId(); }
    private void delegation(String id, String candidate, long authorization) {
        db.update("INSERT INTO con3_worker_delegation VALUES(?,'task','issuer','human','worker',?,'ACTIVE',?)", id, authorization, ts(UNTIL));
        db.update("INSERT INTO con5_comparison_delegation VALUES(?,'task','input',?,?,?,'worker',?,?)", id, candidate, SHA, REQUEST, ts(NOW), ts(UNTIL));
    }
    private void attempt(String id, String candidate, String delegation, String worker, long token) {
        String commandId = db.queryForObject("SELECT command_id FROM con5_start_command WHERE candidate_id=? AND status='DISPATCHING'", String.class, candidate);
        db.update("INSERT INTO con5_candidate_attempt VALUES(?,'task','input',?,?,5,0,?,?,?, ?,?,?,'ACTIVE',NULL,?,?)", id, candidate, SHA, worker, delegation, "start:" + commandId, REQUEST, SHA, token, ts(NOW), ts(UNTIL));
        db.update("INSERT INTO con5_candidate_lease VALUES('input',?,?,?,'ACTIVE',?)", candidate, id, token, ts(UNTIL));
    }
    private String bound() {
        String id = dispatch(); delegation("delegation", "A", 17); store.bindDelegation(id, "delegation");
        attempt("attempt", "A", "delegation", "worker", 1);
        store.bindAttempt(id, new ProductComparisonStartCommands.AttemptBinding("attempt", 1, UNTIL)); return id;
    }
    private String status(String id) { return db.queryForObject("SELECT status FROM con5_start_command WHERE command_id=?", String.class, id); }
    private void conflict(org.junit.jupiter.api.function.Executable action) { assertThrows(IllegalStateException.class, action); }

    @Test void delegationUsesAuthorizationVersionNotBusinessVersion() {
        String id = dispatch(); delegation("correct", "A", 17);
        assertEquals("correct", store.bindDelegation(id, "correct").delegationId());
        assertEquals("correct", store.bindDelegation(id, "correct").delegationId());
    }
    @Test void businessVersionInDelegationIsRejectedWithoutPartialBinding() {
        String id = dispatch(); delegation("wrong", "A", 5);
        conflict(() -> store.bindDelegation(id, "wrong"));
        assertNull(store.readExact(scope, actor, id).delegationId()); assertEquals("DISPATCHING", status(id));
    }
    @Test void staleAuthorizationVersionIsRejectedAndCurrentVersionWorks() {
        String id = dispatch(); delegation("stale", "A", 16); conflict(() -> store.bindDelegation(id, "stale"));
        delegation("current", "A", 17); assertEquals("current", store.bindDelegation(id, "current").delegationId());
        db.update("UPDATE con3_task SET authorization_version=18 WHERE task_id='task'");
        conflict(() -> store.bindDelegation(id, "current"));
        assertEquals("current", store.readExact(scope, actor, id).delegationId());
    }
    @Test void replayIsImmutableAndDoesNotDispatchOrRewriteWindow() {
        var first = reserve("key"); assertTrue(first.newlyReserved());
        var second = store.reserveExact(scope, actor, "key", REQUEST, managed, NOW.plusSeconds(20));
        assertFalse(second.newlyReserved()); assertEquals(first.command(), second.command());
        assertEquals("ACCEPTED", status(first.command().commandId()));
        assertEquals(first.command(), store.lookupOriginalKey("task", actor, "key").orElseThrow());
        assertTrue(store.dispatchOnce(first.command().commandId()).isPresent());
        assertTrue(store.dispatchOnce(first.command().commandId()).isEmpty());
    }
    @Test void requestServiceConfigurationAndInputAreImmutable() {
        reserve("key");
        conflict(() -> store.reserveExact(scope, actor, "key", SHA, managed, UNTIL));
        conflict(() -> store.reserveExact(scope, actor, "key", REQUEST, new ProductComparisonStartCommands.ManagedBinding("other", managed.configurationVersion()), UNTIL));
        conflict(() -> store.reserveExact(scope, actor, "key", REQUEST, new ProductComparisonStartCommands.ManagedBinding("worker", "configuration-id-not-version"), UNTIL));
        conflict(() -> store.reserveExact(new ProductComparisonStartCommands.Scope("task","input","A",REQUEST,5,0), actor,"key",REQUEST,managed,UNTIL));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM con5_start_command", Integer.class));
    }
    @Test void exactReadsDoNotCrossActorIssuerOrCandidate() {
        var c = reserve("key").command();
        conflict(() -> store.readExact(scope,new ProductComparisonStartCommands.Actor("issuer","other"),c.commandId()));
        conflict(() -> store.readExact(scope,new ProductComparisonStartCommands.Actor("other","human"),c.commandId()));
        conflict(() -> store.readExact(new ProductComparisonStartCommands.Scope("task","input","B",SHA,5,0),actor,c.commandId()));
        assertTrue(store.lookupOriginalKey("task",new ProductComparisonStartCommands.Actor("issuer","other"),"key").isEmpty());
    }
    @Test void currentTaskVersionCancelAndInputAreRequired() {
        db.update("UPDATE con3_task SET version=6"); conflict(() -> reserve("key"));
        db.update("UPDATE con3_task SET version=5,cancel_epoch=1"); conflict(() -> reserve("key"));
        db.update("UPDATE con3_task SET cancel_epoch=0,state='CANCELLED'"); conflict(() -> reserve("key"));
        db.update("UPDATE con3_task SET state='IN_PROGRESS'");
        db.update("UPDATE con5_comparison_input SET input_sha=?", REQUEST); conflict(() -> reserve("key"));
        db.update("UPDATE con5_comparison_input SET input_sha=?", SHA);
        db.update("DELETE FROM con5_comparison_current"); conflict(() -> reserve("key"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_start_command",Integer.class));
    }
    @Test void deadlineChecksAndDispatchRecheckFailClosed() {
        conflict(() -> store.reserveExact(scope,actor,"key",REQUEST,managed,NOW));
        conflict(() -> store.reserveExact(scope,actor,"key",REQUEST,managed,UNTIL.plusSeconds(101)));
        var c = reserve("key").command();
        store = new ProductComparisonStartCommands(db,tx,Clock.fixed(UNTIL.plusSeconds(100),ZoneOffset.UTC));
        conflict(() -> store.dispatchOnce(c.commandId())); assertEquals("ACCEPTED",status(c.commandId()));
    }
    @Test void interruptedAcceptedAndUnknownCommandsCannotBeBypassedByNewKey() {
        var c = reserve("first").command(); conflict(() -> reserve("second"));
        store.markReconcile(c.commandId(),"RESPONSE_LOST"); conflict(() -> reserve("second"));
        assertTrue(store.dispatchOnce(c.commandId()).isEmpty());
        assertFalse(reserve("first").newlyReserved());
    }
    @Test void concurrentReserveAndDispatchHaveExactlyOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<ProductComparisonStartCommands.Reservation> action = () -> { barrier.await(5,TimeUnit.SECONDS); return reserve("same"); };
            var a = pool.submit(action); var b = pool.submit(action);
            var one = a.get(15,TimeUnit.SECONDS); var two = b.get(15,TimeUnit.SECONDS);
            assertEquals(one.command().commandId(),two.command().commandId());
            assertEquals(1,(one.newlyReserved()?1:0)+(two.newlyReserved()?1:0));
            var gate = new CyclicBarrier(2);
            Callable<Boolean> send = () -> { gate.await(5,TimeUnit.SECONDS); return store.dispatchOnce(one.command().commandId()).isPresent(); };
            var s1 = pool.submit(send); var s2 = pool.submit(send);
            assertEquals(1,(s1.get(15,TimeUnit.SECONDS)?1:0)+(s2.get(15,TimeUnit.SECONDS)?1:0));
        }
    }
    @Test void delegationWrongHumanServiceCandidateOrExpiryFailsClosed() {
        String id = dispatch(); delegation("delegate","A",17);
        db.update("UPDATE con3_worker_delegation SET human_subject='other'"); conflict(() -> store.bindDelegation(id,"delegate"));
        db.update("UPDATE con3_worker_delegation SET human_subject='human',service_subject='other'"); conflict(() -> store.bindDelegation(id,"delegate"));
        db.update("UPDATE con3_worker_delegation SET service_subject='worker',expires_at=?",ts(UNTIL.minusSeconds(1))); conflict(() -> store.bindDelegation(id,"delegate"));
        db.update("UPDATE con3_worker_delegation SET expires_at=?",ts(UNTIL));
        db.update("UPDATE con5_comparison_delegation SET candidate_id='B'"); conflict(() -> store.bindDelegation(id,"delegate"));
        assertNull(store.readExact(scope,actor,id).delegationId());
    }
    @Test void attemptRequiresExactFenceWorkerAndLeaseAndRetainsBindingsOnUnknown() {
        String id = dispatch(); delegation("delegate","A",17); store.bindDelegation(id,"delegate");
        attempt("attempt","A","delegate","other",1);
        conflict(() -> store.bindAttempt(id,new ProductComparisonStartCommands.AttemptBinding("attempt",1,UNTIL)));
        db.update("UPDATE con5_candidate_attempt SET worker_subject='worker'");
        conflict(() -> store.bindAttempt(id,new ProductComparisonStartCommands.AttemptBinding("attempt",2,UNTIL)));
        conflict(() -> store.bindAttempt(id,new ProductComparisonStartCommands.AttemptBinding("attempt",1,UNTIL.minusSeconds(1))));
        assertNull(store.readExact(scope,actor,id).attemptId());
        store.bindAttempt(id,new ProductComparisonStartCommands.AttemptBinding("attempt",1,UNTIL));
        var unknown = store.markReconcile(id,"TIMEOUT"); assertEquals("attempt",unknown.attemptId());
        assertEquals("TIMEOUT",store.markReconcile(id,"SECOND_ERROR").errorCode());
        assertTrue(store.dispatchOnce(id).isEmpty()); conflict(() -> reserve("new-key"));
    }
    @Test void actualMigrationHas21ColumnsAndEnforcesForeignKeysAndNullableGroup() {
        assertEquals(21,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='CON5_START_COMMAND'",Integer.class));
        var id = reserve("key").command().commandId();
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET configuration_version=NULL WHERE command_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET delegation_id='absent' WHERE command_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET attempt_id='absent' WHERE command_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET comparison_id='absent' WHERE command_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET candidate_id='absent' WHERE command_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET fencing_token=1 WHERE command_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update("UPDATE con5_start_command SET status='COMMITTED' WHERE command_id=?",id));
    }
    @Test void missingTableConfigurationColumnAndAmbientTransactionsFailClosed() {
        conflict(() -> tx.execute(status -> reserve("key")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_start_command",Integer.class));
        db.execute("ALTER TABLE con5_start_command DROP COLUMN configuration_version");
        assertThrows(org.springframework.dao.DataAccessException.class,() -> new ProductComparisonStartCommands(db,tx,Clock.fixed(NOW,ZoneOffset.UTC)));
        db.execute("DROP TABLE con5_start_command");
        assertThrows(org.springframework.dao.DataAccessException.class,() -> new ProductComparisonStartCommands(db,tx,Clock.fixed(NOW,ZoneOffset.UTC)));
    }
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    /** Exact native storage closure only. No AI/professional judgement is produced by this fixture. */
    private String receipt(String id) throws Exception {
        var c = store.readExact(scope,actor,id); String response = "{\"response\":\"engineering\"}";
        String coords = "\"taskId\":\"task\",\"comparisonId\":\"input\",\"candidateId\":\"A\",\"inputSha\":\""+SHA+"\",\"attemptId\":\"attempt\",\"token\":1,\"reportId\":\"report\"";
        String report = "{"+coords+",\"configurationVersion\":\""+managed.configurationVersion()+"\",\"workerSubject\":\"worker\",\"responseSha\":\""+hash(response)+"\",\"approvalSha\":\""+REQUEST+"\"}";
        String sha = hash(report), receipt = "{"+coords+",\"reportSha\":\""+sha+"\",\"status\":\"COMMITTED\"}";
        db.update("UPDATE con5_candidate_attempt SET status='COMMITTED' WHERE attempt_id=?",c.attemptId());
        db.update("UPDATE con5_candidate_lease SET status='COMMITTED' WHERE attempt_id=?",c.attemptId());
        db.update("UPDATE con5_comparison_candidate SET status='COMMITTED' WHERE candidate_id='A'");
        db.update("INSERT INTO con5_matrix_invocation VALUES('attempt',?,?,?,'COMMITTED',?,?,?)",REQUEST,REQUEST,managed.configurationVersion(),hash(response),response,ts(NOW));
        db.update("INSERT INTO con5_matrix_report VALUES('report','task','input','A','attempt',1,?,?,?,?,?)",SHA,sha,report,ts(NOW),ts(UNTIL));
        db.update("INSERT INTO con5_matrix_receipt VALUES('attempt','report',?,?,?)",sha,receipt,hash(receipt)); return sha;
    }
    @Test void onlyExactCommittedReceiptWithConfigurationVersionCanCommit() throws Exception {
        String id = bound(); conflict(() -> store.commitFromExactReceipt(id,"report",SHA));
        String sha = receipt(id); conflict(() -> store.commitFromExactReceipt(id,"report",SHA));
        db.update("UPDATE con5_matrix_invocation SET configuration_version='configuration-id-not-version'");
        conflict(() -> store.commitFromExactReceipt(id,"report",sha)); assertEquals("DISPATCHING",status(id));
        db.update("UPDATE con5_matrix_invocation SET configuration_version=?",managed.configurationVersion());
        assertEquals("COMMITTED",store.commitFromExactReceipt(id,"report",sha).status());
        assertEquals("COMMITTED",store.commitFromExactReceipt(id,"report",sha).status());
        conflict(() -> reserve("new-key"));
    }
    @Test void corruptedReceiptOrReportCoordinatesCannotCommitEvenWithUpdatedDigest() throws Exception {
        String id = bound(); String sha = receipt(id);
        String receipt = db.queryForObject("SELECT receipt_json FROM con5_matrix_receipt",String.class);
        db.update("UPDATE con5_matrix_receipt SET receipt_json=?",receipt+" ");
        conflict(() -> store.commitFromExactReceipt(id,"report",sha));
        String wrong = receipt.replace("\"candidateId\":\"A\"","\"candidateId\":\"B\"");
        db.update("UPDATE con5_matrix_receipt SET receipt_json=?,receipt_sha=?",wrong,hash(wrong));
        conflict(() -> store.commitFromExactReceipt(id,"report",sha));
        db.update("UPDATE con5_matrix_receipt SET receipt_json=?,receipt_sha=?",receipt,hash(receipt));
        String report = db.queryForObject("SELECT package_json FROM con5_matrix_report",String.class);
        wrong = report.replace("\"candidateId\":\"A\"","\"candidateId\":\"B\""); String wrongSha = hash(wrong);
        String changedReceipt = receipt.replace(sha,wrongSha);
        db.update("UPDATE con5_matrix_report SET package_json=?,report_sha=?",wrong,wrongSha);
        db.update("UPDATE con5_matrix_receipt SET report_sha=?,receipt_json=?,receipt_sha=?",wrongSha,changedReceipt,hash(changedReceipt));
        conflict(() -> store.commitFromExactReceipt(id,"report",wrongSha)); assertEquals("DISPATCHING",status(id));
    }
    @Test void cancellationProjectsWithoutOverwritingCommittedHistory() throws Exception {
        String id = bound(); String sha = receipt(id); store.commitFromExactReceipt(id,"report",sha);
        db.update("UPDATE con3_task SET cancel_epoch=1");
        assertEquals("CANCELLED",store.readExact(scope,actor,id).status()); assertEquals("COMMITTED",status(id));
        assertEquals("attempt",store.lookupOriginalKey("task",actor,"start").orElseThrow().attemptId());
        conflict(() -> store.commitFromExactReceipt(id,"report",sha));
    }
    @Test void candidateAFinalResultSurvivesBKnownFailureAndExplicitNewKey() throws Exception {
        String a = bound(); String sha = receipt(a); store.commitFromExactReceipt(a,"report",sha);
        var bScope = new ProductComparisonStartCommands.Scope("task","input","B",SHA,5,0);
        var b = store.reserveExact(bScope,actor,"b-key",REQUEST,managed,UNTIL).command(); store.dispatchOnce(b.commandId());
        delegation("b-delegation","B",17); store.bindDelegation(b.commandId(),"b-delegation");
        attempt("b-attempt","B","b-delegation","worker",2);
        store.bindAttempt(b.commandId(),new ProductComparisonStartCommands.AttemptBinding("b-attempt",2,UNTIL));
        db.update("UPDATE con5_candidate_attempt SET status='FAILED' WHERE attempt_id='b-attempt'");
        db.update("UPDATE con5_candidate_lease SET status='FAILED' WHERE attempt_id='b-attempt'");
        db.update("UPDATE con5_comparison_candidate SET status='FAILED' WHERE candidate_id='B'");
        assertEquals("FAILED",store.lookupOriginalKey("task",actor,"b-key").orElseThrow().status());
        assertFalse(store.reserveExact(bScope,actor,"b-key",REQUEST,managed,UNTIL).newlyReserved());
        assertTrue(store.reserveExact(bScope,actor,"b-explicit-retry",REQUEST,managed,UNTIL).newlyReserved());
        assertEquals("COMMITTED",status(a)); assertEquals(sha,db.queryForObject("SELECT report_sha FROM con5_matrix_report WHERE report_id='report'",String.class));
    }
}
