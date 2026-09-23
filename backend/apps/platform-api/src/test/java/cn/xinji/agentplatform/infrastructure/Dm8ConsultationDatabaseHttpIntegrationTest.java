package cn.xinji.agentplatform.infrastructure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.consultation.application.ConsultationAnswerModelPort;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.LocalKnowledgeBaseImporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dm8-integration")
@Import(Dm8ConsultationDatabaseHttpIntegrationTest.ControlledModelConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8ConsultationDatabaseHttpIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("consultation.search.provider", () -> "database");
        registry.add("consultation.storage", () -> "memory");
        registry.add("patent-search.catalog", () -> "public-local");
        registry.add("knowledge-base.provider", () -> "dm8");
        registry.add("consultation-agent.runtime.enabled", () -> "true");
        registry.add("consultation-agent.runtime.model-chain", () -> "test:dm8-answer");
        registry.add("consultation-agent.models.deepseek-chat.model-id", () -> "test:dm8-answer");
        registry.add("consultation-agent.models.deepseek-chat.provider", () -> "test-provider");
        registry.add("consultation-agent.models.deepseek-chat.model-name", () -> "test-dm8-answer");
        registry.add("consultation-agent.models.deepseek-chat.display-name", () -> "Test DM8 Answer");
        registry.add("consultation-agent.models.deepseek-chat.endpoint", () -> "http://127.0.0.1:9/chat");
        registry.add("consultation-agent.models.deepseek-chat.api-key", () -> "test-secret");
        registry.add("consultation-agent.models.deepseek-chat.data-channel", () -> "PUBLIC_LOCAL");
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
    }

    @BeforeAll
    void prepareKnowledgeBase() throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(connection, "db/dm8/U008__local_public_knowledge_base_v1.sql");
            run(connection, "db/dm8/V008__local_public_knowledge_base_v1.sql");
        }
        new LocalKnowledgeBaseImporter(dataSource, mapper).importAll();
    }

    @Test
    void searchesDm8ThroughHttpAndRechecksTheExactSourceVersion() throws Exception {
        String response = mvc.perform(post("/api/v0/consultations")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "dm8-database-http-hit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"CPC Y02E 光伏 电池\",\"purpose\":\"TECHNICAL_REFERENCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeType").value("PARTIAL_RESULT"))
                .andExpect(jsonPath("$.candidates").isNotEmpty())
                .andExpect(jsonPath("$.candidates[0].sourceId").isNotEmpty())
                .andExpect(jsonPath("$.candidates[0].version").isNotEmpty())
                .andExpect(jsonPath("$.candidates[0].location").value(org.hamcrest.Matchers.containsString("DM8 PUBLIC_LOCAL")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("受控模型根据 DM8 候选组织答复")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("DM8 KB_RECORD_VERSION / 474 件专利")))
                .andExpect(jsonPath("$.limitation").value(org.hamcrest.Matchers.containsString("非中心权威全库")))
                .andReturn().getResponse().getContentAsString();

        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        String consultationId = body.path("consultationId").asText();
        String sourceLinkId = body.path("sourceLinks").path(0).path("sourceLinkId").asText();
        mvc.perform(get("/api/v0/consultations/{id}/source-links/{linkId}", consultationId, sourceLinkId)
                        .header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(body.path("candidates").path(0).path("sourceId").asText()))
                .andExpect(jsonPath("$.version").value(body.path("candidates").path(0).path("version").asText()));
    }

    @Test
    void returnsNoResultWithoutFallingBackToSyntheticCandidates() throws Exception {
        mvc.perform(post("/api/v0/consultations")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "dm8-database-http-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"不存在词项 ZZZ-NO-SYNTHETIC-FALLBACK-998877\",\"purpose\":\"TECHNICAL_REFERENCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeType").value("NO_RESULT"))
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.content").value("当前实际检索范围内未返回候选。"))
                .andExpect(jsonPath("$.limitation").value(org.hamcrest.Matchers.containsString("非中心权威全库")));
    }

    private static void run(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    private static void runIgnoringMissing(Connection connection, String file) {
        try { run(connection, file); } catch (Exception ignored) { }
    }

    @TestConfiguration
    static class ControlledModelConfiguration {
        @Bean @Primary ConsultationAnswerModelPort dm8AnswerModel() {
            return (modelId, question, candidates, traceId) -> new ConsultationAnswerModelPort.AnswerDraft(
                    "受控模型根据 DM8 候选组织答复。", List.of(candidates.getFirst().sourceId()));
        }
    }
}
