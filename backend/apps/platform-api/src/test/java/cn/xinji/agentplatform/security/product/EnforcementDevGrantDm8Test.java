package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class EnforcementDevGrantDm8Test {
    @TempDir Path temporary;

    @Test void namedDevelopmentGrantControlsNewMaterialAndSurvivesIndependentRevocation() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null
                && authorityPassword != null && !businessUrl.equals(authorityUrl), "Dedicated ENF instances required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String suffix = HexFormat.of().formatHex(random).toUpperCase();
        String owner = "ED" + suffix;
        String runtime = "ER" + suffix;
        String backup = "EB" + suffix;
        String password = "T9a" + suffix + "K7z";
        String issuer = "https://enf-dev-issuer.example.org";
        createUser(businessUrl, businessPassword, owner, password, true);
        try {
            createUser(authorityUrl, authorityPassword, owner, password, true);
            try {
                createUser(authorityUrl, authorityPassword, runtime, password, false);
                try {
                    createUser(authorityUrl, authorityPassword, backup, password, false);
                    try (var businessConnection = DriverManager.getConnection(businessUrl, owner, password);
                         var authorityConnection = DriverManager.getConnection(authorityUrl, owner, password)) {
                        ScriptUtils.executeSqlScript(businessConnection,
                                new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
                        ScriptUtils.executeSqlScript(authorityConnection,
                                new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
                        runScenario(businessUrl, authorityUrl, owner, password, runtime, backup, issuer);
                    } finally {
                        dropTables(businessUrl, owner, password, List.of("id2_grant_event", "id2_current_epoch",
                                "id2_grant", "id2_session", "id2_key_event", "id2_signing_key",
                                "id2_key_control", "id2_subject_event", "id2_subject"));
                        dropTables(authorityUrl, owner, password, List.of("id2_maintenance_audit",
                                "id2_maintainer", "id2_authority_watermark"));
                    }
                } finally { dropUser(authorityUrl, authorityPassword, backup); }
            } finally { dropUser(authorityUrl, authorityPassword, runtime); }
        } finally {
            dropUser(authorityUrl, authorityPassword, owner);
            dropUser(businessUrl, businessPassword, owner);
        }
    }

    private void runScenario(String businessUrl, String authorityUrl, String owner, String password,
                             String runtime, String backup, String issuer) throws Exception {
        var businessSource = new DriverManagerDataSource(businessUrl, owner, password);
        var authoritySource = new DriverManagerDataSource(authorityUrl, owner, password);
        var db = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(authoritySource);
        Path caStore = temporary.resolve("ca.p12");
        Path maintainerStore = temporary.resolve("maintainer.p12");
        Path caCert = temporary.resolve("ca.cer");
        Path peerCert = temporary.resolve("maintainer.cer");
        Path request = temporary.resolve("maintainer.csr");
        String secret = "isolated-enforcement-dev";
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=ENF Development CA", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-ext", "bc:c", "-keystore", caStore.toString(),
                "-storepass", secret, "-keypass", secret, "-noprompt");
        keytool("-exportcert", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", secret,
                "-file", caCert.toString());
        keytool("-genkeypair", "-alias", "maintainer", "-dname", "CN=ENF Development Maintainer",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-keystore",
                maintainerStore.toString(), "-storepass", secret, "-keypass", secret, "-noprompt");
        keytool("-certreq", "-alias", "maintainer", "-keystore", maintainerStore.toString(),
                "-storepass", secret, "-file", request.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", secret,
                "-infile", request.toString(), "-outfile", peerCert.toString(), "-validity", "2",
                "-ext", "KU=digitalSignature", "-ext", "EKU=clientAuth");
        var peer = certificate(peerCert);
        var bootstrap = new ProductIdentityBootstrap.Manifest(issuer, "enf-dev-maintainer", "ENF-DEV",
                peerCert.toString(), caCert.toString(), peer.getSubjectX500Principal().getName(), digest(peer),
                List.of("SUBJECTS", "GRANTS", "KEYS"));
        ProductIdentityBootstrap.bootstrap(db, authority,
                new TransactionTemplate(new DataSourceTransactionManager(authoritySource)), bootstrap,
                digest(certificate(caCert)), runtime, backup);

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(businessSource));
        var subjects = new ProductSubjects(db, transaction, new BCryptPasswordEncoder(), Clock.systemUTC(),
                issuer, Duration.ofMinutes(30), 12);
        var member = subjects.register("enf-dev-material-operator", "enf-dev-maintainer");
        subjects.activate(member.subjectId(), member.oneTimeActivation(), "development-only-password");
        var guard = new ProductGrantWatermark(db, authority, issuer);
        var grants = new ProductGrants(db, transaction, Clock.systemUTC());
        var intent = new ObjectMapper().readTree(Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/grant-intent.json")));
        String batch = intent.path("batchId").asText();
        int issued = 0;
        ProductGrants.Receipt preview = null;
        for (JsonNode group : intent.path("humanGrants")) {
            for (JsonNode action : group.path("actions")) {
                for (JsonNode use : group.path("uses")) {
                    var scope = new ProductGrants.Scope(issuer, member.subjectId(), "ENF-DEV", "ENFORCEMENT",
                            batch, batch, group.path("resource").asText(), batch, 1,
                            action.asText(), use.asText());
                    String requestId = "enf-dev-grant-" + (++issued);
                    var receipt = guard.change(() -> grants.issue(scope, Instant.now().plus(Duration.ofHours(6)),
                            0, "enf-dev-maintainer", requestId, "a".repeat(64)));
                    if ("PREVIEW".equals(action.asText()) && "PREVIEW".equals(use.asText())) preview = receipt;
                }
            }
        }
        assertThat(preview).isNotNull();
        assertThat(guard.currentGeneration()).isEqualTo(issued);
        var secondInstance = new ProductGrantWatermark(
                new JdbcTemplate(new DriverManagerDataSource(businessUrl, owner, password)),
                new JdbcTemplate(new DriverManagerDataSource(authorityUrl, owner, password)), issuer);
        long beforeWithdrawal = secondInstance.currentGeneration();
        assertThat(allowed(db, guard, member.subjectId(), "ENF-DEV", batch, preview.grantId())).isTrue();
        assertThat(allowed(db, secondInstance, member.subjectId(), "ENF-DEV", batch,
                preview.grantId())).isTrue();
        assertThat(allowed(db, guard, member.subjectId(), "OTHER-ORG", batch, preview.grantId())).isFalse();
        assertThat(allowed(db, guard, member.subjectId(), "ENF-DEV", "OTHER-BATCH", preview.grantId())).isFalse();
        byte[] original = Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/original-M-01.txt"));
        assertThat(original).hasSize(317);
        String previewGrantId = preview.grantId();
        var revoked = guard.change(() -> grants.revoke(previewGrantId, 1, "enf-dev-maintainer",
                "enf-dev-revoke-preview", "b".repeat(64)));
        assertThatThrownBy(() -> secondInstance.assertUnchanged(beforeWithdrawal))
                .isInstanceOf(IllegalStateException.class);
        assertThat(allowed(db, guard, member.subjectId(), "ENF-DEV", batch, preview.grantId())).isFalse();
        assertThat(allowed(db, secondInstance, member.subjectId(), "ENF-DEV", batch,
                preview.grantId())).isFalse();
        long generation = guard.currentGeneration();
        authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='failure-probe' "
                + "WHERE issuer=?", issuer);
        assertThatThrownBy(guard::assertCurrent).isInstanceOf(IllegalStateException.class);
        authority.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL WHERE issuer=?", issuer);
        assertThat(guard.currentGeneration()).isEqualTo(generation);
        db.update("UPDATE id2_grant SET status='ACTIVE' WHERE grant_id=?", preview.grantId());
        assertThatThrownBy(guard::assertCurrent).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_grant SET status='REVOKED' WHERE grant_id=?", preview.grantId());
        guard.assertCurrent();
        System.out.printf("ENF_GRANT_DM8 issuer=%s member=%s org=ENF-DEV grants=%d revocationEpoch=%d "
                + "independentGeneration=%d fileBytes=%d crossScope=DENIED withdrawn=DENIED "
                + "authorityPending=DENIED oldProjection=DENIED secondInstanceStale=DENIED%n",
                issuer, member.subjectId(), issued,
                revoked.epoch(), generation, original.length);
    }

    private static boolean allowed(JdbcTemplate db, ProductGrantWatermark guard, String subject, String org,
                                   String batch, String grantId) {
        long generation = guard.currentGeneration();
        boolean result = db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch c "
                        + "ON c.current_grant_id=g.grant_id WHERE g.grant_id=? AND g.subject_id=? "
                        + "AND g.organization=? AND g.domain_code='ENFORCEMENT' AND g.task_scope=? "
                        + "AND g.data_scope=? AND g.resource_type='PROJECT-DEV-MATERIAL-WORK' "
                        + "AND g.action_code='PREVIEW' AND g.purpose='PREVIEW' AND g.status='ACTIVE' "
                        + "AND g.expires_at>CURRENT_TIMESTAMP", Integer.class,
                grantId, subject, org, batch, batch) == 1;
        guard.assertUnchanged(generation);
        return result;
    }

    private static X509Certificate certificate(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static String digest(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static void keytool(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException("Certificate setup failed: " + output);
    }

    private static void createUser(String url, String password, String name, String secret, boolean resource)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + name + " IDENTIFIED BY \"" + secret + "\"");
            if (resource) statement.execute("GRANT RESOURCE TO " + name);
        }
    }

    private static void dropUser(String url, String password, String name) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) { statement.execute("DROP USER " + name); }
    }

    private static void dropTables(String url, String name, String password, List<String> tables) throws SQLException {
        try (var connection = DriverManager.getConnection(url, name, password);
             var statement = connection.createStatement()) {
            for (String table : tables) {
                try { statement.execute("DROP TABLE " + table + " CASCADE"); }
                catch (SQLException absent) { /* Partial migration can leave a table absent. */ }
            }
        }
    }
}
