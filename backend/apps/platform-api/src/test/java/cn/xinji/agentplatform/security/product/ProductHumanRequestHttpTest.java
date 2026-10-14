package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real route/security/decoder/SQL and request application; test-signed JWT, H2 and controlled maintenance.
 * Unselected comparison dependency remains a mock. This is not production Bean, OAuth, TLS handshake or DM8 evidence. */
class ProductHumanRequestHttpTest {
    @TempDir Path root;
    private AnnotationConfigWebApplicationContext context;
    private ProductHumanRequestApplicationFixture fixture;
    private ProductHumanRequestApplication application;
    private MockMvc mvc;
    private String body;
    private static final String KEY = "http-original-request";

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("human-request-http-isolated");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-http", Map.of(
                "product.identity.enabled", "true", "product.consultation.human-request.enabled", "true",
                "test.human.evidence-root", root.toString())));
        context.register(SecurityNode.class);
        context.refresh();
        fixture = context.getBean(ProductHumanRequestApplicationFixture.class);
        application = context.getBean(ProductHumanRequestApplication.class);
        body = fixture.mapper.writeValueAsString(fixture.command);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new ProductIdentityTransportFilter(fixture.base.watermark),
                context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void postAndReadUseSameNativeClosureAndExposeOnlyRequestFacts() throws Exception {
        var created = perform(postRequest(body), 200);
        assertThat(created.path("kind").asText()).isEqualTo("REQUEST_RECORD");
        assertThat(created.path("body").path("taskVersion").asLong()).isEqualTo(7);
        assertThat(created.path("body").path("reason").asText()).isEqualTo(fixture.command.reason());
        assertThat(created.path("body").path("gaps").toString()).contains("NO_COMPARISON_SELECTED", "NO_REPORT_SELECTED");
        assertThat(created.path("receipt").size()).isEqualTo(8);
        assertThat(created.toString()).doesNotContain("requester", "scopeId", "historySha", "WAITING", "original user statement", fixture.base.token);
        assertThat(perform(postRequest(body), 200)).isEqualTo(created);
        assertThat(perform(readRequest(KEY), 200)).isEqualTo(created);
        rows(1);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void readOldKeyAfterNewTaskVersionUsesOriginalGoalCoordinates() throws Exception {
        var created = perform(postRequest(body), 200);
        fixture.base.db.update("UPDATE con3_task SET version=8 WHERE task_id=?", fixture.base.task.id());
        fixture.base.db.update("DELETE FROM con3_goal_current");
        assertThat(perform(readRequest(KEY), 200)).isEqualTo(created);
        assertThat(perform(postRequest(body), 200)).isEqualTo(created);
        rows(1);
    }

    @ParameterizedTest @ValueSource(strings = {"anonymous", "malformed", "tampered", "plainHttp"})
    void transportAndIdentityRejectionNeverWrite(String fault) throws Exception {
        var request = post(path()).secure(!fault.equals("plainHttp")).header("Idempotency-Key", KEY)
                .contentType("application/json").content(body);
        String token = fixture.base.token;
        if (fault.equals("malformed")) token = "not-a-jwt";
        if (fault.equals("tampered")) {
            String[] parts = token.split("\\.");
            parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
            token = String.join(".", parts);
        }
        if (!fault.equals("anonymous")) request.header("Authorization", "Bearer " + token);
        perform(request, fault.equals("plainHttp") ? 403 : 401);
        rows(0);
    }

    @Test void humanReadRouteCannotFallThroughWorkerOrAnonymousChain() throws Exception {
        perform(get(path()).secure(true).param("requestId", KEY), 401);
        var probe = get(path()).secure(true).buildRequest(context.getServletContext());
        var chain = context.getBean(FilterChainProxy.class).getFilterChains().stream().filter(c -> c.matches(probe)).findFirst().orElseThrow();
        var worker = post("/v2/consultation/tasks/" + fixture.base.task.id()
                + "/executions/attempt/patent-comparison-result").secure(true).buildRequest(context.getServletContext());
        assertThat(chain.matches(worker)).isFalse();
        rows(0);
    }

    @ParameterizedTest @ValueSource(strings = {"{", "{}", "unknown", "invalidPurpose", "nullMaterials"})
    void malformedCommandsAreBadRequestsWithNoWrites(String value) throws Exception {
        String candidate = switch (value) {
            case "unknown" -> body.substring(0, body.length() - 1) + ",\"unexpected\":true}";
            case "invalidPurpose" -> body.replace("ORDINARY_ASSISTANCE", "PROFESSIONAL_REVIEW");
            case "nullMaterials" -> body.replace("\"materials\":[]", "\"materials\":null");
            default -> value;
        };
        perform(postRequest(candidate), 400);
        rows(0);
    }

    @Test void missingKeyAndUnknownReadAreZeroWriteFailures() throws Exception {
        perform(post(path()).secure(true).header("Authorization", "Bearer " + fixture.base.token)
                .contentType("application/json").content(body), 400);
        assertThat(perform(readRequest("unknown-key"), 404).path("code").asText()).isEqualTo("HUMAN_REQUEST_NOT_FOUND");
        rows(0);
    }

    @ParameterizedTest @ValueSource(strings = {"reason-high", "reason-low", "source", "document", "version", "field", "comparison", "report"})
    void malformedUnicodeInRawJsonIsBadRequestBeforeApplicationOrWrites(String field) throws Exception {
        var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.mapper.readTree(body);
        var material = fixture.mapper.createObjectNode().put("sourceId", "source").put("documentId", "document").put("documentVersion", "v1");
        material.putArray("fields").add("TITLE");
        switch (field) {
            case "reason-high", "reason-low" -> candidate.put("reason", "BAD_UTF16");
            case "source" -> material.put("sourceId", "BAD_UTF16");
            case "document" -> material.put("documentId", "BAD_UTF16");
            case "version" -> material.put("documentVersion", "BAD_UTF16");
            case "field" -> material.putArray("fields").add("BAD_UTF16");
            case "comparison", "report" -> {
                var comparison = candidate.putObject("comparison").put("comparisonId", field.equals("comparison") ? "BAD_UTF16" : "comparison");
                comparison.putArray("reportIds").add(field.equals("report") ? "BAD_UTF16" : "report");
            }
            default -> throw new AssertionError(field);
        }
        if (List.of("source", "document", "version", "field").contains(field)) candidate.putArray("materials").add(material);
        // Keep the malformed code unit as a JSON escape on the wire; UTF-8 must not repair it first.
        String escaped = "a" + "\\" + (field.equals("reason-low") ? "uDC00" : "uD800") + "b";
        perform(postRequest(candidate.toString().replace("BAD_UTF16", escaped)), 400);
        rows(0);
        verifyNoInteractions(application, fixture.comparisons, fixture.files);
    }

    @ParameterizedTest @ValueSource(strings = {"emoji", "controls", "literal"})
    void validUnicodeAndControlTextSurviveHttpCommitAndOriginalKeyLookup(String kind) throws Exception {
        String reason = switch (kind) {
            case "emoji" -> "Ordinary assistance " + new String(Character.toChars(0x1F600));
            case "controls" -> "a" + (char) 0x0B + (char) 0x1F + "\b\t\n\f\r" + "b";
            default -> "a" + "\\" + "u001fb";
        };
        var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.mapper.readTree(body);
        candidate.put("reason", reason);
        var created = perform(postRequest(candidate.toString()), 200);
        assertThat(created.path("body").path("reason").asText()).isEqualTo(reason);
        assertThat(perform(readRequest(KEY), 200)).isEqualTo(created);
        rows(1);
    }

    @Test void sameKeyDifferentPayloadIsConflictAndRetainsOriginal() throws Exception {
        var original = perform(postRequest(body), 200);
        var altered = body.replace(fixture.command.reason(), "different requested assistance");
        assertThat(perform(postRequest(altered), 409).path("code").asText()).isEqualTo("HUMAN_REQUEST_KEY_CONFLICT");
        assertThat(perform(readRequest(KEY), 200)).isEqualTo(original);
        rows(1);
    }

    @Test void expiredBodyReturnsMinimalReceiptWithoutDecodingBadJson() throws Exception {
        var created = perform(postRequest(body), 200);
        fixture.base.clock.now = fixture.base.db.queryForObject("SELECT body_access_until FROM con6_human_package", java.sql.Timestamp.class).toInstant();
        fixture.base.db.update("UPDATE con6_human_package SET manifest_json='expired unreadable body'");
        var result = perform(readRequest(KEY), 200);
        assertThat(result.path("kind").asText()).isEqualTo("RECEIPT_ONLY");
        assertThat(result.path("body").isNull()).isTrue();
        assertThat(result.path("receipt")).isEqualTo(created.path("receipt"));
        assertThat(result.toString()).doesNotContain(fixture.command.reason(), "sourceId", "goal", "scopeId");
        rows(1);
    }

    @Test void missingIndependentCleanupScopeRejectsWithoutInitializing() throws Exception {
        fixture.base.authority.update("DELETE FROM id6_human_cleanup_scope");
        var result = perform(postRequest(body), 503);
        assertThat(result.toString()).isEqualTo("{\"code\":\"HUMAN_REQUEST_UNAVAILABLE\"}");
        assertThat(fixture.base.authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Integer.class)).isZero();
        rows(0);
    }

    @Test void permissionChangeAfterCommitSuppressesResponseButPreservesOriginalRequest() throws Exception {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            rows(1);
            fixture.base.watermark.change(() -> fixture.base.db.update(
                    "UPDATE id2_grant SET status='REVOKED',grant_version=grant_version+1 WHERE action_code='READ'"));
            return result;
        }).when(application).request(anyString(), anyString(), anyString(), any());
        var denied = perform(postRequest(body), 503);
        assertThat(denied.toString()).isEqualTo("{\"code\":\"HUMAN_REQUEST_UNAVAILABLE\"}");
        rows(1);
        assertThat(fixture.base.db.queryForObject("SELECT state FROM con6_human_handling", String.class)).isEqualTo("WAITING");
        perform(readRequest(KEY), 503);
        rows(1);
    }

    @Test void corruptedNativeRequestDigestIsUnavailableNotAClientInputError() throws Exception {
        perform(postRequest(body), 200);
        fixture.base.db.update("UPDATE con6_human_receipt SET request_sha='corrupt-native-digest'");
        var result = perform(readRequest(KEY), 503);
        assertThat(result.toString()).isEqualTo("{\"code\":\"HUMAN_REQUEST_UNAVAILABLE\"}");
        assertThat(result.toString()).doesNotContain("corrupt-native-digest", fixture.command.reason());
        rows(1);
    }

    @Test void invalidOriginalKeyIsBadRequestForBothMethods() throws Exception {
        perform(post(path()).secure(true).header("Authorization", "Bearer " + fixture.base.token)
                .header("Idempotency-Key", "invalid key").contentType("application/json").content(body), 400);
        perform(readRequest("invalid key"), 400);
        rows(0);
    }

    private String path() { return "/v2/consultation/tasks/" + fixture.base.task.id() + "/human-requests"; }
    private MockHttpServletRequestBuilder postRequest(String content) {
        return post(path()).secure(true).header("Authorization", "Bearer " + fixture.base.token)
                .header("Idempotency-Key", KEY).contentType("application/json").content(content);
    }
    private MockHttpServletRequestBuilder readRequest(String key) {
        return get(path()).secure(true).header("Authorization", "Bearer " + fixture.base.token).param("requestId", key);
    }
    private JsonNode perform(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        var text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(text).isEqualTo(expected);
        return text.isBlank() ? fixture.mapper.createObjectNode() : fixture.mapper.readTree(text);
    }
    private void rows(int expected) {
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(expected);
    }

    @org.springframework.boot.test.context.TestConfiguration
    @Profile("human-request-http-isolated") @EnableWebMvc @EnableWebSecurity
    @Import({ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class})
    static class SecurityNode {
        @Bean ProductHumanRequestApplicationFixture fixture(Environment env) throws Exception {
            var fixture = new ProductHumanRequestApplicationFixture();
            fixture.setup(Path.of(env.getRequiredProperty("test.human.evidence-root")), true);
            return fixture;
        }
        @Bean JwtDecoder jwtDecoder(ProductHumanRequestApplicationFixture fixture) { return fixture.base.decoder; }
        @Bean ProductHumanRequestApplication application(ProductHumanRequestApplicationFixture fixture) { return spy(fixture.application); }
        @Bean ProductHumanRequestController controller(ProductHumanRequestApplication application, ProductHumanRequestApplicationFixture fixture) {
            return new ProductHumanRequestController(application, new ProductComparisonJson(fixture.mapper));
        }
    }
}
