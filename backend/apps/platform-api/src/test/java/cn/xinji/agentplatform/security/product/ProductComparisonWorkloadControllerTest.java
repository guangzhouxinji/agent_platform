package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** MockMvc/certificate-double checks only; no TLS or certificate-validation integration claim. */
class ProductComparisonWorkloadControllerTest {
    private static final String ISSUER = "https://comparison-maintenance.example.org";
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String BODY = "{\"taskId\":\"task-1\",\"expiresAt\":\"2026-10-07T00:30:00Z\","
            + "\"expectedEpoch\":0}";
    private JdbcTemplate db;
    private JdbcTemplate authority;
    private ProductGrantWatermark watermark;
    private ProductGrants grants;
    private DataSourceTransactionManager manager;
    private X509Certificate peer;
    private String subject;
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        var primary = database();
        var independent = database();
        try (var connection = primary.getConnection(); var independentConnection = independent.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql", "V041__product_task_authorization_version.sql",
                    "V042__product_consultation_goal.sql", "V046__product_workload_subject.sql"})
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", name)));
            ScriptUtils.executeSqlScript(independentConnection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        db = new JdbcTemplate(primary);
        authority = new JdbcTemplate(independent);
        peer = mock(X509Certificate.class);
        when(peer.getEncoded()).thenReturn("mechanical-certificate-double"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        authority.update("INSERT INTO id2_maintainer VALUES(1,?,?,?,'SUBJECTS,GRANTS','ACTIVE',1)",
                "maintainer", "organization", fingerprint());
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", ISSUER,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        watermark = new ProductGrantWatermark(db, authority, ISSUER);
        manager = new DataSourceTransactionManager(primary);
        var tx = new TransactionTemplate(manager);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        grants = new ProductGrants(db, tx, clock);
        subject = new ProductWorkloadSubjects(db, tx, watermark, clock, ISSUER)
                .register("b".repeat(64), "organization", ProductWorkloadSubjects.Kind.AGENT,
                        NOW.plusSeconds(3600), "register", "maintainer#certificate").subjectId();
        db.update("INSERT INTO con3_organization VALUES('organization','Organization','ACTIVE',1,?)",
                Timestamp.from(NOW));
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                        + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                        + "VALUES('task-1','organization',?,?,'IN_PROGRESS',1,?,'policy',1,?,?)", ISSUER, subject,
                Timestamp.from(NOW.plusSeconds(3600)), "c".repeat(64), Timestamp.from(NOW));
        db.update("INSERT INTO con3_data_scope VALUES('scope-1','task-1','organization',1,'TASK_METADATA')");
        mvc = controller(new ProductMaintenanceProof(authority,
                new TransactionTemplate(new DataSourceTransactionManager(independent))));
    }

    private MockMvc controller(ProductMaintenanceProof maintenance) {
        return MockMvcBuilders.standaloneSetup(new ProductWorkloadController(maintenance, db,
                manager, watermark, grants, ISSUER)).build();
    }

    private MockHttpServletRequestBuilder request(String route, String key, String body) {
        return post("/v2/identity/workloads/" + subject + "/" + route).secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String fingerprint() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(peer.getEncoded()));
    }

    private String digest(String operation) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        for (String field : new String[] {operation, "maintainer#" + fingerprint(), subject, "organization",
                "task-1", "2026-10-07T00:30:00Z", "0"})
            if (field != null) hash.update((field.length() + ":" + field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash.digest());
    }

    @Test void preexistingWriteReceiptKeepsItsOriginalRequestDigest() throws Exception {
        String original = digest(null);
        watermark.change(() -> grants.issueWorkload(ISSUER, subject, "organization", "task-1",
                NOW.plusSeconds(1800), 0, "maintainer#" + fingerprintUnchecked(), "legacy-write", original));
        mvc.perform(request("task-grants", "legacy-write", BODY)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);
        mvc.perform(request("comparison-task-grants", "legacy-write", BODY)).andExpect(status().isServiceUnavailable());
        watermark.assertCurrent();
    }
    private String fingerprintUnchecked() {
        try { return fingerprint(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void fixedComparisonAndWriteStaySeparateIncludingCrossRouteReplay() throws Exception {
        String receipt = mvc.perform(request("comparison-task-grants", "compare", BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(request("comparison-task-grants", "compare", BODY)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo(receipt);
        mvc.perform(request("task-grants", "compare", BODY)).andExpect(status().isServiceUnavailable());
        mvc.perform(request("task-grants", "write", BODY)).andExpect(status().isOk());
        mvc.perform(request("comparison-task-grants", "write", BODY)).andExpect(status().isServiceUnavailable());
        assertThat(db.queryForList("SELECT action_code || ':' || purpose FROM id2_grant", String.class))
                .containsExactlyInAnyOrder("COMPARE:TASK_WORK", "WRITE:TASK_WORK");
        String comparisonSha = db.queryForObject("SELECT request_sha FROM id2_grant_event WHERE request_id='compare'",
                String.class);
        String writeSha = db.queryForObject("SELECT request_sha FROM id2_grant_event WHERE request_id='write'",
                String.class);
        assertThat(comparisonSha).isEqualTo(digest("COMPARE_TASK_GRANT"));
        assertThat(writeSha).isEqualTo(digest(null)).isNotEqualTo(comparisonSha);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(2);
        watermark.assertCurrent();
    }

    @Test void changedPayloadAndCurrentTaskVersionCannotReplay() throws Exception {
        mvc.perform(request("comparison-task-grants", "compare", BODY)).andExpect(status().isOk());
        mvc.perform(request("comparison-task-grants", "compare", BODY.replace("00:30", "00:20")))
                .andExpect(status().isServiceUnavailable());
        db.update("UPDATE con3_task SET authorization_version=authorization_version+1");
        mvc.perform(request("comparison-task-grants", "compare", BODY)).andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);
        watermark.assertCurrent();
    }

    @Test void secureRegisteredCertificateAndBothMaintenanceRolesAreRequired() throws Exception {
        mvc.perform(post("/v2/identity/workloads/" + subject + "/comparison-task-grants").secure(true)
                .header("Idempotency-Key", "no-cert").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(request("comparison-task-grants", "insecure", BODY).secure(false))
                .andExpect(status().isForbidden());
        var other = mock(X509Certificate.class);
        when(other.getEncoded()).thenReturn(new byte[] {1, 2, 3});
        mvc.perform(request("comparison-task-grants", "other-cert", BODY)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {other}))
                .andExpect(status().isForbidden());
        authority.update("UPDATE id2_maintainer SET allowed_roles='SUBJECTS'");
        mvc.perform(request("comparison-task-grants", "no-grants", BODY)).andExpect(status().isForbidden());
        authority.update("UPDATE id2_maintainer SET allowed_roles='GRANTS'");
        mvc.perform(request("comparison-task-grants", "no-subjects", BODY)).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    @Test void samePrincipalAndOrganizationAreRequiredAcrossRoles() throws Exception {
        var changed = mock(ProductMaintenanceProof.class);
        when(changed.require(any(), eq(ProductMaintenanceProof.Role.SUBJECTS))).thenReturn("actor-A");
        when(changed.require(any(), eq(ProductMaintenanceProof.Role.GRANTS))).thenReturn("actor-B");
        controller(changed).perform(request("comparison-task-grants", "different-actor", BODY))
                .andExpect(status().isServiceUnavailable());
        when(changed.require(any(), eq(ProductMaintenanceProof.Role.GRANTS))).thenReturn("actor-A");
        when(changed.requireOrganization(any(), eq(ProductMaintenanceProof.Role.SUBJECTS))).thenReturn("organization");
        when(changed.requireOrganization(any(), eq(ProductMaintenanceProof.Role.GRANTS))).thenReturn("other");
        controller(changed).perform(request("comparison-task-grants", "different-org", BODY))
                .andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    @Test void maintainerOrganizationCannotAuthorizeAnotherWorkloadOrganization() throws Exception {
        authority.update("UPDATE id2_maintainer SET organization='other'");
        mvc.perform(request("comparison-task-grants", "cross-org", BODY)).andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    @Test void dtoExposesOnlyTaskExpiryAndEpochAndExpiredRequestRejects() throws Exception {
        assertThat(Arrays.stream(ProductWorkloadController.GrantTask.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("taskId", "expiresAt", "expectedEpoch");
        mvc.perform(request("comparison-task-grants", "expired", BODY.replace("00:30", "00:00")))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    private static JdbcDataSource database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:comparison-controller-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }
}
