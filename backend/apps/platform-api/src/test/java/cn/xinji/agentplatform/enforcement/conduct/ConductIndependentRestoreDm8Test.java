package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.EnforcementIndependentIPrerequisites;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ConductIndependentRestoreDm8Test {
    @TempDir Path temporary;

    @Test void physicalDbAndOriginalsRestoreWhileCurrentAuthorityNeverRollsBack() throws Exception {
        String gate = System.getenv("ENF_CONDUCT_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_CONDUCT_RESTORED_DB_URL");
        String restoredRoot = System.getenv("ENF_CONDUCT_RESTORED_ROOT");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && restoredRoot != null &&
                authorityUrl != null && System.getenv("ENF_DEV_DB_URL") != null,
                "Independent Conduct DB and original-file physical restore required");
        var checkpoint = new Properties();
        try (var input = Files.newInputStream(Path.of(gate))) { checkpoint.load(input); }
        String owner = checkpoint.getProperty("owner");
        String secret = checkpoint.getProperty("secret");
        assertNotEquals(Path.of(checkpoint.getProperty("sourceRoot")).toAbsolutePath().normalize(),
                Path.of(restoredRoot).toAbsolutePath().normalize());
        EnforcementIndependentIPrerequisites.requireRestoredDm8(System.getenv("ENF_DEV_DB_URL"),
                authorityUrl, restoredUrl, Path.of(checkpoint.getProperty("sourceRoot")), Path.of(restoredRoot));
        var db = new JdbcTemplate(new DriverManagerDataSource(restoredUrl, owner, secret));
        Path root = Path.of(restoredRoot);
        assertEquals(2, count(db, "enf7_conduct_report"));
        assertEquals(4, count(db, "enf7_conduct_receipt"));
        assertEquals(4, count(db, "enf7_conduct_event"));
        int fileCount = Integer.parseInt(checkpoint.getProperty("fileCount"));
        for (int index = 0; index < fileCount; index++) {
            String prefix = "file." + index + ".";
            String id = checkpoint.getProperty(prefix + "id");
            String key = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                    String.class, id);
            assertEquals(checkpoint.getProperty(prefix + "key"), key);
            String recordedSha = db.queryForObject("SELECT sha256 FROM enf3_material WHERE material_id=?",
                    String.class, id);
            assertEquals(checkpoint.getProperty(prefix + "sha256"), recordedSha);
            byte[] original = Files.readAllBytes(root.resolve("files").resolve(key + ".blob"));
            assertEquals(recordedSha, HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(original)));
        }
        Path keyStore = temporary.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "conduct",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "enf-conduct-https-test", "-keypass",
                "enf-conduct-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + keyStore.toUri(),
                "server.ssl.key-store-password=enf-conduct-https-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + restoredUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=https://enf-conduct-dual.example.org",
                "product.identity.audience=https://enf-conduct-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M", "product.identity.activation-lifetime=PT30M",
                "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + root.resolve("files"),
                "enforcement.v2.evidence.case-input-file=" + root.resolve("case.json"),
                "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
                "enforcement.v2.conduct.approved-basis=TRADEMARK_INFRINGEMENT|PROJECT-BASIS|1|section 1|project|project-area|project-procedure"
        };
        try (var app = new SpringApplicationBuilder(ConductIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String base = "https://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            var http = ConductIndependentHttpHarness.client();
            String token = ConductIndependentHttpHarness.login(http, base);
            String path = "/v2/enforcement/conduct-analyses/DUAL-CONDUCT-ANALYSIS/versions/1";
            String review = "{\"expectedVersion\":1,\"reason\":\"Historical right pending\","
                    + "\"decision\":\"RETURNED\"}";
            assertEquals(200, ConductIndependentHttpHarness.get(http, base + path, token).statusCode());
            assertEquals(200, ConductIndependentHttpHarness.post(http, base + path + "/reviews",
                    token, review, "dual-review").statusCode());
            assertEquals(4, count(db, "enf7_conduct_receipt"));
            assertEquals(4, count(db, "enf7_conduct_event"));
            String key = checkpoint.getProperty("file.0.key");
            Path original = root.resolve("files").resolve(key + ".blob");
            Path held = root.resolve("files").resolve(key + ".held");
            Files.move(original, held);
            try {
                assertEquals(503, ConductIndependentHttpHarness.get(http, base + path, token).statusCode());
                assertEquals(4, count(db, "enf7_conduct_receipt"));
                assertEquals(4, count(db, "enf7_conduct_event"));
            } finally { Files.move(held, original); }
            Files.writeString(Path.of(gate + ".restore-ready"), "ready",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            Path withdrawn = Path.of(gate + ".withdrawn");
            Instant deadline = Instant.now().plusSeconds(600);
            while (!Files.exists(withdrawn) && Instant.now().isBefore(deadline)) Thread.sleep(250);
            assertTrue(Files.exists(withdrawn), "Current authority withdrawal not observed");
            var mismatch = assertThrows(IllegalStateException.class, () -> new ProductGrantWatermark(db,
                    new JdbcTemplate(new DriverManagerDataSource(authorityUrl, owner, secret)),
                    "https://enf-conduct-dual.example.org"));
            assertEquals("Authorization history differs from independent high-water mark",
                    mismatch.getMessage());
            var staleRead = ConductIndependentHttpHarness.get(http, base + path, token);
            assertEquals(503, staleRead.statusCode());
            assertTrue(staleRead.body().isBlank());
            var staleReplay = ConductIndependentHttpHarness.post(http, base + path + "/reviews",
                    token, review, "dual-review");
            assertEquals(503, staleReplay.statusCode());
            assertTrue(staleReplay.body().isBlank());
            assertEquals(2, count(db, "enf7_conduct_report"));
            assertEquals(4, count(db, "enf7_conduct_receipt"));
            assertEquals(4, count(db, "enf7_conduct_event"));
            Files.writeString(Path.of(gate + ".verified"), "verified",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println("ENF_CONDUCT_PHYSICAL_RESTORE reports=2 receipts=4 events=4 "
                    + "originals=" + fileCount + " replay=200 missingOriginal=503 staleBackupAuthority=503");
        }
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
