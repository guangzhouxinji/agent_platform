package cn.xinji.agentplatform.enforcement.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class HandoffIndependentRestoreDm8Test {
    @TempDir Path temporary;

    @Test void restoredHandoffDbOriginalsAndReceiptsObeyUnrestoredCurrentAuthority() throws Exception {
        String gate = System.getenv("ENF_HANDOFF_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_HANDOFF_RESTORED_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String restoredRoot = System.getenv("ENF_HANDOFF_RESTORED_ROOT");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && authorityUrl != null
                && restoredRoot != null && !restoredUrl.equals(authorityUrl),
                "Dedicated HANDOFF DB and original-file physical restore required");
        Path marker = Path.of(gate);
        var checkpoint = new Properties();
        try (var input = Files.newInputStream(marker)) { checkpoint.load(input); }
        String owner = checkpoint.getProperty("owner");
        String secret = checkpoint.getProperty("secret");
        var source = new DriverManagerDataSource(restoredUrl, owner, secret);
        var db = new JdbcTemplate(source);
        Path root = Path.of(restoredRoot);
        var caseInput = new ObjectMapper().readTree(root.resolve("case.json").toFile());
        assertThat(caseInput.path("materials").size()).isEqualTo(3);
        for (var ref : caseInput.path("materials")) {
            String fileKey = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                    String.class, ref.path("id").asText());
            var bytes = Files.readAllBytes(root.resolve("originals").resolve(fileKey + ".blob"));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(ref.path("sha256").asText());
        }
        int receipts = Integer.parseInt(checkpoint.getProperty("receipts"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                .isEqualTo(receipts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class))
                .isEqualTo(receipts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_work_package_revision", Integer.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_request", Integer.class))
                .isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization", Integer.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class))
                .isEqualTo(3);
        // The cold point predates the concurrently introduced client-control schema.
        boolean migrated;
        try { db.queryForObject("SELECT COUNT(*) FROM id2_client_current", Integer.class); migrated = true; }
        catch (org.springframework.dao.DataAccessException absent) { migrated = false; }
        if (!migrated) try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V057__product_oauth_client_control.sql")));
        }
        Integer clientCount = db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=?",
                Integer.class, "enf-handoff-client");
        if (clientCount == 0) db.update("INSERT INTO id2_client_current(client_id,registered_id,"
                + "redirect_uri,status,version,created_at) SELECT client_id,id,?,'ACTIVE',1,? "
                + "FROM oauth2_registered_client WHERE client_id=?", "https://localhost/callback",
                Timestamp.from(Instant.now()), "enf-handoff-client");
        Path store = temporary.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "handoff",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-handoff-https-test",
                "-keypass", "enf-handoff-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + store.toUri(),
                "server.ssl.key-store-password=enf-handoff-https-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + restoredUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=https://enf-handoff-dual.example.org",
                "product.identity.audience=https://enf-handoff-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M",
                "product.identity.activation-lifetime=PT30M", "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + root.resolve("originals"),
                "enforcement.v2.evidence.case-input-file=" + root.resolve("case.json"),
                "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
                "enforcement.v2.evidence.approved-basis=PROJECT_DOCUMENT_REVIEW|docs/57#6.24|1|PROJECT-CASE-001"
        };
        try (var app = new SpringApplicationBuilder(HandoffIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String base = "https://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            var http = client();
            String claimant = new ObjectMapper().readTree(db.queryForObject(
                    "SELECT payload FROM enf5_human_request WHERE request_id=?", String.class,
                    "REQUEST-DUAL")).path("claimant").asText();
            String loginName = db.queryForObject("SELECT login_name FROM id2_subject WHERE subject_id=?",
                    String.class, claimant);
            String password = "named-handoff-dual-a".equals(loginName)
                    ? "strong-handoff-dual-a-1" : "strong-handoff-dual-b-1";
            String token = login(base, loginName, password);
            String path = "/v2/enforcement/human-requests/REQUEST-DUAL";
            String claim = path + "/claims";
            String key = checkpoint.getProperty("key");
            assertThat(request(http, "GET", base + path, token, null, null).statusCode()).isEqualTo(200);
            assertThat(request(http, "POST", base + claim, token,
                    "{\"expectedVersion\":1}", key).statusCode()).isEqualTo(200);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                    .isEqualTo(receipts);
            String fileKey = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                    String.class, caseInput.path("materials").get(0).path("id").asText());
            Path original = root.resolve("originals").resolve(fileKey + ".blob");
            Path held = root.resolve("originals").resolve(fileKey + ".held");
            Files.move(original, held);
            try {
                assertThat(request(http, "GET", base + path, token, null, null).statusCode())
                        .isNotEqualTo(200);
                assertThat(request(http, "POST", base + claim, token,
                        "{\"expectedVersion\":1}", key).statusCode()).isNotEqualTo(200);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                        .isEqualTo(receipts);
            } finally { Files.move(held, original); }
            assertThat(request(http, "GET", base + path, token, null, null).statusCode()).isEqualTo(200);
            Files.writeString(marker.resolveSibling(marker.getFileName() + ".restore-ready"), "ready");
            Path withdrawn = marker.resolveSibling(marker.getFileName() + ".withdrawn");
            Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
            while (!Files.exists(withdrawn) && Instant.now().isBefore(deadline)) Thread.sleep(250);
            assertThat(Files.exists(withdrawn)).isTrue();
            assertThat(request(http, "GET", base + path, token, null, null).statusCode()).isNotEqualTo(200);
            assertThat(request(http, "POST", base + claim, token,
                    "{\"expectedVersion\":1}", key).statusCode()).isNotEqualTo(200);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                    .isEqualTo(receipts);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class))
                    .isEqualTo(receipts);
            for (var ref : caseInput.path("materials")) {
                String keyFromDb = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                        String.class, ref.path("id").asText());
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                        Files.readAllBytes(root.resolve("originals").resolve(keyFromDb + ".blob")))))
                        .isEqualTo(ref.path("sha256").asText());
            }
            System.out.printf("ENF_HANDOFF_OLD_BACKUP_CURRENT_AUTHORITY before=200/200 "
                    + "missingFile=DENIED after=DENIED/DENIED receipts=%d events=%d files=3%n",
                    receipts, receipts);
            Files.writeString(marker.resolveSibling(marker.getFileName() + ".verified"), "verified");
        }
    }

    private static HttpClient client() throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return HttpClient.newBuilder().sslContext(tls).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String login(String base, String username, String password) throws Exception {
        var http = client();
        var page = request(http, "GET", base + "/login", null, null, null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        assertThat(csrf.find()).isTrue();
        assertThat(request(http, "POST", base + "/login", null,
                "username=" + username + "&password=" + password + "&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8), null).statusCode())
                .isEqualTo(302);
        String verifier = "enf-handoff-restore-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8);
        var authorize = request(http, "GET", base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-handoff-client&redirect_uri=" + redirect
                + "&scope=openid&state=restore&code_challenge=" + challenge
                + "&code_challenge_method=S256", null, null, null);
        assertThat(authorize.statusCode()).as(authorize.body()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers()
                .firstValue("Location").orElseThrow()).getRawQuery().split("code=")[1].split("&")[0],
                StandardCharsets.UTF_8);
        var issued = request(http, "POST", base + "/oauth2/token", null,
                "grant_type=authorization_code&client_id=enf-handoff-client&redirect_uri=" + redirect
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier, null);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
        return new ObjectMapper().readTree(issued.body()).path("access_token").asText();
    }

    private static HttpResponse<String> request(HttpClient http, String method, String url,
                                                String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", url.endsWith("/login") || url.endsWith("/oauth2/token")
                ? "application/x-www-form-urlencoded" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
