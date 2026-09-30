package cn.xinji.agentplatform.enforcement.conduct;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.evidence.EnforcementEvidenceRuleConfiguration;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceTrialSource;
import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.conduct.ConductHttpRequests.api;
import static cn.xinji.agentplatform.enforcement.conduct.ConductHttpRequests.browser;
import static cn.xinji.agentplatform.enforcement.conduct.ConductHttpRequests.request;
@ActiveProfiles("identity-http")
@SpringBootTest(classes = ConductHttpsTest.IsolatedConduct.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true", "enforcement.v2.enabled=true",
        "enforcement.v2.conduct.approved-basis=TRADEMARK_INFRINGEMENT|PROJECT-BASIS|1|section 1|project|project-area|project-procedure",
        "product.identity.issuer=https://enf-conduct-http.example.org",
        "product.identity.audience=https://enf-conduct-api.example.org",
        "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "product.identity.token-verification-window=PT5M",
        "product.identity.activation-lifetime=PT30M", "product.identity.minimum-password-length=12",
        "product.consultation.retention.ref=isolated-retention-input",
        "product.consultation.retention.version=1",
        "product.consultation.retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.retention.max-age=PT2H",
        "product.consultation.goal-retention.ref=isolated-goal-retention",
        "product.consultation.goal-retention.version=1",
        "product.consultation.goal-retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.consultation.goal-retention.max-age=PT1H",
        "product.consultation.goal-retention.max-bytes=16384",
        "spring.datasource.url=jdbc:h2:mem:enf-conduct-https;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V035__enforcement_v2_material.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql,file:db/dm8/V050__enforcement_v2_evidence_rules.sql,file:db/dm8/V051__enforcement_v2_evidence_publication.sql,file:db/dm8/V052__enforcement_v2_evidence_organizations.sql,file:db/dm8/V060__enforcement_v2_conduct.sql"
})
class ConductHttpsTest {
    private static final String ISSUER = "https://enf-conduct-http.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    private static Path tlsRoot;
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        tlsRoot = Files.createTempDirectory("enf-conduct-https-");
        Path store = tlsRoot.resolve("server.p12");
        var command = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-conduct-test", "-keypass",
                "enf-conduct-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (command.waitFor() != 0) throw new IllegalStateException(output);
        String authorityUrl = "jdbc:h2:mem:enf-conduct-authority;DB_CLOSE_DELAY=-1";
        var authority = new DriverManagerDataSource(authorityUrl, "sa", "");
        try (var connection = authority.getConnection()) {
            for (String script : List.of("V001__grant_watermark.sql", "V002__enforcement_source_watermark.sql",
                    "V005__enforcement_delete_watermark.sql"))
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/identity-authority/" + script)));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark VALUES('"
                    + ISSUER + "',0,'READY',NULL,'"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer "
                    + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                    + "VALUES(1,'enf-conduct-maintainer','" + ORG + "','" + "a".repeat(64)
                    + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> authorityUrl);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("server.ssl.key-store", () -> store.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> "enf-conduct-test");
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
    @Autowired JwtDecoder decoder;
    @Autowired MaterialRepository materials;
    @Autowired MaterialFiles files;
    @Autowired EvidenceRuleStore evidenceRules;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;

