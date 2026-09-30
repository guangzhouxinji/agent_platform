package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialCollectionService;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialProductService;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.web.ReactFrontendController;
import cn.xinji.agentplatform.security.product.ExistingRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrantWatermarkConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductIdentityRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductIdentityTransportFilter;
import cn.xinji.agentplatform.security.product.ProductIssuerConfiguration;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("identity-http")
@SpringBootTest(classes = EvidenceRuleHttpsTest.IsolatedEvidence.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true", "enforcement.v2.enabled=true",
        "enforcement.v2.evidence.approved-basis=PROJECT_DOCUMENT_REVIEW|docs/57#6.24|1|PROJECT-CASE-001",
        "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
        "product.identity.issuer=https://enf-evidence-http.example.org",
        "product.identity.audience=https://enf-evidence-api.example.org",
        "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "product.identity.token-verification-window=PT5M",
        "product.identity.activation-lifetime=PT30M",
        "product.identity.minimum-password-length=12",
        "product.consultation.retention.ref=isolated-retention-input",
        "product.consultation.retention.version=1",
        "product.consultation.retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.retention.max-age=PT2H",
        "product.consultation.goal-retention.ref=isolated-goal-retention",
        "product.consultation.goal-retention.version=1",
        "product.consultation.goal-retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.goal-retention.max-age=PT1H",
        "product.consultation.goal-retention.max-bytes=16384",
        "spring.datasource.url=jdbc:h2:mem:enf-evidence-https;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V035__enforcement_v2_material.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql,file:db/dm8/V050__enforcement_v2_evidence_rules.sql,file:db/dm8/V051__enforcement_v2_evidence_publication.sql,file:db/dm8/V052__enforcement_v2_evidence_organizations.sql"
})
class EvidenceRuleHttpsTest {
    private static final String ISSUER = "https://enf-evidence-http.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    private static Path tlsRoot;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        tlsRoot = Files.createTempDirectory("enf-evidence-https-");
        Path store = tlsRoot.resolve("server.p12");
        var command = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-evidence-test", "-keypass",
                "enf-evidence-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (command.waitFor() != 0) throw new IllegalStateException(output);
        String authorityUrl = "jdbc:h2:mem:enf-evidence-authority;DB_CLOSE_DELAY=-1";
        var authority = new org.springframework.jdbc.datasource.DriverManagerDataSource(authorityUrl, "sa", "");
        try (var connection = authority.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/identity-authority/V002__enforcement_source_watermark.sql")));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/identity-authority/V005__enforcement_delete_watermark.sql")));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark "
                    + "VALUES('" + ISSUER + "',0,'READY',NULL,'"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer "
                    + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                    + "VALUES(1,'enf-evidence-maintainer','" + ORG + "','" + "a".repeat(64)
                    + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> authorityUrl);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("server.ssl.key-store", () -> store.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> "enf-evidence-test");
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("enforcement.v2.file-root", () -> tlsRoot.resolve("files").toString());
        properties.add("enforcement.v2.evidence.case-input-file", () -> tlsRoot.resolve("case.json").toString());
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
    @Autowired MaterialRepository materials;
    @Autowired MaterialFiles files;
    @Autowired EvidenceRuleStore evidenceRules;
    @Autowired JwtDecoder decoder;
    @LocalServerPort int port;

    @Test void loginDraftReadAndWithdrawnReceiptUseV2Https() throws Exception {
        keys.rotate("enf-evidence-maintainer");
        var subject = subjects.register("enf-evidence-operator", "enf-evidence-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "unique-evidence-password-1");
        clients.save(RegisteredClient.withId("enf-evidence-client").clientId("enf-evidence-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback")
                .redirectUri("https://localhost:" + port + "/app/enforcement/evidence").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) "
                + "VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)", "enf-evidence-client",
                "enf-evidence-client", "https://localhost/callback");
        var trust = SSLContext.getInstance("TLS");
        trust.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        var http = HttpClient.newBuilder().sslContext(trust).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "https://localhost:" + port;
        var login = send(http, "GET", base + "/login", null, null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        assertThat(send(http, "POST", base + "/login", null,
                "username=enf-evidence-operator&password=unique-evidence-password-1&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").statusCode()).isEqualTo(302);
        String verifier = "enf-evidence-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorize = send(http, "GET", base + "/oauth2/authorize?response_type=code&client_id=enf-evidence-client"
                + "&redirect_uri=" + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                + "&scope=openid&state=enf-evidence&code_challenge=" + challenge + "&code_challenge_method=S256",
                null, null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location").orElseThrow())
                .getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = send(http, "POST", base + "/oauth2/token", null,
                "grant_type=authorization_code&client_id=enf-evidence-client&redirect_uri="
                        + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier, "application/x-www-form-urlencoded");
        assertThat(issued.statusCode()).isEqualTo(200);
        String token = json.readTree(issued.body()).get("access_token").asText();
        var reviewer = subjects.register("enf-evidence-reviewer", "enf-evidence-reviewer-enrollment");
        subjects.activate(reviewer.subjectId(), reviewer.oneTimeActivation(), "unique-evidence-reviewer-1");
        String reviewerToken = EvidenceHttpsLogin.token(trust, json, base,
                "enf-evidence-reviewer", "unique-evidence-reviewer-1");
        var input = json.createObjectNode().put("id", "RULE-001").put("organization", ORG)
                .put("caseType", "PROJECT_DOCUMENT_REVIEW").put("basisRef", "docs/57#6.24");
        input.putArray("matters").addObject().put("id", "ITEM_IDENTITY").put("required", true)
                .put("expectedMaterial", "line-prefix:The submitted item is labeled ");
        input.withArray("matters").addObject().put("id", "EVENT_TIME").put("required", true)
                .put("expectedMaterial", "line-prefix:The observed event time is ");
        assertThat(request(http, "POST", base + "/v2/enforcement/evidence-rules", null,
                json.writeValueAsString(input), "no-grant").statusCode()).isEqualTo(401);
        assertThat(request(http, "POST", base + "/v2/enforcement/evidence-rules", token,
                json.writeValueAsString(input), "no-grant").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isZero();
        var grant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-EVIDENCE", "ENF-EVIDENCE-V2-ACCEPTANCE-01",
                "EVIDENCE_RULE", "RULE-001", 1, "EDIT_RULE", "RULE_MANAGEMENT"),
                Instant.now().plusSeconds(3600), 0, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(grant.status()).isEqualTo("ACTIVE");
        var created = request(http, "POST", base + "/v2/enforcement/evidence-rules", token,
                json.writeValueAsString(input), "draft-1");
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", base + "/v2/enforcement/evidence-rules", token,
                json.writeValueAsString(input), "draft-1").statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", base + "/v2/enforcement/evidence-rules", token,
                json.writeValueAsString(input), "new-key").statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(1);
        var readGrant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                "ENF-EVIDENCE-V2-ACCEPTANCE-01", "EVIDENCE_RULE", "RULE-001", 1,
                "READ_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(readGrant.status()).isEqualTo("ACTIVE");
        assertThat(request(http, "GET", base + "/v2/enforcement/evidence-rules/RULE-001/versions/1",
                token, null, null).statusCode()).isEqualTo(200);
        var trialInput = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("caseVersion", "1");
        var trialMaterials = trialInput.putArray("materials");
        var trialManifest = json.readTree(Path.of("data/enforcement/evidence-v2-acceptance-v1/manifest.json").toFile());
        trialManifest.path("materials").forEach(item -> trialMaterials.addObject()
                .put("id", item.path("ref").asText()).put("version", item.path("version").asText())
                .put("sha256", item.path("sha256").asText()));
        String trialUrl = base + "/v2/enforcement/evidence-rules/RULE-001/versions/1/trials";
        String trialBody = json.writeValueAsString(trialInput);
        assertThat(request(http, "POST", trialUrl, token, trialBody, null).statusCode()).isEqualTo(403);
        var caseGrant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                "ENF-EVIDENCE-V2-ACCEPTANCE-01", "CASE", "PROJECT-CASE-001", 1,
                "READ", "ANALYZE"), Instant.now().plusSeconds(3600), 0,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(caseGrant.status()).isEqualTo("ACTIVE");
        for (var item : trialManifest.path("materials")) {
            var grantForMaterial = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                    subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                    "ENF-EVIDENCE-V2-ACCEPTANCE-01", "EVIDENCE", item.path("ref").asText(), 1,
                    "READ", "ANALYZE"), Instant.now().plusSeconds(3600), 0,
                    "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
            assertThat(grantForMaterial.status()).isEqualTo("ACTIVE");
        }
        var trialResponse = request(http, "POST", trialUrl, token, trialBody, null);
        assertThat(trialResponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(trialResponse.body()).path("matters").get(0).path("coverage").asText())
                .isEqualTo("CONFLICT");
        assertThat(json.readTree(trialResponse.body()).path("unrelatedMaterials").get(0).asText())
                .isEqualTo("PROJECT-RECORD-C");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(1);
        var incorrectDigest = trialInput.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) incorrectDigest.path("materials").get(0))
                .put("sha256", "0".repeat(64));
        assertThat(request(http, "POST", trialUrl, token, json.writeValueAsString(incorrectDigest), null)
                .statusCode()).isEqualTo(403);
        watermark.change(() -> grants.revoke(caseGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "POST", trialUrl, token, trialBody, null).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(1);
        var review = json.createObjectNode().put("basisVersion", "1").put("scope", "PROJECT-CASE-001")
                .put("reviewRef", "REVIEW-001");
        review.putArray("positive").addObject().put("id", "positive")
                .putArray("observedMatters").add("ITEM_IDENTITY").add("EVENT_TIME");
        ((com.fasterxml.jackson.databind.node.ObjectNode) review.withArray("positive").get(0))
                .putArray("expectedMissing");
        review.putArray("negative").addObject().put("id", "negative")
                .putArray("observedMatters").add("ITEM_IDENTITY");
        var expectedMissing = ((com.fasterxml.jackson.databind.node.ObjectNode)
                review.withArray("negative").get(0)).putArray("expectedMissing");
        expectedMissing.add("EVENT_TIME");
        String publishUrl = base + "/v2/enforcement/evidence-rules/RULE-001/publications";
        String reviewBody = json.writeValueAsString(review);
        assertThat(request(http, "POST", publishUrl, token, reviewBody, "publish-no-grant")
                .statusCode()).isEqualTo(403);
        var publishGrant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                "ENF-EVIDENCE-V2-ACCEPTANCE-01", "EVIDENCE_RULE", "RULE-001", 1,
                "PUBLISH_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(publishGrant.status()).isEqualTo("ACTIVE");
        expectedMissing.removeAll();
        assertThat(request(http, "POST", publishUrl, token, json.writeValueAsString(review), "bad-review")
                .statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isZero();
        assertThat(request(http, "POST", publishUrl, token, reviewBody, "publish-1")
                .statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", publishUrl, token, reviewBody, "publish-1")
                .statusCode()).isEqualTo(200);
        watermark.change(() -> grants.revoke(readGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        var readPublished = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                "ENF-EVIDENCE-V2-ACCEPTANCE-01", "EVIDENCE_RULE", "RULE-001", 2,
                "READ_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 2,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(readPublished.status()).isEqualTo("ACTIVE");
        String publishedUrl = base + "/v2/enforcement/evidence-rules/RULE-001/versions/2";
        assertThat(json.readTree(request(http, "GET", publishedUrl, token, null, null).body())
                .get("state").asText()).isEqualTo("ACTIVE");
        var publicationView = request(http, "GET", publishedUrl + "/publication", token, null, null);
        assertThat(publicationView.statusCode()).isEqualTo(200);
        assertThat(json.readTree(publicationView.body()).get("reviewRef").asText())
                .isEqualTo("REVIEW-001");
        assertThat(json.readTree(publicationView.body()).get("reviewDigest").asText())
                .matches("[0-9a-f]{64}");
        String organizationUrl = new EvidenceOrganizationHttpsScenario(grants, watermark, db, json,
                materials, files, decoder, tlsRoot).run(http, base, token, subject.subjectId(),
                reviewerToken, reviewer.subjectId());
        String disableUrl = publishedUrl + "/disable";
        assertThat(request(http, "POST", disableUrl, token, null, "disable-no-grant")
                .statusCode()).isEqualTo(403);
        var disableGrant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                "ENF-EVIDENCE-V2-ACCEPTANCE-01", "EVIDENCE_RULE", "RULE-001", 2,
                "DISABLE_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(disableGrant.status()).isEqualTo("ACTIVE");
        assertThat(request(http, "POST", disableUrl, token, null, "disable-1")
                .statusCode()).isEqualTo(200);
        assertThat(json.readTree(request(http, "GET", publishedUrl, token, null, null).body())
                .get("state").asText()).isEqualTo("DISABLED");
        assertThat(request(http, "GET", organizationUrl + "/ORG-CASE-001/versions/2", token,
                null, null).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request(http, "GET", publishedUrl + "/publication", token, null, null).body())
                .get("basisVersion").asText()).isEqualTo("1");
        watermark.change(() -> grants.revoke(publishGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "POST", publishUrl, token, reviewBody, "publish-1")
                .statusCode()).isEqualTo(403);
        watermark.change(() -> grants.revoke(grant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "POST", base + "/v2/enforcement/evidence-rules", token,
                json.writeValueAsString(input), "draft-1").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_event", Integer.class)).isEqualTo(3);
        System.out.println("ENF_V2_EVIDENCE_RULE_HTTP login=PKCE draft=DRAFT publication=ACTIVE disable=DISABLED withdrawal=403 receipts=3 events=3");
        new EvidenceRuleHistoryHttpsScenario(grants, watermark, db, json).run(http, base,
                token, subject.subjectId(), input, reviewBody, publishUrl, publishedUrl,
                readPublished.grantId());
    }

    private static HttpResponse<String> send(HttpClient client, String method, String url,
                                             String token, String body) throws Exception {
        return send(client, method, url, token, body, null);
    }

    private static HttpResponse<String> send(HttpClient client, String method, String url,
                                             String token, String body, String type) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (type != null) request.header("Content-Type", type);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> request(HttpClient client, String method, String url,
                                                String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
            ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
            ProductIdentityTransportFilter.class, EnforcementMaterialConfiguration.class,
            EnforcementEvidenceRuleConfiguration.class, EnforcementEvidenceRuleController.class,
            EnforcementEvidenceOrganizationController.class, ReactFrontendController.class})
    static class IsolatedEvidence { }
}
