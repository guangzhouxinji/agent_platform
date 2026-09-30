package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
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
@SpringBootTest(classes = ProcedureHttpsTest.IsolatedProcedure.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true", "enforcement.v2.enabled=true",
        "enforcement.v2.procedure.source-file=data/enforcement/procedure-v2-acceptance-v1/manifest.json",
        "enforcement.v2.procedure.approved-basis=PROJECT-METHOD|1|section|PROJECT_TEST|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_HANDOFF_REVIEW",
        "product.identity.issuer=https://enf-procedure-http.example.org",
        "product.identity.audience=https://enf-procedure-api.example.org",
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
        "spring.datasource.url=jdbc:h2:mem:enf-procedure-https;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V035__enforcement_v2_material.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql,file:db/dm8/V059__enforcement_v2_procedure.sql"
})
class ProcedureHttpsTest {
    private static final String ISSUER = "https://enf-procedure-http.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    private static Path tlsRoot;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        tlsRoot = Files.createTempDirectory("enf-procedure-https-");
        Path store = tlsRoot.resolve("server.p12");
        var command = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-procedure-test", "-keypass",
                "enf-procedure-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (command.waitFor() != 0) throw new IllegalStateException(output);
        String authorityUrl = "jdbc:h2:mem:enf-procedure-authority;DB_CLOSE_DELAY=-1";
        var authority = new org.springframework.jdbc.datasource.DriverManagerDataSource(authorityUrl, "sa", "");
        try (var connection = authority.getConnection()) {
            for (var script : List.of("V001__grant_watermark.sql", "V002__enforcement_source_watermark.sql",
                    "V005__enforcement_delete_watermark.sql"))
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/identity-authority/" + script)));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark VALUES('"
                    + ISSUER + "',0,'READY',NULL,'"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer "
                    + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                    + "VALUES(1,'enf-procedure-maintainer','" + ORG + "','" + "a".repeat(64)
                    + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> authorityUrl);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("server.ssl.key-store", () -> store.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> "enf-procedure-test");
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("enforcement.v2.file-root", () -> tlsRoot.resolve("files").toString());
        properties.add("enforcement.v2.procedure.source-file", () -> tlsRoot.resolve("acceptance.json").toString());
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
    @LocalServerPort int port;

