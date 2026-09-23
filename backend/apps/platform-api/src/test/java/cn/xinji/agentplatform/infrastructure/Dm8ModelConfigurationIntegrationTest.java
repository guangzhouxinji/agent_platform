package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService;
import cn.xinji.agentplatform.config.ConsultationAgentModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_PASSWORD", matches = ".+")
class Dm8ModelConfigurationIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ADMIN = "v0-authorized-user";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
        registry.add("model-configuration.master-key", () -> MASTER_KEY);
        registry.add("model-configuration.admin-requester", () -> ADMIN);
    }

    @Autowired MockMvc mvc;
    @Autowired ManagedModelConfigurationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsultationAgentModelProperties fallback;
    @Autowired ObjectMapper mapper;

    @BeforeAll void initialize() throws Exception {
        runIgnoringMissing("db/dm8/U017__consultation_model_configuration_v1.sql");
        run("db/dm8/V017__consultation_model_configuration_v1.sql");
    }

    @AfterAll void cleanup() throws Exception { runIgnoringMissing("db/dm8/U017__consultation_model_configuration_v1.sql"); }

    @Test void managesEncryptedModelsAuditsChangesAndHotReadsOrder() throws Exception {
        mvc.perform(get("/api/v0/model-configurations").header("X-V0-Requester", "other-user"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v0/model-configurations/apply-consultation-agent-draft")
                        .header("X-V0-Requester", "other-user"))
                .andExpect(status().isForbidden());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/chat", exchange -> {
                byte[] response = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var body = exchange.getResponseBody()) { body.write(response); }
            });
            server.start();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
            save("first", "First model", endpoint, "first-secret", true, 1, "create first");
            save("second", "Second model", endpoint, "second-secret", true, 2, "create second");

            mvc.perform(get("/api/v0/model-configurations").header("X-V0-Requester", ADMIN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].modelId").value("first"))
                    .andExpect(jsonPath("$[0].credentialConfigured").value(true))
                    .andExpect(jsonPath("$[0].apiKey").doesNotExist());

            save("first", "First model updated", endpoint, "", false, 1, "disable first");
            save("first", "First model updated", endpoint, "", true, 1, "enable first");
            mvc.perform(put("/api/v0/model-configurations/order").header("X-V0-Requester", ADMIN)
                            .contentType("application/json")
                            .content("{\"modelIds\":[\"second\",\"first\"],\"changeNote\":\"swap order\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].modelId").value("second"));
            assertThat(service.runtimeModelChain(java.util.List.of("fallback"))).containsExactly("second", "first");

            mvc.perform(put("/api/v0/consultation-agent-draft").header("X-V0-Requester", ADMIN)
                            .contentType("application/json")
                            .content("""
                                    {"agentId":"CONSULTATION-AGENT-001","name":"知识产权咨询助手","description":"开发草稿",
                                     "scenarioIds":["PATENT"],"modelChain":["first"],"switchOn":["TIMEOUT"],
                                     "toolIds":["PATENT_INFORMATION"],"expectedVersion":1,"changeNote":"应用模型排序"}
                                    """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
            mvc.perform(post("/api/v0/model-configurations/apply-consultation-agent-draft").header("X-V0-Requester", ADMIN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].modelId").value("first"))
                    .andExpect(jsonPath("$[1].modelId").value("second"));
            assertThat(service.runtimeModelChain(java.util.List.of("fallback"))).containsExactly("first", "second");
            assertThat(service.endpoint("first").apiKey()).isEqualTo("first-secret");
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("jdbc", jdbc);
            String wrongKey = Base64.getEncoder().encodeToString(new byte[] {
                    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 });
            ManagedModelConfigurationService wrongKeyService = new ManagedModelConfigurationService(
                    beans.getBeanProvider(JdbcTemplate.class), fallback, mapper, wrongKey, ADMIN);
            assertThat(wrongKeyService.list()).hasSize(2);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrongKeyService.endpoint("first"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(503);

            mvc.perform(post("/api/v0/model-configurations/first/test").header("X-V0-Requester", ADMIN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.lastTestStatus").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.lastTestErrorCode").doesNotExist());
        } finally {
            server.stop(0);
        }

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD); Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT encrypted_api_key FROM con_model_configuration WHERE model_id='first'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).doesNotContain("first-secret");
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM con_model_configuration_audit")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isGreaterThanOrEqualTo(7);
            }
        }
    }

    private void save(String id, String name, String endpoint, String apiKey, boolean enabled, int priority, String note) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "displayName", name, "provider", "test", "modelName", "test-model", "endpoint", endpoint,
                "apiKey", apiKey, "enabled", enabled, "priority", priority, "changeNote", note));
        mvc.perform(put("/api/v0/model-configurations/{id}", id).header("X-V0-Requester", ADMIN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    private static void run(String path) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD); Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(path)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void runIgnoringMissing(String path) throws Exception { try { run(path); } catch (Exception ignored) { } }
}
