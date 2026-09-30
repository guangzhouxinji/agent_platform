package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class ProductWorkloadControllerTest {
    @TempDir Path directory;

    @Test void maintenanceCertificateControlsRegistrationAndRevocation() throws Exception {
        String issuer = "https://workload-route.example.org";
        var primary = database("workload-route-");
        var independent = database("workload-route-authority-");
        try (var connection = primary.getConnection(); var authority = independent.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql",
                    "V041__product_task_authorization_version.sql",
                    "V042__product_consultation_goal.sql",
                    "V046__product_workload_subject.sql"})
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", name)));
            ScriptUtils.executeSqlScript(authority, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        var db = new JdbcTemplate(primary);
        var authority = new JdbcTemplate(independent);
        String certificate = directory.resolve("maintainer.p12").toString();
        String exported = directory.resolve("maintainer.cer").toString();
        keytool("-genkeypair", "-alias", "maintainer", "-dname", "CN=Workload Maintainer",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-ext", "EKU=clientAuth",
                "-keystore", certificate, "-storepass", "local-password-123",
                "-keypass", "local-password-123", "-noprompt");
        keytool("-exportcert", "-alias", "maintainer", "-keystore", certificate,
                "-storepass", "local-password-123", "-file", exported);
        X509Certificate peer;
        try (var input = Files.newInputStream(Path.of(exported))) {
            peer = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(peer.getEncoded()));
        authority.update("INSERT INTO id2_maintainer VALUES(1,?,?,?,'SUBJECTS,GRANTS','ACTIVE',1)",
                "maintainer", "organization", fingerprint);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var proof = new ProductMaintenanceProof(authority,
                new TransactionTemplate(new DataSourceTransactionManager(independent)));
        var manager = new DataSourceTransactionManager(primary);
        var mvc = MockMvcBuilders.standaloneSetup(new ProductWorkloadController(proof, db,
                manager, watermark, new ProductGrants(db, new TransactionTemplate(manager),
                        java.time.Clock.systemUTC()), issuer)).build();
        String body = "{\"certificateSha256\":\"" + "b".repeat(64)
                + "\",\"kind\":\"AGENT\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}";
        var registration = post("/v2/identity/workloads").secure(true)
                .header("Idempotency-Key", "workload-register").contentType(MediaType.APPLICATION_JSON)
                .content(body);
        mvc.perform(registration).andExpect(status().isForbidden());
        mvc.perform(post("/v2/identity/workloads").secure(false)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "workload-register")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String response = mvc.perform(post("/v2/identity/workloads").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "workload-register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String subject = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .get("subjectId").asText();
        assertThat(db.queryForObject("SELECT subject_kind FROM id2_subject WHERE subject_id=?",
                String.class, subject)).isEqualTo("SERVICE");
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        db.update("INSERT INTO con3_organization VALUES('organization','Organization','ACTIVE',1,?)", now);
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                        + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,"
                        + "created_at) VALUES('task-1','organization',?,?,'IN_PROGRESS',1,?,'policy',1,?,?)",
                issuer, subject, java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)),
                "c".repeat(64), now);
        db.update("INSERT INTO con3_data_scope VALUES('scope-1','task-1','organization',1,'TASK_METADATA')");
        String grantBody = "{\"taskId\":\"task-1\",\"expiresAt\":\""
                + java.time.Instant.now().plusSeconds(1800) + "\",\"expectedEpoch\":0}";
        mvc.perform(post("/v2/identity/workloads/" + subject + "/task-grants").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "cross-task")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"other\",\"expiresAt\":\"2099-01-01T00:00:00Z\","
                        + "\"expectedEpoch\":0}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/v2/identity/workloads/" + subject + "/task-grants").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "exact-task")
                .contentType(MediaType.APPLICATION_JSON).content(grantBody))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT data_scope FROM id2_grant WHERE subject_id=?",
                String.class, subject)).isEqualTo("scope-1:1");
        mvc.perform(post("/v2/identity/workloads/" + subject + "/revoke").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "workload-revoke")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT status FROM id2_workload WHERE subject_id=?",
                String.class, subject)).isEqualTo("REVOKED");
        watermark.assertCurrent();
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit "
                + "WHERE decision='DENY'", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    private static JdbcDataSource database(String prefix) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + prefix + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void keytool(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "keytool";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }
}
