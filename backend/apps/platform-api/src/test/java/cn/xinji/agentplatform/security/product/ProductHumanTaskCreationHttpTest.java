package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real controller, Spring security, product decoder and two H2 stores; unrelated Task operations are mocks. */
class ProductHumanTaskCreationHttpTest {
    private AnnotationConfigWebApplicationContext context;
    private ProductHumanTaskCreationFixture f;
    private MockMvc mvc;
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("native-human-create-test");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("native-create", Map.of(
                "product.identity.enabled", "true", "product.consultation.human-request.enabled", "true")));
        context.register(Node.class); context.refresh();
        f = context.getBean(ProductHumanTaskCreationFixture.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new ProductIdentityTransportFilter(f.base.watermark),
                context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void originalPostShapeCreatesScopeAndReturnsOnlyOriginalTaskFields() throws Exception {
        var first = perform("native-http", f.base.input, 200);
        assertThat(first.size()).isEqualTo(5);
        assertThat(first.has("humanReady")).isFalse();
        assertThat(first.path("organizationId").asText()).isEqualTo("org");
        assertThat(f.authorityState()).isEqualTo("READY");
        assertThat(perform("native-http", f.base.input, 200)).isEqualTo(first);
        assertThat(f.count("con6_human_cleanup_event")).isEqualTo(1);
    }

    @Test void scopeFailureIs503AndReplayReturnsCommittedTaskWithoutRepair() throws Exception {
        f.base.db.execute("ALTER TABLE con6_human_cleanup_event ADD CONSTRAINT fail_http CHECK(generation>1)");
        perform("partial-http", f.base.input, 503);
        assertThat(f.count("con3_task")).isEqualTo(1);
        assertThat(f.authorityState()).isEqualTo("PENDING");
        var replay = perform("partial-http", f.base.input, 200);
        assertThat(replay.size()).isEqualTo(5);
        assertThat(f.count("con6_human_cleanup_scope")).isZero();
        assertThat(f.authorityState()).isEqualTo("PENDING");
    }

    @ParameterizedTest @ValueSource(strings = {"anonymous", "bad-token", "plain-http"})
    void normalTransportAndAuthenticationRemainRequired(String fault) throws Exception {
        var request = post("/v2/consultation/tasks").secure(!fault.equals("plain-http"))
                .header("Idempotency-Key", "rejected").contentType("application/json")
                .content(f.mapper.writeValueAsBytes(f.base.input));
        if (!fault.equals("anonymous")) request.header("Authorization", "Bearer "
                + (fault.equals("bad-token") ? "invalid.token.signature" : f.original.token));
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(fault.equals("plain-http") ? 403 : 401);
        assertThat(f.count("con3_task")).isZero();
    }

    @Test void nullOrganizationKeepsOriginalBadRequestContractAndWritesNothing() throws Exception {
        perform("invalid-org", new ProductTaskCreationService.Input(null, f.base.expires, "approved", 1), 400);
        assertThat(f.count("con3_task")).isZero();
    }

    @Test void sessionRevokedAfterTaskCommitReturns503WithoutPretendingTaskRollback() throws Exception {
        f.beforeDecode = () -> {
            if (f.original.committed("con3_task") == 1 && f.changed.compareAndSet(false, true))
                f.base.db.update("UPDATE id2_session SET status='REVOKED'");
        };
        perform("late-session", f.base.input, 503);
        assertThat(f.changed).isTrue(); assertThat(f.count("con3_task")).isEqualTo(1);
        assertThat(f.count("con6_human_cleanup_scope")).isZero();
    }

    private com.fasterxml.jackson.databind.JsonNode perform(String key, ProductTaskCreationService.Input input, int status) throws Exception {
        var response = mvc.perform(post("/v2/consultation/tasks").secure(true)
                .header("Authorization", "Bearer " + f.original.token).header("Idempotency-Key", key)
                .contentType("application/json").content(f.mapper.writeValueAsBytes(input))).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isBlank() ? f.mapper.createObjectNode() : f.mapper.readTree(response.getContentAsString());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @Profile("native-human-create-test") @EnableWebMvc @EnableWebSecurity
    @Import({ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class})
    static class Node {
        @Bean ProductHumanTaskCreationFixture fixture() throws Exception { return new ProductHumanTaskCreationFixture(); }
        @Bean JwtDecoder decoder(ProductHumanTaskCreationFixture f) { return f.decoder; }
        @Bean ProductHumanTaskCreation humanCreation(ProductHumanTaskCreationFixture f) { return f.service(); }
        @Bean ProductConsultationTaskController controller(ProductHumanTaskCreationFixture f, ObjectProvider<ProductHumanTaskCreation> human) {
            return new ProductConsultationTaskController(f.base.directory, f.base.access, mock(ProductTaskGoalService.class),
                    mock(ProductTaskGoalReader.class), mock(ProductTaskCancelService.class), mock(ProductTaskSessionService.class),
                    f.base.service, human, true);
        }
    }
}
