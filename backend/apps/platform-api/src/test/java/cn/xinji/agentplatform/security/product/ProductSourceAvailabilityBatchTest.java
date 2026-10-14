package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Isolated SQL-predicate/fault C only; not a real source activation or product integration receipt. */
class ProductSourceAvailabilityBatchTest {
    private final Instant now = Instant.parse("2026-10-02T22:00:00Z");
    private final ProductSourceWatermark watermark = mock(ProductSourceWatermark.class);
    private JdbcTemplate db;
    private ProductSourceAvailability availability;
    @BeforeEach void sqlProjection() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:field-matrix-C-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_source_current(source_id VARCHAR,contract_version VARCHAR,decision_id VARCHAR,"
                + "source_epoch BIGINT,row_version BIGINT,status VARCHAR)");
        db.execute("CREATE TABLE con4_source_contract(source_id VARCHAR,contract_version VARCHAR,source_version VARCHAR,"
                + "status VARCHAR,row_version BIGINT,valid_until TIMESTAMP)");
        db.execute("CREATE TABLE con4_source_admission(decision_id VARCHAR,source_id VARCHAR,contract_version VARCHAR,valid_until TIMESTAMP)");
        db.execute("CREATE TABLE con4_source_product_decision(decision_id VARCHAR,rights_evidence_status VARCHAR)");
        db.execute("CREATE TABLE con4_source_verification(source_id VARCHAR,contract_version VARCHAR,source_version VARCHAR,"
                + "candidate_row_version BIGINT,manifest_ref VARCHAR,manifest_sha VARCHAR,records_sha VARCHAR)");
        // Deliberately no unique key here: inject duplicate rows to prove the reader defaults to denial.
        db.execute("CREATE TABLE con4_source_field_use(decision_id VARCHAR,field_code VARCHAR,use_code VARCHAR,valid_until TIMESTAMP)");
        var until = Timestamp.from(now.plusSeconds(60));
        db.update("INSERT INTO con4_source_current VALUES('source-C','contract-C','decision-C',7,9,'ACTIVE')");
        db.update("INSERT INTO con4_source_contract VALUES('source-C','contract-C','capture-C','ACTIVE',2,?)",until);
        db.update("INSERT INTO con4_source_admission VALUES('decision-C','source-C','contract-C',?)",until);
        db.update("INSERT INTO con4_source_product_decision VALUES('decision-C','UNRESOLVED')");
        db.update("INSERT INTO con4_source_verification VALUES('source-C','contract-C','capture-C',1,'mechanical-C',?,?)",
                "a".repeat(64),"b".repeat(64));
        for (String field : Set.of("TITLE","CLAIMS"))
            db.update("INSERT INTO con4_source_field_use VALUES('decision-C',?,'QUERY',?)",field,until);
        when(watermark.current("source-C")).thenReturn(new ProductSourceWatermark.Current(
                7,"ACTIVE",9,"contract-C","decision-C"));
        availability = new ProductSourceAvailability(db,watermark,Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void fullMatrixUsesOneBeforeAfterAuthorityAndKeepsSingletonCompatible() {
        var batch = availability.requireFields("source-C",Set.of("TITLE","CLAIMS"),"QUERY");
        assertThat(batch.sourceVersion()).isEqualTo("capture-C");
        verify(watermark).current("source-C"); verify(watermark).assertUnchanged("source-C",7);
        assertThat(availability.require("source-C","TITLE","QUERY")).isEqualTo(batch);
    }
    @Test void missingOrWrongUseFieldNeverAcceptsTheOtherField() {
        db.update("DELETE FROM con4_source_field_use WHERE field_code='CLAIMS'");
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE","CLAIMS"),"QUERY"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE"),"DISPLAY"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void duplicateTitleCannotMasqueradeAsTitleAndClaims() {
        db.update("DELETE FROM con4_source_field_use WHERE field_code='CLAIMS'");
        db.update("INSERT INTO con4_source_field_use SELECT * FROM con4_source_field_use");
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE","CLAIMS"),"QUERY"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void oneExpiredFieldOrWithdrawnRightsDeniesWholeMatrix() {
        db.update("UPDATE con4_source_field_use SET valid_until=? WHERE field_code='CLAIMS'",Timestamp.from(now));
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE","CLAIMS"),"QUERY"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_product_decision SET rights_evidence_status='WITHDRAWN'");
        assertThatThrownBy(() -> availability.require("source-C","TITLE","QUERY"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void rowDriftAndIndependentWatermarkFailureBothDeny() {
        db.update("UPDATE con4_source_current SET row_version=10");
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE","CLAIMS"),"QUERY"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_current SET row_version=9");
        doThrow(new IllegalStateException("Independent source changed")).when(watermark).assertUnchanged("source-C",7);
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE","CLAIMS"),"QUERY"))
                .hasMessage("Independent source changed");
    }
    @Test void emptyMatrixAndUnknownFieldOrUseAreRejected() {
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of(),"QUERY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("UNKNOWN"),"QUERY"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> availability.requireFields("source-C",Set.of("TITLE"),"UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void comparisonRequiresAnExplicitCurrentFieldDecisionNotQueryOrDisplay() {
        assertThat(ProductSourceAdmission.allowedUse("COMPARISON")).isTrue();
        assertThatThrownBy(() -> availability.require("source-C","CLAIMS","COMPARISON"))
                .isInstanceOf(IllegalStateException.class);
        db.update("INSERT INTO con4_source_field_use VALUES('decision-C','CLAIMS','DISPLAY',?)",Timestamp.from(now.plusSeconds(60)));
        assertThatThrownBy(() -> availability.require("source-C","CLAIMS","COMPARISON"))
                .isInstanceOf(IllegalStateException.class);
        db.update("INSERT INTO con4_source_field_use VALUES('decision-C','CLAIMS','COMPARISON',?)",Timestamp.from(now.plusSeconds(60)));
        assertThat(availability.require("source-C","CLAIMS","COMPARISON").epoch()).isEqualTo(7);
        assertThatThrownBy(() -> availability.require("source-C","TITLE","COMPARISON"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_field_use SET valid_until=? WHERE use_code='COMPARISON'",Timestamp.from(now));
        assertThatThrownBy(() -> availability.require("source-C","CLAIMS","COMPARISON"))
                .isInstanceOf(IllegalStateException.class);
    }
}
