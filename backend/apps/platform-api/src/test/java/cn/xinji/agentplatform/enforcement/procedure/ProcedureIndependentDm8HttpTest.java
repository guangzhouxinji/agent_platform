package cn.xinji.agentplatform.enforcement.procedure;

import cn.xinji.agentplatform.enforcement.EnforcementIndependentIPrerequisites;

import static org.assertj.core.api.Assertions.assertThat;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

class ProcedureIndependentDm8HttpTest {
    private static final String ISSUER = "https://enf-procedure-dual.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    @TempDir Path root;

    @Test void independentHttpsNodesReplayOnceAndRejectWithdrawnOldKey() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null &&
                businessPassword != null && authorityPassword != null,
                "Independent enforcement business and authority DM8 required");
        EnforcementIndependentIPrerequisites.requireDualDm8(businessUrl, authorityUrl);
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EP" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        createUser(businessUrl, businessPassword, owner, secret);
        try {
            createUser(authorityUrl, authorityPassword, owner, secret);
            try {
                var business = new DriverManagerDataSource(businessUrl, owner, secret);
                var authority = new DriverManagerDataSource(authorityUrl, owner, secret);
                migrate(business, List.of("db/dm8/V032__product_identity.sql",
                        "db/dm8/V033__product_oauth2.sql", "db/dm8/V057__product_oauth_client_control.sql",
                        "db/dm8/V035__enforcement_v2_material.sql", "db/dm8/V059__enforcement_v2_procedure.sql"));
                migrate(authority, List.of("db/identity-authority/V001__grant_watermark.sql",
                        "db/identity-authority/V002__enforcement_source_watermark.sql",
                        "db/identity-authority/V005__enforcement_delete_watermark.sql"));
                try {
                    var current = new JdbcTemplate(authority);
                    current.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                            + "VALUES(?,0,'READY',NULL,?)", ISSUER,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                    current.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,"
                            + "certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,?, 'ACTIVE',1)",
                            "enf-procedure-maintainer", ORG, "a".repeat(64), "SUBJECTS,GRANTS,KEYS");
                    exercise(businessUrl, authorityUrl, owner, secret, business, current);
                } finally {
                    migrate(business, List.of("db/dm8/U059__enforcement_v2_procedure.sql",
                            "db/dm8/U035__enforcement_v2_material.sql",
                            "db/dm8/U057__product_oauth_client_control.sql",
                            "db/dm8/U033__product_oauth2.sql", "db/dm8/U032__product_identity.sql"));
                    try (var connection = authority.getConnection(); var statement = connection.createStatement()) {
                        for (String table : List.of("enf3_delete_watermark", "enf3_source_watermark",
                                "id2_maintenance_audit", "id2_maintainer", "id2_authority_watermark"))
                            statement.execute("DROP TABLE " + table + " CASCADE");
                    }
                }
            } finally { dropUser(authorityUrl, authorityPassword, owner); }
        } finally { dropUser(businessUrl, businessPassword, owner); }
    }

    private void exercise(String businessUrl, String authorityUrl, String owner, String secret,
                          DriverManagerDataSource business, JdbcTemplate authority) throws Exception {
        Path keyStore = root.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "procedure",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "enf-procedure-dual-test",
                "-keypass", "enf-procedure-dual-test", "-noprompt").redirectErrorStream(true).start();
        if (keytool.waitFor() != 0) throw new IllegalStateException("Procedure TLS key unavailable");
        Path acceptance = root.resolve("acceptance.json");
        Files.copy(Path.of("data/enforcement/procedure-v2-acceptance-v1/manifest.json"), acceptance);
        String[] settings = { "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http",
                "server.port=0", "server.ssl.key-store=" + keyStore.toUri(),
                "server.ssl.key-store-password=enf-procedure-dual-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + businessUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=" + ISSUER,
                "product.identity.audience=https://enf-procedure-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M",
                "product.identity.activation-lifetime=PT30M", "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + root.resolve("originals"),
                "enforcement.v2.procedure.source-file=" + acceptance,
                "enforcement.v2.procedure.approved-basis="
                        + "PROJECT-METHOD|1|section|PROJECT_TEST|PROJECT_TEST|PROJECT_DOCUMENT_REVIEW|PROJECT_HANDOFF_REVIEW" };
        try (var first = new SpringApplicationBuilder(ProcedureIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String firstBase = "https://localhost:" + ((WebServerApplicationContext) first).getWebServer().getPort();
            int secondPort;
            try (var socket = new ServerSocket(0)) { secondPort = socket.getLocalPort(); }
            Process second = second(settings, secondPort);
            try {
                String secondBase = "https://localhost:" + secondPort;
                var http = client();
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
                boolean ready = false;
                while (System.nanoTime() < deadline && second.isAlive()) {
                    try { if (request(http, "GET", secondBase + "/login", null, null, null).statusCode() == 200)
                        { ready = true; break; } }
                    catch (Exception notReady) { Thread.sleep(200); }
                }
                assertThat(ready).as("Second independent Procedure HTTPS JVM").isTrue();
                first.getBean(ProductSigningKeys.class).rotate("enf-procedure-maintainer");
                var subjects = first.getBean(ProductSubjects.class);
                var actor = subjects.register("named-procedure-dual", "procedure-dual-enrollment");
                subjects.activate(actor.subjectId(), actor.oneTimeActivation(), "strong-procedure-dual-password-1");
                first.getBean(RegisteredClientRepository.class).save(RegisteredClient.withId("enf-procedure-client")
                        .clientId("enf-procedure-client")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://localhost/callback").scope("openid")
                        .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
                var db = new JdbcTemplate(business);
                db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,created_at) "
                        + "VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)", "enf-procedure-client",
                        "enf-procedure-client", "https://localhost/callback");
                String firstToken = ProcedureHttpsLogin.token(trust(), new ObjectMapper(), firstBase,
                        "named-procedure-dual", "strong-procedure-dual-password-1");
                String secondToken = ProcedureHttpsLogin.token(trust(), new ObjectMapper(), secondBase,
                        "named-procedure-dual", "strong-procedure-dual-password-1");
                var watermark = first.getBean(ProductGrantWatermark.class);
                var grants = first.getBean(ProductGrants.class);
                var edit = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                        actor.subjectId(), ORG, "ENFORCEMENT", "ENF-V2-PROCEDURE",
                        "ENF-PROCEDURE-PRODUCT-ACCEPTANCE-01", "PROCEDURE_RULE", "DUAL-RULE", 1,
                        "EDIT_RULE", "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), 0,
                        "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
                String body = "{\"id\":\"DUAL-RULE\",\"organization\":\"" + ORG + "\","
                        + "\"region\":\"PROJECT_TEST\",\"procedure\":\"PROJECT_DOCUMENT_REVIEW\","
                        + "\"stage\":\"PROJECT_HANDOFF_REVIEW\",\"requiredEvents\":[\"REQUEST_RECORDED\"],"
                        + "\"requiredMaterials\":[]}";
                String path = "/v2/enforcement/procedure-rules";
                var readyBoth = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var left = CompletableFuture.supplyAsync(() -> compete(http, firstBase + path,
                        firstToken, body, readyBoth, start));
                var right = CompletableFuture.supplyAsync(() -> compete(http, secondBase + path,
                        secondToken, body, readyBoth, start));
                assertThat(readyBoth.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 200);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_rule", Integer.class)).isEqualTo(1);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(1);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(1);
                var recovery = prepareRecoveryReport(first, firstBase, secondBase, firstToken, db, authority, http,
                        actor.subjectId(), watermark, grants, acceptance);
                recoveryGate(owner, secret, recovery, watermark, grants);
                watermark.change(() -> grants.revoke(edit.grantId(), edit.grantVersion(),
                        "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
                assertThat(request(http, "POST", firstBase + path, firstToken, body, "dual-draft").statusCode())
                        .isEqualTo(403);
                assertThat(request(http, "POST", secondBase + path, secondToken, body, "dual-draft").statusCode())
                        .isEqualTo(403);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(4);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(4);
                System.out.println("ENF_PROCEDURE_DUAL_DM8_HTTP replay=200/200 receipts=4 events=4 revoked=403/403 original=verified");
            } finally { second.destroy(); if (!second.waitFor(10, TimeUnit.SECONDS)) second.destroyForcibly(); }
        }
    }

    private record RecoveryInput(String ref, ProductGrants.Receipt readGrant) { }

    private RecoveryInput prepareRecoveryReport(org.springframework.context.ConfigurableApplicationContext first,
                                       String firstBase, String secondBase, String firstToken,
                                       JdbcTemplate db, JdbcTemplate authority, HttpClient http,
                                       String author, ProductGrantWatermark watermark, ProductGrants grants,
                                       Path acceptance) throws Exception {
        var json = first.getBean(ObjectMapper.class);
        String ref = ProcedureHttpsMaterialFixture.prepare(first.getBean(MaterialRepository.class),
                first.getBean(MaterialFiles.class), json, acceptance, ORG, author, authority,
                "PROCEDURE-DUAL-SOURCE");
        var reviewer = first.getBean(ProductSubjects.class).register("named-procedure-dual-reviewer",
                "procedure-dual-reviewer-enrollment");
        first.getBean(ProductSubjects.class).activate(reviewer.subjectId(), reviewer.oneTimeActivation(),
                "strong-procedure-dual-reviewer-1");
        String reviewerToken = ProcedureHttpsLogin.token(trust(), json, secondBase,
                "named-procedure-dual-reviewer", "strong-procedure-dual-reviewer-1");
        ProductGrants.Receipt authorRead = null;
        for (String subject : List.of(author, reviewer.subjectId())) {
            var read = grant(grants, watermark, subject, "EVIDENCE", ref.split("@")[0], 3,
                    "READ", "ANALYZE");
            if (subject.equals(author)) authorRead = read;
            grant(grants, watermark, subject, "CASE", "PROJECT-CASE-PROCEDURE-01", 1,
                    "READ_CASE", "PROCEDURE_CHECK");
        }
        grant(grants, watermark, author, "PROCEDURE_RULE", "RECOVERY-RULE", 1,
                "EDIT_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, reviewer.subjectId(), "PROCEDURE_RULE", "RECOVERY-RULE", 1,
                "READ_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, reviewer.subjectId(), "PROCEDURE_RULE", "RECOVERY-RULE", 1,
                "PUBLISH_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, reviewer.subjectId(), "CASE", "PROJECT-CASE-PROCEDURE-01", 1,
                "READ_CASE", "RULE_MANAGEMENT");
        grant(grants, watermark, reviewer.subjectId(), "CASE", "PROJECT-CASE-PROCEDURE-MISSING", 1,
                "READ_CASE", "RULE_MANAGEMENT");
        var rule = new Rule("RECOVERY-RULE", 0, ORG, "PROJECT_TEST", "PROJECT_DOCUMENT_REVIEW",
                "PROJECT_HANDOFF_REVIEW", null,
                new Basis("PROJECT-METHOD", "1", "section", "VERIFIED", "PROJECT_TEST",
                        LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-22")),
                new ClockRule(Unit.WORKDAY, 2, "REQUEST_RECORDED", false, "NEXT_WORKDAY",
                        "PROJECT-CALENDAR-01", "1", "INCLUSIVE_PAUSE_RESUME_NEXT_DAY", "NONE", 0),
                List.of("REQUEST_RECORDED"), List.of(ref), null, null, null);
        String rules = "/v2/enforcement/procedure-rules";
        assertThat(request(http, "POST", firstBase + rules, firstToken,
                json.writeValueAsString(rule), "recovery-draft").statusCode()).isEqualTo(200);
        String positive = "{\"caseRef\":\"PROJECT-CASE-PROCEDURE-01\",\"caseVersion\":\"1\","
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        String negative = positive.replace("PROJECT-CASE-PROCEDURE-01", "PROJECT-CASE-PROCEDURE-MISSING");
        String publish = "{\"reviewRef\":\"PROJECT-METHOD-REVIEW-DUAL\",\"reviewer\":\""
                + reviewer.subjectId() + "\",\"positive\":[" + positive + "],\"negative\":[" + negative + "]}";
        assertThat(request(http, "POST", secondBase + rules + "/RECOVERY-RULE/versions/1/publish",
                reviewerToken, publish, "recovery-publish").statusCode()).isEqualTo(200);
        grant(grants, watermark, author, "PROCEDURE_RULE", "RECOVERY-RULE", 2,
                "READ_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, author, "CASE", "PROJECT-CASE-PROCEDURE-01", 1,
                "CHECK_CASE", "PROCEDURE_CHECK");
        String check = "{\"id\":\"RECOVERY-REPORT\",\"caseRef\":\"PROJECT-CASE-PROCEDURE-01\","
                + "\"caseVersion\":\"1\",\"ruleId\":\"RECOVERY-RULE\",\"ruleVersion\":2,"
                + "\"calendarRef\":\"PROJECT-CALENDAR-01\",\"calendarVersion\":\"1\"}";
        assertThat(request(http, "POST", firstBase + "/v2/enforcement/procedure-checks",
                firstToken, check, "recovery-check").statusCode()).isEqualTo(200);
        grant(grants, watermark, author, "PROCEDURE_REPORT", "RECOVERY-REPORT", 1,
                "READ_REPORT", "PROCEDURE_CHECK");
        String report = "/v2/enforcement/procedure-checks/RECOVERY-REPORT/versions/1";
        assertThat(request(http, "GET", secondBase + report, firstToken, null, null).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(4);
        return new RecoveryInput(ref, authorRead);
    }

    private void recoveryGate(String owner, String secret, RecoveryInput input,
                              ProductGrantWatermark watermark, ProductGrants grants) throws Exception {
        String gate = System.getenv("ENF_PROCEDURE_RECOVERY_GATE");
        if (gate == null || gate.isBlank()) return;
        Path marker = Path.of(gate);
        Path ready = Path.of(gate + ".restore-ready");
        Path withdrawn = Path.of(gate + ".withdrawn");
        Path verified = Path.of(gate + ".verified");
        EnforcementIndependentIPrerequisites.requireFreshRecoveryGate(marker);
        var checkpoint = new Properties();
        checkpoint.setProperty("owner", owner);
        checkpoint.setProperty("secret", secret);
        checkpoint.setProperty("sourceRoot", root.toAbsolutePath().toString());
        checkpoint.setProperty("materialRef", input.ref());
        checkpoint.setProperty("receipts", "4");
        try (var output = Files.newOutputStream(marker, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            checkpoint.store(output, "Procedure cold restore checkpoint");
        }
        waitFor(ready);
        watermark.change(() -> grants.revoke(input.readGrant().grantId(), input.readGrant().grantVersion(),
                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        Files.writeString(withdrawn, "withdrawn", java.nio.file.StandardOpenOption.CREATE_NEW);
        waitFor(verified);
    }

    private static void waitFor(Path marker) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        while (!Files.exists(marker) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        assertThat(Files.exists(marker)).as("Independent physical restore marker " + marker).isTrue();
    }

    private static ProductGrants.Receipt grant(ProductGrants grants, ProductGrantWatermark watermark,
                                                String actor, String type, String id, long version,
                                                String action, String purpose) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-PROCEDURE", "ENF-PROCEDURE-PRODUCT-ACCEPTANCE-01",
                type, id, version, action, purpose), Instant.now().plusSeconds(3600), 0,
                "enf-procedure-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }

    private static int compete(HttpClient http, String url, String token, String body,
                               CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try { start.await(); return request(http, "POST", url, token, body, "dual-draft").statusCode(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private Process second(String[] settings, int port) throws Exception {
        Path jar = Path.of("backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertThat(Files.exists(jar)).as("Packaged product executable for independent JVM").isTrue();
        var command = new java.util.ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dloader.path=" + Path.of("backend/apps/platform-api/target/test-classes").toAbsolutePath(),
                "-Dloader.main=" + ProcedureIndependentHttpNode.class.getName(),
                "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher"));
        for (var setting : settings)
            if (!setting.startsWith("server.port=") && !setting.startsWith("spring.datasource.password=") &&
                    !setting.startsWith("product.identity.authority.password=")) command.add("--" + setting);
        command.add("--server.port=" + port);
        var builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(root.resolve("second-node.log").toFile());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", value(settings, "spring.datasource.password="));
        builder.environment().put("PRODUCT_IDENTITY_AUTHORITY_PASSWORD",
                value(settings, "product.identity.authority.password="));
        return builder.start();
    }

    private static String value(String[] settings, String prefix) {
        for (var setting : settings) if (setting.startsWith(prefix)) return setting.substring(prefix.length());
        throw new IllegalArgumentException("Missing isolated setting");
    }
    private static void migrate(DriverManagerDataSource source, List<String> scripts) throws Exception {
        try (var connection = source.getConnection()) {
            for (var script : scripts)
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(script)));
        }
    }
    private static void createUser(String url, String password, String owner, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + owner);
        }
    }
    private static void dropUser(String url, String password, String owner) throws Exception {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) { statement.execute("DROP USER " + owner); }
    }
    private static SSLContext trust() throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return tls;
    }
    private static HttpClient client() throws Exception {
        return HttpClient.newBuilder().sslContext(trust()).build();
    }
    private static HttpResponse<String> request(HttpClient http, String method, String url, String token,
                                                String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
