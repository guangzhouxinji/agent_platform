package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.EnforcementIndependentIPrerequisites;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ConductStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.evidence.VerifiedEvidenceSource;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ConductIndependentDm8HttpTest {
    private static final String ISSUER = "https://enf-conduct-dual.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    @TempDir Path root;

    @Test void twoIndependentHttpsJvmsReconcileReviewAndCurrentAuthority() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null &&
                authorityPassword != null, "Dedicated ENF DBs required");
        EnforcementIndependentIPrerequisites.requireDualDm8(businessUrl, authorityUrl);
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "ED" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        createUser(businessUrl, businessPassword, owner, secret);
        try {
            createUser(authorityUrl, authorityPassword, owner, secret);
            try {
                var business = new DriverManagerDataSource(businessUrl, owner, secret);
                var authority = new DriverManagerDataSource(authorityUrl, owner, secret);
                migrate(business, "db/dm8/V032__product_identity.sql", "db/dm8/V033__product_oauth2.sql",
                        "db/dm8/V057__product_oauth_client_control.sql",
                        "db/dm8/V035__enforcement_v2_material.sql",
                        "db/dm8/V050__enforcement_v2_evidence_rules.sql",
                        "db/dm8/V051__enforcement_v2_evidence_publication.sql",
                        "db/dm8/V052__enforcement_v2_evidence_organizations.sql",
                        "db/dm8/V060__enforcement_v2_conduct.sql");
                migrate(authority, "db/identity-authority/V001__grant_watermark.sql",
                        "db/identity-authority/V002__enforcement_source_watermark.sql",
                        "db/identity-authority/V005__enforcement_delete_watermark.sql");
                try {
                    var current = new JdbcTemplate(authority);
                    current.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                            + "VALUES(?,0,'READY',NULL,?)", ISSUER,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                    current.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,"
                            + "certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,?, 'ACTIVE',1)",
                            "enf-conduct-maintainer", ORG, "a".repeat(64), "SUBJECTS,GRANTS,KEYS");
                    exercise(businessUrl, authorityUrl, owner, secret);
                } finally {
                    migrate(business, "db/dm8/U060__enforcement_v2_conduct.sql",
                            "db/dm8/U052__enforcement_v2_evidence_organizations.sql",
                            "db/dm8/U051__enforcement_v2_evidence_publication.sql",
                            "db/dm8/U050__enforcement_v2_evidence_rules.sql",
                            "db/dm8/U035__enforcement_v2_material.sql",
                            "db/dm8/U057__product_oauth_client_control.sql",
                            "db/dm8/U033__product_oauth2.sql", "db/dm8/U032__product_identity.sql");
                    dropAuthority(authorityUrl, owner, secret);
                }
            } finally { dropUser(authorityUrl, authorityPassword, owner); }
        } finally { dropUser(businessUrl, businessPassword, owner); }
    }

    private void exercise(String businessUrl, String authorityUrl, String owner, String secret)
            throws Exception {
        var source = new DriverManagerDataSource(businessUrl, owner, secret);
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var json = new ObjectMapper();
        var materialStore = new cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository(
                db, tx, Clock.systemUTC());
        var originalFiles = new cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles(
                root.resolve("files"));
        var evidenceRules = new cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore(
                db, tx, Clock.systemUTC());
        var refs = new ConductHttpsEvidenceFixture(db, new DataSourceTransactionManager(source), json,
                materialStore, originalFiles, evidenceRules, root, ORG).prepare("seed-operator");
        var store = new Dm8ConductStore(db, tx, json, Clock.systemUTC());
        var basis = new Basis("PROJECT-BASIS", "1", "section 1", "VERIFIED", "project",
                "project-area", "project-procedure");
        var ruleService = new ConductRuleService(store, (a, o, id, v, action) -> { },
                (approved, behavior) -> {
                    if (!basis.equals(approved) || behavior != Behavior.TRADEMARK_INFRINGEMENT)
                        throw new SecurityException("Project basis mismatch");
                });
        ruleService.draft("seed-operator", "dual-rule-draft", new ConductRuleService.Draft(
                "DUAL-CONDUCT-RULE", ORG, Behavior.TRADEMARK_INFRINGEMENT, basis,
                List.of(new Element("BEHAVIOR_FACT", "label observation", true)),
                List.of(), true, "project review"));
        var locked = new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC())
                .revision("CONDUCT-HTTP-EVIDENCE", 1).orElseThrow();
        var linked = locked.links().getFirst();
        var fact = new Fact("POSITIVE-FACT", "MACHINE_CANDIDATE",
                linked.evidenceId(), linked.locator());
        var positive = new Input("positive", ORG, "PROJECT-CASE-001", "1", "CONDUCT-HTTP-EVIDENCE",
                1, "DUAL-CONDUCT-RULE", 1, Behavior.TRADEMARK_INFRINGEMENT, "2026-09-14",
                new RightAtTime("project-right", "1", "VERIFIED_AT_BEHAVIOR_TIME",
                        "2026-09-14", "project-source"), List.of(fact),
                List.of(new Link("BEHAVIOR_FACT", fact.ref(), fact.sourceRef(), fact.locator(),
                        Relation.SUPPORT)), List.of());
        var negative = new Input("negative", ORG, "PROJECT-CASE-001", "1", "CONDUCT-HTTP-EVIDENCE",
                1, "DUAL-CONDUCT-RULE", 1, Behavior.TRADEMARK_INFRINGEMENT, "2026-09-14",
                null, List.of(fact), List.of(), List.of());
        ruleService.publish("seed-operator", "dual-rule-publish", "DUAL-CONDUCT-RULE", 1,
                new ConductRuleService.Publication("named-project-review",
                        List.of(new ConductRuleService.Example(positive, Outcome.SUSPECTED, List.of())),
                        List.of(new ConductRuleService.Example(negative, Outcome.INSUFFICIENT,
                                List.of("ELEMENT:BEHAVIOR_FACT:MISSING", "RIGHT_AT_BEHAVIOR_TIME_UNKNOWN")))));
        var organizations = new EvidenceOrganizationService(evidenceRules,
                new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC()),
                new VerifiedEvidenceSource(root.resolve("case.json"), json, materialStore, originalFiles,
                        unguarded(), Clock.systemUTC()), (a, o, type, id, v, action, purpose) -> { });
        var lockedSource = new VerifiedEvidenceSource(root.resolve("case.json"), json, materialStore,
                originalFiles, unguarded(), Clock.systemUTC());
        var analysis = new ConductAnalysisService(store,
                (a, o, type, id, v, action, purpose) -> { },
                new ConductAnalysisService.LockedEvidence() {
                    @Override public void require(String actor, Input input) {
                        organizations.read(actor, input.evidenceOrganizationId(),
                                input.evidenceOrganizationVersion());
                        lockedSource.read(input.caseRef(), input.caseVersion(), refs);
                    }
                    @Override public void require(String actor, Report report) {
                        organizations.read(actor, report.evidenceOrganizationId(),
                                report.evidenceOrganizationVersion());
                        lockedSource.read(report.caseRef(), report.caseVersion(), refs);
                    }
                },
                new CaseFileConductScope(root.resolve("case.json"), json));
        var input = new Input("DUAL-CONDUCT-ANALYSIS", ORG, "PROJECT-CASE-001", "1",
                "CONDUCT-HTTP-EVIDENCE", 1, "DUAL-CONDUCT-RULE", 2,
                Behavior.TRADEMARK_INFRINGEMENT, "2026-09-14",
                null, List.of(), List.of(), List.of());
        // The locked source is independently rechecked by each HTTP node below.
        analysis.analyze("seed-operator", "dual-analysis", input);
        Path keyStore = root.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "conduct",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "enf-conduct-https-test", "-keypass",
                "enf-conduct-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + keyStore.toUri(),
                "server.ssl.key-store-password=enf-conduct-https-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + businessUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=" + ISSUER,
                "product.identity.audience=https://enf-conduct-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M", "product.identity.activation-lifetime=PT30M",
                "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + root.resolve("files"),
                "enforcement.v2.evidence.case-input-file=" + root.resolve("case.json"),
                "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
                "enforcement.v2.conduct.approved-basis=TRADEMARK_INFRINGEMENT|PROJECT-BASIS|1|section 1|project|project-area|project-procedure"
        };
        try (var first = new SpringApplicationBuilder(ConductIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String firstBase = "https://localhost:" + ((WebServerApplicationContext) first).getWebServer().getPort();
            try (var port = new ServerSocket(0)) {
                int secondPort = port.getLocalPort();
                port.close();
                Process second = ConductIndependentHttpHarness.second(settings, secondPort,
                        root.resolve("second-node.log"));
                try { verify(first, firstBase, "https://localhost:" + secondPort, second,
                        refs, db, materialStore, originalFiles, owner, secret); }
                finally { ConductIndependentHttpHarness.stop(second); }
            }
        }
    }

    private void verify(org.springframework.context.ConfigurableApplicationContext first,
                        String firstBase, String secondBase, Process second,
                        List<cn.xinji.agentplatform.enforcement.evidence.EvidenceTrialSource.Ref> refs,
                        JdbcTemplate db, MaterialRepository materials, MaterialFiles files,
                        String owner, String secret) throws Exception {
        var http = ConductIndependentHttpHarness.client();
        ConductIndependentHttpHarness.ready(http, secondBase, second, root.resolve("second-node.log"));
        first.getBean(ProductSigningKeys.class).rotate("enf-conduct-maintainer");
        var subjects = first.getBean(ProductSubjects.class);
        var subject = subjects.register("enf-conduct-operator", "enf-conduct-enrollment");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "unique-conduct-password-1");
        first.getBean(RegisteredClientRepository.class).save(RegisteredClient.withId("enf-conduct-client")
                .clientId("enf-conduct-client").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) "
                + "VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)", "enf-conduct-client",
                "enf-conduct-client", "https://localhost/callback");
        String token = ConductIndependentHttpHarness.login(http, firstBase);
        var watermark = first.getBean(ProductGrantWatermark.class);
        var grants = first.getBean(ProductGrants.class);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                first.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(token),
                ISSUER, Clock.systemUTC());
        access.prepareSource("CONDUCT-HTTP-SOURCE", 1);
        access.activateSource("CONDUCT-HTTP-SOURCE", 1);
        String actor = subject.subjectId();
        grant(grants, watermark, actor, "EVIDENCE_RULE", "CONDUCT-HTTP-EVIDENCE-RULE", 2,
                "READ_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, actor, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE");
        for (var ref : refs) grant(grants, watermark, actor, "EVIDENCE", ref.id(), 3, "READ", "ANALYZE");
        grant(grants, watermark, actor, "EVIDENCE_ORGANIZATION", "CONDUCT-HTTP-EVIDENCE", 1,
                "READ", "DISPLAY");
        var read = grant(grants, watermark, actor, "CONDUCT_ANALYSIS", "DUAL-CONDUCT-ANALYSIS", 1,
                "READ", "DISPLAY");
        grant(grants, watermark, actor, "CONDUCT_ANALYSIS", "DUAL-CONDUCT-ANALYSIS", 1,
                "REVIEW", "HUMAN_REVIEW");
        String path = "/v2/enforcement/conduct-analyses/DUAL-CONDUCT-ANALYSIS/versions/1";
        assertEquals(200, ConductIndependentHttpHarness.get(http, firstBase + path, token).statusCode());
        assertEquals(200, ConductIndependentHttpHarness.get(http, secondBase + path, token).statusCode());
        String command = path + "/reviews";
        String body = "{\"expectedVersion\":1,\"reason\":\"Historical right pending\","
                + "\"decision\":\"RETURNED\"}";
        var start = new CountDownLatch(1);
        var left = CompletableFuture.supplyAsync(() -> concurrent(http, firstBase + command, token, body, start));
        var right = CompletableFuture.supplyAsync(() -> concurrent(http, secondBase + command, token, body, start));
        start.countDown();
        assertEquals(200, left.get());
        assertEquals(200, right.get());
        assertEquals(2, count(db, "enf7_conduct_report"));
        assertEquals(4, count(db, "enf7_conduct_receipt"));
        assertEquals(4, count(db, "enf7_conduct_event"));
        capture(owner, secret, db, refs);
        String gate = System.getenv("ENF_CONDUCT_RECOVERY_GATE");
        String resumedToken = gate == null ? token : ConductIndependentHttpHarness.login(http, firstBase);
        var original = materials.material(refs.getFirst().id()).orElseThrow();
        Path file = root.resolve("files").resolve(original.fileKey() + ".blob");
        Path held = root.resolve("files").resolve(original.fileKey() + ".held");
        Files.move(file, held);
        try {
            assertEquals(503, ConductIndependentHttpHarness.get(http, secondBase + path, resumedToken).statusCode());
            assertEquals(503, ConductIndependentHttpHarness.get(http, firstBase + path, resumedToken).statusCode());
            assertEquals(4, count(db, "enf7_conduct_receipt"));
        } finally { Files.move(held, file); }
        if (gate != null && !gate.isBlank()) waitFor(Path.of(gate + ".restore-ready"));
        watermark.change(() -> grants.revoke(read.grantId(), 1, "enf-conduct-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertEquals(403, ConductIndependentHttpHarness.get(http, firstBase + path, resumedToken).statusCode());
        assertEquals(403, ConductIndependentHttpHarness.get(http, secondBase + path, resumedToken).statusCode());
        assertEquals(4, count(db, "enf7_conduct_receipt"));
        if (gate != null) {
            Files.writeString(Path.of(gate + ".withdrawn"), "withdrawn", java.nio.file.StandardOpenOption.CREATE_NEW);
            waitFor(Path.of(gate + ".verified"));
        }
        System.out.println("ENF_CONDUCT_DUAL_DM8_HTTP jvms=2 read=200/200 review=200/200 "
                + "missingOriginal=503/503 revoked=403/403 reports=2 receipts=4 events=4");
    }

    private void capture(String owner, String secret, JdbcTemplate db,
                         List<cn.xinji.agentplatform.enforcement.evidence.EvidenceTrialSource.Ref> refs)
            throws Exception {
        String gate = System.getenv("ENF_CONDUCT_RECOVERY_GATE");
        if (gate == null || gate.isBlank()) return;
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        EnforcementIndependentIPrerequisites.requireFreshRecoveryGate(marker);
        var checkpoint = new Properties();
        checkpoint.setProperty("owner", owner);
        checkpoint.setProperty("secret", secret);
        checkpoint.setProperty("sourceRoot", root.toAbsolutePath().normalize().toString());
        checkpoint.setProperty("receiptCount", "4");
        checkpoint.setProperty("fileCount", Integer.toString(refs.size()));
        for (int index = 0; index < refs.size(); index++) {
            var ref = refs.get(index);
            checkpoint.setProperty("file." + index + ".id", ref.id());
            checkpoint.setProperty("file." + index + ".sha256", ref.sha256());
            checkpoint.setProperty("file." + index + ".key", db.queryForObject(
                    "SELECT file_key FROM enf3_material WHERE material_id=?", String.class, ref.id()));
        }
        try (var output = Files.newOutputStream(marker, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            checkpoint.store(output, "Conduct physical recovery; local sensitive test credentials");
        }
        Path resume = Path.of(gate + ".resume");
        Instant deadline = Instant.now().plusSeconds(1200);
        while (!Files.exists(resume) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        if (!Files.exists(resume)) throw new IllegalStateException("Conduct recovery capture timed out");
    }

    private static void waitFor(Path marker) throws Exception {
        Instant deadline = Instant.now().plusSeconds(600);
        while (!Files.exists(marker) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        if (!Files.exists(marker)) throw new IllegalStateException("Conduct recovery verification timed out");
    }

    private static int concurrent(java.net.http.HttpClient http, String url, String token,
                                  String body, CountDownLatch start) {
        try {
            start.await();
            return ConductIndependentHttpHarness.post(http, url, token, body, "dual-review").statusCode();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static ProductGrants.Receipt grant(ProductGrants grants, ProductGrantWatermark watermark,
                                               String actor, String type, String id, long version,
                                               String action, String purpose) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-CONDUCT", "ENF-CONDUCT-D-INPUT-01", type, id,
                version, action, purpose), Instant.now().plusSeconds(3600), 0,
                "enf-conduct-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }

    private static MaterialAccess unguarded() {
        return new MaterialAccess() {
            @Override public void require(String a, String o, String t, String id,
                                          long v, String action, String purpose) { }
            @Override public void requireSource(String id, String use, long epoch) { }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void migrate(DriverManagerDataSource source, String... scripts) throws Exception {
        try (var connection = source.getConnection()) {
            for (String script : scripts)
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(script)));
        }
    }

    private static void createUser(String url, String password, String name, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + name + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + name);
        }
    }

    private static void dropUser(String url, String password, String name) throws Exception {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) { statement.execute("DROP USER " + name); }
    }

    private static void dropAuthority(String url, String name, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, name, secret);
             var statement = connection.createStatement()) {
            for (String table : List.of("enf3_delete_watermark", "enf3_source_watermark",
                    "id2_maintenance_audit", "id2_maintainer", "id2_authority_watermark"))
                statement.execute("DROP TABLE " + table + " CASCADE");
        }
    }
}