    @Test void pkceLoginV2DraftTrialAndRevocationFailClosed() throws Exception {
        keys.rotate("enf-procedure-maintainer");
        var subject = subjects.register("enf-procedure-operator", "enf-procedure-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "unique-procedure-password-1");
        String materialRef = ProcedureHttpsMaterialFixture.prepare(materials, files, json,
                tlsRoot.resolve("acceptance.json"), ORG, subject.subjectId());
        issue(subject.subjectId(), "EVIDENCE", materialRef.split("@")[0], 3, "READ", "ANALYZE");
        clients.save(RegisteredClient.withId("enf-procedure-client").clientId("enf-procedure-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback")
                .redirectUri("https://localhost:" + port + "/app/enforcement/procedure")
                .scope("openid").clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) "
                + "VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)", "enf-procedure-client",
                "enf-procedure-client", "https://localhost/callback");
        var trust = SSLContext.getInstance("TLS");
        trust.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        var http = HttpClient.newBuilder().sslContext(trust).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "https://localhost:" + port;
        var login = request(http, "GET", base + "/login", null, null, null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        assertThat(request(http, "POST", base + "/login", null,
                "username=enf-procedure-operator&password=unique-procedure-password-1&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").statusCode()).isEqualTo(302);
        String verifier = "enf-procedure-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorize = request(http, "GET", base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-procedure-client&redirect_uri="
                + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                + "&scope=openid&state=procedure&code_challenge=" + challenge
                + "&code_challenge_method=S256", null, null, null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location")
                .orElseThrow()).getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = request(http, "POST", base + "/oauth2/token", null,
                "grant_type=authorization_code&client_id=enf-procedure-client&redirect_uri="
                        + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier, "application/x-www-form-urlencoded");
        assertThat(issued.statusCode()).isEqualTo(200);
        String token = json.readTree(issued.body()).path("access_token").asText();
        String url = base + "/v2/enforcement/procedure-rules";
        String body = json.writeValueAsString(new Rule("PROJECT-RULE", 0, ORG, "PROJECT_TEST",
                "PROJECT_DOCUMENT_REVIEW", "PROJECT_HANDOFF_REVIEW", null,
                new Basis("docs/57#6.26:PROJECT_METHOD_ONLY", "1", "6.26", "PROJECT_ONLY",
                        "PROJECT_TEST", java.time.LocalDate.parse("2026-09-14"),
                        java.time.LocalDate.parse("2026-09-22")),
                new ClockRule(Unit.WORKDAY, 2, "REQUEST_RECORDED", false, "NEXT_WORKDAY",
                        "PROJECT-CALENDAR-01", "1", "INCLUSIVE_PAUSE_RESUME_NEXT_DAY", "UNCONFIRMED", 1),
                List.of("REQUEST_RECORDED"), List.of(materialRef), null, null, null));
        assertThat(api(http, "POST", url, null, body, "unauthenticated").statusCode()).isEqualTo(401);
        assertThat(api(http, "POST", url, token, body, "no-grant").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isZero();
        var edit = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-D-INPUT-01", "PROCEDURE_RULE",
                "PROJECT-RULE", 1, "EDIT_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(api(http, "POST", url, token, body, "draft-1").statusCode()).isEqualTo(200);
        assertThat(api(http, "POST", url, token, body, "draft-1").statusCode()).isEqualTo(200);
        String trialUrl = url + "/PROJECT-RULE/versions/1/trials";
        String trialBody = "{\"caseRef\":\"PROJECT-CASE-PROCEDURE-UNKNOWN\",\"caseVersion\":\"1\","
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        assertThat(api(http, "POST", trialUrl, token, trialBody, null).statusCode()).isEqualTo(403);
        watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-D-INPUT-01", "PROCEDURE_RULE",
                "PROJECT-RULE", 1, "READ_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-D-INPUT-01", "PROCEDURE_RULE",
                "PROJECT-RULE", 1, "TRIAL_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-D-INPUT-01", "CASE",
                "PROJECT-CASE-PROCEDURE-UNKNOWN", 1, "READ_CASE", "PROCEDURE_CHECK"),
                Instant.now().plusSeconds(3600), 0, "enf-procedure-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        var trial = api(http, "POST", trialUrl, token, trialBody, null);
        assertThat(trial.statusCode()).isEqualTo(200);
        assertThat(json.readTree(trial.body()).path("state").asText()).isEqualTo("UNDETERMINED");
        assertThat(json.readTree(trial.body()).path("gaps").toString()).contains("INTERRUPTION_POLICY_UNCONFIRMED");
        assertThat(api(http, "POST", url + "/PROJECT-RULE/versions/1/publish", token,
                "{\"reviewRef\":\"x\",\"reviewer\":\"other\",\"positive\":["
                        + trialBody + "],\"negative\":[" + trialBody + "]}", "publish-no-grant")
                .statusCode()).isEqualTo(403);
        watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-D-INPUT-01", "PROCEDURE_RULE",
                "PROJECT-RULE", 1, "PUBLISH_RULE", "RULE_MANAGEMENT"),
                Instant.now().plusSeconds(3600), 0, "enf-procedure-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(api(http, "POST", url + "/PROJECT-RULE/versions/1/publish", token,
                "{\"reviewRef\":\"x\",\"reviewer\":\"other\",\"positive\":["
                        + trialBody + "],\"negative\":[" + trialBody + "]}", "publish-incomplete")
                .statusCode()).isEqualTo(409);
        watermark.change(() -> grants.revoke(edit.grantId(), 1, "enf-procedure-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(api(http, "POST", url, token, body, "draft-1").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_rule", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(1);

        var reviewer = subjects.register("enf-procedure-reviewer", "enf-procedure-reviewer-enrollment");
        subjects.activate(reviewer.subjectId(), reviewer.oneTimeActivation(), "unique-procedure-reviewer-1");
        issue(reviewer.subjectId(), "EVIDENCE", materialRef.split("@")[0], 3, "READ", "ANALYZE");
        String reviewerToken = ProcedureHttpsLogin.token(trust, json, base,
                "enf-procedure-reviewer", "unique-procedure-reviewer-1");
        String acceptedId = "PROJECT-ACCEPTED-RULE";
        String accepted = json.writeValueAsString(new Rule(acceptedId, 0, ORG, "PROJECT_TEST",
                "PROJECT_DOCUMENT_REVIEW", "PROJECT_HANDOFF_REVIEW", null,
                new Basis("PROJECT-METHOD", "1", "section", "VERIFIED", "PROJECT_TEST",
                        java.time.LocalDate.parse("2026-09-14"), java.time.LocalDate.parse("2026-09-22")),
                new ClockRule(Unit.WORKDAY, 2, "REQUEST_RECORDED", false, "NEXT_WORKDAY",
                        "PROJECT-CALENDAR-01", "1", "INCLUSIVE_PAUSE_RESUME_NEXT_DAY", "NONE", 0),
                List.of("REQUEST_RECORDED"), List.of(materialRef), null, null, null));
        issue(subject.subjectId(), "PROCEDURE_RULE", acceptedId, 1, "EDIT_RULE", "RULE_MANAGEMENT");
        assertThat(api(http, "POST", url, token, accepted, "accepted-draft").statusCode()).isEqualTo(200);
        issue(reviewer.subjectId(), "PROCEDURE_RULE", acceptedId, 1, "READ_RULE", "RULE_MANAGEMENT");
        issue(reviewer.subjectId(), "PROCEDURE_RULE", acceptedId, 1, "PUBLISH_RULE", "RULE_MANAGEMENT");
        issue(reviewer.subjectId(), "CASE", "PROJECT-CASE-PROCEDURE-01", 1,
                "READ_CASE", "RULE_MANAGEMENT");
        issue(reviewer.subjectId(), "CASE", "PROJECT-CASE-PROCEDURE-MISSING", 1,
                "READ_CASE", "RULE_MANAGEMENT");
        String complete = "{\"caseRef\":\"PROJECT-CASE-PROCEDURE-01\",\"caseVersion\":\"1\","
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        String missing = "{\"caseRef\":\"PROJECT-CASE-PROCEDURE-MISSING\",\"caseVersion\":\"1\","
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        String publishBody = "{\"reviewRef\":\"PROJECT-METHOD-REVIEW-1\",\"reviewer\":\""
                + reviewer.subjectId() + "\",\"positive\":[" + complete + "],\"negative\":[" + missing + "]}";
        var published = api(http, "POST", url + "/" + acceptedId + "/versions/1/publish",
                reviewerToken, publishBody, "accepted-publish");
        assertThat(published.statusCode()).describedAs(published.body()).isEqualTo(200);
        assertThat(json.readTree(published.body()).path("version").asLong()).isEqualTo(2);
        issue(subject.subjectId(), "PROCEDURE_RULE", acceptedId, 2, "READ_RULE", "RULE_MANAGEMENT");
        issue(subject.subjectId(), "CASE", "PROJECT-CASE-PROCEDURE-01", 1,
                "CHECK_CASE", "PROCEDURE_CHECK");
        String check = "{\"id\":\"PROJECT-PROCEDURE-REPORT-1\",\"caseRef\":\"PROJECT-CASE-PROCEDURE-01\","
                + "\"caseVersion\":\"1\",\"ruleId\":\"" + acceptedId + "\",\"ruleVersion\":2,"
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        var checked = api(http, "POST", base + "/v2/enforcement/procedure-checks", token, check, "check-1");
        assertThat(checked.statusCode()).describedAs(checked.body()).isEqualTo(200);
        var reportRead = issue(subject.subjectId(), "PROCEDURE_REPORT", "PROJECT-PROCEDURE-REPORT-1", 1,
                "READ_REPORT", "PROCEDURE_CHECK");
        var report = api(http, "GET", base + "/v2/enforcement/procedure-checks/PROJECT-PROCEDURE-REPORT-1/versions/1",
                token, null, null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(json.readTree(report.body()).path("items").toString()).contains("FAIL", "2026-09-17");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(1);
        String reportUrl = base + "/v2/enforcement/procedure-checks/PROJECT-PROCEDURE-REPORT-1";
        issue(subject.subjectId(), "PROCEDURE_REPORT", "PROJECT-PROCEDURE-REPORT-1", 1,
                "RECTIFY_REPORT", "PROCEDURE_CHECK");
        String correction = "{\"reason\":\"Project source correction requested\","
                + "\"evidenceRefs\":[\"PROJECT-RECORD-A@1\"],\"expectedVersion\":1}";
        assertThat(api(http, "POST", reportUrl + "/rectifications", token, correction, "rectify-1")
                .statusCode()).isEqualTo(200);
        assertThat(api(http, "POST", reportUrl + "/rectifications", token, correction, "rectify-1")
                .statusCode()).isEqualTo(200);
        issue(subject.subjectId(), "PROCEDURE_REPORT", "PROJECT-PROCEDURE-REPORT-1", 2,
                "RECHECK_REPORT", "PROCEDURE_CHECK");
        String recheck = "{\"expectedVersion\":2,\"caseRef\":\"PROJECT-CASE-PROCEDURE-01\","
                + "\"caseVersion\":\"1\",\"ruleId\":\"" + acceptedId + "\",\"ruleVersion\":2,"
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        assertThat(api(http, "POST", reportUrl + "/rechecks", token, recheck, "recheck-1")
                .statusCode()).isEqualTo(200);
        assertThat(api(http, "POST", reportUrl + "/rechecks", token, recheck, "recheck-1")
                .statusCode()).isEqualTo(200);
        var revokedReportRead = watermark.change(() -> grants.revoke(reportRead.grantId(),
                reportRead.grantVersion(), "enf-procedure-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        var currentReportRead = issue(subject.subjectId(), "PROCEDURE_REPORT", "PROJECT-PROCEDURE-REPORT-1", 3,
                "READ_REPORT", "PROCEDURE_CHECK", revokedReportRead.epoch());
        var revised = api(http, "GET", reportUrl + "/versions/3", token, null, null);
        assertThat(revised.statusCode()).isEqualTo(200);
        assertThat(json.readTree(revised.body()).path("previousVersion").asLong()).isEqualTo(2);
        assertThat(json.readTree(report.body()).path("rectification").isNull()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(6);
        for (var variant : List.of("PROJECT-CASE-PROCEDURE-MISSING", "PROJECT-CASE-OTHER-PROCEDURE")) {
            issue(subject.subjectId(), "CASE", variant, 1, "CHECK_CASE", "PROCEDURE_CHECK");
            String variantId = "REPORT-" + variant;
            String command = "{\"id\":\"" + variantId + "\",\"caseRef\":\"" + variant
                    + "\",\"caseVersion\":\"1\",\"ruleId\":\"" + acceptedId
                    + "\",\"ruleVersion\":2,\"calendarRef\":\"PROJECT-CALENDAR-01\","
                    + "\"calendarVersion\":\"1\"}";
            assertThat(api(http, "POST", base + "/v2/enforcement/procedure-checks", token,
                    command, "check-" + variant).statusCode()).isEqualTo(200);
            issue(subject.subjectId(), "PROCEDURE_REPORT", variantId, 1,
                    "READ_REPORT", "PROCEDURE_CHECK");
            var variantReport = api(http, "GET", base + "/v2/enforcement/procedure-checks/"
                    + variantId + "/versions/1", token, null, null);
            assertThat(variantReport.statusCode()).isEqualTo(200);
            String expected = variant.endsWith("MISSING") ? "UNDETERMINED" : "NOT_APPLICABLE";
            assertThat(json.readTree(variantReport.body()).path("items").toString()).contains(expected).doesNotContain("PASS");
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(8);
        ProcedureBrowserE2e.run(base, "enf-procedure-operator", "unique-procedure-password-1",
                acceptedId, "PROJECT-PROCEDURE-REPORT-1", () -> watermark.change(() ->
                        grants.revoke(currentReportRead.grantId(), currentReportRead.grantVersion(),
                                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64))));
        issue(reviewer.subjectId(), "PROCEDURE_REPORT", "PROJECT-PROCEDURE-REPORT-1", 1,
                "READ_REPORT", "PROCEDURE_CHECK");
        assertThat(api(http, "GET", reportUrl + "/versions/1", reviewerToken, null, null).statusCode()).isEqualTo(200);
        ProcedureHttpsMaterialFixture.assertMissingOriginalFailsClosed(materials, tlsRoot.resolve("files"),
                materialRef, () -> api(http, "GET", reportUrl + "/versions/1", reviewerToken, null, null).statusCode());
        assertThat(api(http, "GET", reportUrl + "/versions/1", reviewerToken, null, null).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_rule", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(8);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(8);
    }
    private ProductGrants.Receipt issue(String subject, String type, String id, long version,
                                        String action, String purpose) {
        return issue(subject, type, id, version, action, purpose, 0);
    }
    private ProductGrants.Receipt issue(String subject, String type, String id, long version,
                                        String action, String purpose, long expectedEpoch) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject, ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-PRODUCT-ACCEPTANCE-01",
                type, id, version, action, purpose), Instant.now().plusSeconds(3600), expectedEpoch,
                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }
    private static HttpResponse<String> request(HttpClient client, String method, String url,
                                                 String token, String body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (contentType != null) builder.header("Content-Type", contentType);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> api(HttpClient client, String method, String url,
                                             String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
            ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
            ProductIdentityTransportFilter.class, EnforcementMaterialConfiguration.class,
            EnforcementProcedureConfiguration.class, EnforcementProcedureController.class,
            ReactFrontendController.class})
    static class IsolatedProcedure { }
}
