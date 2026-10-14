package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductTrademarkProjectionStoreTest {
    private ProductTrademarkProjectionFixture fixture;
    @BeforeEach void setup() throws Exception { fixture = new ProductTrademarkProjectionFixture(); }

    @Test void originalsVersionsTokensAndAllRelationsArePersisted() {
        Input input = fixture.input(2); var selected = fixture.project(input);
        var verified = fixture.inTransaction(() -> fixture.store.verify(selected));
        assertEquals(2, verified.records().size());
        assertEquals(input.registeredSourceVersion(), verified.capture().registeredSourceVersion());
        assertEquals("00012A", verified.records().values().iterator().next().observation().registrationNumber());
        assertEquals(4, count("kb_trademark_party")); assertEquals(4, count("kb_trademark_class"));
        assertEquals(20, count("kb_field_provenance"));
        assertEquals(new String(input.originals().getFirst().bytes(), java.nio.charset.StandardCharsets.UTF_8),
                fixture.db.queryForObject("SELECT raw_content FROM kb_raw_record WHERE source_record_id='r00'", String.class));
        assertEquals("PRODUCT_SOURCE_LOCAL", fixture.db.queryForObject("SELECT data_mode FROM kb_record_version WHERE stable_record_key='r00'", String.class));
    }
    @Test void sameKeyReplaysReceiptWithoutAnyNewRows() {
        var input = fixture.input(2); var selected = fixture.project(input);
        var replay = fixture.inTransaction(() -> fixture.store.project(input, fixture.context, "original-key"));
        assertEquals(selected.receiptId(), replay.id()); assertEquals(selected.requestSha(), replay.requestSha());
        for (String table : List.of("con1tm_capture_request", "kb_ingest_batch")) assertEquals(1, count(table));
        for (String table : List.of("kb_raw_record", "kb_record_version", "kb_trademark", "con1tm_record_binding")) assertEquals(2, count(table));
    }
    @Test void sameKeyChangedCaptureConflictsWithoutOverwrite() {
        fixture.project(fixture.input(2));
        assertEquals("TRADEMARK_REQUEST_CONFLICT", assertThrows(IllegalStateException.class,
                () -> fixture.inTransaction(() -> fixture.store.project(fixture.input(2, detail -> detail.put("name", "Changed")),
                        fixture.context, "original-key"))).getMessage());
        assertEquals("Brand %_ 0", fixture.db.queryForObject("SELECT mark_name FROM kb_trademark WHERE record_version_id=?", String.class, fixture.versionId("r00")));
        assertEquals(1, count("con1tm_capture_request"));
    }
    @Test void approvalShaAloneChangesRequestDigestAndNativeTamperIsRejected() {
        var input = fixture.input(1); var selected = fixture.project(input); var old = fixture.context;
        var changed = new ProductTrademarkProjectionStore.Context(old.organization(), old.taskId(), old.authorizationVersion(), old.maintainer(),
                old.certificateSha(), old.decisionId(), old.sourceEpoch(), old.sourceRowVersion(), old.retentionRef(), old.retentionVersion(),
                old.retentionSha(), old.approvedScopeSha(), "e".repeat(64), old.expiresAt());
        assertEquals("TRADEMARK_REQUEST_CONFLICT", assertThrows(IllegalStateException.class,
                () -> fixture.inTransaction(() -> fixture.store.project(input, changed, "original-key"))).getMessage());
        fixture.db.update("UPDATE con1tm_capture_request SET approval_sha=?", "e".repeat(64));
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.verify(selected)));
    }
    @Test void reusedShortOriginCannotBeExtendedByChangingBothNativeDeadlines() {
        var input = fixture.input(1); var a = fixture.project(input); var old = fixture.context;
        var longContext = new ProductTrademarkProjectionStore.Context(old.organization(), old.taskId(), old.authorizationVersion(), old.maintainer(),
                old.certificateSha(), old.decisionId(), old.sourceEpoch(), old.sourceRowVersion(), old.retentionRef(), old.retentionVersion(),
                old.retentionSha(), old.approvedScopeSha(), old.approvalSha(), old.expiresAt().plusSeconds(3600));
        var b = fixture.inTransaction(() -> fixture.store.project(input, longContext, "long-key"));
        var selected = new ProductTrademarkProjectionStore.Selection(input, b.id(), b.requestSha());
        assertEquals(old.expiresAt(), fixture.inTransaction(() -> fixture.store.verify(selected)).expiresAt());
        fixture.db.update("UPDATE con1tm_record_binding SET expires_at=?", java.sql.Timestamp.from(longContext.expiresAt()));
        fixture.db.update("UPDATE con1tm_capture_request SET expires_at=? WHERE receipt_id=?", java.sql.Timestamp.from(longContext.expiresAt()), a.receiptId());
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.verify(selected)));
    }
    @Test void expiredReceiptRejectsBeforeActualJdbcPayloadClobRead() {
        var selected = fixture.project(fixture.input(1)); fixture.clock.now = ProductTrademarkProjectionFixture.UNTIL;
        javax.sql.DataSource guarded = new org.springframework.jdbc.datasource.DelegatingDataSource(fixture.source) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                        (proxy, method, arguments) -> {
                            Object result = invoke(connection, method, arguments);
                            if (!(result instanceof java.sql.PreparedStatement statement)) return result;
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                                    (p, m, a) -> {
                                        Object value = invoke(statement, m, a);
                                        if (!(value instanceof java.sql.ResultSet rs)) return value;
                                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.ResultSet.class},
                                                (rp, rm, ra) -> {
                                                    if (rm.getName().equals("getString") && ra != null && Integer.valueOf(17).equals(ra[0]))
                                                        throw new AssertionError("Expired request payload CLOB was read");
                                                    return invoke(rs, rm, ra);
                                                });
                                    });
                        });
            }
        };
        var db = new org.springframework.jdbc.core.JdbcTemplate(guarded);
        var store = new ProductTrademarkProjectionStore(db, fixture.json, fixture.clock);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(guarded));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> store.verify(selected)));
    }
    @Test void newKeyCanReuseIdenticalProjectionWithShorterRequestDeadlineWithoutExtendingOriginal() {
        var input = fixture.input(2); fixture.project(input);
        var old = fixture.context;
        var shorter = new ProductTrademarkProjectionStore.Context(old.organization(), old.taskId(), old.authorizationVersion(), old.maintainer(),
                old.certificateSha(), old.decisionId(), old.sourceEpoch(), old.sourceRowVersion(), old.retentionRef(), old.retentionVersion(),
                old.retentionSha(), old.approvedScopeSha(), old.approvalSha(), ProductTrademarkProjectionFixture.NOW.plusSeconds(1800));
        var receipt = fixture.inTransaction(() -> fixture.store.project(input, shorter, "second-key"));
        var selected = new ProductTrademarkProjectionStore.Selection(input, receipt.id(), receipt.requestSha());
        assertEquals(2, fixture.inTransaction(() -> fixture.store.verify(selected)).records().size());
        assertEquals(2, count("kb_record_version")); assertEquals(2, count("con1tm_capture_request"));
        assertEquals(ProductTrademarkProjectionFixture.UNTIL,
                fixture.db.queryForObject("SELECT MIN(expires_at) FROM con1tm_record_binding", java.sql.Timestamp.class).toInstant());
        fixture.clock.now = shorter.expiresAt();
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.verify(selected)));
    }
    @Test void sqlFailureOnSecondMemberRollsBackAllRowsEvenWhenCallerCatchesFailure() {
        Input input = fixture.input(2, detail -> { if (detail.path("sourceRecordId").asText().equals("r01")) detail.put("name", "x".repeat(1001)); });
        fixture.inTransaction(() -> {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> fixture.store.project(input, fixture.context, "failure-key"));
            return true;
        });
        for (String table : List.of("con1tm_capture_request", "con1tm_record_binding", "kb_ingest_batch", "kb_raw_record", "kb_record_version",
                "kb_trademark", "kb_trademark_party", "kb_trademark_class", "kb_field_provenance")) assertEquals(0, count(table), table);
        assertEquals(1, count("kb_source"));
    }
    @Test void failedSavepointRollbackMarksActualTransactionRollbackOnlyWhenCallerCatches() {
        javax.sql.DataSource fault = new org.springframework.jdbc.datasource.DelegatingDataSource(fixture.source) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var actual = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("rollback") && arguments != null && arguments.length == 1)
                                throw new java.sql.SQLException("Injected savepoint rollback failure");
                            try { return method.invoke(actual, arguments); }
                            catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                        });
            }
        };
        var db = new org.springframework.jdbc.core.JdbcTemplate(fault);
        var store = new ProductTrademarkProjectionStore(db, fixture.json, fixture.clock);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(fault));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        Input input = fixture.input(2, detail -> { if (detail.path("sourceRecordId").asText().equals("r01")) detail.put("name", "x".repeat(1001)); });
        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class, () -> transaction.execute(status -> {
            var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> store.project(input, fixture.context, "fault-key"));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(((org.springframework.jdbc.datasource.ConnectionHolder)
                    org.springframework.transaction.support.TransactionSynchronizationManager.getResource(fault)).isRollbackOnly());
            return true;
        }));
        for (String table : List.of("con1tm_capture_request", "con1tm_record_binding", "kb_ingest_batch", "kb_raw_record", "kb_record_version",
                "kb_trademark", "kb_trademark_party", "kb_trademark_class", "kb_field_provenance")) assertEquals(0, count(table), table);
    }
    @Test void nullNameAndPartialDatesRemainMissingAndDoNotInventSqlDays() {
        var selected = fixture.project(fixture.input(1, detail -> detail.putNull("name").put("registrationDate", "2024")));
        var view = fixture.inTransaction(() -> fixture.store.verify(selected)).records().values().iterator().next().observation();
        assertNull(view.name()); assertEquals("YEAR", view.registered().precision()); assertNull(view.registered().day());
        assertNull(fixture.db.queryForObject("SELECT mark_name FROM kb_trademark", String.class));
        assertNull(fixture.db.queryForObject("SELECT registration_date FROM kb_trademark", java.sql.Date.class));
        assertEquals("SOURCE_NOT_PROVIDED", fixture.db.queryForObject("SELECT value_state FROM kb_field_provenance WHERE field_path='/name'", String.class));
    }
    @Test void currentExactDataSourceTransactionIsRequired() {
        assertThrows(IllegalStateException.class, () -> fixture.store.project(fixture.input(1), fixture.context, "key"));
        assertEquals(0, count("con1tm_capture_request"));
        fixture.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.project(fixture.input(1), fixture.context, "key")));
        assertEquals(0, count("con1tm_capture_request"));
    }
    @Test void expiredProjectionDoesNotReplayOrExtendDeadline() {
        var input = fixture.input(1); var selected = fixture.project(input); fixture.clock.now = ProductTrademarkProjectionFixture.UNTIL;
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.verify(selected)));
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.project(input, fixture.context, "original-key")));
        assertEquals(ProductTrademarkProjectionFixture.UNTIL,
                fixture.db.queryForObject("SELECT expires_at FROM con1tm_capture_request", java.sql.Timestamp.class).toInstant());
        assertEquals(1, count("con1tm_capture_request"));
    }
    @Test void changedOriginalBytesAndMissingBindingCloseWholeCapture() {
        var input = fixture.input(2); var selected = fixture.project(input);
        fixture.db.update("DELETE FROM con1tm_record_binding WHERE source_record_id='r01'");
        assertThrows(IllegalStateException.class, () -> fixture.inTransaction(() -> fixture.store.verify(selected)));
        var raw = input.originals().getFirst(); byte[] changed = raw.bytes(); changed[0] = ' ';
        Input forged = new Input(input.sourceId(), input.contractVersion(), input.registeredSourceVersion(), input.manifest(), input.records(),
                List.of(new Artifact(raw.ref(), raw.sha256(), raw.byteLength(), changed), input.originals().get(1)), input.mapping(), input.natureEvidence());
        assertThrows(InvalidCapture.class, () -> fixture.inTransaction(() -> fixture.store.verify(
                new ProductTrademarkProjectionStore.Selection(forged, selected.receiptId(), selected.requestSha()))));
    }
    private int count(String table) { return fixture.db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
    }
}
