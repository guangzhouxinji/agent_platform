package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductHumanMaintenanceProofTest {
    @Test void lockedTypedIdentityHasFiniteCertificateAndCreatesNoGenericAudit() throws Exception {
        var f = new Fixture();
        var escaped = f.proof.withRetentionProof(f.request, lease -> {
            assertThat(lease.subject()).isEqualTo("retention-operator");
            assertThat(lease.organization()).isEqualTo("organization");
            assertThat(lease.certificateSha()).isEqualTo(f.sha);
            assertThat(lease.operator()).isEqualTo("retention-operator#" + f.sha);
            assertThat(lease.version()).isEqualTo(1);
            assertThat(lease.certificateExpiresAt()).isEqualTo(f.expires);
            lease.requireCurrent();
            return lease;
        });
        assertThatThrownBy(escaped::requireCurrent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(escaped::operator).isInstanceOf(IllegalStateException.class);
        assertThat(f.auditCount()).isZero();
    }

    @Test void genericRoleAndOrganizationMethodsCannotBypassLeaseOrWriteAudit() throws Exception {
        var f = new Fixture();
        assertThatThrownBy(() -> f.proof.require(f.request, ProductMaintenanceProof.Role.HUMAN_RETENTION))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.proof.requireOrganization(f.request, ProductMaintenanceProof.Role.HUMAN_RETENTION))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.auditCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBJECTS", "GRANTS", "KEYS", "SOURCES", "SUBJECTS,GRANTS,KEYS,SOURCES"})
    void existingRolesDoNotImplyRetention(String roles) throws Exception {
        var f = new Fixture();
        f.db.update("UPDATE id2_maintainer SET allowed_roles=?", roles);
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> "unreachable"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(f.auditCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"insecure", "header-only", "empty-chain", "null-peer", "wrong-cert", "expired", "future"})
    void certificateFailuresNeverEnterCallback(String failure) throws Exception {
        var f = new Fixture();
        switch (failure) {
            case "insecure" -> f.request.setSecure(false);
            case "header-only" -> {
                f.request.removeAttribute("jakarta.servlet.request.X509Certificate");
                f.request.addHeader("X-Maintainer", "retention-operator");
            }
            case "empty-chain" -> f.request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[0]);
            case "null-peer" -> f.request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {null});
            case "wrong-cert" -> when(f.peer.getEncoded()).thenReturn(new byte[] {1, 2, 3});
            case "expired" -> when(f.peer.getNotAfter()).thenReturn(Date.from(Instant.now().minusSeconds(1)));
            case "future" -> when(f.peer.getNotBefore()).thenReturn(Date.from(Instant.now().plusSeconds(60)));
            default -> throw new AssertionError(failure);
        }
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> { throw new AssertionError("callback entered"); }))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(f.auditCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"organization", "subject", "certificate", "version", "role", "status", "missing"})
    void callbackReturnRechecksExactRegistrationEvenForSameVersionChanges(String field) throws Exception {
        var f = new Fixture();
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> {
            switch (field) {
                case "organization" -> f.db.update("UPDATE id2_maintainer SET organization='other'");
                case "subject" -> f.db.update("UPDATE id2_maintainer SET subject_ref='other'");
                case "certificate" -> f.db.update("UPDATE id2_maintainer SET certificate_sha=?", "a".repeat(64));
                case "version" -> f.db.update("UPDATE id2_maintainer SET version=2");
                case "role" -> f.db.update("UPDATE id2_maintainer SET allowed_roles='KEYS'");
                case "status" -> f.db.update("UPDATE id2_maintainer SET status='REVOKED'");
                case "missing" -> f.db.update("DELETE FROM id2_maintainer");
                default -> throw new AssertionError(field);
            }
            return "must not commit";
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(f.db.queryForObject("SELECT version FROM id2_maintainer", Long.class)).isEqualTo(1);
        assertThat(f.db.queryForObject("SELECT organization FROM id2_maintainer", String.class)).isEqualTo("organization");
    }

    @Test void certificateInvalidatedDuringCallbackRejectsFinalBoundary() throws Exception {
        var f = new Fixture();
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> {
            try { doThrow(new CertificateExpiredException()).when(f.peer).checkValidity(); }
            catch (Exception invalid) { throw new AssertionError(invalid); }
            return "must not return";
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void callbackFailureInvalidatesEscapedLeaseAndReleasesRowLock() throws Exception {
        var f = new Fixture();
        var escaped = new AtomicReference<ProductMaintenanceProof.RetentionLease>();
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> {
            escaped.set(lease);
            throw new IllegalArgumentException("callback failed");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("callback failed");
        assertThatThrownBy(escaped.get()::requireCurrent).isInstanceOf(IllegalStateException.class);
        f.second.revoke(f.request, 1);
        assertThat(f.db.queryForObject("SELECT status FROM id2_maintainer", String.class)).isEqualTo("REVOKED");
    }

    @Test void leaseIsThreadBoundEvenWhileOriginalCallbackStillHoldsLock() throws Exception {
        var f = new Fixture();
        try (var executor = Executors.newSingleThreadExecutor()) {
            f.proof.withRetentionProof(f.request, lease -> {
                try {
                    executor.submit(() -> assertThatThrownBy(lease::requireCurrent)
                            .isInstanceOf(IllegalStateException.class)).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                lease.requireCurrent();
                return null;
            });
        }
    }

    @Test void actualOrSynchronizationOnlyOrBoundResourceAmbientStateIsRefused() throws Exception {
        var f = new Fixture();
        new TransactionTemplate(f.manager).executeWithoutResult(status -> assertAmbientRefused(f));
        TransactionSynchronizationManager.initSynchronization();
        try { assertAmbientRefused(f); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        Object key = new Object();
        TransactionSynchronizationManager.bindResource(key, new Object());
        try { assertAmbientRefused(f); }
        finally { TransactionSynchronizationManager.unbindResource(key); }
        assertThat(f.auditCount()).isZero();
    }

    @Test void wrongAuthorityManagerIsRefusedBeforeAnyCallback() throws Exception {
        var f = new Fixture();
        var wrong = new ProductMaintenanceProof(f.db, new TransactionTemplate(
                new DataSourceTransactionManager(source("wrong-authority"))));
        assertThatThrownBy(() -> wrong.withRetentionProof(f.request, lease -> { throw new AssertionError("callback entered"); }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void revokedFirstRefusesRetentionAndFreshReplacementHasItsOwnIdentity() throws Exception {
        var f = new Fixture();
        f.second.revoke(f.request, 1);
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> "unreachable"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        f.db.update("UPDATE id2_maintainer SET subject_ref='replacement',status='ACTIVE',version=3");
        assertThat(f.proof.withRetentionProof(f.request, ProductMaintenanceProof.RetentionLease::subject))
                .isEqualTo("replacement");
    }

    @Test void realSecondConnectionRevocationWaitsThroughActualIndependentMainCommit() throws Exception {
        var f = new Fixture();
        var main = new JdbcTemplate(source("retention-main"));
        main.execute("CREATE TABLE commit_evidence(id INTEGER PRIMARY KEY)");
        var mainTransactions = new TransactionTemplate(new DataSourceTransactionManager(main.getDataSource()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            f.proof.withRetentionProof(f.request, lease -> {
                var revocation = executor.submit(() -> f.second.revoke(f.request, 1));
                try {
                    assertThat(f.revokeUpdating.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> revocation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    mainTransactions.executeWithoutResult(status -> {
                        lease.requireCurrent();
                        main.update("INSERT INTO commit_evidence VALUES(1)");
                    });
                    assertThat(main.queryForObject("SELECT COUNT(*) FROM commit_evidence", Integer.class)).isEqualTo(1);
                    lease.requireCurrent();
                    assertThatThrownBy(() -> revocation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    f.revocation.set(revocation);
                } catch (Exception failure) { throw new AssertionError(failure); }
                return null;
            });
            f.revocation.get().get(5, TimeUnit.SECONDS);
        }
        assertThat(f.db.queryForObject("SELECT status FROM id2_maintainer", String.class)).isEqualTo("REVOKED");
        assertThat(main.queryForObject("SELECT COUNT(*) FROM commit_evidence", Integer.class)).isEqualTo(1);
    }

    private static void assertAmbientRefused(Fixture f) {
        assertThatThrownBy(() -> f.proof.withRetentionProof(f.request, lease -> { throw new AssertionError("callback entered"); }))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JdbcDataSource source(String name) {
        var result = new JdbcDataSource();
        result.setURL("jdbc:h2:mem:" + name + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return result;
    }

    private static final class Fixture {
        final JdbcDataSource source = source("retention-authority");
        final JdbcTemplate db = new JdbcTemplate(source);
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        final X509Certificate peer = mock(X509Certificate.class);
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final CountDownLatch revokeUpdating = new CountDownLatch(1);
        final AtomicReference<java.util.concurrent.Future<?>> revocation = new AtomicReference<>();
        final Instant expires = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        final String sha;
        final ProductMaintenanceProof proof;
        final ProductMaintenanceProof second;

        Fixture() throws Exception {
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            }
            byte[] encoded = "owned-retention-certificate".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            when(peer.getEncoded()).thenReturn(encoded);
            when(peer.getNotBefore()).thenReturn(Date.from(Instant.now().minusSeconds(60)));
            when(peer.getNotAfter()).thenReturn(Date.from(expires));
            request.setSecure(true);
            request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer});
            db.update("INSERT INTO id2_maintainer VALUES(1,?,?,?,'HUMAN_RETENTION,KEYS','ACTIVE',1)",
                    "retention-operator", "organization", sha);
            proof = new ProductMaintenanceProof(db, new TransactionTemplate(manager));
            var observed = new JdbcTemplate(source) {
                @Override public int update(String sql, Object... args) {
                    if (sql.startsWith("UPDATE id2_maintainer SET status='REVOKED'")) revokeUpdating.countDown();
                    return super.update(sql, args);
                }
            };
            second = new ProductMaintenanceProof(observed, new TransactionTemplate(new DataSourceTransactionManager(source)));
        }

        int auditCount() { return db.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit", Integer.class); }
    }
}
