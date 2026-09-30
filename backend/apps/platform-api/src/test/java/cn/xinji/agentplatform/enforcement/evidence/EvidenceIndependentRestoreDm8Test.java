package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
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

class EvidenceIndependentRestoreDm8Test {
    @TempDir Path temporary;

    @Test void restoredProductReceiptsCannotOutliveIndependentCurrentRights() throws Exception {
        String gate = System.getenv("ENF_EVIDENCE_HTTP_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_EVIDENCE_HTTP_RESTORED_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String restoredRoot = System.getenv("ENF_EVIDENCE_HTTP_RESTORED_ROOT");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && authorityUrl != null
                && restoredRoot != null && !restoredUrl.equals(authorityUrl),
                "Independent Evidence physical restore required");
        Properties checkpoint = new Properties();
        Path marker = Path.of(gate);
        try (var input = Files.newInputStream(marker)) { checkpoint.load(input); }
        String owner = checkpoint.getProperty("owner");
        String secret = checkpoint.getProperty("secret");
        var db = new JdbcTemplate(new DriverManagerDataSource(restoredUrl, owner, secret));
        Path root = Path.of(restoredRoot);
        var caseInput = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(root.resolve("case-snapshot.json").toFile());
        for (var ref : caseInput.path("materials")) {
            String id = ref.path("id").asText();
            String key = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                    String.class, id);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(root.resolve("files").resolve(key + ".blob"))));
            assertThat(digest).isEqualTo(ref.path("sha256").asText());
        }
        int receipts = checkpoint.getProperty("receipts") == null ? 6
                : Integer.parseInt(checkpoint.getProperty("receipts"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class))
                .isEqualTo(receipts);
        Path keyStore = temporary.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "evidence",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "enf-evidence-https-test", "-keypass",
                "enf-evidence-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + keyStore.toUri(),
                "server.ssl.key-store-password=enf-evidence-https-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + restoredUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=https://enf-evidence-dual.example.org",
                "product.identity.audience=https://enf-evidence-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M", "product.identity.activation-lifetime=PT30M",
                "product.identity.minimum-password-length=12", "enforcement.v2.file-root=" + root.resolve("files"),
                "enforcement.v2.evidence.case-input-file=" + root.resolve("case-snapshot.json"),
                "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
                "enforcement.v2.evidence.approved-basis=PROJECT_DOCUMENT_REVIEW|docs/57#6.24|1|PROJECT-CASE-001"
        };
        try (var app = new SpringApplicationBuilder(EvidenceIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String base = "https://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            var http = EvidenceIndependentHttpHarness.client();
            String token = checkpoint.getProperty("token");
            String path = "/v2/enforcement/evidence-organizations/ORG-CASE-001/versions/4";
            String command = "/v2/enforcement/evidence-organizations/ORG-CASE-001/exclusions";
            assertThat(EvidenceIndependentHttpHarness.get(http, base + path, token).statusCode()).isEqualTo(200);
            assertThat(EvidenceIndependentHttpHarness.post(http, base + command, token,
                    checkpoint.getProperty("body"), "dual-exclude-c").statusCode()).isEqualTo(200);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class))
                    .isEqualTo(receipts);
            Files.writeString(marker.resolveSibling(marker.getFileName() + ".restore-ready"), "ready");
            Path withdrawn = marker.resolveSibling(marker.getFileName() + ".withdrawn");
            Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
            while (!Files.exists(withdrawn) && Instant.now().isBefore(deadline)) Thread.sleep(250);
            assertThat(Files.exists(withdrawn)).isTrue();
            assertThat(EvidenceIndependentHttpHarness.get(http, base + path, token).statusCode())
                    .isNotEqualTo(200);
            assertThat(EvidenceIndependentHttpHarness.post(http, base + command, token,
                    checkpoint.getProperty("body"), "dual-exclude-c").statusCode()).isNotEqualTo(200);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class))
                    .isEqualTo(receipts);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_event", Integer.class))
                    .isEqualTo(receipts);
            System.out.printf("ENF_EVIDENCE_OLD_BACKUP_CURRENT_AUTHORITY restored=5293 authority=5258 "
                    + "before=200/200 after=DENIED/DENIED receipts=%d files=3%n", receipts);
            Files.writeString(marker.resolveSibling(marker.getFileName() + ".verified"), "verified");
        }
    }
}
