package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static cn.xinji.agentplatform.enforcement.evidence.HandoffHttpTestSupport.browser;
import static cn.xinji.agentplatform.enforcement.evidence.HandoffHttpTestSupport.request;

import cn.xinji.agentplatform.enforcement.handoff.EnforcementHandoffController;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialCollectionService;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialProductService;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("identity-http")
@SpringBootTest(classes = HandoffHttpsTest.IsolatedHandoff.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true", "enforcement.v2.enabled=true",
        "product.identity.issuer=https://enf-handoff-http.example.org",
        "product.identity.audience=https://enf-handoff-api.example.org",
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
        "spring.datasource.url=jdbc:h2:mem:enf-handoff-https;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V035__enforcement_v2_material.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql,file:db/dm8/V050__enforcement_v2_evidence_rules.sql,file:db/dm8/V051__enforcement_v2_evidence_publication.sql,file:db/dm8/V052__enforcement_v2_evidence_organizations.sql,file:db/dm8/V054__enforcement_v2_handoff.sql,file:db/dm8/V055__enforcement_v2_handoff_event_reason.sql"
})
class HandoffHttpsTest {
    private static final String ISSUER = "https://enf-handoff-http.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    private static Path root;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        root = Files.createTempDirectory("enf-handoff-https-");
        Path store = root.resolve("server.p12");
        var command = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-handoff-test", "-keypass",
                "enf-handoff-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (command.waitFor() != 0) throw new IllegalStateException(output);
        String url = "jdbc:h2:mem:enf-handoff-authority;DB_CLOSE_DELAY=-1";
        var authority = new DriverManagerDataSource(url, "sa", "");
        try (var connection = authority.getConnection()) {
            for (String migration : List.of("V001__grant_watermark.sql",
                    "V002__enforcement_source_watermark.sql", "V005__enforcement_delete_watermark.sql"))
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/identity-authority", migration)));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark "
                    + "VALUES('" + ISSUER + "',0,'READY',NULL,'"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer "
                    + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                    + "VALUES(1,'enf-handoff-maintainer','" + ORG + "','" + "a".repeat(64)
                    + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> url);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("server.ssl.key-store", () -> store.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> "enf-handoff-test");
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("enforcement.v2.file-root", () -> root.resolve("files").toString());
        properties.add("enforcement.v2.evidence.case-input-file", () -> root.resolve("case.json").toString());
    }

    @AfterAll static void cleanup() throws Exception {
        if (root != null) try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Autowired ProductSigningKeys keys;
    @Autowired ProductSubjects subjects;
    @Autowired ProductGrants grants;
    @Autowired ProductGrantWatermark watermark;
    @Autowired RegisteredClientRepository clients;
    @Autowired JwtDecoder decoder;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MaterialRepository materials;
    @Autowired MaterialFiles files;
    @Autowired EvidenceRuleStore rules;
    @LocalServerPort int port;

    @Test void loggedInV2CommandsKeepReceiptAndOriginalClosure() throws Exception {
        keys.rotate("enf-handoff-maintainer");
        String creator = activate("named-handoff-creator", "unique-handoff-creator-1");
        String receiver = activate("named-handoff-receiver", "unique-handoff-receiver-1");
        clients.save(RegisteredClient.withId("enf-evidence-client").clientId("enf-evidence-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback")
                .redirectUri("https://localhost:" + port + "/app/enforcement/handoff").scope("openid")
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
        var http = HttpClient.newBuilder().sslContext(trust).build();
        String base = "https://localhost:" + port;
        String creatorToken = EvidenceHttpsLogin.token(trust, json, base,
                "named-handoff-creator", "unique-handoff-creator-1");
        String receiverToken = EvidenceHttpsLogin.token(trust, json, base,
                "named-handoff-receiver", "unique-handoff-receiver-1");
        var refs = prepareProductInputs();
        var current = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                decoder.decode(creatorToken), ISSUER, Clock.systemUTC());
        current.prepareSource("HANDOFF-HTTP-SOURCE", 1);
        current.activateSource("HANDOFF-HTTP-SOURCE", 1);
        ProductGrants.Receipt receiverPackageRead = null;
        for (String actor : List.of(creator, receiver)) {
            grant(actor, "EVIDENCE_RULE", "HANDOFF-HTTP-RULE", 2, "READ_RULE", "RULE_MANAGEMENT");
            grant(actor, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE");
            for (var ref : refs) grant(actor, "EVIDENCE", ref.id(), 3, "READ", "ANALYZE");
            grant(actor, "EVIDENCE_ORGANIZATION", "HANDOFF-HTTP-ORG", 1, "READ", "DISPLAY");
            var packageRead = grant(actor, "WORK_PACKAGE", "HANDOFF-HTTP-PACK", 1, "READ", "DISPLAY");
            if (actor.equals(receiver)) receiverPackageRead = packageRead;
            grant(actor, "HUMAN_REQUEST", "HANDOFF-HTTP-REQ", 1, "READ", "HUMAN_REVIEW");
        }
        grant(creator, "WORK_PACKAGE", "HANDOFF-HTTP-PACK", 1, "CREATE", "ANALYZE");
        grant(creator, "HUMAN_REQUEST", "HANDOFF-HTTP-REQ", 1, "REQUEST", "HUMAN_REVIEW");
        grant(receiver, "HUMAN_TASK", "HANDOFF-HTTP-REQ", 1, "CLAIM", "HUMAN_REVIEW");
        grant(receiver, "HUMAN_REQUEST", "HANDOFF-HTTP-REQ", 1, "READ_RESULT", "HUMAN_REVIEW");
        var submitGrant = grant(receiver, "HUMAN_TASK", "HANDOFF-HTTP-REQ", 1,
                "SUBMIT", "HUMAN_REVIEW");
        String url = base + "/v2/enforcement";
        var pack = json.createObjectNode().put("id", "HANDOFF-HTTP-PACK")
                .put("evidenceOrganizationId", "HANDOFF-HTTP-ORG")
                .put("evidenceOrganizationVersion", 1).put("purposeRef", "REVIEW_EVIDENCE_GAPS");
        assertThat(request(http, "POST", url + "/work-packages", null, pack.toString(), "anonymous")
                .statusCode()).isEqualTo(401);
        assertThat(request(http, "POST", url + "/work-packages", creatorToken, pack.toString(), "pack-1")
                .statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", url + "/work-packages", creatorToken, pack.toString(), "pack-1")
                .statusCode()).isEqualTo(200);
        var viewed = request(http, "GET", url + "/work-packages/HANDOFF-HTTP-PACK/versions/1",
                creatorToken, null, null);
        assertThat(viewed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(viewed.body()).path("gaps").toString()).contains("EVENT_TIME:CONFLICT");
        var requested = json.createObjectNode().put("id", "HANDOFF-HTTP-REQ")
                .put("packageId", "HANDOFF-HTTP-PACK").put("packageVersion", 1)
                .put("purposeRef", "REVIEW_EVIDENCE_GAPS");
        String req = url + "/work-packages/HANDOFF-HTTP-PACK/versions/1/human-requests";
        assertThat(request(http, "POST", req, creatorToken, requested.toString(), "request-1")
                .statusCode()).isEqualTo(200);
        assertThat(json.readTree(request(http, "GET", url + "/human-requests/HANDOFF-HTTP-REQ",
                creatorToken, null, null).body()).path("state").asText()).isEqualTo("PENDING");
        assertThat(json.readTree(request(http, "GET", url + "/human-tasks", receiverToken,
                null, null).body()).size()).isEqualTo(1);
        assertThat(request(http, "POST", url + "/human-requests/HANDOFF-HTTP-REQ/claims",
                creatorToken, "{\"expectedVersion\":1}", "actor-claim").statusCode()).isEqualTo(403);
        assertThat(request(http, "POST", url + "/human-requests/HANDOFF-HTTP-REQ/claims",
                receiverToken, "{\"expectedVersion\":1}", "claim-1").statusCode()).isEqualTo(200);
        var opinion = json.createObjectNode().put("expectedVersion", 2)
                .put("opinion", "Human review required").put("reason", "Conflicting original times");
        assertThat(request(http, "POST", url + "/human-requests/HANDOFF-HTTP-REQ/results",
                creatorToken, opinion.toString(), "agent-result").statusCode()).isEqualTo(403);
        assertThat(request(http, "POST", url + "/human-requests/HANDOFF-HTTP-REQ/results",
                receiverToken, opinion.toString(), "result-1").statusCode()).isEqualTo(200);
        assertThat(json.readTree(request(http, "GET", url + "/human-requests/HANDOFF-HTTP-REQ/result",
                receiverToken, null, null).body()).path("packageVersion").asLong()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(3);
        for (int i = 0; i < refs.size(); i++) {
            var saved = materials.material(refs.get(i).id()).orElseThrow();
            assertThat(files.read(saved.fileKey())).isEqualTo(Files.readAllBytes(Path.of(
                    "data/enforcement/evidence-v2-acceptance-v1", "record-" + "abc".charAt(i) + ".txt")));
        }
        watermark.change(() -> grants.revoke(submitGrant.grantId(), 1, "enf-handoff-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "POST", url + "/human-requests/HANDOFF-HTTP-REQ/results",
                receiverToken, opinion.toString(), "result-1").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(4);
        grant(creator, "HUMAN_REQUEST", "HANDOFF-HTTP-DISCONNECT", 1, "REQUEST", "HUMAN_REVIEW");
        grant(creator, "HUMAN_REQUEST", "HANDOFF-HTTP-DISCONNECT", 1, "READ", "HUMAN_REVIEW");
        grant(receiver, "HUMAN_REQUEST", "HANDOFF-HTTP-DISCONNECT", 1, "READ", "HUMAN_REVIEW");
        grant(receiver, "HUMAN_TASK", "HANDOFF-HTTP-DISCONNECT", 1, "CLAIM", "HUMAN_REVIEW");
        var disconnected = json.createObjectNode().put("id", "HANDOFF-HTTP-DISCONNECT")
                .put("packageId", "HANDOFF-HTTP-PACK").put("packageVersion", 1)
                .put("purposeRef", "REVIEW_EVIDENCE_GAPS");
        assertThat(request(http, "POST", req, creatorToken, disconnected.toString(), "request-disconnect")
                .statusCode()).isEqualTo(200);
        String claimPath = "/v2/enforcement/human-requests/HANDOFF-HTTP-DISCONNECT/claims";
        String claimBody = "{\"expectedVersion\":1}";
        byte[] bodyBytes = claimBody.getBytes(StandardCharsets.UTF_8);
        try (var socket = (SSLSocket) trust.getSocketFactory().createSocket("localhost", port)) {
            socket.startHandshake();
            String headers = "POST " + claimPath + " HTTP/1.1\r\nHost: localhost:" + port
                    + "\r\nAuthorization: Bearer " + receiverToken
                    + "\r\nIdempotency-Key: lost-claim-response\r\nContent-Type: application/json"
                    + "\r\nContent-Length: " + bodyBytes.length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bodyBytes);
            socket.getOutputStream().flush();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class) != 6
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                .as("disconnected claim committed before replay").isEqualTo(6);
        var replay = request(http, "POST", base + claimPath, receiverToken,
                claimBody, "lost-claim-response");
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(json.readTree(request(http, "GET", url + "/human-requests/HANDOFF-HTTP-DISCONNECT",
                receiverToken, null, null).body()).path("state").asText()).isEqualTo("ACCEPTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(6);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(6);
        if ("1".equals(System.getenv("ENF_BROWSER_E2E"))) {
            grant(creator, "HUMAN_REQUEST", "HANDOFF-HTTP-UI", 1, "READ", "HUMAN_REVIEW");
            grant(creator, "HUMAN_REQUEST", "HANDOFF-HTTP-UI", 1, "REQUEST", "HUMAN_REVIEW");
            grant(receiver, "HUMAN_REQUEST", "HANDOFF-HTTP-UI", 1, "READ", "HUMAN_REVIEW");
            grant(receiver, "HUMAN_REQUEST", "HANDOFF-HTTP-UI", 1, "READ_RESULT", "HUMAN_REVIEW");
            grant(receiver, "HUMAN_TASK", "HANDOFF-HTTP-UI", 1, "CLAIM", "HUMAN_REVIEW");
            grant(receiver, "HUMAN_TASK", "HANDOFF-HTTP-UI", 1, "SUBMIT", "HUMAN_REVIEW");
            var uiRequest = json.createObjectNode().put("id", "HANDOFF-HTTP-UI")
                    .put("packageId", "HANDOFF-HTTP-PACK").put("packageVersion", 1)
                    .put("purposeRef", "REVIEW_EVIDENCE_GAPS");
            assertThat(request(http, "POST", req, creatorToken, uiRequest.toString(), "request-ui")
                    .statusCode()).isEqualTo(200);
            var browserRead = receiverPackageRead;
            browser(base, () -> watermark.change(() -> grants.revoke(browserRead.grantId(), 1,
                    "enf-handoff-maintainer", UUID.randomUUID().toString(), "a".repeat(64))));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(9);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(9);
            assertThat(db.queryForObject("SELECT request_state FROM enf5_human_request WHERE request_id=?",
                    String.class, "HANDOFF-HTTP-UI")).isEqualTo("RETURNED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class))
                    .isEqualTo(2);
        }
        System.out.println("ENF_HANDOFF_V2_HTTPS login=PKCE package=1 request=PENDING "
                + "claim=ACCEPTED result=RETURNED disconnectedReplay=ONE_RECEIPT receipts="
                + ("1".equals(System.getenv("ENF_BROWSER_E2E")) ? 9 : 6)
                + " originals=3 revoked=403");
    }

    private String activate(String username, String password) {
        var subject = subjects.register(username, username + "-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), password);
        return subject.subjectId();
    }

    private ProductGrants.Receipt grant(String actor, String type, String id, long version,
                                        String action, String purpose) {
        var receipt = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-HANDOFF", "ENF-HANDOFF-V2-ACCEPTANCE-01", type, id,
                version, action, purpose), Instant.now().plusSeconds(3600), 0,
                "enf-handoff-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(receipt.status()).isEqualTo("ACTIVE");
        return receipt;
    }

    private List<EvidenceTrialSource.Ref> prepareProductInputs() throws Exception {
        MaterialAccess setup = new MaterialAccess() {
            @Override public void require(String actor, String org, String type, String id,
                                          long version, String action, String purpose) { }
            @Override public void requireSource(String id, String use, long epoch) { }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var material = new MaterialProductService(materials, files, setup, Clock.systemUTC());
        var collection = new MaterialCollectionService(materials, files, setup, Clock.systemUTC());
        String actor = "named-handoff-creator";
        material.registerSource(actor, "source", new MaterialProductService.SourceInput(
                "HANDOFF-HTTP-SOURCE", ORG, "PROJECT-CASE-001", 1, Instant.now().plusSeconds(3600),
                List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"), "project-owned-evidence"));
        var positive = material.observe(actor, "positive", new MaterialProductService.ObservationInput(
                "HANDOFF-HTTP-SOURCE", "POS", "1", "record positive", null));
        var negative = material.observe(actor, "negative", new MaterialProductService.ObservationInput(
                "HANDOFF-HTTP-SOURCE", "NEG", "1", "missing", null));
        material.draftRule(actor, "draft", new MaterialProductService.RuleInput(
                "HANDOFF-HTTP-DISCOVERY", "HANDOFF-HTTP-SOURCE", "record", "project-method"));
        material.ruleState(actor, "publish", "HANDOFF-HTTP-DISCOVERY", 1, "PUBLISH_RULE",
                positive.resultId(), negative.resultId());
        var refs = new ArrayList<EvidenceTrialSource.Ref>();
        for (String label : List.of("a", "b", "c")) {
            var observed = material.observe(actor, "observe-" + label,
                    new MaterialProductService.ObservationInput("HANDOFF-HTTP-SOURCE",
                            "RECORD-" + label, "1", "record " + label, null));
            var clue = material.discover(actor, "clue-" + label, observed.resultId(),
                    "HANDOFF-HTTP-DISCOVERY");
            byte[] bytes = Files.readAllBytes(Path.of("data/enforcement/evidence-v2-acceptance-v1",
                    "record-" + label + ".txt"));
            var saved = material.registerMaterial(actor, "material-" + label,
                    new MaterialProductService.MaterialInput(clue.resultId(), bytes,
                            "PROJECT-CASE-001", Instant.now().plusSeconds(3600),
                            List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-28"));
            collection.attest(actor, "collect-" + label, saved.resultId(), 1,
                    "RECORD-" + label, "Original collected");
            collection.verify("named-independent-verifier", "verify-" + label, saved.resultId(), 2,
                    "SHA checked");
            var record = materials.material(saved.resultId()).orElseThrow();
            refs.add(new EvidenceTrialSource.Ref(record.id(), "3", record.sha256()));
        }
        var caseInput = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("version", "1").put("caseType", "PROJECT_DOCUMENT_REVIEW")
                .put("organization", ORG).put("eventRef", "PROJECT-EVENT-001");
        var members = caseInput.putArray("materials");
        refs.forEach(ref -> members.addObject().put("id", ref.id()).put("version", ref.version())
                .put("sha256", ref.sha256()));
        json.writeValue(root.resolve("case.json").toFile(), caseInput);
        var rule = new EvidenceRuleService(rules, (a, o, id, version, action) -> { },
                (type, ref, version, scope) -> { });
        rule.draft(actor, "rule-draft", new EvidenceRuleService.DraftInput("HANDOFF-HTTP-RULE",
                ORG, "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                new EvidenceRuleStore.Matter("ITEM_IDENTITY", true,
                        "line-prefix:The submitted item is labeled "),
                new EvidenceRuleStore.Matter("EVENT_TIME", true,
                        "line-prefix:The observed event time is "))));
        rule.publish(actor, "rule-publish", "HANDOFF-HTTP-RULE", new EvidenceRuleService.Publication(
                "1", "PROJECT-CASE-001", "REVIEW-HANDOFF-HTTP", List.of(
                new EvidenceRuleService.Example("positive", List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                List.of(new EvidenceRuleService.Example("negative", List.of("ITEM_IDENTITY"),
                        List.of("EVENT_TIME")))));
        var organizations = new EvidenceOrganizationService(rules,
                new cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore(db,
                        new org.springframework.transaction.support.TransactionTemplate(
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                        new DriverManagerDataSource("jdbc:h2:mem:enf-handoff-https;DB_CLOSE_DELAY=-1",
                                                "sa", ""))), json, Clock.systemUTC()),
                new VerifiedEvidenceSource(root.resolve("case.json"), json, materials, files, setup,
                        Clock.systemUTC()), (a, o, type, id, version, action, purpose) -> { });
        organizations.organize(actor, "organize", new EvidenceOrganizationService.Input(
                "HANDOFF-HTTP-ORG", "HANDOFF-HTTP-RULE", 2,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs)));
        return refs;
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @Import({EvidenceRuleHttpsTest.IsolatedEvidence.class, EnforcementHandoffController.class})
    static class IsolatedHandoff { }
}
