package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8CaseLibraryAdapter;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8KnowledgeBaseJsonReader;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8PatentLibraryAdapter;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8TrademarkLibraryAdapter;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.LocalKnowledgeBaseImporter;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.publiclocal.PublicLocalPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.publiclocal.PublicLocalTrademarkCatalogAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalKnowledgeBaseDm8IntegrationTest {
    @Test
    void migratesImportsIdempotentlyAndServesAllPublicLocalDomains() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            executeRollback(connection, "db/dm8/U008__local_public_knowledge_base_v1.sql");
            executeScript(connection, "db/dm8/V008__local_public_knowledge_base_v1.sql");
        }

        var dataSource = new DriverManagerDataSource(url, user, password);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var importer = new LocalKnowledgeBaseImporter(dataSource, mapper);
        assertEquals(new LocalKnowledgeBaseImporter.ImportResult(474, 100, 100), importer.importAll());
        assertEquals(new LocalKnowledgeBaseImporter.ImportResult(0, 0, 0), importer.importAll());

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals(674, count(connection, "kb_raw_record"));
            assertEquals(674, count(connection, "kb_record_version"));
            assertEquals(17_972, count(connection, "kb_field_provenance"));
            assertEquals(0, count(connection, "kb_ingest_error"));
            assertEquals(100, scalar(connection, "SELECT COUNT(*) FROM kb_trademark WHERE application_number IS NOT NULL AND filing_date IS NOT NULL"));
            assertEquals(100, count(connection, "kb_case_document"));
            assertEquals(948, count(connection, "kb_patent_document"));
            assertTrue(scalar(connection, "SELECT COUNT(*) FROM kb_field_provenance WHERE value_state='SOURCE_NOT_PROVIDED'") > 0);
        }

        var reader = new Dm8KnowledgeBaseJsonReader(dataSource, mapper);
        var patents = new Dm8PatentLibraryAdapter(reader);
        var trademarks = new Dm8TrademarkLibraryAdapter(reader);
        var cases = new Dm8CaseLibraryAdapter(reader);
        assertEquals("PUBLIC_LOCAL", patents.dataMode());
        assertEquals("PUBLIC_LOCAL", trademarks.dataMode());
        assertEquals("PUBLIC_LOCAL", cases.dataMode());
        assertEquals(474, patents.records().size());
        assertEquals(100, trademarks.records().size());
        assertEquals(100, cases.records().size());

        var patentCatalog = new PublicLocalPatentCatalogAdapter(patents);
        var trademarkCatalog = new PublicLocalTrademarkCatalogAdapter(trademarks);
        assertEquals("PUBLIC_LOCAL", patentCatalog.descriptor().dataMode());
        assertEquals("PUBLIC_LOCAL", patentCatalog.dataMode());
        assertEquals("PUBLIC-LOCAL-LITERAL-CLAIM-COMPARE-V1", patentCatalog.ruleVersion());
        assertEquals("PUBLIC_LOCAL", trademarkCatalog.descriptor().dataMode());
        assertEquals("PUBLIC_LOCAL", trademarkCatalog.dataMode());
        assertEquals("PUBLIC-LOCAL-TRADEMARK-OBSERVATION-V1", trademarkCatalog.ruleVersion());

        var consultationSearch = new Dm8ConsultationPatentSearchAdapter(reader);
        var databaseResult = consultationSearch.search(patents.records().getFirst().title());
        assertEquals(PatentSearchPort.SearchResult.Type.SUCCESS, databaseResult.type());
        assertTrue(databaseResult.actualScope().contains("PUBLIC_LOCAL / DM8 KB_RECORD_VERSION / 474 件专利"));
        assertTrue(databaseResult.candidates().stream().allMatch(candidate ->
                consultationSearch.isSourceAvailable(candidate.sourceId(), candidate.version())));
    }

    private static int count(Connection connection, String table) throws Exception {
        return scalar(connection, "SELECT COUNT(*) FROM " + table);
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void executeScript(Connection connection, String path) throws Exception {
        String script = Files.readString(Path.of(path));
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    private static void executeRollback(Connection connection, String path) throws Exception {
        String script = Files.readString(Path.of(path));
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) {
                if (sql.isBlank()) continue;
                try { statement.execute(sql); } catch (java.sql.SQLException ignored) { }
            }
        }
    }
}
