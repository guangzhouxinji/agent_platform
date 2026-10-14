package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mechanical schema and corrupt-row counterexamples, not a physical restore or real model claim. */
class ProductModelTurnRetentionReferencesTest {
    @Test void legacySchemaDoesNotInventModelReferences() {
        var references = new HashMap<String, String>();
        ProductModelTurnRetentionReferences.collect(database(false), references);
        assertThat(references).isEmpty();
    }
    @Test void allRowsIncludingExpiredHistoryAreCollectedAndCaseEquivalentShaIsAccepted() {
        var db = database(true);
        db.update("INSERT INTO con3_model_turn VALUES(?,?,?)", "model-policy.json", "a".repeat(64), 0);
        db.update("INSERT INTO con3_model_turn VALUES(?,?,?)", "model-policy.json", "A".repeat(64), 1);
        var references = new HashMap<String, String>();
        ProductModelTurnRetentionReferences.collect(db, references);
        assertThat(references).containsExactlyEntriesOf(java.util.Map.of("model-policy.json", "a".repeat(64)));
    }
    @Test void conflictingExistingReferenceRejects() {
        var db = database(true);
        db.update("INSERT INTO con3_model_turn VALUES(?,?,?)", "model-policy.json", "b".repeat(64), 0);
        var references = new HashMap<>(java.util.Map.of("model-policy.json", "a".repeat(64)));
        assertThatThrownBy(() -> ProductModelTurnRetentionReferences.collect(db, references))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void nullEmptyAndMalformedReferencesReject() {
        for (String[] value : new String[][]{{null, "a".repeat(64)}, {" ", "a".repeat(64)},
                {"model-policy.json", null}, {"model-policy.json", "broken"}}) {
            var db = database(true);
            db.update("INSERT INTO con3_model_turn VALUES(?,?,?)", value[0], value[1], 0);
            assertThatThrownBy(() -> ProductModelTurnRetentionReferences.collect(db, new HashMap<>()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void presentButUnqueryableModelSchemaCannotBeTreatedAsLegacyEmpty() {
        var db = database(false);
        db.execute("CREATE TABLE con3_model_turn(wrong_column VARCHAR(20))");
        assertThatThrownBy(() -> ProductModelTurnRetentionReferences.collect(db, new HashMap<>()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private JdbcTemplate database(boolean model) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        if (model) db.execute("CREATE TABLE con3_model_turn(retention_ref VARCHAR(100),"
                + "retention_evidence_sha VARCHAR(64),expired_marker INTEGER)");
        return db;
    }
}
