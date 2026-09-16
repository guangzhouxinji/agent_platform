package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.application.ConsultationApplicationService;
import cn.xinji.agentplatform.consultation.adapter.out.synthetic.SyntheticPatentSearchAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8ConsultationApplicationIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");

    @Autowired ConsultationApplicationService service;
    @Autowired SyntheticPatentSearchAdapter patentSearch;

    @BeforeEach void resetSyntheticSources() { patentSearch.reset(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("consultation.storage", () -> "dm8");
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
    }

    @BeforeAll void initializeSchema() throws Exception {
        Assumptions.assumeTrue(URL != null && USER != null && PASSWORD != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(connection, "db/dm8/U001__consultation_mvp_v0.sql");
            run(connection, "db/dm8/V001__consultation_mvp_v0.sql");
        }
    }

    @AfterAll void rollbackSchema() throws Exception {
        if (URL == null || USER == null || PASSWORD == null) return;
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) { run(connection, "db/dm8/U001__consultation_mvp_v0.sql"); }
    }

    @Test void injectsDm8StoreAndPersistsApplicationResults() {
        var first = service.submit("v0-authorized-user", "水性粘结剂和双区烘干", "TECHNICAL_REFERENCE", "app-key");
        assertEquals("ANSWER_READY", first.type());
        patentSearch.markSourceUnavailable("V0-SYN-PAT-002");
        var refreshed = service.refresh("v0-authorized-user", first.consultationId());
        assertEquals("PARTIAL_RESULT", refreshed.type());
        assertEquals(1, refreshed.candidates().size());
    }

    @Test void persistsSupplementAndKeepsOriginalAnswer() {
        var first=service.submit("v0-authorized-user","帮我查一下涂布工艺相关专利。","","dm-supplement-initial");
        var completed=service.supplement("v0-authorized-user",first.consultationId(),"水性粘结剂、双区烘干；用于技术参考。","dm-supplement");
        assertEquals("ANSWER_READY",completed.type()); assertEquals(2,completed.answerVersion());
        assertEquals("NEEDS_CLARIFICATION",service.readAnswer("v0-authorized-user",first.consultationId(),first.answerId()).type());
        assertEquals(completed.answerId(),service.read("v0-authorized-user",first.consultationId()).answerId());
    }

    private static void run(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void runIgnoringMissing(Connection connection, String file) throws Exception { try { run(connection, file); } catch (Exception ignored) { } }
}
