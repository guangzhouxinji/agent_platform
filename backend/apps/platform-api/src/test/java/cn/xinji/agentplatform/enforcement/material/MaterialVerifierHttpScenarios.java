package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialVerifierHttpScenarios {
    private MaterialVerifierHttpScenarios() { }

    static void advanceReadGrant(ProductGrants grants, ProductGrantWatermark watermark,
                                 ProductGrants.Receipt oldGrant, String actor, String materialId) {
        watermark.change(() -> grants.revoke(oldGrant.grantId(), 1, "enf-http-maintainer",
                UUID.randomUUID().toString(), "b".repeat(64)));
        grant(grants, watermark, actor, materialId, 3, "READ", "DISPLAY", 2);
    }

    static void run(SSLContext tls, String base, String materialId, byte[] original, Path root,
                    ProductSubjects subjects, ProductGrants grants, ProductGrantWatermark watermark,
                    JdbcTemplate db, ObjectMapper json) throws Exception {
        String loginName = "enf-http-independent-verifier";
        String password = "unique-strong-verifier-password-1";
        var verifier = subjects.register(loginName, "enf-http-maintainer");
        subjects.activate(verifier.subjectId(), verifier.oneTimeActivation(), password);
        grant(grants, watermark, verifier.subjectId(), materialId, 2, "VERIFY", "SAVE");
        grant(grants, watermark, verifier.subjectId(), materialId, 3, "READ", "DISPLAY");
        var http = HttpClient.newBuilder().sslContext(tls).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var login = request(http, "GET", base + "/login", null, null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        var loggedIn = request(http, "POST", base + "/login", "application/x-www-form-urlencoded",
                "username=" + loginName + "&password=" + password + "&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8), null);
        assertThat(loggedIn.statusCode()).isEqualTo(302);
        String pkce = "enf-independent-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pkce.getBytes(StandardCharsets.US_ASCII)));
        String redirect = URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8);
        var authorize = request(http, "GET", base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-http-client&redirect_uri=" + redirect
                + "&scope=openid&state=enf-verifier-state&code_challenge=" + challenge
                + "&code_challenge_method=S256", null, null, null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location").orElseThrow())
                .getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = request(http, "POST", base + "/oauth2/token", "application/x-www-form-urlencoded",
                "grant_type=authorization_code&client_id=enf-http-client&redirect_uri=" + redirect
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + pkce, null);
        assertThat(issued.statusCode()).isEqualTo(200);
        String token = json.readTree(issued.body()).get("access_token").asText();
        var body = json.createObjectNode().put("expectedVersion", 2).put("reason", "independent bytes checked");
        var verified = http.send(HttpRequest.newBuilder(URI.create(base + "/v2/enforcement/materials/"
                + materialId + "/verification")).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "independent-verify-http")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
        var actions = request(http, "GET", base + "/v2/enforcement/materials/" + materialId + "/actions",
                null, null, token);
        assertThat(actions.statusCode()).as(actions.body()).isEqualTo(200);
        assertThat(json.readTree(actions.body()).size()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT state FROM enf3_material WHERE material_id=?",
                String.class, materialId)).isEqualTo("VERIFIED");
        assertThat(db.queryForObject("SELECT revision FROM enf3_material WHERE material_id=?",
                Long.class, materialId)).isEqualTo(3L);
        String fileKey = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                String.class, materialId);
        assertThat(Files.readAllBytes(root.resolve(fileKey + ".blob"))).isEqualTo(original);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='self-verify-http'",
                Integer.class)).isZero();
        System.out.println("ENF_V2_M03_HTTP login=PKCE verifier=INDEPENDENT state=VERIFIED "
                + "actions=3 selfReceipt=0 original=UNCHANGED");
    }

    private static HttpResponse<String> request(HttpClient http, String method, String url,
                                                String type, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (type != null) request.header("Content-Type", type);
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String actor,
                              String materialId, long version, String action, String purpose) {
        grant(grants, watermark, actor, materialId, version, action, purpose, 0);
    }

    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String actor,
                              String materialId, long version, String action, String purpose, long epoch) {
        watermark.change(() -> grants.issue(new ProductGrants.Scope("https://enf-http.example.org", actor,
                "ENF-HTTP-DEV", "ENFORCEMENT", "ENF-V2-MATERIAL", "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1",
                "MATERIAL", materialId, version, action, purpose), Instant.now().plusSeconds(3600), epoch,
                "enf-http-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }
}
