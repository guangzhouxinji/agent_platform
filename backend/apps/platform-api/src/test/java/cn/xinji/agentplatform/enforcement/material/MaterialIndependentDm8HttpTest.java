package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import cn.xinji.agentplatform.security.product.ProductSubjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.ServerSocket;
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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

class MaterialIndependentDm8HttpTest {
    private static final String ISSUER = "https://enf-m04.example.org";
    private static final String ORG = "ENF-M04-DEV";
    private static final String SOURCE = "PROJECT-DEV-OBSERVATION";
    private static final String RULE = "ENF-RULE-V1-DEV-MARKING-01";
    private static final String CONTRACT = "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1";
    @TempDir Path temporary;

    @Test void twoSeparateHttpsProcessesOnDedicatedDm8ObserveCurrentWithdrawal() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null
                && authorityPassword != null && !businessUrl.equals(authorityUrl), "Dedicated ENF DBs required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EM" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        createUser(businessUrl, businessPassword, owner, secret);
        try {
            createUser(authorityUrl, authorityPassword, owner, secret);
            try {
                var business = new DriverManagerDataSource(businessUrl, owner, secret);
                var authoritySource = new DriverManagerDataSource(authorityUrl, owner, secret);
                migrate(business, "db/dm8/V032__product_identity.sql", "db/dm8/V033__product_oauth2.sql",
                        "db/dm8/V057__product_oauth_client_control.sql",
                        "db/dm8/V035__enforcement_v2_material.sql");
                migrate(authoritySource, "db/identity-authority/V001__grant_watermark.sql",
                        "db/identity-authority/V002__enforcement_source_watermark.sql",
                        "db/identity-authority/V005__enforcement_delete_watermark.sql");
                var authority = new JdbcTemplate(authoritySource);
                authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                        + "VALUES(?,0,'READY',NULL,?)", ISSUER,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                authority.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,"
                        + "certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,?, 'ACTIVE',1)",
                        "enf-m04-maintainer", ORG, "a".repeat(64), "SUBJECTS,GRANTS,KEYS");
                try { exercise(businessUrl, authorityUrl, owner, secret, authority); }
                finally {
                    migrate(business, "db/dm8/U035__enforcement_v2_material.sql",
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
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "enforcement",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-m04-https-test", "-keypass",
                "enf-m04-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        Path root = temporary.resolve("files");
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + store.toUri(), "server.ssl.key-store-password=enf-m04-https-test",
                "server.ssl.key-store-type=PKCS12", "spring.datasource.url=" + businessUrl,
                "spring.datasource.username=" + owner, "spring.datasource.password=" + secret,
                "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver", "spring.sql.init.mode=never",
                "product.identity.enabled=true", "enforcement.v2.enabled=true", "product.identity.issuer=" + ISSUER,
                "product.identity.audience=https://enf-m04-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M",
                "product.identity.activation-lifetime=PT30M",
                "product.identity.minimum-password-length=12", "enforcement.v2.file-root=" + root
        };
        try (var first = new SpringApplicationBuilder(MaterialIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String firstBase = "https://localhost:" + ((WebServerApplicationContext) first).getWebServer().getPort();
            try (var port = new ServerSocket(0)) {
                int secondPort = port.getLocalPort();
                // Release the reservation before the separate JVM binds this port.
                port.close();
                Process second = startSecond(settings, secondPort);
                try { verifyTwoProcesses(first, firstBase, "https://localhost:" + secondPort,
                        second, root, authority); }
                finally {
                    second.destroy();
                    if (!second.waitFor(10, TimeUnit.SECONDS)) second.destroyForcibly();
                }
            }
        }
    }

    private Process startSecond(String[] settings, int port) throws Exception {
        Path jar = Path.of("backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar")
                .toAbsolutePath();
        Path testClasses = Path.of("backend/apps/platform-api/target/test-classes").toAbsolutePath();
        assertThat(Files.exists(jar)).isTrue();
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Dloader.path=" + testClasses);
        command.add("-Dloader.main=" + MaterialIndependentHttpNode.class.getName());
        command.add("-cp");
        command.add(jar.toString());
        command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        for (String setting : settings) {
            if (setting.startsWith("server.port=") || setting.startsWith("spring.datasource.password=")
                    || setting.startsWith("product.identity.authority.password=")) continue;
            command.add("--" + setting);
        }
        command.add("--server.port=" + port);
        var builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("second-node.log").toFile());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", settingsValue(settings, "spring.datasource.password="));
        builder.environment().put("PRODUCT_IDENTITY_AUTHORITY_PASSWORD",
                settingsValue(settings, "product.identity.authority.password="));
        return builder.start();
    }

    private void verifyTwoProcesses(ConfigurableApplicationContext first, String firstBase, String secondBase,
                                    Process second, Path root, JdbcTemplate authority) throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        var http = HttpClient.newBuilder().sslContext(tls).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline && second.isAlive()) {
            try { ready = get(http, secondBase + "/login", null).statusCode() == 200; }
            catch (Exception notReady) { Thread.sleep(200); }
            if (ready) break;
        }
        assertThat(ready).as("Second isolated HTTPS JVM must be ready").isTrue();
        first.getBean(ProductSigningKeys.class).rotate("enf-m04-maintainer");
        var subjects = first.getBean(ProductSubjects.class);
        var subject = subjects.register("enf-m04-operator", "enf-m04-maintainer");
        subjects.activate(subject.subjectId(), subject.oneTimeActivation(), "enf-m04-strong-password-1");
        first.getBean(RegisteredClientRepository.class).save(RegisteredClient.withId("enf-m04-client")
                .clientId("enf-m04-client").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        first.getBean(JdbcTemplate.class).update("INSERT INTO id2_client_current(client_id,registered_id,"
                + "redirect_uri,status,version,created_at) VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                "enf-m04-client", "enf-m04-client", "https://localhost/callback");
        String token = login(http, firstBase);
        var jwt = first.getBean(JwtDecoder.class).decode(token);
        var db = first.getBean(JdbcTemplate.class);
        var watermark = first.getBean(ProductGrantWatermark.class);
        var grants = first.getBean(ProductGrants.class);
        var service = new MaterialProductService(first.getBean(MaterialRepository.class),
                first.getBean(MaterialFiles.class), new ProductEnforcementAccess(db, authority, watermark,
                        jwt, ISSUER, Clock.systemUTC()), Clock.systemUTC());
        String actor = subject.subjectId();
        grant(grants, watermark, actor, "ENFORCEMENT_SOURCE", SOURCE, 1, "CONFIGURE", "SOURCE_MANAGEMENT");
        service.registerSource(actor, "m04-source", new MaterialProductService.SourceInput(SOURCE, ORG,
                "docs/02#40", 1, Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY"),
                "m04-source"));
        grant(grants, watermark, actor, "ENFORCEMENT_SOURCE", SOURCE, 1, "SYNC", "SAVE");
        var records = first.getBean(ObjectMapper.class).readTree(Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/observations.json"))).get("records");
        var observed = service.observe(actor, "m04-observe", new MaterialProductService.ObservationInput(SOURCE,
                records.get(0).get("recordId").asText(), records.get(0).get("version").asText(),
                records.get(0).get("sourceText").asText(), records.get(0).get("materialMention").asText()));
        var negative = service.observe(actor, "m04-negative", new MaterialProductService.ObservationInput(SOURCE,
                records.get(1).get("recordId").asText(), records.get(1).get("version").asText(),
                records.get(1).get("sourceText").asText(), records.get(1).get("materialMention").asText()));
        grant(grants, watermark, actor, "RULE", RULE, 1, "EDIT_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, actor, "RULE", RULE, 1, "PUBLISH_RULE", "RULE_MANAGEMENT");
        service.draftRule(actor, "m04-draft", new MaterialProductService.RuleInput(RULE, SOURCE,
                "专利产品", "docs/02#40"));
        grant(grants, watermark, actor, "OBSERVATION", observed.resultId(), 1, "READ", "DISPLAY");
        grant(grants, watermark, actor, "OBSERVATION", negative.resultId(), 1, "READ", "DISPLAY");
        service.ruleState(actor, "m04-publish", RULE, 1, "PUBLISH_RULE",
                observed.resultId(), negative.resultId());
        grant(grants, watermark, actor, "OBSERVATION", observed.resultId(), 1, "CREATE_CLUE", "QUERY");
        var clue = service.discover(actor, "m04-discover", observed.resultId(), RULE);
        grant(grants, watermark, actor, "CLUE", clue.resultId(), 1, "REVISE", "SAVE");
        service.revise(actor, "m04-revise", clue.resultId(), 1, "待核原文", "人工核对");
        grant(grants, watermark, actor, "CLUE", clue.resultId(), 2, "REGISTER_MATERIAL", "SAVE");
        byte[] original = Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/original-M-01.txt"));
        Instant expiresAt = Instant.now().plusSeconds(3600);
        var input = new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40",
                expiresAt, List.of("SAVE", "PREVIEW"), actor, "2026-09-27");
        var material = service.registerMaterial(actor, "m04-material", input);
        var previewGrant = grant(grants, watermark, actor, "MATERIAL", material.resultId(), 1,
                "PREVIEW", "PREVIEW");
        String preview = "/v2/enforcement/materials/" + material.resultId() + "/preview";
        assertThat(get(http, firstBase + preview, token).body().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(original);
        var before = get(http, secondBase + preview, token);
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
        assertThat(before.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(original);
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        int materials = count(db, "enf3_material");
        long files;
        try (var paths = Files.list(root)) { files = paths.count(); }
        watermark.change(() -> grants.revoke(previewGrant.grantId(), 1, "enf-m04-maintainer",
                UUID.randomUUID().toString(), "b".repeat(64)));
        var secondDenied = get(http, secondBase + preview, token);
        var firstDenied = get(http, firstBase + preview, token);
        assertThat(secondDenied.statusCode()).isEqualTo(403);
        assertThat(firstDenied.statusCode()).isEqualTo(403);
        assertThat(secondDenied.body()).doesNotContain("虚构");
        assertThat(firstDenied.body()).doesNotContain("虚构");
        assertThat(authority.update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?",
                SOURCE)).isEqualTo(1);
        var replayBody = first.getBean(ObjectMapper.class).createObjectNode().put("clueId", clue.resultId())
                .put("bytes", Base64.getEncoder().encodeToString(original)).put("rightsRef", "docs/02#40")
                .put("expiresAt", expiresAt.toString()).put("collector", actor).put("collectedAt", "2026-09-27");
        replayBody.putArray("uses").add("SAVE").add("PREVIEW");
        assertThat(post(http, secondBase + "/v2/enforcement/materials",
                first.getBean(ObjectMapper.class).writeValueAsString(replayBody), token, "m04-material")
                .statusCode()).isEqualTo(403);
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        assertThat(count(db, "enf3_material")).isEqualTo(materials);
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(files); }
        System.out.printf("ENF_V2_M04_INDEPENDENT_DM8 jvms=2 business=5257 authority=5258 "
                + "before=200/200 after=403/403 oldReceipt=403 receipts=%d events=%d materials=%d files=%d%n",
                receipts, events, materials, files);
    }

    private static String login(HttpClient http, String base) throws Exception {
        var login = get(http, base + "/login", null);
        assertThat(login.statusCode()).isEqualTo(200);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        var loggedIn = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=enf-m04-operator&password="
                        + "enf-m04-strong-password-1&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loggedIn.statusCode()).isEqualTo(302);
        String verifier = "enf-m04-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8);
        var authorize = get(http, base + "/oauth2/authorize?response_type=code&client_id=enf-m04-client"
                + "&redirect_uri=" + redirect + "&scope=openid&state=enf-m04-state&code_challenge="
                + challenge + "&code_challenge_method=S256", null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location").orElseThrow())
                .getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var token = http.send(HttpRequest.newBuilder(URI.create(base + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&client_id=enf-m04-client"
                        + "&redirect_uri=" + redirect + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        return new ObjectMapper().readTree(token.body()).get("access_token").asText();
    }

    private static HttpResponse<String> get(HttpClient http, String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, String url, String body,
                                             String token, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ProductGrants.Receipt grant(ProductGrants grants, ProductGrantWatermark watermark,
                                               String actor, String type, String id, long version,
                                               String action, String purpose) {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-MATERIAL", CONTRACT, type, id, version, action, purpose),
                Instant.now().plusSeconds(3600), 0, "enf-m04-maintainer", UUID.randomUUID().toString(),
                "a".repeat(64)));
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String settingsValue(String[] settings, String prefix) {
        for (String setting : settings) if (setting.startsWith(prefix)) return setting.substring(prefix.length());
        throw new IllegalArgumentException("Missing isolated setting");
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
