package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class EvidenceIndependentDm8HttpTest {
    private static final String ISSUER = "https://enf-evidence-dual.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    @TempDir Path temporary;

    @Test void twoIndependentHttpsJvmsUseCurrentEvidenceRightsAndOneReceipt() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null
                && authorityPassword != null && !businessUrl.equals(authorityUrl), "Dedicated ENF DBs required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EH" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        createUser(businessUrl, businessPassword, owner, secret);
        try {
            createUser(authorityUrl, authorityPassword, owner, secret);
            try {
                var business = new DriverManagerDataSource(businessUrl, owner, secret);
                var authoritySource = new DriverManagerDataSource(authorityUrl, owner, secret);
                migrate(business, "db/dm8/V032__product_identity.sql", "db/dm8/V033__product_oauth2.sql",
                        "db/dm8/V057__product_oauth_client_control.sql",
                        "db/dm8/V035__enforcement_v2_material.sql",
                        "db/dm8/V050__enforcement_v2_evidence_rules.sql",
                        "db/dm8/V051__enforcement_v2_evidence_publication.sql",
                        "db/dm8/V052__enforcement_v2_evidence_organizations.sql");
                migrate(authoritySource, "db/identity-authority/V001__grant_watermark.sql",
                        "db/identity-authority/V002__enforcement_source_watermark.sql",
                        "db/identity-authority/V005__enforcement_delete_watermark.sql");
                var authority = new JdbcTemplate(authoritySource);
                authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                        + "VALUES(?,0,'READY',NULL,?)", ISSUER,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                authority.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,"
                        + "certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,?, 'ACTIVE',1)",
                        "enf-evidence-maintainer", ORG, "a".repeat(64), "SUBJECTS,GRANTS,KEYS");
                try {
                    EvidenceOrganizationProductTest.exercise(business, temporary);
                    exercise(businessUrl, authorityUrl, owner, secret, authority);
                } finally {
                    migrate(business, "db/dm8/U052__enforcement_v2_evidence_organizations.sql",
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

    private void exercise(String businessUrl, String authorityUrl, String owner, String secret,
                          JdbcTemplate authority) throws Exception {
        Path store = temporary.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "evidence",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-evidence-https-test", "-keypass",
                "enf-evidence-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + store.toUri(), "server.ssl.key-store-password=enf-evidence-https-test",
                "server.ssl.key-store-type=PKCS12", "spring.datasource.url=" + businessUrl,
                "spring.datasource.username=" + owner, "spring.datasource.password=" + secret,
                "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver", "spring.sql.init.mode=never",
                "product.identity.enabled=true", "enforcement.v2.enabled=true", "product.identity.issuer=" + ISSUER,
                "product.identity.audience=https://enf-evidence-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M",
                "product.identity.activation-lifetime=PT30M", "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + temporary.resolve("files"),
                "enforcement.v2.evidence.case-input-file=" + temporary.resolve("case-snapshot.json"),
                "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
                "enforcement.v2.evidence.approved-basis=PROJECT_DOCUMENT_REVIEW|docs/57#6.24|1|PROJECT-CASE-001"
        };
        try (var first = new SpringApplicationBuilder(EvidenceIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String firstBase = "https://localhost:" + ((WebServerApplicationContext) first).getWebServer().getPort();
            try (var port = new ServerSocket(0)) {
                int secondPort = port.getLocalPort();
                port.close();
                Process second = EvidenceIndependentHttpHarness.second(settings, secondPort,
                        temporary.resolve("second-node.log"));
                try { verify(first, firstBase, "https://localhost:" + secondPort, second, authority,
                        owner, secret); }
                finally { EvidenceIndependentHttpHarness.stop(second); }
            }
        }
    }

    private void verify(ConfigurableApplicationContext first, String firstBase, String secondBase,
                        Process second, JdbcTemplate authority, String owner, String secret) throws Exception {
        HttpClient http = EvidenceIndependentHttpHarness.client();
        EvidenceIndependentHttpHarness.ready(http, secondBase, second,
                temporary.resolve("second-node.log"));
        first.getBean(ProductSigningKeys.class).rotate("enf-evidence-maintainer");
        var subjects = first.getBean(ProductSubjects.class);
        var subject = subjects.register("enf-evidence-operator", "enf-evidence-maintainer");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "evidence-strong-password-1");
        first.getBean(RegisteredClientRepository.class).save(RegisteredClient.withId("enf-evidence-client")
                .clientId("enf-evidence-client").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        first.getBean(JdbcTemplate.class).update("INSERT INTO id2_client_current(client_id,registered_id,"
                + "redirect_uri,status,version,created_at) VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                "enf-evidence-client", "enf-evidence-client", "https://localhost/callback");
        String token = EvidenceIndependentHttpHarness.login(http, firstBase);
        var db = first.getBean(JdbcTemplate.class);
        var watermark = first.getBean(ProductGrantWatermark.class);
        var grants = first.getBean(ProductGrants.class);
        var jwt = first.getBean(JwtDecoder.class).decode(token);
        var access = new ProductEnforcementAccess(db, authority, watermark, jwt, ISSUER, Clock.systemUTC());
        access.prepareSource("EVIDENCE-SOURCE", 1);
        access.activateSource("EVIDENCE-SOURCE", 1);
        String actor = subject.subjectId();
        grant(grants, watermark, actor, "EVIDENCE_RULE", "RULE-ORG", 2, "READ_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, actor, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE");
        grant(grants, watermark, actor, "CASE", "PROJECT-CASE-001", 1, "ORGANIZE", "ANALYZE");
        var caseInput = first.getBean(ObjectMapper.class).readTree(Files.readAllBytes(
                temporary.resolve("case-snapshot.json")));
        for (var ref : caseInput.path("materials")) {
            if (ref.path("id").asText().equals(caseInput.path("materials").get(1).path("id").asText())) continue;
            grant(grants, watermark, actor, "EVIDENCE", ref.path("id").asText(), 3, "READ", "ANALYZE");
            grant(grants, watermark, actor, "EVIDENCE", ref.path("id").asText(), 3, "ANALYZE", "ANALYZE");
        }
        var readGrant = grant(grants, watermark, actor, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 4,
                "READ", "DISPLAY");
        var correctGrant = grant(grants, watermark, actor, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 4,
                "CORRECT", "ANALYZE");
        String path = "/v2/enforcement/evidence-organizations/ORG-CASE-001/versions/4";
        var firstRead = EvidenceIndependentHttpHarness.get(http, firstBase + path, token);
        var secondRead = EvidenceIndependentHttpHarness.get(http, secondBase + path, token);
        assertThat(firstRead.statusCode()).as(firstRead.body()).isEqualTo(200);
        assertThat(secondRead.statusCode()).as(secondRead.body()).isEqualTo(200);
        assertThat(firstRead.body()).isEqualTo(secondRead.body()).contains("REVIEW_REQUIRED");
        var exclusion = first.getBean(ObjectMapper.class).createObjectNode()
                .put("expectedVersion", 4).put("evidenceId", caseInput.path("materials").get(2).path("id").asText())
                .put("reason", "C is unrelated to the current event");
        String body = exclusion.toString();
        String command = "/v2/enforcement/evidence-organizations/ORG-CASE-001/exclusions";
        int before = count(db, "enf4_organization_receipt");
        var start = new CountDownLatch(1);
        var firstReplay = CompletableFuture.supplyAsync(() -> replay(http, firstBase + command, token, body, start));
        var secondReplay = CompletableFuture.supplyAsync(() -> replay(http, secondBase + command, token, body, start));
        start.countDown();
        assertThat(firstReplay.get()).isEqualTo(200);
        assertThat(secondReplay.get()).isEqualTo(200);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(before + 1);
        assertThat(count(db, "enf4_organization_event")).isEqualTo(before + 1);
        assertThat(db.queryForObject("SELECT MAX(revision) FROM enf4_organization "
                + "WHERE organization_id='ORG-CASE-001'", Long.class)).isEqualTo(5L);
        EvidenceIndependentRecoveryCapture.beforeWithdrawal(temporary, db, owner, secret, token, body);
        watermark.change(() -> grants.revoke(readGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(EvidenceIndependentHttpHarness.get(http, secondBase + path, token).statusCode()).isEqualTo(403);
        assertThat(EvidenceIndependentHttpHarness.get(http, firstBase + path, token).statusCode()).isEqualTo(403);
        watermark.change(() -> grants.revoke(correctGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(EvidenceIndependentHttpHarness.post(http, secondBase + command, token, body, "dual-exclude-c")
                .statusCode()).isEqualTo(403);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(before + 1);
        assertThat(count(db, "enf4_organization_event")).isEqualTo(before + 1);
        assertThat(authority.update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?",
                "EVIDENCE-SOURCE")).isEqualTo(1);
        assertThat(EvidenceIndependentHttpHarness.post(http, firstBase + command, token, body, "dual-exclude-c")
                .statusCode()).isEqualTo(403);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(before + 1);
        EvidenceIndependentRecoveryCapture.afterWithdrawal();
        System.out.printf("ENF_EVIDENCE_DUAL_DM8_HTTP jvms=2 business=5257 authority=5258 "
                + "before=200/200 concurrentCommand=200/200 after=403/403 oldReceipt=403 receipts=%d%n",
                before + 1);
    }

    private static int replay(HttpClient http, String url, String token, String body, CountDownLatch start) {
        try {
            start.await();
            return EvidenceIndependentHttpHarness.post(http, url, token, body, "dual-exclude-c").statusCode();
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }

    private static ProductGrants.Receipt grant(ProductGrants grants, ProductGrantWatermark watermark,
                                               String actor, String type, String id, long version,
                                               String action, String purpose) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-EVIDENCE", "ENF-EVIDENCE-V2-ACCEPTANCE-01",
                type, id, version, action, purpose), Instant.now().plusSeconds(3600), 0,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
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

    private static void createUser(String url, String password, String name, String secret) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + name + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + name);
        }
    }

    private static void dropUser(String url, String password, String name) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) { statement.execute("DROP USER " + name); }
    }

    private static void dropAuthority(String url, String name, String secret) throws SQLException {
        try (var connection = DriverManager.getConnection(url, name, secret);
             var statement = connection.createStatement()) {
            for (String table : List.of("enf3_delete_watermark", "enf3_source_watermark",
                    "id2_maintenance_audit", "id2_maintainer", "id2_authority_watermark"))
                statement.execute("DROP TABLE " + table + " CASCADE");
        }
    }
}
