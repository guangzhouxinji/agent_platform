package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductWorkerExecutionRequestsTest {
    @Test void expiredAcquisitionCommitsReconciliationStateWithoutIssuingReceipt() {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:worker-expiry-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_execution_request (task_id VARCHAR(36),"
                + "service_subject VARCHAR(200),request_id VARCHAR(180),request_sha CHAR(64),"
                + "input_id VARCHAR(36),delegation_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "fencing_token BIGINT,lease_until TIMESTAMP,created_at TIMESTAMP,"
                + "PRIMARY KEY(task_id,service_subject,request_id))");
        db.execute("CREATE TABLE con3_execution_lease (task_id VARCHAR(36),status VARCHAR(24))");
        db.update("INSERT INTO con3_execution_lease VALUES('task','ACTIVE')");
        var access = mock(ProductWorkerAccess.class);
        var execution = mock(ProductTaskWorkerExecution.class);
        var request = mock(HttpServletRequest.class);
        doReturn(new ProductWorkerAccess.Authorized("worker", "task", "input", 2))
                .when(access).require(request, "task", "input", "delegation");
        doAnswer(invocation -> {
            db.update("UPDATE con3_execution_lease SET status='RECONCILE_REQUIRED' WHERE task_id='task'");
            throw new ProductTaskExecutionFence.ReconciliationRequired();
        }).when(execution).acquire(eq(request), eq("task"), eq("input"), eq("delegation"),
                any(Duration.class));
        var requests = new ProductWorkerExecutionRequests(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), access, execution,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> requests.acquire(request, "task", "input", "delegation",
                "expired-request")).isInstanceOf(ProductTaskExecutionFence.ReconciliationRequired.class);
        assertThat(db.queryForObject("SELECT status FROM con3_execution_lease WHERE task_id='task'",
                String.class)).isEqualTo("RECONCILE_REQUIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_execution_request", Integer.class))
                .isZero();
    }

    @Test void reconciliationHasSeparateIdentityAndOnlyReplaysCurrentAuthorizedLease() {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:worker-reconcile-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_execution_request (task_id VARCHAR(36),"
                + "service_subject VARCHAR(200),request_id VARCHAR(180),request_sha CHAR(64),"
                + "input_id VARCHAR(36),delegation_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "fencing_token BIGINT,lease_until TIMESTAMP,created_at TIMESTAMP,"
                + "PRIMARY KEY(task_id,service_subject,request_id))");
        db.execute("CREATE TABLE con3_execution_lease (task_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "input_id VARCHAR(36),worker_ref VARCHAR(200),fencing_token BIGINT,"
                + "status VARCHAR(24),lease_until TIMESTAMP)");
        var access = mock(ProductWorkerAccess.class);
        var execution = mock(ProductTaskWorkerExecution.class);
        var request = mock(HttpServletRequest.class);
        var worker = new ProductWorkerAccess.Authorized("worker", "task", "input", 2);
        doReturn(worker).when(access).require(request, "task", "input", "delegation");
        var newLease = new ProductTaskExecutionFence.Lease("new-attempt", 2, now.plusSeconds(30));
        when(execution.reconcile(eq(request), eq("task"), eq("input"), eq("delegation"),
                any(Duration.class))).thenReturn(newLease);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var requests = new ProductWorkerExecutionRequests(db, transactions, access, execution,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(requests.reconcile(request, "task", "input", "delegation", "same-key"))
                .isEqualTo(new ProductWorkerExecutionRequests.Receipt(
                        "new-attempt", 2, now.plusSeconds(30)));
        db.update("INSERT INTO con3_execution_lease VALUES(?,?,?,?,?,?,?)", "task", "new-attempt",
                "input", "worker", 2, "ACTIVE", Timestamp.from(now.plusSeconds(30)));
        assertThat(requests.reconcile(request, "task", "input", "delegation", "same-key"))
                .isEqualTo(new ProductWorkerExecutionRequests.Receipt(
                        "new-attempt", 2, now.plusSeconds(30)));
        verify(execution).reconcile(eq(request), eq("task"), eq("input"), eq("delegation"),
                any(Duration.class));
        assertThatThrownBy(() -> requests.acquire(request, "task", "input", "delegation",
                "same-key")).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con3_execution_lease SET status='RECONCILE_REQUIRED'");
        assertThatThrownBy(() -> requests.reconcile(request, "task", "input", "delegation",
                "same-key")).isInstanceOf(IllegalStateException.class);
        doReturn(worker).when(access).require(request, "task", "input", "delegation");
        when(access.require(request, "task", "input", "delegation"))
                .thenThrow(new IllegalStateException("Revoked"));
        assertThatThrownBy(() -> requests.reconcile(request, "task", "input", "delegation",
                "same-key")).isInstanceOf(IllegalStateException.class);
    }

    @Test void currentReplayIsStableButConflictAndStaleLeaseFailClosed() {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:worker-request-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_execution_request (task_id VARCHAR(36),"
                + "service_subject VARCHAR(200),request_id VARCHAR(180),request_sha CHAR(64),"
                + "input_id VARCHAR(36),delegation_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "fencing_token BIGINT,lease_until TIMESTAMP,created_at TIMESTAMP,"
                + "PRIMARY KEY(task_id,service_subject,request_id))");
        db.execute("CREATE TABLE con3_execution_lease (task_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "input_id VARCHAR(36),worker_ref VARCHAR(200),fencing_token BIGINT,"
                + "status VARCHAR(24),lease_until TIMESTAMP)");
        var access = mock(ProductWorkerAccess.class);
        var execution = mock(ProductTaskWorkerExecution.class);
        var request = mock(HttpServletRequest.class);
        doReturn(new ProductWorkerAccess.Authorized("worker", "task", "input", 2))
                .when(access).require(request, "task", "input", "delegation");
        when(access.require(request, "task", "other", "delegation"))
                .thenReturn(new ProductWorkerAccess.Authorized("worker", "task", "other", 2));
        var lease = new ProductTaskExecutionFence.Lease("attempt", 1, now.plusSeconds(30));
        when(execution.acquire(eq(request), eq("task"), eq("input"), eq("delegation"),
                any(Duration.class))).thenReturn(lease);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var requests = new ProductWorkerExecutionRequests(db, transactions, access, execution,
                Clock.fixed(now, ZoneOffset.UTC));

        var first = requests.acquire(request, "task", "input", "delegation", "request-one");
        assertThat(first).isEqualTo(new ProductWorkerExecutionRequests.Receipt(
                "attempt", 1, now.plusSeconds(30)));
        db.update("INSERT INTO con3_execution_lease VALUES(?,?,?,?,?,?,?)", "task", "attempt",
                "input", "worker", 1, "ACTIVE", Timestamp.from(now.plusSeconds(30)));
        assertThat(requests.acquire(request, "task", "input", "delegation", "request-one"))
                .isEqualTo(first);
        verify(execution).acquire(eq(request), eq("task"), eq("input"), eq("delegation"),
                any(Duration.class));
        assertThatThrownBy(() -> requests.acquire(request, "task", "other", "delegation",
                "request-one")).isInstanceOf(IllegalStateException.class);
        when(access.require(request, "task", "input", "delegation"))
                .thenThrow(new IllegalStateException("Current grant or member revoked"));
        assertThatThrownBy(() -> requests.acquire(request, "task", "input", "delegation",
                "request-one")).isInstanceOf(IllegalStateException.class);
        doReturn(new ProductWorkerAccess.Authorized("worker", "task", "input", 2))
                .when(access).require(request, "task", "input", "delegation");
        db.update("UPDATE con3_execution_lease SET status='CANCELLED' WHERE task_id='task'");
        assertThatThrownBy(() -> requests.acquire(request, "task", "input", "delegation",
                "request-one")).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_execution_request",
                Integer.class)).isEqualTo(1);
    }
}
