package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementStore;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8EnforcementApplicationIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");

    @Autowired EnforcementApplicationService service;
    @Autowired EnforcementStore store;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("enforcement.storage", () -> "dm8");
        registry.add("consultation.storage", () -> "memory");
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
    }

    @BeforeAll
    void initializeSchema() throws Exception {
        Assumptions.assumeTrue(URL != null && USER != null && PASSWORD != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
            run(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
        }
    }

    @AfterAll
    void rollbackSchema() throws Exception {
        if (URL == null || USER == null || PASSWORD == null) return;
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
        }
    }

    @Test
    void selectsDm8RepositoryAndRestoresServiceSnapshotFromDatabase() {
        assertInstanceOf(Dm8EnforcementStore.class, store);
        var opened = service.openWork("v0-enforcement-user", "org-v0", "V0-SYN-CLUE-001", "v1", "spring-open");
        var processed = service.process("v0-enforcement-user", opened.workItem().workItemId(),
                List.of("V0-SYN-EVD-001", "V0-SYN-EVD-002"), "spring-process");
        assertEquals(processed, service.read("v0-enforcement-user", opened.workItem().workItemId()));
        assertEquals(processed, new Dm8EnforcementStore(() -> {
            try {
                return DriverManager.getConnection(URL, USER, PASSWORD);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }).findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot());
    }

    @Test
    void httpUsesDm8AndKeepsIdempotencyAndVersionErrorsStable() throws Exception {
        String created = http.perform(post("/api/v0/enforcement/work-items")
                        .header("X-V0-Actor", "v0-enforcement-user")
                        .header("X-V0-Organization", "org-v0")
                        .header("Idempotency-Key", "http-open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-001\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("WORK_ITEM_CREATED"))
                .andReturn().getResponse().getContentAsString();
        String workItemId = json.readTree(created).path("data").path("workItem").path("workItemId").asText();

        http.perform(post("/api/v0/enforcement/work-items/{id}/process", workItemId)
                        .header("X-V0-Actor", "v0-enforcement-user")
                        .header("Idempotency-Key", "http-process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceIds\":[\"V0-SYN-EVD-001\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("AUXILIARY_ANALYSIS_READY"))
                .andExpect(jsonPath("$.data.auxiliaryAnalyses[0].notOfficialDecision").value(true));
        http.perform(get("/api/v0/enforcement/work-items/{id}", workItemId)
                        .header("X-V0-Actor", "v0-enforcement-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.evidenceReferences[0].version").value(1));
        http.perform(post("/api/v0/enforcement/work-items")
                        .header("X-V0-Actor", "v0-enforcement-user")
                        .header("X-V0-Organization", "other-org")
                        .header("Idempotency-Key", "http-open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-001\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private static void run(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    private static void runIgnoringMissing(Connection connection, String file) throws Exception {
        try {
            run(connection, file);
        } catch (Exception ignored) {
        }
    }
}
