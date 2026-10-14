package cn.xinji.agentplatform.security.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.h2.jdbcx.JdbcDataSource;
import java.nio.file.Path;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@ActiveProfiles("identity-http")
@AutoConfigureMockMvc
@SpringBootTest(classes = ProductIssuerHttpTest.IsolatedIssuer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.identity.enabled=true",
        "product.identity.issuer=https://identity.example.org",
        "product.identity.audience=https://api.example.org",
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
        "product.source.registry.retention.ref=isolated-source-retention-input",
        "product.source.registry.retention.version=1",
        "product.source.registry.retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.source.registry.retention.max-age=PT2H",
        "product.source.engine.enabled=true",
        "product.source.evidence.max-bytes=10000000",
        "product.source.results.retention.ref=isolated-result-retention",
        "product.source.results.retention.version=1",
        "product.source.results.retention.evidence-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "product.source.results.retention.max-age=PT1H",
        "product.source.results.max-results=100",
        "product.source.results.cursor.hmac-key-base64=Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2M=",
        "spring.datasource.url=jdbc:h2:mem:product-issuer-http;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V036__product_source_candidates.sql,file:db/dm8/V088__product_source_capture_identity.sql,file:db/dm8/V037__product_source_admission.sql,file:db/dm8/V038__product_task_source_scope.sql,file:db/dm8/V039__product_source_verification.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql,file:db/dm8/V044__product_task_session_resume.sql,file:db/dm8/V045__product_task_execution_fence.sql,file:db/dm8/V048__product_task_execution_reconciliation.sql,file:db/dm8/V077__product_execution_attempt_history.sql,file:db/dm8/V072__product_search_metadata_filters.sql,file:db/dm8/V046__product_workload_subject.sql,file:db/dm8/V047__product_task_worker_delegation.sql,file:db/dm8/V053__product_source_use_decision.sql,file:db/dm8/V056__product_query_only_source_scope.sql,file:db/dm8/V061__product_worker_execution_request.sql,file:db/dm8/V040__product_source_results.sql,file:db/dm8/V063__product_search_execution_binding.sql,file:db/dm8/V064__product_search_ranking_evidence.sql,file:db/dm8/V065__product_search_stable_cursor.sql,file:db/dm8/V076__product_search_selection.sql,file:db/dm8/V078__product_search_attempt_history_binding.sql"
})
class ProductIssuerHttpTest {
    private static JdbcDataSource authoritySource;
    @DynamicPropertySource
    static void independentAuthority(DynamicPropertyRegistry properties) throws Exception {
        String url = "jdbc:h2:mem:product-issuer-authority;DB_CLOSE_DELAY=-1";
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        authoritySource = source;
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V003__consultation_source_watermark.sql")));
            connection.createStatement().executeUpdate("INSERT INTO id2_authority_watermark "
                    + "(issuer,generation,state,intent_id,history_sha) VALUES "
                    + "('https://identity.example.org',0,'READY',NULL,'"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')");
            connection.createStatement().executeUpdate("INSERT INTO id2_maintainer "
                    + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) VALUES "
                    + "(1,'isolated-maintainer-probe','isolated-organization','" + "a".repeat(64)
                    + "','SUBJECTS,GRANTS,KEYS','ACTIVE',1)");
        }
        properties.add("product.identity.authority.jdbc-url", () -> url);
        properties.add("product.identity.authority.schema", () -> "PUBLIC");
        properties.add("product.identity.authority.username", () -> "sa");
        properties.add("product.source.evidence.root",
                () -> Path.of("data/patents").toAbsolutePath().toString());
    }
    @Autowired TestRestTemplate http;
    @Autowired MockMvc mvc;
    @Test void workerLeaseRequiresClientCertificateBeforeAnyReceipt() throws Exception {
        mvc.perform(post("/v2/consultation/tasks/00000000-0000-4000-8000-000000000001/executions")
                .secure(true).header("Idempotency-Key", "worker-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputId\":\"input\",\"delegationId\":\"delegation\"}"))
                .andExpect(status().isForbidden());
    }
    @Autowired ProductSigningKeys keys;
    @Autowired ProductSubjects subjects;
    @Autowired RegisteredClientRepository clients;
    @Autowired JwtDecoder decoder;
    @Autowired ProductGrants grants;
    @Autowired ProductGrantWatermark watermark;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @TempDir Path certificateDirectory;
    @LocalServerPort int port;
    @Test void emptyRegistryCannotIssueOrExposePrivateSigningMaterial() throws Exception {
        String base = "http://127.0.0.1:" + port;
        assertThat(http.getForEntity(URI.create(base + "/oauth2/jwks"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var jwks = mvc.perform(get("/oauth2/jwks").secure(true)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jwks).contains("\"keys\":[]").doesNotContain("private", "d\":");
        String kid = keys.rotate("isolated-key-probe");
        String published = mvc.perform(get("/oauth2/jwks").secure(true)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(published).contains(kid, "\"kty\":\"RSA\"")
                .doesNotContain("\"d\":", "\"p\":", "\"q\":");
        var token = http.postForEntity(URI.create(base + "/oauth2/token"), null, String.class);
        assertThat(token.getStatusCode().is2xxSuccessful()).isFalse();
        mvc.perform(get("/v2/identity/me").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v2/consultation/tasks/00000000-0000-4000-8000-000000000001"
                + "/documents/real-collected/US_GRANT:3986239/versions/sha256-v1/content")
                .secure(true).param("kind", "CLAIMS").param("resultSetId", "result")
                .param("selectionId", "selection")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v2/identity/keys/rotate").secure(true)).andExpect(status().isForbidden());
        // Uses the actual issuer, repositories and routes, but isolated enrollment is only technical C.
        var enrolled = subjects.register("internal-issuer-check", "isolated-maintenance-probe");
        subjects.activate(enrolled.subjectId(), enrolled.oneTimeActivation(), "unique-strong-password-1");
        ProductIssuerClientScenario.register(db, clients);
        var loginPage = mvc.perform(get("/login").secure(true)).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) loginPage.getRequest().getSession(false);
        CsrfToken csrf = (CsrfToken) loginPage.getRequest().getAttribute(CsrfToken.class.getName());
        var loggedIn = mvc.perform(post("/login").secure(true).session(session)
                .param("username", "internal-issuer-check")
                .param("password", "unique-strong-password-1")
                .param(csrf.getParameterName(), csrf.getToken())).andReturn();
        assertThat(loggedIn.getResponse().getStatus()).isEqualTo(302);

        String verifier = "a-unique-pkce-verifier-with-enough-entropy-123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorized = mvc.perform(get("/oauth2/authorize").secure(true).session(session)
                .queryParam("response_type", "code").queryParam("client_id", "internal-client-check")
                .queryParam("redirect_uri", "https://client.example.org/callback")
                .queryParam("scope", "openid").queryParam("state", "isolated-state")
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
                .andReturn();
        assertThat(authorized.getResponse().getStatus()).isEqualTo(302);
        String location = authorized.getResponse().getHeader("Location");
        assertThat(location).startsWith("https://client.example.org/callback?code=");
        String code = java.net.URLDecoder.decode(location.substring(location.indexOf("code=") + 5)
                .split("&")[0], StandardCharsets.UTF_8);
        var issued = mvc.perform(post("/oauth2/token").secure(true)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code").param("client_id", "internal-client-check")
                .param("redirect_uri", "https://client.example.org/callback")
                .param("code", code).param("code_verifier", verifier)).andReturn();
        assertThat(issued.getResponse().getStatus()).isEqualTo(200);
        String accessToken = json.readTree(issued.getResponse().getContentAsString())
                .get("access_token").asText();
        assertThat(decoder.decode(accessToken).getSubject()).isEqualTo(enrolled.subjectId());
        mvc.perform(get("/v2/identity/me").secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        Path store = certificateDirectory.resolve("maintainer.p12");
        Path certificatePath = certificateDirectory.resolve("maintainer.cer");
        keytool("-genkeypair", "-alias", "maintainer", "-dname", "CN=Isolated Maintainer",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-ext", "EKU=clientAuth",
                "-keystore", store.toString(), "-storepass", "isolated-password-123",
                "-keypass", "isolated-password-123", "-noprompt");
        keytool("-exportcert", "-alias", "maintainer", "-keystore", store.toString(),
                "-storepass", "isolated-password-123", "-file", certificatePath.toString());
        X509Certificate peer;
        try (var input = java.nio.file.Files.newInputStream(certificatePath)) {
            peer = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(peer.getEncoded()));
        var authority = new org.springframework.jdbc.core.JdbcTemplate(authoritySource);
        authority.update("UPDATE id2_maintainer SET certificate_sha=? WHERE singleton_key=1", fingerprint);
        var sourceInput = new ProductSourceCandidates.Declaration("isolated-source", "v1", "observed-1",
                "recorded-origin", "recorded-owner", "evidence-ref", "b".repeat(64),
                java.util.Map.of("jurisdiction", java.util.List.of("CN")), java.util.List.of("title"),
                java.util.Map.of("title", java.util.List.of("QUERY")), java.util.List.of("title"),
                false, false, true, true, true, java.time.Instant.now().minusSeconds(60),
                java.time.Instant.now().plusSeconds(3600));
        mvc.perform(post("/v2/consultation/source-contracts/candidates").secure(true)
                .header("Idempotency-Key", "candidate-http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(sourceInput)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v2/consultation/source-contracts/candidates").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "candidate-http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(sourceInput)))
                .andExpect(status().isForbidden());
        authority.update("UPDATE id2_maintainer SET allowed_roles='SUBJECTS,GRANTS,KEYS,SOURCES' "
                + "WHERE singleton_key=1");
        mvc.perform(post("/v2/consultation/source-contracts/candidates").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "candidate-http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(sourceInput)))
                .andExpect(status().isOk());
        mvc.perform(get("/v2/consultation/source-contracts/candidates/isolated-source/v1").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer}))
                .andExpect(status().isOk());
        mvc.perform(post("/v2/consultation/source-contracts/candidates/isolated-source/v1/withdraw").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "withdraw-http")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v2/consultation/source-contracts/candidates/isolated-source/v1/verify")
                .secure(true).header("Idempotency-Key", "verify-without-certificate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"manifestRef\":\"public-pilot-v1/manifest.json\","
                        + "\"manifestSha\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isForbidden());
        var initialRequest = new ProductInitialTaskService.Input("isolated-organization",
                "Isolated organization", enrolled.subjectId(), "isolated-retention-input", 1,
                java.time.Instant.now().plusSeconds(3600));
        var created = mvc.perform(post("/v2/identity/organizations/initial-task").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "initial-task-http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(initialRequest)))
                .andExpect(status().isOk()).andReturn();
        var initial = json.readValue(created.getResponse().getContentAsByteArray(),
                ProductInitialTaskService.Result.class);
        String scopePath = "/v2/consultation/source-contracts/scopes/isolated-source/authorize";
        var sourceScopeRequest = new ProductSourceScopeController.Authorize(initial.taskId(),
                enrolled.subjectId(), initial.version(), 1, java.time.Instant.now().plusSeconds(900));
        mvc.perform(post(scopePath).secure(true).header("Idempotency-Key", "source-no-cert")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(sourceScopeRequest)))
                .andExpect(status().isForbidden());
        authority.update("UPDATE id2_maintainer SET allowed_roles='SOURCES' WHERE singleton_key=1");
        mvc.perform(post(scopePath).secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "source-missing-grants-role")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(sourceScopeRequest)))
                .andExpect(status().isForbidden());
        authority.update("UPDATE id2_maintainer SET allowed_roles='SUBJECTS,GRANTS,KEYS,SOURCES' "
                + "WHERE singleton_key=1");
        mvc.perform(post(scopePath).secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "source-no-admission")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(sourceScopeRequest)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/v2/identity/organizations/initial-task").secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer})
                .header("Idempotency-Key", "initial-task-http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(initialRequest)))
                .andExpect(status().isOk());
        mvc.perform(get("/v2/consultation/tasks/" + initial.taskId()).secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        String currentGoalPath = "/v2/consultation/tasks/" + initial.taskId() + "/goals/current";
        mvc.perform(get(currentGoalPath).secure(true).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        String goalPath = "/v2/consultation/tasks/" + initial.taskId() + "/goals/confirm";
        var goalInput = new ProductTaskGoalService.Input(1, "binder coating", "SEARCH", "binder coating",
                java.util.List.of("isolated-source"), java.util.List.of("TITLE"), java.util.List.of(),
                true, "isolated-goal-retention", 1);
        byte[] goalBody = json.writeValueAsBytes(goalInput);
        taskPost(goalPath, accessToken, "goal-http", goalBody)
                .andExpect(status().isNotFound());
        var writeScope = new ProductGrants.Scope("https://identity.example.org", enrolled.subjectId(),
                "isolated-organization", "CONSULTATION", initial.taskId(), initial.dataScope(),
                "TASK", initial.taskId(), 1, "WRITE", "TASK_WORK");
        var goalGrant = watermark.change(() -> grants.issue(writeScope,
                java.time.Instant.now().plusSeconds(900), 0, "isolated-maintainer-probe",
                "goal-write-http", "d".repeat(64)));
        var goalResponse = taskPost(goalPath, accessToken, "goal-http", goalBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskVersion").value(2)).andReturn();
        taskPost(goalPath, accessToken, "goal-http", goalBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalId").value(json.readTree(
                        goalResponse.getResponse().getContentAsByteArray()).get("goalId").asText()));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_goal_revision", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_search_input", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_current", Integer.class)).isZero();
        mvc.perform(get(currentGoalPath).secure(true).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskVersion").value(2))
                .andExpect(jsonPath("$.messageText").value("binder coating"))
                .andExpect(jsonPath("$.sourceIds[0]").value("isolated-source"));
        mvc.perform(post(goalPath).secure(true).header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", "goal-http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                        new ProductTaskGoalService.Input(1, "changed", "SEARCH", "binder coating",
                                java.util.List.of("isolated-source"), java.util.List.of("TITLE"),
                                java.util.List.of(), true, "isolated-goal-retention", 1))))
                .andExpect(status().isConflict());
        mvc.perform(get("/v2/consultation/tasks/" + initial.taskId()).secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"));
        String sessionPath = "/v2/consultation/tasks/" + initial.taskId() + "/sessions";
        byte[] sessionBody = json.writeValueAsBytes(new ProductConsultationTaskController.CancelRequest(2));
        db.update("UPDATE con3_search_input SET task_version=1 WHERE task_id=?", initial.taskId());
        taskPost(sessionPath, accessToken, "resume-stale-input", sessionBody).andExpect(status().isNotFound());
        db.update("UPDATE con3_search_input SET task_version=2 WHERE task_id=?", initial.taskId());
        var resumed = taskPost(sessionPath, accessToken, "resume-http", sessionBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskVersion").value(2)).andReturn();
        String sessionId = json.readTree(resumed.getResponse().getContentAsByteArray()).get("sessionId").asText();
        taskPost(sessionPath, accessToken, "resume-http", sessionBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(sessionId));
        taskPost(sessionPath, accessToken, "resume-next", sessionBody).andExpect(status().isOk());
        taskPost(sessionPath, accessToken, "resume-http", sessionBody).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_session WHERE task_id=? AND status='ACTIVE'",
                Integer.class, initial.taskId())).isEqualTo(1);
        String cancelPath = "/v2/consultation/tasks/" + initial.taskId() + "/cancel";
        byte[] cancelBody = json.writeValueAsBytes(new ProductConsultationTaskController.CancelRequest(2));
        taskPost(cancelPath, accessToken, "cancel-http", cancelBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskVersion").value(3))
                .andExpect(jsonPath("$.cancelEpoch").value(1));
        taskPost(cancelPath, accessToken, "cancel-http", cancelBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelEpoch").value(1));
        taskPost(cancelPath, accessToken, "cancel-other", cancelBody)
                .andExpect(status().isConflict());
        mvc.perform(get(currentGoalPath).secure(true).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        taskPost(sessionPath, accessToken, "resume-after-cancel", sessionBody).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_event WHERE operation='USER_CANCELLED'",
                Integer.class)).isEqualTo(1);
        watermark.change(() -> grants.revoke(goalGrant.grantId(), 1,
                "isolated-maintainer-probe", "goal-write-revoke", "e".repeat(64)));
        taskPost(goalPath, accessToken, "goal-http", goalBody)
                .andExpect(status().isNotFound());
        mvc.perform(get("/v2/consultation/tasks/00000000-0000-0000-0000-000000000000").secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isNotFound());
        mvc.perform(get("/v2/consultation/tasks/" + initial.taskId()).secure(true))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v2/identity/organizations/initial-task").secure(true)
                .header("Idempotency-Key", "no-certificate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ProductInitialTaskService.Input("isolated-organization",
                        "Isolated organization", enrolled.subjectId(), "retention-input", 1,
                        java.time.Instant.now().plusSeconds(3600)))))
                .andExpect(status().isForbidden());
        watermark.change(() -> grants.revoke(initial.grantId(), 1, "isolated-maintainer-probe",
                "revoke-http", "c".repeat(64)));
        mvc.perform(get("/v2/consultation/tasks/" + initial.taskId()).secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isNotFound());
        mvc.perform(get(currentGoalPath).secure(true).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v2/identity/logout").secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isOk());
        mvc.perform(get("/v2/identity/me").secure(true)
                .header("Authorization", "Bearer " + accessToken)).andExpect(status().isUnauthorized());
        authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unresolved' "
                + "WHERE issuer=?", "https://identity.example.org");
        mvc.perform(get("/v2/identity/me").secure(true)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/oauth2/jwks").secure(true)).andExpect(status().isServiceUnavailable());
        authority.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL "
                + "WHERE issuer=?", "https://identity.example.org");
    }

    private org.springframework.test.web.servlet.ResultActions taskPost(String path, String token,
            String requestId, byte[] body) throws Exception {
        return mvc.perform(post(path).secure(true).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", requestId).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private static void keytool(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "keytool";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Isolated certificate generation failed: " + output);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProductIssuerConfiguration.class, ProductConsultationTaskConfiguration.class,
            ProductSourceCandidateConfiguration.class, ProductSourceCandidateController.class,
            ProductSourceEngineConfiguration.class, ProductSourceScopeController.class,
            ProductSourceVerificationController.class,
            ProductWorkloadController.class, ProductWorkerDelegationController.class,
            ProductWorkerExecutionController.class,
            ProductIdentityRouteSecurityConfiguration.class,
            ProductGrantWatermarkConfiguration.class,
            ExistingRouteSecurityConfiguration.class,
            ProductIdentityController.class, ProductConsultationTaskController.class,
            ProductIdentityTransportFilter.class})
    static class IsolatedIssuer { }
}
