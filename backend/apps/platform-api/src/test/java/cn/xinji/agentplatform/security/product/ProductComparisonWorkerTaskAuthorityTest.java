package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

/** Real SQL predicate checks in isolated H2, not physical TLS/DM8 or business acceptance. */
class ProductComparisonWorkerTaskAuthorityTest {
    private static final String ISSUER = "https://comparison-worker.example.org";
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(600);
    private static final byte[] CERT_BYTES = "isolated-certificate-fixture".getBytes(StandardCharsets.UTF_8);
    private JdbcTemplate db;
    private ProductGrantWatermark grants;
    private ProductTaskDirectory directory;
    private ProductTaskDirectory.Task task;
    private MockHttpServletRequest request;
    private X509Certificate certificate;
    private ProductComparisonWorkerTaskAuthority authority;

    @BeforeEach void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:comparison-task-authority-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(source);
        // Deliberately omit CHECK/FK constraints so each corrupted coordinate tests the authorization
        // predicate itself, rather than being rejected by a fixture constraint before require runs.
        db.execute("CREATE TABLE id2_subject (issuer VARCHAR(300),subject_id VARCHAR(200),"
                + "status VARCHAR(16),subject_kind VARCHAR(16),password_hash VARCHAR(200))");
        db.execute("CREATE TABLE id2_workload (issuer VARCHAR(300),subject_id VARCHAR(200),"
                + "certificate_sha VARCHAR(64),organization VARCHAR(200),status VARCHAR(16),"
                + "workload_kind VARCHAR(24),allowed_purpose VARCHAR(40),expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_grant (grant_id VARCHAR(36),issuer VARCHAR(300),"
                + "subject_id VARCHAR(200),organization VARCHAR(200),domain_code VARCHAR(40),"
                + "task_scope VARCHAR(200),data_scope VARCHAR(300),resource_type VARCHAR(40),"
                + "resource_id VARCHAR(200),resource_version BIGINT,action_code VARCHAR(40),"
                + "purpose VARCHAR(40),grant_generation BIGINT,status VARCHAR(16),expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_current_epoch (issuer VARCHAR(300),subject_id VARCHAR(200),"
                + "organization VARCHAR(200),domain_code VARCHAR(40),resource_type VARCHAR(40),"
                + "resource_id VARCHAR(200),action_code VARCHAR(40),purpose VARCHAR(40),"
                + "authorization_epoch BIGINT,current_grant_id VARCHAR(36))");
        db.execute("CREATE TABLE con3_worker_delegation (delegation_id VARCHAR(36),task_id VARCHAR(36),"
                + "organization_id VARCHAR(200),data_scope VARCHAR(300),authorization_version BIGINT,"
                + "human_issuer VARCHAR(300),human_subject VARCHAR(200),service_issuer VARCHAR(300),"
                + "service_subject VARCHAR(200),human_grant_id VARCHAR(36),service_grant_id VARCHAR(36),"
                + "status VARCHAR(16),expires_at TIMESTAMP)");
        db.update("INSERT INTO id2_subject VALUES(?,?,?,?,?)", ISSUER, "worker", "ACTIVE", "SERVICE", null);
        db.update("INSERT INTO id2_subject VALUES(?,?,?,?,?)", ISSUER, "human", "ACTIVE", "HUMAN", "hash");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CERT_BYTES));
        db.update("INSERT INTO id2_workload VALUES(?,?,?,?,?,?,?,?)", ISSUER, "worker", fingerprint,
                "org", "ACTIVE", "AGENT", "TASK_WORK", Timestamp.from(UNTIL));
        for (String subject : List.of("worker", "human")) {
            db.update("INSERT INTO id2_grant VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", subject + "-grant",
                    ISSUER, subject, "org", "CONSULTATION", "task", "scope:4", "TASK", "task", 3L,
                    "COMPARE", "TASK_WORK", 7L, "ACTIVE", Timestamp.from(UNTIL));
            db.update("INSERT INTO id2_current_epoch VALUES(?,?,?,?,?,?,?,?,?,?)", ISSUER, subject,
                    "org", "CONSULTATION", "TASK", "task", "COMPARE", "TASK_WORK", 7L, subject + "-grant");
        }
        db.update("INSERT INTO con3_worker_delegation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", "delegation",
                "task", "org", "scope:4", 3L, ISSUER, "human", ISSUER, "worker", "human-grant",
                "worker-grant", "ACTIVE", Timestamp.from(UNTIL));
        // SQL is never mocked. These collaborators define the independent-authority and domain
        // snapshot boundaries; their physical implementations have separate tests.
        grants = mock(ProductGrantWatermark.class);
        when(grants.currentGeneration()).thenReturn(17L);
        directory = mock(ProductTaskDirectory.class);
        task = task("task", "org", "IN_PROGRESS", 11, 3, "scope", 4, UNTIL);
        when(directory.find("task")).thenReturn(Optional.of(task));
        certificate = mock(X509Certificate.class);
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        when(certificate.getEncoded()).thenReturn(CERT_BYTES);
        request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
        authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), ISSUER);
    }

    @Test void currentDualCompareReturnsOnlyExactTaskAuthorityAndCanBeRechecked() {
        var authorized = require();
        assertThat(authorized).isEqualTo(new ProductComparisonWorkerTaskAuthority.Authorized(
                "worker", "human", "task", 11, 17, UNTIL));
        verify(grants).assertUnchanged(17L);
        authority.recheck(request, "task", "delegation", authorized);
        // There are no source, Input, Goal, binding, session, JWT or matrix tables in this fixture.
    }

    static Stream<String> invalidRows() {
        var sql = new ArrayList<String>();
        for (String subject : List.of("worker", "human")) {
            String grant = " WHERE grant_id='" + subject + "-grant'";
            for (String mutation : List.of("issuer='other'", "subject_id='other'", "organization='other'",
                    "domain_code='ENFORCEMENT'", "task_scope='other'", "data_scope='scope:5'",
                    "resource_type='SOURCE'", "resource_id='other'", "resource_version=4",
                    "action_code='WRITE'", "purpose='MODEL_INPUT'", "grant_generation=8",
                    "status='REVOKED'", "expires_at=TIMESTAMP '2026-10-07 00:00:00'"))
                sql.add("UPDATE id2_grant SET " + mutation + grant);
            String epoch = " WHERE subject_id='" + subject + "'";
            for (String mutation : List.of("issuer='other'", "subject_id='other'", "organization='other'",
                    "domain_code='ENFORCEMENT'", "resource_type='SOURCE'", "resource_id='other'",
                    "action_code='WRITE'", "purpose='MODEL_INPUT'", "authorization_epoch=8",
                    "current_grant_id='missing'", "current_grant_id=NULL"))
                sql.add("UPDATE id2_current_epoch SET " + mutation + epoch);
            sql.add("DELETE FROM id2_current_epoch" + epoch);
            sql.add("DELETE FROM id2_grant" + grant);
            sql.add("DELETE FROM id2_subject WHERE subject_id='" + subject + "'");
        }
        for (String mutation : List.of("delegation_id='other'", "task_id='other'", "organization_id='other'",
                "data_scope='scope:5'", "authorization_version=4", "human_issuer='other'",
                "service_issuer='other'", "human_subject='other'", "service_subject='other'",
                "human_grant_id='worker-grant'", "service_grant_id='human-grant'", "status='REVOKED'",
                "expires_at=TIMESTAMP '2026-10-07 00:00:00'"))
            sql.add("UPDATE con3_worker_delegation SET " + mutation);
        for (String mutation : List.of("issuer='other'", "subject_id='other'", "certificate_sha='other'",
                "organization='other'", "status='REVOKED'", "workload_kind='SEARCH_JOB'",
                "allowed_purpose='MODEL_INPUT'", "expires_at=TIMESTAMP '2026-10-07 00:00:00'"))
            sql.add("UPDATE id2_workload SET " + mutation);
        sql.add("UPDATE id2_subject SET status='DISABLED' WHERE subject_id='worker'");
        sql.add("UPDATE id2_subject SET subject_kind='HUMAN' WHERE subject_id='worker'");
        sql.add("UPDATE id2_subject SET password_hash='hash' WHERE subject_id='worker'");
        sql.add("UPDATE id2_subject SET status='DISABLED' WHERE subject_id='human'");
        sql.add("UPDATE id2_subject SET subject_kind='SERVICE' WHERE subject_id='human'");
        sql.add("UPDATE id2_subject SET password_hash=NULL WHERE subject_id='human'");
        sql.add("DELETE FROM con3_worker_delegation");
        sql.add("DELETE FROM id2_workload");
        return sql.stream();
    }

    @ParameterizedTest @MethodSource("invalidRows")
    void everySqlCoordinateMustMatchWithoutRelyingOnSchemaConstraints(String sql) {
        db.update(sql);
        assertDenied();
    }

    @ParameterizedTest @ValueSource(strings = {"action_code='WRITE'", "purpose='MODEL_INPUT'",
            "domain_code='ENFORCEMENT'", "resource_type='SOURCE'"})
    void fixedContractCannotBeReplacedEvenWithMatchingEpochs(String mutation) {
        db.update("UPDATE id2_grant SET " + mutation);
        db.update("UPDATE id2_current_epoch SET " + mutation);
        assertDenied();
    }

    @ParameterizedTest @ValueSource(strings = {"task", "delegation", "workload", "worker", "human"})
    void expiryIsMinimumOfAllFiveAuthorities(String limiting) {
        Instant shortExpiry = NOW.plusSeconds(60);
        switch (limiting) {
            case "task" -> when(directory.find("task")).thenReturn(Optional.of(
                    task("task", "org", "IN_PROGRESS", 11, 3, "scope", 4, shortExpiry)));
            case "delegation" -> db.update("UPDATE con3_worker_delegation SET expires_at=?", Timestamp.from(shortExpiry));
            case "workload" -> db.update("UPDATE id2_workload SET expires_at=?", Timestamp.from(shortExpiry));
            default -> db.update("UPDATE id2_grant SET expires_at=? WHERE subject_id=?", Timestamp.from(shortExpiry), limiting);
        }
        assertThat(require().expiresAt()).isEqualTo(shortExpiry);
    }

    static Stream<ProductTaskDirectory.Task> invalidTasks() {
        return Stream.of(task("other", "org", "IN_PROGRESS", 11, 3, "scope", 4, UNTIL),
                task("task", "other", "IN_PROGRESS", 11, 3, "scope", 4, UNTIL),
                task("task", "org", "CLOSED", 11, 3, "scope", 4, UNTIL),
                task("task", "org", "IN_PROGRESS", 11, 4, "scope", 4, UNTIL),
                task("task", "org", "IN_PROGRESS", 11, 3, "other", 4, UNTIL),
                task("task", "org", "IN_PROGRESS", 11, 3, "scope", 5, UNTIL),
                task("task", "org", "IN_PROGRESS", 11, 3, "scope", 4, NOW));
    }

    @ParameterizedTest @MethodSource("invalidTasks")
    void rejectsInexecutableOrWrongScopedTask(ProductTaskDirectory.Task invalid) {
        when(directory.find("task")).thenReturn(Optional.of(invalid));
        assertDenied();
    }

    @ParameterizedTest @MethodSource("invalidTasks")
    void rejectsTaskSnapshotDriftDuringRequire(ProductTaskDirectory.Task invalid) {
        when(directory.find("task")).thenReturn(Optional.of(task), Optional.of(invalid));
        assertDenied();
    }

    @Test void taskVersionAndOwnerChangesAreAlsoSnapshotDrift() {
        var changed = task("task", "org", "IN_PROGRESS", 12, 3, "scope", 4, UNTIL);
        when(directory.find("task")).thenReturn(Optional.of(task), Optional.of(changed));
        assertDenied();
        changed = new ProductTaskDirectory.Task("task", "org", ISSUER, "different-owner",
                "IN_PROGRESS", 11, 3, "scope", 4, UNTIL);
        when(directory.find("task")).thenReturn(Optional.of(task), Optional.of(changed));
        assertDenied();
    }

    @Test void missingTaskAtStartOrEndIsRejected() {
        when(directory.find("task")).thenReturn(Optional.empty());
        assertDenied();
        when(directory.find("task")).thenReturn(Optional.of(task), Optional.empty());
        assertDenied();
    }

    @Test void independentAuthorityFailureAndGenerationDriftFailClosed() {
        when(grants.currentGeneration()).thenThrow(new IllegalStateException("PENDING"));
        assertDenied();
        doReturn(17L).when(grants).currentGeneration();
        doThrow(new IllegalStateException("generation changed")).when(grants).assertUnchanged(17L);
        assertDenied();
    }

    @Test void checksExpiryAgainAtEndOfWindow() {
        Clock advancing = mock(Clock.class);
        when(advancing.instant()).thenReturn(NOW, UNTIL);
        authority = authority(advancing, ISSUER);
        assertDenied();
    }

    @ParameterizedTest @ValueSource(strings = {"insecure", "missing", "empty", "null-leaf", "wrong-type",
            "no-clientAuth", "no-usage", "expired", "not-yet-valid", "wrong-fingerprint", "changed-certificate"})
    void rejectsInvalidCertificateBoundary(String invalid) throws Exception {
        switch (invalid) {
            case "insecure" -> request.setSecure(false);
            case "missing" -> request.removeAttribute("jakarta.servlet.request.X509Certificate");
            case "empty" -> request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[0]);
            case "null-leaf" -> request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {null});
            case "wrong-type" -> request.setAttribute("jakarta.servlet.request.X509Certificate", "untrusted-header");
            case "no-clientAuth" -> when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.1"));
            case "no-usage" -> when(certificate.getExtendedKeyUsage()).thenReturn(null);
            case "expired" -> doThrow(new CertificateExpiredException()).when(certificate).checkValidity(any(Date.class));
            case "not-yet-valid" -> doThrow(new CertificateNotYetValidException()).when(certificate).checkValidity(any(Date.class));
            case "wrong-fingerprint" -> when(certificate.getEncoded()).thenReturn(new byte[] {1});
            case "changed-certificate" -> when(certificate.getEncoded()).thenReturn(CERT_BYTES, new byte[] {1});
            default -> throw new AssertionError(invalid);
        }
        assertDenied();
    }

    @Test void certificateExpirationAtWindowEndIsRejected() throws Exception {
        doAnswer(invocation -> {
            Date checked = invocation.getArgument(0);
            if (!checked.toInstant().isBefore(NOW.plusSeconds(1))) throw new CertificateExpiredException();
            return null;
        }).when(certificate).checkValidity(any(Date.class));
        Clock advancing = mock(Clock.class);
        when(advancing.instant()).thenReturn(NOW, NOW.plusSeconds(1));
        authority = authority(advancing, ISSUER);
        assertDenied();
    }

    @Test void issuerAndExactRequestCoordinatesCannotBeSubstituted() {
        authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), ISSUER + "/wrong");
        assertDenied();
        assertThatThrownBy(() -> authority(Clock.fixed(NOW, ZoneOffset.UTC), " "))
                .isInstanceOf(IllegalArgumentException.class);
        authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), ISSUER);
        assertThatThrownBy(() -> authority.require(null, "task", "delegation")).isInstanceOf(IllegalStateException.class);
        for (String value : new String[] {null, "", " ", "other"}) {
            assertThatThrownBy(() -> authority.require(request, value, "delegation")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> authority.require(request, "task", value)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void recheckRejectsEveryAuthorizedFieldMismatchIncludingGenerationAndExpiry() {
        var original = require();
        var mismatches = List.of(
                new ProductComparisonWorkerTaskAuthority.Authorized("other", "human", "task", 11, 17, UNTIL),
                new ProductComparisonWorkerTaskAuthority.Authorized("worker", "other", "task", 11, 17, UNTIL),
                new ProductComparisonWorkerTaskAuthority.Authorized("worker", "human", "other", 11, 17, UNTIL),
                new ProductComparisonWorkerTaskAuthority.Authorized("worker", "human", "task", 12, 17, UNTIL),
                new ProductComparisonWorkerTaskAuthority.Authorized("worker", "human", "task", 11, 18, UNTIL),
                new ProductComparisonWorkerTaskAuthority.Authorized("worker", "human", "task", 11, 17, UNTIL.minusSeconds(1)));
        for (var mismatch : mismatches)
            assertThatThrownBy(() -> authority.recheck(request, "task", "delegation", mismatch))
                    .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> authority.recheck(request, "task", "delegation", null))
                .isInstanceOf(IllegalStateException.class);
        when(grants.currentGeneration()).thenReturn(18L);
        assertThatThrownBy(() -> authority.recheck(request, "task", "delegation", original))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"delegation", "worker", "human"})
    void recheckRejectsWithdrawalAfterIntentValidation(String revoked) {
        var original = require();
        if (revoked.equals("delegation")) db.update("UPDATE con3_worker_delegation SET status='REVOKED'");
        else db.update("UPDATE id2_grant SET status='REVOKED' WHERE subject_id=?", revoked);
        assertThatThrownBy(() -> authority.recheck(request, "task", "delegation", original))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void recheckRejectsTaskVersionChangeAndShortenedLiveExpiry() {
        var original = require();
        when(directory.find("task")).thenReturn(Optional.of(
                task("task", "org", "IN_PROGRESS", 12, 3, "scope", 4, UNTIL)));
        assertThatThrownBy(() -> authority.recheck(request, "task", "delegation", original))
                .isInstanceOf(IllegalStateException.class);
        when(directory.find("task")).thenReturn(Optional.of(task));
        db.update("UPDATE id2_workload SET expires_at=?", Timestamp.from(UNTIL.minusSeconds(1)));
        assertThatThrownBy(() -> authority.recheck(request, "task", "delegation", original))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void ambiguousRowsAreNotTreatedAsOneAuthority() {
        db.update("INSERT INTO id2_current_epoch SELECT * FROM id2_current_epoch WHERE subject_id='worker'");
        assertDenied();
    }

    private ProductComparisonWorkerTaskAuthority authority(Clock clock, String issuer) {
        return new ProductComparisonWorkerTaskAuthority(db, grants, directory, clock, issuer);
    }

    private ProductComparisonWorkerTaskAuthority.Authorized require() {
        return authority.require(request, "task", "delegation");
    }

    private void assertDenied() {
        assertThatThrownBy(this::require).isInstanceOf(IllegalStateException.class);
    }

    private static ProductTaskDirectory.Task task(String id, String org, String state, long version,
            long authorizationVersion, String scope, long scopeVersion, Instant expiresAt) {
        return new ProductTaskDirectory.Task(id, org, ISSUER, "human", state, version,
                authorizationVersion, scope, scopeVersion, expiresAt);
    }
}


