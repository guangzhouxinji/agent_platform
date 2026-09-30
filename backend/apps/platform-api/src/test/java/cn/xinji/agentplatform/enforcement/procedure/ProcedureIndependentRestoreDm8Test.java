package cn.xinji.agentplatform.enforcement.procedure;

import cn.xinji.agentplatform.enforcement.EnforcementIndependentIPrerequisites;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProcedureIndependentRestoreDm8Test {
    @TempDir Path temporary;

    @Test void physicalDbAndOriginalRestoreObeysUnrestoredAuthorityAndFailsClosed() throws Exception {
        String gate = System.getenv("ENF_PROCEDURE_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_PROCEDURE_RESTORED_DB_URL");
        String rootValue = System.getenv("ENF_PROCEDURE_RESTORED_ROOT");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && rootValue != null &&
                authorityUrl != null && System.getenv("ENF_DEV_DB_URL") != null,
                "Independent Procedure DB and original-file physical cold restore required");
        Path marker = Path.of(gate);
        var checkpoint = new Properties();
        try (var input = Files.newInputStream(marker)) { checkpoint.load(input); }
        String owner = checkpoint.getProperty("owner");
        String secret = checkpoint.getProperty("secret");
        assertThat(Path.of(checkpoint.getProperty("sourceRoot")).toAbsolutePath())
                .isNotEqualTo(Path.of(rootValue).toAbsolutePath());
        EnforcementIndependentIPrerequisites.requireRestoredDm8(System.getenv("ENF_DEV_DB_URL"),
                authorityUrl, restoredUrl, Path.of(checkpoint.getProperty("sourceRoot")), Path.of(rootValue));
        String[] materialRef = checkpoint.getProperty("materialRef").split("@", -1);
        assertThat(materialRef).hasSize(2);
        Path root = Path.of(rootValue);
        var db = new JdbcTemplate(new DriverManagerDataSource(restoredUrl, owner, secret));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(1);
        int receipts = Integer.parseInt(checkpoint.getProperty("receipts"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class))
                .isEqualTo(receipts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class))
                .isEqualTo(receipts);
        String fileKey = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                String.class, materialRef[0]);
        String sha = db.queryForObject("SELECT sha256 FROM enf3_material WHERE material_id=?",
                String.class, materialRef[0]);
        Path original = root.resolve("originals").resolve(fileKey + ".blob");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(original)))).isEqualTo(sha);
        assertThat(Files.exists(root.resolve("acceptance.json"))).isTrue();
        Path keyStore = temporary.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "procedure",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "enf-procedure-restore-test",
                "-keypass", "enf-procedure-restore-test", "-noprompt")
                .redirectErrorStream(true).start();
        if (keytool.waitFor() != 0) throw new IllegalStateException("Procedure restore TLS unavailable");
        String[] settings = { "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http",
                "server.port=0", "server.ssl.key-store=" + keyStore.toUri(),
                "server.ssl.key-store-password=enf-procedure-restore-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + restoredUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=https://enf-procedure-dual.example.org",
                "product.identity.audience=https://enf-procedure-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M",
                "product.identity.activation-lifetime=PT30M", "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + root.resolve("originals"),
                "enforcement.v2.procedure.source-file=" + root.resolve("acceptance.json"),
                "enforcement.v2.procedure.approved-basis="
                        + "PROJECT-METHOD|1|section|PROJECT_TEST|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_HANDOFF_REVIEW" };
        try (var app = new SpringApplicationBuilder(ProcedureIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String base = "https://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            String token = ProcedureHttpsLogin.token(trust(), new ObjectMapper(), base,
                    "named-procedure-dual", "strong-procedure-dual-password-1");
            var http = HttpClient.newBuilder().sslContext(trust()).build();
            String report = base + "/v2/enforcement/procedure-checks/RECOVERY-REPORT/versions/1";
            assertThat(read(http, report, token).statusCode()).isEqualTo(200);
            Path held = root.resolve("originals").resolve(fileKey + ".held");
            Files.move(original, held);
            try {
                assertThat(read(http, report, token).statusCode()).isEqualTo(503);
                unchanged(db, receipts);
            } finally { Files.move(held, original); }
            assertThat(read(http, report, token).statusCode()).isEqualTo(200);
            Files.writeString(Path.of(gate + ".restore-ready"), "ready",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            waitFor(Path.of(gate + ".withdrawn"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProductGrantWatermark(db,
                    new JdbcTemplate(new DriverManagerDataSource(authorityUrl, owner, secret)),
                    "https://enf-procedure-dual.example.org"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Authorization history differs from independent high-water mark");
            var staleRead = read(http, report, token);
            assertThat(staleRead.statusCode()).isEqualTo(503);
            assertThat(staleRead.body()).isBlank();
            unchanged(db, receipts);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(original)))).isEqualTo(sha);
            Files.writeString(Path.of(gate + ".verified"), "verified",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.printf("ENF_PROCEDURE_PHYSICAL_RESTORE report=200 missingOriginal=503 "
                    + "restored=200 staleBackupAuthority=503 receipts=%d events=%d originals=1%n", receipts, receipts);
        }
    }

    private static void unchanged(JdbcTemplate db, int receipts) {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class))
                .isEqualTo(receipts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class))
                .isEqualTo(receipts);
    }
    private static void waitFor(Path marker) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        while (!Files.exists(marker) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        assertThat(Files.exists(marker)).as("Current authority withdrawal marker").isTrue();
    }
    private static HttpResponse<String> read(HttpClient http, String url, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static SSLContext trust() throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return tls;
    }
}
