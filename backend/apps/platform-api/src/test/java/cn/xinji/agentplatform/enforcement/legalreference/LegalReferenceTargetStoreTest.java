package cn.xinji.agentplatform.enforcement.legalreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegalReferenceTargetStoreTest {
    private JdbcTemplate db;
    private LegalReferenceTargetStore targets;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:legal-target-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE enf7_conduct_report(analysis_id VARCHAR(120),revision BIGINT," +
                "organization_id VARCHAR(200),payload CLOB,PRIMARY KEY(analysis_id,revision))");
        db.execute("CREATE TABLE enf6_procedure_report(report_id VARCHAR(128),revision BIGINT," +
                "organization_id VARCHAR(128),payload CLOB,PRIMARY KEY(report_id,revision))");
        db.update("INSERT INTO enf7_conduct_report VALUES(?,?,?,?)", "conduct", 1, "org",
                "{\"findings\":[{\"basis\":\"conduct basis\"}]}");
        db.update("INSERT INTO enf6_procedure_report VALUES(?,?,?,?)", "procedure", 1, "org",
                "{\"items\":[{\"basis\":\"procedure basis\"}]}");
        targets = new LegalReferenceTargetStore(db, new ObjectMapper());
    }

    @Test void resolvesConductAndProcedureReportAnchors() {
        assertDoesNotThrow(() -> targets.require("actor", "org", "CONDUCT_REPORT", "conduct",
                1, "findings[0].basis", "LEGAL_REFERENCE"));
        assertDoesNotThrow(() -> targets.require("actor", "org", "PROCEDURE_REPORT", "procedure",
                1, "items[0].basis", "LEGAL_REFERENCE"));
    }

    @Test void rejectsUnsupportedTargetsAndCrossOrganizationReads() {
        assertThrows(SecurityException.class, () -> targets.require("actor", "org", "DOCUMENT_DRAFT",
                "draft", 1, "body", "LEGAL_REFERENCE"));
        assertThrows(SecurityException.class, () -> targets.require("actor", "other", "CONDUCT_REPORT",
                "conduct", 1, "findings[0].basis", "LEGAL_REFERENCE"));
    }
}
