package cn.xinji.agentplatform.enforcement.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static cn.xinji.agentplatform.enforcement.handoff.HandoffDm8HttpTestSupport.createUser;
import static cn.xinji.agentplatform.enforcement.handoff.HandoffDm8HttpTestSupport.dropAuthority;
import static cn.xinji.agentplatform.enforcement.handoff.HandoffDm8HttpTestSupport.dropUser;
import static cn.xinji.agentplatform.enforcement.handoff.HandoffDm8HttpTestSupport.migrate;
import static cn.xinji.agentplatform.enforcement.handoff.HandoffDm8HttpTestSupport.second;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

class HandoffIndependentDm8HttpTest {
    private static final String ISSUER = "https://enf-handoff-dual.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    @TempDir Path root;

    @Test void independentHttpsDeploymentsCompeteForOneCurrentHumanClaim() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null
                && authorityPassword != null && !businessUrl.equals(authorityUrl),
                "Dedicated ENF business and authority DM8 required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EH" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        createUser(businessUrl, businessPassword, owner, secret);
        try {
            createUser(authorityUrl, authorityPassword, owner, secret);
            try {
                var business = new DriverManagerDataSource(businessUrl, owner, secret);
                var authority = new DriverManagerDataSource(authorityUrl, owner, secret);
                migrate(business, List.of("db/dm8/V032__product_identity.sql",
                        "db/dm8/V033__product_oauth2.sql", "db/dm8/V057__product_oauth_client_control.sql",
                        "db/dm8/V035__enforcement_v2_material.sql",
                        "db/dm8/V050__enforcement_v2_evidence_rules.sql",
                        "db/dm8/V051__enforcement_v2_evidence_publication.sql",
                        "db/dm8/V052__enforcement_v2_evidence_organizations.sql",
                        "db/dm8/V054__enforcement_v2_handoff.sql",
                        "db/dm8/V055__enforcement_v2_handoff_event_reason.sql"));
                migrate(authority, List.of("db/identity-authority/V001__grant_watermark.sql",
                        "db/identity-authority/V002__enforcement_source_watermark.sql",
                        "db/identity-authority/V005__enforcement_delete_watermark.sql"));
                var current = new JdbcTemplate(authority);
                current.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                        + "VALUES(?,0,'READY',NULL,?)", ISSUER,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                current.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,"
                        + "certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,?, 'ACTIVE',1)",
                        "enf-handoff-maintainer", ORG, "a".repeat(64), "SUBJECTS,GRANTS,KEYS");
                try {
                    HandoffProductTest.exercise(business, root);
                    exercise(businessUrl, authorityUrl, owner, secret);
                } finally {
                    migrate(business, List.of("db/dm8/U055__enforcement_v2_handoff_event_reason.sql",
                            "db/dm8/U054__enforcement_v2_handoff.sql",
                            "db/dm8/U052__enforcement_v2_evidence_organizations.sql",
                            "db/dm8/U051__enforcement_v2_evidence_publication.sql",
                            "db/dm8/U050__enforcement_v2_evidence_rules.sql",
                            "db/dm8/U035__enforcement_v2_material.sql",
                            "db/dm8/U057__product_oauth_client_control.sql",
                            "db/dm8/U033__product_oauth2.sql", "db/dm8/U032__product_identity.sql"));
                    dropAuthority(authorityUrl, owner, secret);
                }
            } finally { dropUser(authorityUrl, authorityPassword, owner); }
        } finally { dropUser(businessUrl, businessPassword, owner); }
    }

    private void exercise(String businessUrl, String authorityUrl, String owner, String secret)
            throws Exception {
        Path keyStore = root.resolve("server.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "handoff",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "enf-handoff-https-test",
                "-keypass", "enf-handoff-https-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        String[] settings = {
                "spring.config.name=enf-isolated-http", "spring.profiles.active=identity-http", "server.port=0",
                "server.ssl.key-store=" + keyStore.toUri(),
                "server.ssl.key-store-password=enf-handoff-https-test", "server.ssl.key-store-type=PKCS12",
                "spring.datasource.url=" + businessUrl, "spring.datasource.username=" + owner,
                "spring.datasource.password=" + secret, "spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver",
                "spring.sql.init.mode=never", "product.identity.enabled=true", "enforcement.v2.enabled=true",
                "product.identity.issuer=" + ISSUER,
                "product.identity.audience=https://enf-handoff-dual-api.example.org",
                "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "product.identity.authority.jdbc-url=" + authorityUrl,
                "product.identity.authority.schema=" + owner,
                "product.identity.authority.username=" + owner,
                "product.identity.authority.password=" + secret,
                "product.identity.token-verification-window=PT5M",
                "product.identity.activation-lifetime=PT30M", "product.identity.minimum-password-length=12",
                "enforcement.v2.file-root=" + root.resolve("originals"),
                "enforcement.v2.evidence.case-input-file=" + root.resolve("case.json"),
                "enforcement.v2.evidence.trial-input-root=data/enforcement/evidence-v2-acceptance-v1",
                "enforcement.v2.evidence.approved-basis=PROJECT_DOCUMENT_REVIEW|docs/57#6.24|1|PROJECT-CASE-001"
        };
        try (var first = new SpringApplicationBuilder(HandoffIndependentHttpNode.class)
                .profiles("identity-http").properties(settings).run()) {
            String firstBase = "https://localhost:" + ((WebServerApplicationContext) first).getWebServer().getPort();
            int secondPort;
            try (var port = new ServerSocket(0)) { secondPort = port.getLocalPort(); }
            var second = second(settings, secondPort, root.resolve("second-node.log"));
            try { verify(first, firstBase, "https://localhost:" + secondPort, second,
                    businessUrl, owner, secret); }
            finally { second.destroy(); if (!second.waitFor(10, TimeUnit.SECONDS)) second.destroyForcibly(); }
        }
    }

    private void verify(ConfigurableApplicationContext first, String firstBase, String secondBase,
                        Process second, String businessUrl, String owner, String secret) throws Exception {
        var http = client();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline && second.isAlive()) {
            try { if (request(http, "GET", secondBase + "/login", null, null, null)
                    .statusCode() == 200) { ready = true; break; } }
            catch (Exception notReady) { Thread.sleep(200); }
        }
        assertThat(ready).as("Second independent HANDOFF HTTPS JVM").isTrue();
        first.getBean(ProductSigningKeys.class).rotate("enf-handoff-maintainer");
        var subjects = first.getBean(ProductSubjects.class);
        var firstActor = subjects.register("named-handoff-dual-a", "dual-a-enrollment");
        subjects.activate(firstActor.subjectId(), firstActor.oneTimeActivation(), "strong-handoff-dual-a-1");
        var secondActor = subjects.register("named-handoff-dual-b", "dual-b-enrollment");
        subjects.activate(secondActor.subjectId(), secondActor.oneTimeActivation(), "strong-handoff-dual-b-1");
        first.getBean(RegisteredClientRepository.class).save(RegisteredClient.withId("enf-handoff-client")
                .clientId("enf-handoff-client").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost/callback").scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build());
        first.getBean(JdbcTemplate.class).update("INSERT INTO id2_client_current(client_id,registered_id,"
                + "redirect_uri,status,version,created_at) VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                "enf-handoff-client", "enf-handoff-client", "https://localhost/callback");
        String firstToken = login(firstBase, "named-handoff-dual-a", "strong-handoff-dual-a-1");
        String secondToken = login(secondBase, "named-handoff-dual-b", "strong-handoff-dual-b-1");
        var db = first.getBean(JdbcTemplate.class);
        var watermark = first.getBean(ProductGrantWatermark.class);
        var grants = first.getBean(ProductGrants.class);
        var current = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                first.getBean(JwtDecoder.class).decode(firstToken), ISSUER, Clock.systemUTC());
        current.prepareSource("HANDOFF-SOURCE", 1);
        current.activateSource("HANDOFF-SOURCE", 1);
        var caseInput = first.getBean(ObjectMapper.class).readTree(root.resolve("case.json").toFile());
        ProductGrants.Receipt firstClaimGrant = null;
        ProductGrants.Receipt secondClaimGrant = null;
        ProductGrants.Receipt firstPackageRead = null;
        ProductGrants.Receipt secondPackageRead = null;
        for (String actor : List.of(firstActor.subjectId(), secondActor.subjectId())) {
            grant(grants, watermark, actor, "EVIDENCE_RULE", "HANDOFF-RULE", 2,
                    "READ_RULE", "RULE_MANAGEMENT");
            grant(grants, watermark, actor, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE");
            for (var ref : caseInput.path("materials")) grant(grants, watermark, actor,
                    "EVIDENCE", ref.path("id").asText(), 3, "READ", "ANALYZE");
            grant(grants, watermark, actor, "EVIDENCE_ORGANIZATION", "HANDOFF-ORG", 2,
                    "READ", "DISPLAY");
            var packageRead = grant(grants, watermark, actor, "WORK_PACKAGE", "PACKAGE-001", 2,
                    "READ", "DISPLAY");
            if (actor.equals(firstActor.subjectId())) firstPackageRead = packageRead;
            else secondPackageRead = packageRead;
            grant(grants, watermark, actor, "HUMAN_REQUEST", "REQUEST-DUAL", 1,
                    "READ", "HUMAN_REVIEW");
            var claimGrant = grant(grants, watermark, actor, "HUMAN_TASK", "REQUEST-DUAL", 1,
                    "CLAIM", "HUMAN_REVIEW");
            if (actor.equals(firstActor.subjectId())) firstClaimGrant = claimGrant;
            else secondClaimGrant = claimGrant;
        }
        grant(grants, watermark, firstActor.subjectId(), "HUMAN_REQUEST", "REQUEST-DUAL", 1,
                "REQUEST", "HUMAN_REVIEW");
        var url = "/v2/enforcement";
        var input = "{\"id\":\"REQUEST-DUAL\",\"packageId\":\"PACKAGE-001\","
                + "\"packageVersion\":2,\"purposeRef\":\"REVIEW_EVIDENCE_GAPS\"}";
        assertThat(request(http, "POST", firstBase + url + "/work-packages/PACKAGE-001/versions/2/human-requests",
                firstToken, input, "dual-request").statusCode()).isEqualTo(200);
        assertThat(request(http, "GET", secondBase + url + "/human-requests/REQUEST-DUAL",
                secondToken, null, null).statusCode()).isEqualTo(200);
        int before = db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class);
        var claimReady = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var firstClaim = CompletableFuture.supplyAsync(() -> compete(firstBase, firstToken,
                "dual-claim-a", claimReady, start));
        var secondClaim = CompletableFuture.supplyAsync(() -> compete(secondBase, secondToken,
                "dual-claim-b", claimReady, start));
        assertThat(claimReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int firstStatus = firstClaim.get(15, TimeUnit.SECONDS);
        int secondStatus = secondClaim.get(15, TimeUnit.SECONDS);
        assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                .isEqualTo(before + 1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class))
                .isEqualTo(before + 1);
        String winner = firstStatus == 200 ? firstActor.subjectId() : secondActor.subjectId();
        String winningToken = firstStatus == 200 ? firstToken : secondToken;
        String winningBase = firstStatus == 200 ? firstBase : secondBase;
        String winningKey = firstStatus == 200 ? "dual-claim-a" : "dual-claim-b";
        assertThat(first.getBean(ObjectMapper.class).readTree(request(http, "GET",
                winningBase + url + "/human-requests/REQUEST-DUAL", winningToken, null, null).body())
                .path("claimant").asText()).isEqualTo(winner);
        var winningGrant = firstStatus == 200 ? firstClaimGrant : secondClaimGrant;
        var packageReadGrant = firstStatus == 200 ? firstPackageRead : secondPackageRead;
        recoveryCheckpoint(owner, secret, winningToken, winningKey, before + 1);
        watermark.change(() -> grants.revoke(winningGrant.grantId(), 1, "enf-handoff-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        watermark.change(() -> grants.revoke(packageReadGrant.grantId(), 1, "enf-handoff-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        String freshToken = firstStatus == 200
                ? login(winningBase, "named-handoff-dual-a", "strong-handoff-dual-a-1")
                : login(winningBase, "named-handoff-dual-b", "strong-handoff-dual-b-1");
        assertThat(request(http, "POST", winningBase + url + "/human-requests/REQUEST-DUAL/claims",
                freshToken, "{\"expectedVersion\":1}", winningKey).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class))
                .isEqualTo(before + 1);
        for (var ref : caseInput.path("materials")) {
            String fileKey = db.queryForObject("SELECT file_key FROM enf3_material WHERE material_id=?",
                    String.class, ref.path("id").asText());
            var bytes = Files.readAllBytes(root.resolve("originals").resolve(fileKey + ".blob"));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(ref.path("sha256").asText());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(3);
        System.out.printf("ENF_HANDOFF_DUAL_DM8_HTTP outcomes=%d/%d receipts=%d events=%d "
                + "revoked=403 evidence=2 originals=3%n", firstStatus, secondStatus, before + 1, before + 1);
        recoveryWithdrawn();
    }

    private void recoveryCheckpoint(String owner, String secret, String token, String key,
                                    int receipts) throws Exception {
        String gate = System.getenv("ENF_HANDOFF_RECOVERY_GATE");
        if (gate == null) return;
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        if (!Files.isDirectory(marker.getParent()) || Files.exists(marker))
            throw new IllegalStateException("Unused HANDOFF recovery marker required");
        var values = new Properties();
        values.setProperty("owner", owner);
        values.setProperty("secret", secret);
        values.setProperty("token", token);
        values.setProperty("key", key);
        values.setProperty("receipts", Integer.toString(receipts));
        values.setProperty("root", root.toAbsolutePath().toString());
        try (var output = Files.newOutputStream(marker)) { values.store(output, "Local sensitive HANDOFF checkpoint"); }
        await(marker.resolveSibling(marker.getFileName() + ".resume"));
    }

    private static void recoveryWithdrawn() throws Exception {
        String gate = System.getenv("ENF_HANDOFF_RECOVERY_GATE");
        if (gate == null) return;
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        Files.writeString(marker.resolveSibling(marker.getFileName() + ".withdrawn"), "withdrawn");
        await(marker.resolveSibling(marker.getFileName() + ".verified"));
    }

    private static void await(Path marker) throws Exception {
        Instant deadline = Instant.now().plus(java.time.Duration.ofMinutes(20));
        while (!Files.exists(marker) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        if (!Files.exists(marker)) throw new IllegalStateException("HANDOFF recovery gate timed out");
    }

    private static int compete(String base, String token, String key, CountDownLatch ready,
                               CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start unavailable");
            return request(client(), "POST", base + "/v2/enforcement/human-requests/REQUEST-DUAL/claims",
                    token, "{\"expectedVersion\":1}", key).statusCode();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static ProductGrants.Receipt grant(ProductGrants grants, ProductGrantWatermark watermark,
            String actor, String type, String id, long version, String action, String purpose) {
        var result = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, actor, ORG,
                "ENFORCEMENT", "ENF-V2-HANDOFF", "ENF-HANDOFF-V2-ACCEPTANCE-01", type, id,
                version, action, purpose), Instant.now().plusSeconds(3600), 0,
                "enf-handoff-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(result.status()).isEqualTo("ACTIVE");
        return result;
    }

    private static HttpClient client() throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return HttpClient.newBuilder().sslContext(tls).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String login(String base, String username, String password) throws Exception {
        var http = client();
        var login = request(http, "GET", base + "/login", null, null, null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        assertThat(request(http, "POST", base + "/login", null,
                "username=" + username + "&password=" + password + "&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8), null).statusCode())
                .isEqualTo(302);
        String verifier = "enf-handoff-dual-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8);
        var authorize = request(http, "GET", base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-handoff-client&redirect_uri=" + redirect
                + "&scope=openid&state=handoff&code_challenge=" + challenge
                + "&code_challenge_method=S256", null, null, null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers()
                .firstValue("Location").orElseThrow()).getRawQuery().split("code=")[1].split("&")[0],
                StandardCharsets.UTF_8);
        var token = request(http, "POST", base + "/oauth2/token", null,
                "grant_type=authorization_code&client_id=enf-handoff-client&redirect_uri=" + redirect
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier, null);
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        return new ObjectMapper().readTree(token.body()).path("access_token").asText();
    }

    private static HttpResponse<String> request(HttpClient http, String method, String url,
                                                String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", url.endsWith("/login") || url.endsWith("/oauth2/token")
                ? "application/x-www-form-urlencoded" : "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

}
