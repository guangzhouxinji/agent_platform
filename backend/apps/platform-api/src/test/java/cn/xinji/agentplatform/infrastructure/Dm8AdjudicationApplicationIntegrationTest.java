package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8AdjudicationStore;
import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8AdjudicationApplicationIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String DB_USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private static final String ACTOR = "v0-adjudication-user";
    private static final String REVIEWER = "v0-adjudication-reviewer";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired AdjudicationStore store;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("adjudication.storage", () -> "dm8");
        registry.add("enforcement.storage", () -> "memory");
        registry.add("consultation.storage", () -> "memory");
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
    }

    @BeforeAll
    void initializeSchema() throws Exception {
        Assumptions.assumeTrue(URL != null && DB_USER != null && PASSWORD != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) {
            runIgnoring(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
            run(connection, "db/dm8/V003__adjudication_mvp_v0.sql");
        }
    }

    @AfterAll
    void rollbackSchema() throws Exception {
        if (URL == null || DB_USER == null || PASSWORD == null) return;
        try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U003__adjudication_mvp_v0.sql"); }
    }

    @Test
    void httpRunsThroughServiceAndDm8ThenRecoversFromANewRepository() throws Exception {
        assertInstanceOf(Dm8AdjudicationStore.class, store);
        JsonNode opened = body(http.perform(post("/api/v0/adjudication/work-items")
                .header("X-V0-Actor", ACTOR).header("X-V0-Organization", "org-v0")
                .header("Idempotency-Key", "dm8-http-open").contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-001\"]}"))
                .andExpect(status().isOk()));
        String workId = opened.at("/data/work/workItemId").asText();
        http.perform(post("/api/v0/adjudication/work-items/{id}/material-inspections", workId)
                .header("X-V0-Actor", ACTOR).header("Idempotency-Key", "dm8-http-inspect")).andExpect(status().isOk());
        postWork(workId, "legal-candidate-sets", ACTOR, "dm8-http-match", "{\"inspectionVersion\":1}")
                .andExpect(status().isOk());
        postWork(workId, "citations/actions", REVIEWER, "dm8-http-cite",
                "{\"expectedVersion\":0,\"code\":\"V0-SYN-ADJ-LAW-001\",\"action\":\"SELECT\",\"reason\":\"test selection\"}")
                .andExpect(status().isOk());
        JsonNode drafted = body(postWork(workId, "drafts", ACTOR, "dm8-http-draft", "{\"inspectionVersion\":1}")
                .andExpect(status().isOk()));
        String draftId = drafted.at("/data/drafts/0/draftId").asText();
        postWork(workId, "drafts/actions", REVIEWER, "dm8-http-revise",
                "{\"expectedVersion\":1,\"replacementText\":\"DM8 合成修订\",\"reason\":\"test revision\"}")
                .andExpect(status().isOk());
        postWork(workId, "drafts/test-reviews", REVIEWER, "dm8-http-review",
                "{\"expectedDraftVersion\":2,\"action\":\"CONFIRM\",\"reason\":\"test review\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.limitations[1]").value("NOT_OFFICIAL_DECISION"));
        http.perform(get("/api/v0/adjudication/work-items/{id}/drafts/{draftId}/versions/1", workId, draftId)
                        .header("X-V0-Actor", ACTOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));

        Dm8AdjudicationStore restarted = new Dm8AdjudicationStore(() -> {
            try { return connection(); } catch (SQLException exception) { throw new IllegalStateException(exception); }
        });
        AdjudicationStore.StoredWork recovered = restarted.findByWorkItemId(workId).orElseThrow();
        assertEquals(7, recovered.persistenceVersion());
        assertEquals(2, recovered.snapshot().drafts().size());
        assertEquals("DM8 合成修订", recovered.snapshot().drafts().getLast().sections().getFirst().content());
        assertTrue(recovered.snapshot().reviews().getFirst().notOfficialDecision());
    }

    @Test
    void dm8HttpKeepsIdempotencyConflictStableWithoutOverwritingOriginal() throws Exception {
        http.perform(post("/api/v0/adjudication/work-items")
                        .header("X-V0-Actor", ACTOR).header("X-V0-Organization", "org-a")
                        .header("Idempotency-Key", "dm8-idem").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-001\"]}"))
                .andExpect(status().isOk());
        http.perform(post("/api/v0/adjudication/work-items")
                        .header("X-V0-Actor", ACTOR).header("X-V0-Organization", "org-b")
                        .header("Idempotency-Key", "dm8-idem").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-001\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private org.springframework.test.web.servlet.ResultActions postWork(
            String workId, String path, String actor, String key, String content) throws Exception {
        return http.perform(post("/api/v0/adjudication/work-items/" + workId + "/" + path)
                .header("X-V0-Actor", actor).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return json.readTree(action.andReturn().getResponse().getContentAsString());
    }

    private static Connection connection() throws SQLException { return DriverManager.getConnection(URL, DB_USER, PASSWORD); }
    private static void run(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void runIgnoring(Connection connection, String file) throws Exception {
        try { run(connection, file); } catch (SQLException ignored) { }
    }
}
