package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import cn.xinji.agentplatform.security.product.ExistingRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrantWatermarkConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductIdentityRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductIdentityTransportFilter;
import cn.xinji.agentplatform.security.product.ProductIssuerConfiguration;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
@SpringBootTest(classes = EnforcementMaterialHttpsTest.IsolatedEnforcement.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true", "enforcement.v2.enabled=true",
        "product.identity.issuer=https://enf-http.example.org",
        "product.identity.audience=https://enf-api.example.org",
        "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "product.identity.token-verification-window=PT5M",
        "product.identity.activation-lifetime=PT30M",
        "product.identity.minimum-password-length=12",
        "product.consultation.retention.ref=isolated-retention-input",
        "product.consultation.retention.version=1",
        "product.consultation.retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.retention.max-age=PT2H",
        "product.consultation.goal-retention.ref=isolated-goal-retention", "product.consultation.goal-retention.version=1",
        "product.consultation.goal-retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "product.consultation.goal-retention.max-age=PT1H",
        "product.consultation.goal-retention.max-bytes=16384",
        "spring.datasource.url=jdbc:h2:mem:enf-https;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V035__enforcement_v2_material.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql"
})
class EnforcementMaterialHttpsTest {
    private static final String ISSUER = "https://enf-http.example.org";
    private static final String ORG = "ENF-HTTP-DEV";
    private static final String SOURCE = "PROJECT-DEV-OBSERVATION";
    private static final String RULE = "ENF-RULE-V1-DEV-MARKING-01";
    private static Path tlsRoot;
    private static MaterialPageHttpsSource pageServer;
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        tlsRoot = Files.createTempDirectory("enf-https-");
        Path store = tlsRoot.resolve("server.p12");
        var command = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-local-https-test", "-keypass",
                "enf-local-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (command.waitFor() != 0) throw new IllegalStateException(output);
        pageServer = new MaterialPageHttpsSource(tlsRoot);
        String authorityUrl = "jdbc:h2:mem:enf-https-authority;DB_CLOSE_DELAY=-1";
        var authority = new org.springframework.jdbc.datasource.DriverManagerDataSource(authorityUrl, "sa", "");
        try (var connection = authority.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/identity-authority/V002__enforcement_source_watermark.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V005__enforcement_delete_watermark.sql")));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark "
                    + "VALUES('" + ISSUER + "',0,'READY',NULL,'"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer "
                    + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                    + "VALUES(1,'enf-http-maintainer','" + ORG + "','" + "a".repeat(64)
                    + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> authorityUrl);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("server.ssl.key-store", () -> store.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> "enf-local-https-test");
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("enforcement.v2.file-root", () -> tlsRoot.resolve("files").toString());
    }
    @AfterAll static void cleanup() throws Exception {
        if (pageServer != null) pageServer.close();
        if (tlsRoot != null) try (var paths = Files.walk(tlsRoot)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @Autowired ProductSigningKeys keys;
    @Autowired ProductSubjects subjects;
    @Autowired ProductGrants grants;
    @Autowired ProductGrantWatermark watermark;
    @Autowired RegisteredClientRepository clients;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Test void loggedInHttpsSourceRuleClueRevisionAndMaterialWithWithdrawal() throws Exception {
        keys.rotate("enf-http-maintainer");
        var subject = subjects.register("enf-http-operator", "enf-http-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "unique-strong-password-1");
        clients.save(RegisteredClient.withId("enf-http-client").clientId("enf-http-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback").redirectUri("https://localhost:" + port + "/app/enforcement/material").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) "
                + "VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)", "enf-http-client", "enf-http-client",
                "https://localhost/callback");
        var trust = SSLContext.getInstance("TLS");
        trust.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        var http = HttpClient.newBuilder().sslContext(trust).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "https://localhost:" + port;
        var login = send(http, "GET", base + "/login", null, null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(login.body());
        assertThat(csrf.find()).isTrue();
        var loggedIn = send(http, "POST", base + "/login", "application/x-www-form-urlencoded",
                "username=enf-http-operator&password=unique-strong-password-1&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8), null);
        assertThat(loggedIn.statusCode()).isEqualTo(302);
        String verifier = "enf-pkce-verifier-unique-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String query = "?response_type=code&client_id=enf-http-client&redirect_uri="
                + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                + "&scope=openid&state=enf-state&code_challenge=" + challenge + "&code_challenge_method=S256";
        var authorize = send(http, "GET", base + "/oauth2/authorize" + query, null, null, null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location").orElseThrow())
                .getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = send(http, "POST", base + "/oauth2/token", "application/x-www-form-urlencoded",
                "grant_type=authorization_code&client_id=enf-http-client&redirect_uri="
                        + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier, null);
        assertThat(issued.statusCode()).isEqualTo(200);
        String token = json.readTree(issued.body()).get("access_token").asText();
        var input = json.createObjectNode().put("id", SOURCE).put("organization", ORG)
                .put("rightsRef", "docs/02#40").put("epoch", 1)
                .put("credentialRef", MaterialPageHttpsSource.CREDENTIAL_REF)
                .put("expiresAt", Instant.now().plusSeconds(3600).toString());
        input.putArray("uses").add("QUERY").add("SAVE").add("DISPLAY");
        assertThat(post(http, base, "/sources", input, null, "no-token").statusCode()).isEqualTo(401);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_source", Integer.class)).isZero();
        var wrongScope = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), "OTHER-ORG",
                "ENFORCEMENT", "ENF-V2-MATERIAL", "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1",
                "ENFORCEMENT_SOURCE", SOURCE, 1, "CONFIGURE", "SOURCE_MANAGEMENT"), Instant.now().plusSeconds(3600),
                0, "enf-http-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(wrongScope.status()).isEqualTo("ACTIVE");
        assertThat(post(http, base, "/sources", input, token, "wrong-org").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_source", Integer.class)).isZero();
        grant(subject.subjectId(), "SOURCE", SOURCE, 1, "CONFIGURE", "SOURCE_MANAGEMENT");
        assertThat(post(http, base, "/sources", input, token, "source-http").statusCode()).isEqualTo(200);
        grant(subject.subjectId(), "SOURCE", SOURCE, 1, "SYNC", "QUERY");
        var records = json.readTree(Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/observations.json"))).get("records");
        var wrongUse = json.createObjectNode().put("sourceId", SOURCE)
                .put("recordId", records.get(0).get("recordId").asText())
                .put("sourceVersion", records.get(0).get("version").asText())
                .put("text", records.get(0).get("sourceText").asText());
        assertThat(post(http, base, "/observations", wrongUse, token, "wrong-use").statusCode())
                .isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_observation", Integer.class)).isZero();
        grant(subject.subjectId(), "SOURCE", SOURCE, 1, "SYNC", "SAVE");
        String syncUrl = base + "/v2/enforcement/sources/" + SOURCE + "/syncs";
        assertThat(json.readTree(send(http, "POST", syncUrl, null, null, token).body())
                .get("state").asText()).isEqualTo("CONTINUE");
        var partialPage = send(http, "POST", syncUrl, null, null, token);
        assertThat(json.readTree(partialPage.body()).get("state").asText()).isEqualTo("PARTIAL_FAILED");
        assertThat(send(http, "POST", syncUrl, null, null, token).statusCode()).isEqualTo(503);
        assertThat(json.readTree(send(http, "POST", syncUrl, null, null, token).body())
                .get("state").asText()).isEqualTo("SUCCEEDED");
        var emptyPage = json.readTree(send(http, "POST", syncUrl, null, null, token).body());
        assertThat(emptyPage.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(emptyPage.get("processed").asInt()).isZero();
        assertThat(db.queryForObject("SELECT cursor_value FROM enf3_sync_cursor WHERE source_id=?",
                String.class, SOURCE)).isEqualTo("p2-3");
        MaterialSourceConfigurationHttpScenarios.run(http, base, token, SOURCE, json, db);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_observation", Integer.class)).isEqualTo(2);
        String first = MaterialDigests.hash(SOURCE, records.get(0).get("recordId").asText(),
                records.get(0).get("version").asText());
        String negative = MaterialDigests.hash(SOURCE, records.get(1).get("recordId").asText(),
                records.get(1).get("version").asText());
        grant(subject.subjectId(), "OBSERVATION", first, 1, "READ", "DISPLAY");
        grant(subject.subjectId(), "OBSERVATION", negative, 1, "READ", "DISPLAY");
        var actors = send(http, "GET", base + "/v2/enforcement/observations/" + first + "/actors",
                null, null, token);
        assertThat(actors.statusCode()).isEqualTo(200);
        assertThat(json.readTree(actors.body()).size()).isEqualTo(2);
        grant(subject.subjectId(), "RULE", RULE, 1, "EDIT_RULE", "RULE_MANAGEMENT");
        grant(subject.subjectId(), "RULE", RULE, 1, "PUBLISH_RULE", "RULE_MANAGEMENT");
        grant(subject.subjectId(), "RULE", RULE, 1, "DISABLE_RULE", "RULE_MANAGEMENT");
        var draft = json.createObjectNode().put("id", RULE).put("sourceId", SOURCE)
                .put("literal", "专利产品").put("basisRef", "docs/02#40");
        assertThat(post(http, base, "/discovery-rules", draft, token, "draft-http").statusCode()).isEqualTo(200);
        int receiptsBeforeTrial = db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class);
        var matched = send(http, "GET", base + "/v2/enforcement/discovery-rules/" + RULE
                + "/trial/" + first, null, null, token);
        var unmatched = send(http, "GET", base + "/v2/enforcement/discovery-rules/" + RULE
                + "/trial/" + negative, null, null, token);
        assertThat(json.readTree(matched.body()).get("outcome").asText()).isEqualTo("MATCH");
        assertThat(json.readTree(unmatched.body()).get("outcome").asText()).isEqualTo("NO_MATCH");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class)).isEqualTo(receiptsBeforeTrial);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isZero();
        MaterialRuleHttpNegativeScenarios.run(http, base, token, subject.subjectId(),
                records.get(0).get("sourceText").asText(), json, db, grants, watermark, RULE);
        var review = json.createObjectNode().put("revision", 1)
                .put("positiveObservationId", first).put("negativeObservationId", negative);
        assertThat(post(http, base, "/discovery-rules/" + RULE + "/publish", review,
                token, "publish-http").statusCode()).isEqualTo(200);
        grant(subject.subjectId(), "OBSERVATION", first, 1, "CREATE_CLUE", "QUERY");
        var clueRequest = json.createObjectNode().put("observationId", first).put("ruleId", RULE);
        String clue = result(post(http, base, "/discoveries", clueRequest, token, "clue-http"));
        grant(subject.subjectId(), "CLUE", clue, 1, "REVISE", "SAVE");
        var revision = json.createObjectNode().put("expectedVersion", 1)
                .put("correctedText", records.get(0).get("sourceText").asText() + " 待核")
                .put("reason", "人工核对原文");
        assertThat(post(http, base, "/clues/" + clue + "/revisions", revision,
                token, "revision-http").statusCode()).isEqualTo(200);
        grant(subject.subjectId(), "CLUE", clue, 2, "REGISTER_MATERIAL", "SAVE");
        byte[] bytes = Files.readAllBytes(Path.of("data/enforcement/material-dev-product-contract-v1/original-M-01.txt"));
        var material = json.createObjectNode().put("clueId", clue).put("bytes", Base64.getEncoder().encodeToString(bytes))
                .put("rightsRef", "docs/02#40").put("expiresAt", Instant.now().plusSeconds(3600).toString())
                .put("collector", subject.subjectId()).put("collectedAt", "2026-09-27");
        material.putArray("uses").add("SAVE").add("PREVIEW");
        MaterialDroppedResponse.send(trust, port, "/v2/enforcement/materials", json.writeValueAsString(material),
                token, "material-http");
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='material-http'",
                Integer.class) == 0 && System.nanoTime() < until) Thread.sleep(20);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='material-http'",
                Integer.class)).isEqualTo(1);
        String materialId = result(post(http, base, "/materials", material, token, "material-http"));
        assertThat(result(post(http, base, "/materials", material, token, "material-http")))
                .isEqualTo(materialId);
        var previewGrant = grant(subject.subjectId(), "MATERIAL", materialId, 1, "PREVIEW", "PREVIEW");
        var preview = send(http, "GET", base + "/v2/enforcement/materials/" + materialId + "/preview",
                null, null, token);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(bytes);
        grant(subject.subjectId(), "MATERIAL", materialId, 1, "COLLECT_ATTEST", "SAVE");
        grant(subject.subjectId(), "MATERIAL", materialId, 2, "VERIFY", "SAVE");
        var materialReadGrant = grant(subject.subjectId(), "MATERIAL", materialId, 2, "READ", "DISPLAY");
        var collection = json.createObjectNode().put("expectedVersion", 1)
                .put("sourceRef", "OBS-001/original-M-01").put("reason", "received original file");
        assertThat(post(http, base, "/materials/" + materialId + "/collection", collection,
                token, "collect-http").statusCode()).isEqualTo(200);
        assertThat(post(http, base, "/materials/" + materialId + "/collection", collection,
                token, "collect-http").statusCode()).isEqualTo(200);
        var actions = send(http, "GET", base + "/v2/enforcement/materials/" + materialId + "/actions",
                null, null, token);
        assertThat(actions.statusCode()).isEqualTo(200);
        assertThat(json.readTree(actions.body()).size()).isEqualTo(2);
        var verification = json.createObjectNode().put("expectedVersion", 2)
                .put("reason", "file digest checked");
        assertThat(post(http, base, "/materials/" + materialId + "/verification", verification,
                token, "self-verify-http").statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='self-verify-http'",
                Integer.class)).isZero();
        MaterialVerifierHttpScenarios.run(trust, base, materialId, bytes, tlsRoot.resolve("files"),
                subjects, grants, watermark, db, json);
        MaterialVerifierHttpScenarios.advanceReadGrant(grants, watermark, materialReadGrant,
                subject.subjectId(), materialId);
        int materialRows = db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class);
        int eventRows = db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class);
        long fileCount;
        try (var paths = Files.walk(tlsRoot.resolve("files"))) {
            fileCount = paths.filter(Files::isRegularFile).count();
        }
        watermark.change(() -> grants.revoke(previewGrant.grantId(), 1, "enf-http-maintainer", UUID.randomUUID().toString(), "b".repeat(64)));
        var currentPreviewGrant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                subject.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-MATERIAL", "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1",
                "MATERIAL", materialId, 3, "PREVIEW", "PREVIEW"), Instant.now().plusSeconds(3600), 2,
                "enf-http-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        try (var second = new MaterialSecondHttpsInstance(tlsRoot)) {
            second.assertPreviewWithdrawal(base, http, materialId, token, bytes, () -> watermark.change(() ->
                    grants.revoke(currentPreviewGrant.grantId(), 1, "enf-http-maintainer",
                            UUID.randomUUID().toString(), "b".repeat(64))));
        }
        MaterialSourceInFlightHttpScenarios.run(pageServer, http, base, token, SOURCE, db,
                tlsRoot.resolve("files"));
        MaterialSyncRecordHttpScenarios.run(pageServer, http, base, token, SOURCE, json, db);
        eventRows = db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class);
        MaterialDeletionHttpScenarios.oldReceiptCannotReviveDeletedSourceMaterial(http, base, token, materialId,
                json.writeValueAsString(material), db, watermark.independentDatabase(), SOURCE,
                db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?", String.class, materialId));
        var revokedPreview = send(http, "GET", base + "/v2/enforcement/materials/" + materialId + "/preview",
                null, null, token);
        assertThat(revokedPreview.statusCode()).isEqualTo(403);
        assertThat(revokedPreview.body()).doesNotContain("虚构");
        watermark.independentDatabase().update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?", SOURCE);
        assertThat(post(http, base, "/materials", material, token, "material-http").statusCode())
                .isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='material-http'",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(materialRows);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class)).isEqualTo(eventRows);
        try (var paths = Files.walk(tlsRoot.resolve("files"))) {
            assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(fileCount);
        }
        assertThat(send(http, "GET", base + "/v2/enforcement/materials/" + materialId + "/preview",
                null, null, token).statusCode()).isEqualTo(403);
        System.out.printf("ENF_V2_HTTPS login=PKCE source=2 observations=3 rule=REVIEWED "
                + "clue=1 revision=1 material=%d receipt=DISCONNECT_REPLAY withdrawal=DENIED%n", bytes.length);
    }
    private ProductGrants.Receipt grant(String actor, String type, String id, long version, String action, String purpose) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG, "ENFORCEMENT",
                "ENF-V2-MATERIAL", "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1",
                "SOURCE".equals(type) ? "ENFORCEMENT_SOURCE" : type, id, version, action, purpose),
                Instant.now().plusSeconds(3600), 0, "enf-http-maintainer", UUID.randomUUID().toString(),
                "a".repeat(64)));
    }
    private HttpResponse<String> post(HttpClient http, String base, String path, JsonNode body,
                                      String token, String key) throws Exception {
        return send(http, "POST", base + "/v2/enforcement" + path,
                "application/json", json.writeValueAsString(body), token, key);
    }
    private String result(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("resultId").asText();
    }
    private static HttpResponse<String> send(HttpClient client, String method, String url,
                                             String type, String body, String token) throws Exception {
        return send(client, method, url, type, body, token, null);
    }
    private static HttpResponse<String> send(HttpClient client, String method, String url,
                                             String type, String body, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (type != null) request.header("Content-Type", type);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    // Explicitly registered by this test; no configuration stereotype, so other tests cannot scan it.
    @EnableAutoConfiguration
    @Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
            ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
            ProductIdentityTransportFilter.class,
            EnforcementMaterialConfiguration.class, EnforcementMaterialController.class,
            cn.xinji.agentplatform.web.ReactFrontendController.class})
    static class IsolatedEnforcement {
        @Bean @Primary MaterialPageSource materialTestPageSource() {
            return pageServer.connector(SOURCE);
        }
    }
}
