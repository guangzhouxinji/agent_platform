package cn.xinji.agentplatform.enforcement.jobpack;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.PublicInput;
import cn.xinji.agentplatform.security.product.ExistingRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrantWatermarkConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductIdentityRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductIdentityTransportFilter;
import cn.xinji.agentplatform.security.product.ProductIssuerConfiguration;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import cn.xinji.agentplatform.web.ReactFrontendController;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("identity-http")
@SpringBootTest(classes = JobPackHttpsTest.IsolatedJobPack.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true", "enforcement.v2.enabled=true",
        "product.identity.issuer=https://enf-job-pack-http.example.org",
        "product.identity.audience=https://enf-job-pack-api.example.org",
        "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "product.identity.token-verification-window=PT5M", "product.identity.activation-lifetime=PT30M",
        "product.identity.minimum-password-length=12",
        "product.consultation.retention.ref=isolated-retention", "product.consultation.retention.version=1",
        "product.consultation.retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.retention.max-age=PT2H",
        "product.consultation.goal-retention.ref=isolated-goal", "product.consultation.goal-retention.version=1",
        "product.consultation.goal-retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.goal-retention.max-age=PT1H", "product.consultation.goal-retention.max-bytes=16384",
        "spring.datasource.url=jdbc:h2:mem:enf-job-pack-https;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V035__enforcement_v2_material.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V062__enforcement_v2_job_pack.sql,classpath:db/h2/V066__enforcement_real_business_collection.sql",
        "enforcement.v2.job-pack.public-candidates-path="
})
class JobPackHttpsTest {
    private static final String ISSUER = "https://enf-job-pack-http.example.org";
    private static final String ORG = "ORG-ENF-JOB";
    private static final String PACK = "ENF-REAL-JOB-HTTP-001";
    private static Path tlsRoot;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        tlsRoot = Files.createTempDirectory("enf-job-pack-https-");
        Path store = tlsRoot.resolve("server.p12");
        var command = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement", "-dname", "CN=localhost",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "enf-job-pack-test",
                "-keypass", "enf-job-pack-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!command.waitFor(20, TimeUnit.SECONDS) || command.exitValue() != 0) throw new IllegalStateException(output);
        String authorityUrl = "jdbc:h2:mem:enf-job-pack-authority;DB_CLOSE_DELAY=-1";
        var authority = new org.springframework.jdbc.datasource.DriverManagerDataSource(authorityUrl, "sa", "");
        try (var connection = authority.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark VALUES('" + ISSUER
                    + "',0,'READY',NULL,'" + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" + "')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer (singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) VALUES(1,'enf-job-maintainer','"
                    + ORG + "','" + "a".repeat(64) + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> authorityUrl);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("server.ssl.key-store", () -> store.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> "enf-job-pack-test");
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("enforcement.v2.file-root", () -> tlsRoot.resolve("files").toString());
    }

