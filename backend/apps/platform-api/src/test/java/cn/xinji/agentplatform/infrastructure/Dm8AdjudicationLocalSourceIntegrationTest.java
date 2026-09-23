package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.AdjudicationLocalSourceImporter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8AdjudicationSourceAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Dm8AdjudicationLocalSourceIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private Dm8AdjudicationSourceAdapter sources;
    private DriverManagerDataSource dataSource;
    private ObjectMapper mapper;

    @BeforeAll void initialize() throws Exception {
        Assumptions.assumeTrue(URL != null && USER != null && PASSWORD != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) {
            runIgnoring(connection, "db/dm8/U010__adjudication_local_source_v1.sql");
            run(connection, "db/dm8/V010__adjudication_local_source_v1.sql");
        }
        dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        mapper = new ObjectMapper();
        new AdjudicationLocalSourceImporter(dataSource, mapper).importSyntheticBaseline();
        assertEquals(0, new AdjudicationLocalSourceImporter(dataSource, mapper).importSyntheticBaseline().casesImported());
        sources = new Dm8AdjudicationSourceAdapter(dataSource, mapper);
    }

    @AfterAll void cleanup() throws Exception {
        if (URL != null && USER != null && PASSWORD != null) try (Connection connection = connection()) {
            runIgnoring(connection, "db/dm8/U010__adjudication_local_source_v1.sql");
        }
    }

    @Test void importsAndReadsTheExactSyntheticLocalBaseline() throws Exception {
        assertEquals(20, sources.cases().size());
        assertEquals(80, sources.materialItems().size());
        assertEquals(1, sources.legalCandidates(null).size());
        assertEquals("V0-SYN-ADJ-TPL-001", sources.template().id());
        assertEquals("SYNTHETIC_LOCAL", sources.dataMode());
        assertEquals("v1", sources.findCase("V0-SYN-ADJ-CASE-001", "v1").orElseThrow().version());
        assertEquals("SOURCE_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> sources.materialData("V0-SYN-ADJ-MAT-003")).getMessage());
    }

    @Test void distinguishesIntegrityAndConnectionFailures() throws Exception {
        try {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement(
                    "UPDATE adj_local_case SET payload_json='{}' WHERE source_id='V0-SYN-ADJ-CASE-001'")) {
                update.executeUpdate();
                assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class,
                        () -> sources.findCase("V0-SYN-ADJ-CASE-001", "v1")).getMessage());
            }
        } finally {
            try (Connection connection = connection()) {
                run(connection, "db/dm8/U010__adjudication_local_source_v1.sql");
                run(connection, "db/dm8/V010__adjudication_local_source_v1.sql");
            }
            new AdjudicationLocalSourceImporter(dataSource, mapper).importSyntheticBaseline();
            sources = new Dm8AdjudicationSourceAdapter(dataSource, mapper);
        }
        Dm8AdjudicationSourceAdapter unavailable = new Dm8AdjudicationSourceAdapter(
                new DriverManagerDataSource("jdbc:invalid:adjudication-source"), mapper);
        assertEquals("UPSTREAM_FAILURE", assertThrows(IllegalStateException.class,
                () -> unavailable.findCase("V0-SYN-ADJ-CASE-001", "v1")).getMessage());
    }

    @Test void rejectsWithdrawnLocalMaterialWithoutErasingItsHistory() throws Exception {
        try {
            try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement(
                    "UPDATE adj_local_material SET availability='WITHDRAWN', withdrawal_reason='test' WHERE source_id='V0-SYN-ADJ-MAT-001'")) {
                update.executeUpdate();
                assertEquals("SOURCE_UNAVAILABLE", assertThrows(IllegalStateException.class,
                        () -> sources.materialData("V0-SYN-ADJ-MAT-001")).getMessage());
            }
        } finally {
            try (Connection connection = connection()) {
                run(connection, "db/dm8/U010__adjudication_local_source_v1.sql");
                run(connection, "db/dm8/V010__adjudication_local_source_v1.sql");
            }
            new AdjudicationLocalSourceImporter(dataSource, mapper).importSyntheticBaseline();
            sources = new Dm8AdjudicationSourceAdapter(dataSource, mapper);
        }
    }

    private static Connection connection() throws Exception { return DriverManager.getConnection(URL, USER, PASSWORD); }
    private static void run(Connection connection, String path) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(path)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void runIgnoring(Connection connection, String path) throws Exception { try { run(connection, path); } catch (Exception ignored) { } }
}
