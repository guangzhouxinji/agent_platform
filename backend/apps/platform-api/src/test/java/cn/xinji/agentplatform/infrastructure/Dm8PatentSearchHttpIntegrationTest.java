package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
class Dm8PatentSearchHttpIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private static final String REQUESTER = "v0-authorized-user";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        initializeSchema();
        registry.add("consultation.storage", () -> "memory");
        registry.add("enforcement.storage", () -> "memory");
        registry.add("patent-search.storage", () -> "dm8");
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
    }

    @AfterAll void rollbackSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            run(connection, "db/dm8/U001__consultation_mvp_v0.sql");
        }
    }

    @Test void routesHttpThroughServiceAndDm8WithIdempotentReplayAndHistory() throws Exception {
        String body = """
                {"inputMode":"CORE_FEATURES","input":"水性粘结剂、双区烘干","purpose":"TECHNICAL_REFERENCE",
                 "confirmedFeatures":["水性粘结剂","双区烘干"],"scope":{"patentTypes":["发明"],"regions":["CN"]}}
                """;
        JsonNode created = create("dm8-http-key", body);
        JsonNode replay = create("dm8-http-key", body);
        assertEquals(created.get("taskId").asText(), replay.get("taskId").asText());

        JsonNode completed = awaitTerminal(created.get("taskId").asText());
        assertEquals("SUCCEEDED", completed.get("status").asText());
        assertEquals("SYNTHETIC", completed.get("dataMode").asText());
        mvc.perform(get("/api/v0/patent-search-tasks").header("X-V0-Requester", REQUESTER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].taskId").value(created.get("taskId").asText()))
                .andExpect(jsonPath("$[0].candidates.length()").value(completed.get("candidates").size()));
    }

    private JsonNode create(String key, String body) throws Exception {
        String response = mvc.perform(post("/api/v0/patent-search-tasks")
                        .header("X-V0-Requester", REQUESTER).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private JsonNode awaitTerminal(String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonNode body;
        do {
            String response = mvc.perform(get("/api/v0/patent-search-tasks/{id}", taskId).header("X-V0-Requester", REQUESTER))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            body = mapper.readTree(response);
            if (Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(body.get("status").asText())) return body;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not reach terminal state: " + body);
    }

    private static void initializeSchema() {
        try {
            Class.forName("dm.jdbc.driver.DmDriver");
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                runIgnoring(connection, "db/dm8/U001__consultation_mvp_v0.sql");
                run(connection, "db/dm8/V001__consultation_mvp_v0.sql");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot initialize DM8 patent-search schema", exception);
        }
    }

    private static void run(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void runIgnoring(Connection connection, String file) throws Exception { try { run(connection, file); } catch (Exception ignored) { } }
}