    @AfterAll static void cleanup() throws Exception {
        if (tlsRoot != null) try (var paths = Files.walk(tlsRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Autowired ProductSigningKeys keys;
    @Autowired ProductSubjects subjects;
    @Autowired ProductGrants grants;
    @Autowired ProductGrantWatermark watermark;
    @Autowired RegisteredClientRepository clients;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RealBusinessCollectionTaskStore collectionTasks;
    @Autowired cn.xinji.agentplatform.enforcement.material.MaterialFiles materialFiles;
    @LocalServerPort int port;

    @Test void loginUploadCollectReadAndRevocationFailClosed() throws Exception {
        keys.rotate("enf-job-maintainer");
        var subject = subjects.register("enf-job-operator", "enf-job-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "unique-job-pack-password-1");
        var reviewer = subjects.register("enf-job-reviewer", "enf-job-enrollment");
        subjects.activate(reviewer.subjectId(), reviewer.oneTimeActivation(), "unique-job-pack-password-2");
        clients.save(RegisteredClient.withId("enf-job-pack-client").clientId("enf-job-pack-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                "enf-job-pack-client", "enf-job-pack-client", "https://localhost/callback");
        SSLContext trust = trustAll();
        String token = login(trust, "enf-job-operator", "unique-job-pack-password-1");
        String reviewerToken = login(trust, "enf-job-reviewer", "unique-job-pack-password-2");
        var http = HttpClient.newBuilder().sslContext(trust).build();
        String base = "https://localhost:" + port + "/v2/enforcement/job-packs";
        String create = "{\"id\":\"" + PACK + "\",\"organization\":\"" + ORG
                + "\",\"title\":\"功能验证任务（测试）\",\"purpose\":\"FUNCTIONAL_VALIDATION\"}";
        assertThat(api(http, "POST", base, token, create, "no-grant").statusCode()).isEqualTo(403);
        issue(subject.subjectId(), "CREATE"); issue(subject.subjectId(), "COLLECT");
        var readGrant = issue(subject.subjectId(), "READ");
        assertThat(api(http, "POST", base, token, create, "create-pack").statusCode()).isEqualTo(200);
        byte[] original = "actual uploaded task bytes".getBytes(StandardCharsets.UTF_8);
        var uploaded = multipart(http, base + "/" + PACK + "/evidence", token, "TASK-ORIGINAL", "task.txt", original);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        var evidence = json.readTree(uploaded.body());
        assertThat(evidence.path("sha256").asText()).isEqualTo(hex(original));
        String fact = json.writeValueAsString(java.util.Map.of("fields", java.util.Map.of(
                "externalTaskRef", "REAL-TASK-PENDING", "externalTaskVersion", "1", "originalTaskSha256", hex(original),
                "currentProcess", "等待真实人员确认", "expectedOutput", "辅助任务包"),
                "evidence", List.of(json.convertValue(evidence, java.util.Map.class))));
        assertThat(api(http, "POST", base + "/" + PACK + "/facts/TASK", token, fact, "submit-task").statusCode()).isEqualTo(200);
        var current = api(http, "GET", base + "/" + PACK, token, null, null);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(json.readTree(current.body()).path("state").asText()).isEqualTo("WAITING_DATA");
        assertThat(json.readTree(current.body()).path("findings").toString()).doesNotContain("ACTOR_POSITION");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_file WHERE package_id=?", Integer.class, PACK)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id=?", Integer.class, PACK)).isZero();
        String publicPack = "ENF-JOB-CNIPA-GUIDANCE-CASE-1";
        byte[] publicOriginal = "actual captured CNIPA public bytes".getBytes(StandardCharsets.UTF_8);
        var staged = materialFiles.stage(publicOriginal);
        var publicInput = new PublicInput("ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION",
                "https://www.cnipa.gov.cn/art/2021/4/1/art_2432_170426.html", "国家知识产权局",
                "2021-04-01", "2026-09-29T06:52:02Z", staged.sha256(), staged.key(), "source.html",
                "ENF-PUBLIC-COLLECT-20260929065202", "COLLECTION_ALLOWED", Map.of("realPublicTask", "指导案例1号"), null);
        assertThat(collectionTasks.seed(new RealBusinessCollectionTask.Seed("ENF-RBC-CNIPA-GUIDANCE-CASE-1",
                publicInput.candidateId(), publicPack, "CNIPA 指导案例1号真实业务采集", publicInput.collectionBatchId(),
                publicInput))).isTrue();
        assertThat(api(http, "GET", base + "/collection-tasks", token, null, null).statusCode()).isEqualTo(200);
        String claim = "{\"organization\":\"" + ORG + "\"}";
        assertThat(api(http, "POST", base + "/collection-tasks/ENF-RBC-CNIPA-GUIDANCE-CASE-1/claim",
                token, claim, "claim-public").statusCode()).isEqualTo(403);
        issue(subject.subjectId(), publicPack, "CREATE"); issue(subject.subjectId(), publicPack, "COLLECT");
        issue(subject.subjectId(), publicPack, "READ");
        var claimed = api(http, "POST", base + "/collection-tasks/ENF-RBC-CNIPA-GUIDANCE-CASE-1/claim",
                token, claim, "claim-public");
        assertThat(claimed.statusCode()).isEqualTo(200);
        var claimedJson = json.readTree(claimed.body());
        assertThat(claimedJson.path("task").path("claimedBy").asText()).isEqualTo(subject.subjectId());
        assertThat(claimedJson.path("jobPack").path("state").asText()).isEqualTo("WAITING_REAL_HUMAN_ACTION");
        assertThat(claimedJson.path("jobPack").path("publicInput").path("rawSha256").asText())
                .isEqualTo(staged.sha256());
        assertThat(api(http, "POST", base + "/collection-tasks/ENF-RBC-CNIPA-GUIDANCE-CASE-1/claim",
                token, claim, "claim-public").statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_real_business_task_event WHERE task_id=?",
                Integer.class, "ENF-RBC-CNIPA-GUIDANCE-CASE-1")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_revision WHERE package_id=?",
                Integer.class, publicPack)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id=?",
                Integer.class, publicPack)).isZero();
        assertThat(json.readTree(api(http, "GET", base + "/collection-tasks", reviewerToken, null, null).body()).size()).isZero();
        issue(reviewer.subjectId(), publicPack, "READ");
        var reviewerTasks = json.readTree(api(http, "GET", base + "/collection-tasks", reviewerToken, null, null).body());
        assertThat(reviewerTasks.size()).isEqualTo(1);
        assertThat(reviewerTasks.get(0).path("taskId").asText()).isEqualTo("ENF-RBC-CNIPA-GUIDANCE-CASE-1");
        watermark.change(() -> grants.revoke(readGrant.grantId(), 1, "enf-job-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(api(http, "GET", base + "/" + PACK, token, null, null).statusCode()).isEqualTo(403);
    }

    private ProductGrants.Receipt issue(String subject, String action) {
        return issue(subject, PACK, action);
    }

    private ProductGrants.Receipt issue(String subject, String resourceId, String action) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject, ORG, "ENFORCEMENT",
                "ENF-V2-JOB-PACK", resourceId, "ENFORCEMENT_JOB_PACK", resourceId, 1, action, "JOB_PACK"),
                Instant.now().plusSeconds(3600), 0, "enf-job-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }

    private String login(SSLContext trust, String username, String password) throws Exception {
        var client = HttpClient.newBuilder().sslContext(trust).cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
        var login = send(client, "GET", "https://localhost:" + port + "/login", null, null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        assertThat(send(client, "POST", "https://localhost:" + port + "/login", "username=" + username + "&password=" + password + "&_csrf="
                + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8), "application/x-www-form-urlencoded").statusCode()).isEqualTo(302);
        String verifier = "enf-job-pack-verifier-1234567890123456789012345";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String callback = URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8);
        var authorize = send(client, "GET", "https://localhost:" + port + "/oauth2/authorize?response_type=code&client_id=enf-job-pack-client&redirect_uri="
                + callback + "&scope=openid&state=job&code_challenge=" + challenge + "&code_challenge_method=S256", null, null);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location").orElseThrow()).getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = send(client, "POST", "https://localhost:" + port + "/oauth2/token", "grant_type=authorization_code&client_id=enf-job-pack-client&redirect_uri="
                + callback + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&code_verifier=" + verifier, "application/x-www-form-urlencoded");
        return json.readTree(issued.body()).path("access_token").asText();
    }

    private static HttpResponse<String> multipart(HttpClient client, String url, String token, String ref, String name, byte[] bytes) throws Exception {
        String boundary = "job-pack-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"evidenceRef\"\r\n\r\n" + ref
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(head),
                        HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(tail))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> api(HttpClient client, String method, String url, String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)); if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key); if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> send(HttpClient client, String method, String url, String body, String type) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)); if (type != null) builder.header("Content-Type", type);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static SSLContext trustAll() throws Exception {
        var trust = SSLContext.getInstance("TLS"); trust.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String type) { } public void checkServerTrusted(X509Certificate[] chain, String type) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom()); return trust;
    }
    private static String hex(byte[] bytes) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
            ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
            ProductIdentityTransportFilter.class, EnforcementMaterialConfiguration.class,
            EnforcementJobPackConfiguration.class, EnforcementJobPackController.class, ReactFrontendController.class})
    static class IsolatedJobPack { }
}