    @Test void pkceV2DraftTrialAndRevocationKeepOneReceipt() throws Exception {
        keys.rotate("enf-conduct-maintainer");
        var subject = subjects.register("enf-conduct-operator", "enf-conduct-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "unique-conduct-password-1");
        clients.save(RegisteredClient.withId("enf-conduct-client").clientId("enf-conduct-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback")
                .redirectUri("https://localhost:" + port + "/app/enforcement/conduct").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) "
                + "VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)", "enf-conduct-client",
                "enf-conduct-client", "https://localhost/callback");
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
        assertTrue(csrf.find());
        assertEquals(302, request(http, "POST", base + "/login", null,
                "username=enf-conduct-operator&password=unique-conduct-password-1&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").statusCode());
        String verifier = "enf-conduct-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorize = request(http, "GET", base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-conduct-client&redirect_uri="
                + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                + "&scope=openid&state=conduct&code_challenge=" + challenge
                + "&code_challenge_method=S256", null, null, null);
        assertEquals(302, authorize.statusCode());
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location")
                .orElseThrow()).getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = request(http, "POST", base + "/oauth2/token", null,
                "grant_type=authorization_code&client_id=enf-conduct-client&redirect_uri="
                        + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier, "application/x-www-form-urlencoded");
        assertEquals(200, issued.statusCode());
        String token = json.readTree(issued.body()).path("access_token").asText();
        String url = base + "/v2/enforcement/conduct-rules";
        String draft = "{\"id\":\"PROJECT-CONDUCT\",\"organization\":\"" + ORG
                + "\",\"behavior\":\"TRADEMARK_INFRINGEMENT\",\"basis\":{\"ref\":\"project\","
                + "\"version\":\"1\",\"locator\":\"section 1\",\"effectStatus\":\"UNVERIFIED\","
                + "\"scope\":\"project\",\"jurisdiction\":\"project\",\"procedure\":\"project\"},"
                + "\"elements\":[{\"id\":\"BEHAVIOR_FACT\",\"description\":\"label observation\","
                + "\"required\":true}],\"defenses\":[\"UNASSESSED\"],"
                + "\"noDefensesConfirmed\":false}";
        assertEquals(401, api(http, "POST", url, null, draft, "unauthorized").statusCode());
        assertEquals(403, api(http, "POST", url, token, draft, "no-grant").statusCode());
        assertEquals(0, count("enf7_conduct_receipt"));
        var edit = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-CONDUCT", "ENF-CONDUCT-D-INPUT-01", "CONDUCT_RULE",
                "PROJECT-CONDUCT", 1, "EDIT_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        var read = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-CONDUCT", "ENF-CONDUCT-D-INPUT-01", "CONDUCT_RULE",
                "PROJECT-CONDUCT", 1, "READ_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertEquals(200, api(http, "POST", url, token, draft, "draft-1").statusCode());
        assertEquals(200, api(http, "POST", url, token, draft, "draft-1").statusCode());
        watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, subject.subjectId(), ORG,
                "ENFORCEMENT", "ENF-V2-CONDUCT", "ENF-CONDUCT-D-INPUT-01", "CONDUCT_RULE",
                "PROJECT-CONDUCT", 1, "TRIAL_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        String trial = "{\"id\":\"analysis\",\"organization\":\"" + ORG
                + "\",\"caseRef\":\"PROJECT-CASE\",\"caseVersion\":\"1\","
                + "\"evidenceOrganizationId\":\"evidence\",\"evidenceOrganizationVersion\":1,"
                + "\"ruleId\":\"PROJECT-CONDUCT\",\"ruleVersion\":1,"
                + "\"behavior\":\"TRADEMARK_INFRINGEMENT\","
                + "\"behaviorAt\":\"2026-09-14\",\"right\":null,\"facts\":[],"
                + "\"links\":[],\"defenseRefs\":[]}";
        var response = api(http, "POST", url + "/PROJECT-CONDUCT/versions/1/trials", token, trial, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("INSUFFICIENT", json.readTree(response.body()).path("outcome").asText());
        assertTrue(response.body().contains("RIGHT_AT_BEHAVIOR_TIME_UNKNOWN"));
        assertEquals(403, api(http, "POST", url + "/PROJECT-CONDUCT/versions/1/publish", token,
                "{\"reviewRef\":\"unverified\",\"positive\":[],\"negative\":[]}",
                "publish-denied").statusCode());
        watermark.change(() -> grants.revoke(edit.grantId(), 1, "enf-conduct-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertEquals(403, api(http, "POST", url, token, draft, "draft-1").statusCode());
        assertEquals(1, count("enf7_conduct_rule"));
        assertEquals(1, count("enf7_conduct_receipt"));
        assertEquals(1, count("enf7_conduct_event"));
        assertEquals(0, count("enf7_conduct_report"));
        assertNotNull(read.grantId());
        exerciseLockedAnalysis(http, base, token, subject.subjectId());
    }

    private void exerciseLockedAnalysis(HttpClient http, String base, String token,
                                        String actor) throws Exception {
        var refs = new ConductHttpsEvidenceFixture(db, manager, json, materials, files,
                evidenceRules, tlsRoot, ORG).prepare(actor);
        var current = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                decoder.decode(token), ISSUER, java.time.Clock.systemUTC());
        current.prepareSource("CONDUCT-HTTP-SOURCE", 1);
        current.activateSource("CONDUCT-HTTP-SOURCE", 1);
        grant(actor, "EVIDENCE_RULE", "CONDUCT-HTTP-EVIDENCE-RULE", 2,
                "READ_RULE", "RULE_MANAGEMENT");
        grant(actor, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE");
        for (var ref : refs) grant(actor, "EVIDENCE", ref.id(), 3, "READ", "ANALYZE");
        grant(actor, "EVIDENCE_ORGANIZATION", "CONDUCT-HTTP-EVIDENCE", 1,
                "READ", "DISPLAY");
        var rulesUrl = base + "/v2/enforcement/conduct-rules";
        var basis = new ConductContract.Basis("PROJECT-BASIS", "1", "section 1", "VERIFIED",
                "project", "project-area", "project-procedure");
        var draft = new ConductRuleService.Draft("CONDUCT-APPROVED", ORG,
                ConductContract.Behavior.TRADEMARK_INFRINGEMENT, basis,
                List.of(new ConductContract.Element("BEHAVIOR_FACT", "label observation", true)),
                List.of(), true, "project-acceptance-no-defense-confirmation");
        grant(actor, "CONDUCT_RULE", draft.id(), 1, "EDIT_RULE", "RULE_MANAGEMENT");
        var draftRead = grant(actor, "CONDUCT_RULE", draft.id(), 1, "READ_RULE", "RULE_MANAGEMENT");
        grant(actor, "CONDUCT_RULE", draft.id(), 1, "PUBLISH_RULE", "RULE_MANAGEMENT");
        String draftJson = json.writeValueAsString(draft);
        assertEquals(200, api(http, "POST", rulesUrl, token, draftJson, "approved-draft").statusCode());
        var organizationStore = new Dm8EvidenceOrganizationStore(db,
                new TransactionTemplate(manager), json, java.time.Clock.systemUTC());
        EvidenceOrganizationStore.Revision locked = organizationStore.revision("CONDUCT-HTTP-EVIDENCE", 1)
                .orElseThrow();
        var first = locked.links().stream().filter(link -> refs.stream().anyMatch(ref ->
                ref.id().equals(link.evidenceId()))).findFirst().orElseThrow();
        var fact = new ConductContract.Fact("FACT-LABEL", "MACHINE_CANDIDATE",
                first.evidenceId(), first.locator());
        var support = new ConductContract.Link("BEHAVIOR_FACT", fact.ref(), first.evidenceId(),
                first.locator(), ConductContract.Relation.SUPPORT);
        var right = new ConductContract.RightAtTime("PROJECT-RIGHT", "1",
                "VERIFIED_AT_BEHAVIOR_TIME", "2026-09-14", "PROJECT-ONLY-SOURCE");
        var positive = new ConductContract.Input("positive", ORG, "PROJECT-CASE-001", "1",
                locked.id(), 1, draft.id(), 1, draft.behavior(), "2026-09-14", right,
                List.of(fact), List.of(support), List.of());
        var negative = new ConductContract.Input("negative", ORG, "PROJECT-CASE-001", "1",
                locked.id(), 1, draft.id(), 1, draft.behavior(), "2026-09-14", null,
                List.of(fact), List.of(), List.of());
        var review = new ConductRuleService.Publication("named-project-rule-review",
                List.of(new ConductRuleService.Example(positive, ConductContract.Outcome.SUSPECTED,
                        List.of())),
                List.of(new ConductRuleService.Example(negative, ConductContract.Outcome.INSUFFICIENT,
                        List.of("ELEMENT:BEHAVIOR_FACT:MISSING", "RIGHT_AT_BEHAVIOR_TIME_UNKNOWN"))));
        var published = api(http, "POST", rulesUrl + "/" + draft.id() + "/versions/1/publish",
                token, json.writeValueAsString(review), "approved-publish");
        assertEquals(200, published.statusCode(), published.body());
        var revokedRead = watermark.change(() -> grants.revoke(draftRead.grantId(), 1,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        grant(actor, "CONDUCT_RULE", draft.id(), 2, "READ_RULE", "RULE_MANAGEMENT",
                revokedRead.epoch());
        grant(actor, "CONDUCT_RULE", draft.id(), 2, "USE_RULE", "ANALYZE");
        var analysis = new ConductContract.Input("CONDUCT-HTTP-ANALYSIS", ORG,
                "PROJECT-CASE-001", "1", locked.id(), 1, draft.id(), 2,
                draft.behavior(), "2026-09-14",
                null, List.of(fact), List.of(support), List.of());
        String analysesUrl = base + "/v2/enforcement/conduct-analyses";
        assertEquals(403, api(http, "POST", analysesUrl, token,
                json.writeValueAsString(analysis), "analysis-no-grant").statusCode());
        grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 1, "ANALYZE", "ANALYZE");
        var firstRead = grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 1, "READ", "DISPLAY");
        var created = api(http, "POST", analysesUrl, token,
                json.writeValueAsString(analysis), "approved-analysis");
        assertEquals(200, created.statusCode(), created.body());
        var report = api(http, "GET", analysesUrl + "/" + analysis.id() + "/versions/1",
                token, null, null);
        assertEquals(200, report.statusCode(), report.body());
        assertEquals("INSUFFICIENT", json.readTree(report.body()).path("outcome").asText());
        assertEquals("UNKNOWN", json.readTree(report.body()).path("scope").path("state").asText());
        assertEquals("PROJECT-CASE-001", json.readTree(report.body()).path("scope").path("sourceRef").asText());
        assertTrue(report.body().contains("APPLICABILITY_SCOPE_UNKNOWN"));
        assertEquals("MACHINE_CANDIDATE", json.readTree(report.body()).path("facts").get(0)
                .path("kind").asText());
        assertFalse(json.readTree(report.body()).has("severityGrade"));
        assertFalse(json.readTree(report.body()).has("amount"));
        assertTrue(report.body().contains(first.locator()));
        grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 1, "REVIEW", "HUMAN_REVIEW");
        var reviewed = api(http, "POST", analysesUrl + "/" + analysis.id() + "/versions/1/reviews",
                token, json.writeValueAsString(new ConductAnalysisService.Review(1,
                        "Historical right must be checked", "RETURNED")), "named-review");
        assertEquals(200, reviewed.statusCode(), reviewed.body());
        var revokedFirst = watermark.change(() -> grants.revoke(firstRead.grantId(), 1,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        var secondRead = grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 2, "READ", "DISPLAY",
                revokedFirst.epoch());
        var human = api(http, "GET", analysesUrl + "/" + analysis.id() + "/versions/2",
                token, null, null);
        assertEquals(200, human.statusCode(), human.body());
        assertEquals("RETURNED", json.readTree(human.body()).path("reviewState").asText());
        assertEquals(actor, json.readTree(human.body()).path("reviewedBy").asText());
        grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 2, "RERUN", "ANALYZE");
        var rerun = api(http, "POST", analysesUrl + "/" + analysis.id() + "/versions/2/reruns",
                token, json.writeValueAsString(new ConductAnalysisService.Rerun(2, analysis)),
                "new-machine-run");
        assertEquals(200, rerun.statusCode(), rerun.body());
        var revokedSecond = watermark.change(() -> grants.revoke(secondRead.grantId(), 1,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        var thirdRead = grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 3, "READ", "DISPLAY",
                revokedSecond.epoch());
        var machine = api(http, "GET", analysesUrl + "/" + analysis.id() + "/versions/3",
                token, null, null);
        assertEquals(200, machine.statusCode(), machine.body());
        assertEquals("REVIEW_REQUIRED", json.readTree(machine.body()).path("reviewState").asText());
        assertEquals("RETURNED", json.readTree(human.body()).path("reviewState").asText());
        assertEquals(3, count("enf7_conduct_report"));
        assertEquals(6, count("enf7_conduct_receipt"));
        assertEquals(6, count("enf7_conduct_event"));
        if ("1".equals(System.getenv("ENF_BROWSER_E2E"))) {
            var revoked = new java.util.concurrent.atomic.AtomicReference<ProductGrants.Receipt>();
            browser(base, () -> revoked.set(watermark.change(() -> grants.revoke(
                    thirdRead.grantId(), 1, "enf-conduct-maintainer", UUID.randomUUID().toString(),
                    "a".repeat(64)))));
            grant(actor, "CONDUCT_ANALYSIS", analysis.id(), 3, "READ", "DISPLAY",
                    revoked.get().epoch());
        }
        var original = materials.material(refs.getFirst().id()).orElseThrow();
        files.discard(original.fileKey());
        assertEquals(503, api(http, "GET", analysesUrl + "/" + analysis.id() + "/versions/3",
                token, null, null).statusCode());
        assertEquals(3, count("enf7_conduct_report"));
        assertEquals(6, count("enf7_conduct_receipt"));
        assertEquals(6, count("enf7_conduct_event"));
    }
    private ProductGrants.Receipt grant(String actor, String type, String id, long version,
                                        String action, String purpose) {
        return grant(actor, type, id, version, action, purpose, 0);
    }
    private ProductGrants.Receipt grant(String actor, String type, String id, long version,
                                        String action, String purpose, long epoch) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-CONDUCT", "ENF-CONDUCT-D-INPUT-01", type, id,
                version, action, purpose), Instant.now().plusSeconds(3600), epoch,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
            ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
            ProductIdentityTransportFilter.class, EnforcementMaterialConfiguration.class,
            EnforcementEvidenceRuleConfiguration.class, EnforcementConductConfiguration.class,
            EnforcementConductRuleController.class, EnforcementConductAnalysisController.class,
            ReactFrontendController.class})
    static class IsolatedConduct { }
}
