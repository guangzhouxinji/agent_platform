package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/** SQL three-valued-logic patch engineering; no business permission or model acceptance. */
class ProductComparisonStartSchemaTest {
    private JdbcTemplate db;
    private DriverManagerDataSource dataSource;
    @BeforeEach void setup() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:fence_" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(dataSource);
        db.execute("CREATE TABLE con5_start_command(command_id VARCHAR(36) PRIMARY KEY,attempt_id VARCHAR(36),"
                + "fencing_token BIGINT,status VARCHAR(24) NOT NULL,CONSTRAINT old_nullable_check CHECK("
                + "(attempt_id IS NULL AND fencing_token IS NULL) OR (attempt_id IS NOT NULL AND fencing_token>0)))");
    }
    private void patch() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("db/dm8"))) root = root.getParent();
        assertNotNull(root, "Actual repository V103 required");
        String sql = Files.readString(root.resolve("db/dm8/V103__comparison_start_fence_presence.sql"))
                .replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
    }
    @Test void oldUnknownCheckIsReproducedAndNewConstraintRejectsBothNullFenceStates() throws Exception {
        db.update("INSERT INTO con5_start_command VALUES('unbound',NULL,NULL,'ACCEPTED')");
        db.update("INSERT INTO con5_start_command VALUES('bound','attempt',1,'DISPATCHING')");
        assertEquals(1, db.update("UPDATE con5_start_command SET fencing_token=NULL WHERE command_id='bound'"));
        db.update("UPDATE con5_start_command SET fencing_token=1 WHERE command_id='bound'");
        var before = db.queryForList("SELECT * FROM con5_start_command ORDER BY command_id");
        patch();
        assertEquals(before, db.queryForList("SELECT * FROM con5_start_command ORDER BY command_id"));
        assertThrows(RuntimeException.class, () -> db.update("UPDATE con5_start_command SET fencing_token=NULL WHERE command_id='bound'"));
        assertThrows(RuntimeException.class, () -> db.update("UPDATE con5_start_command SET status='COMMITTED',fencing_token=NULL WHERE command_id='bound'"));
        for (long token : new long[]{0,-1}) assertThrows(RuntimeException.class,
                () -> db.update("UPDATE con5_start_command SET fencing_token=? WHERE command_id='bound'", token));
        assertEquals(before, db.queryForList("SELECT * FROM con5_start_command ORDER BY command_id"));
        assertEquals(1, db.update("UPDATE con5_start_command SET status='COMMITTED' WHERE command_id='bound'"));
        ProductComparisonStartSchema.verify(db, "PUBLIC");
    }
    @Test void invalidExistingHistoryRefusesPatchWithoutEditingOrDroppingOriginal() {
        db.update("INSERT INTO con5_start_command VALUES('unknown','attempt',NULL,'RECONCILE_REQUIRED')");
        var before = db.queryForList("SELECT * FROM con5_start_command");
        assertThrows(RuntimeException.class, this::patch);
        assertEquals(before, db.queryForList("SELECT * FROM con5_start_command"));
        assertThrows(IllegalStateException.class, () -> ProductComparisonStartSchema.verify(db, "PUBLIC"));
    }
    @Test void missingFenceConstraintRefusesExplicitStartBean() {
        var env = new MockEnvironment().withProperty("spring.datasource.hikari.schema", "PUBLIC");
        assertThrows(IllegalStateException.class, () -> new ProductComparisonStartConfiguration()
                .productComparisonStartCommands(db, new DataSourceTransactionManager(dataSource), env));
    }
    @Test void correctNameWithWeakClauseIsNotEnough() {
        db.execute("ALTER TABLE con5_start_command ADD CONSTRAINT ck_con5_start_fence_present CHECK(fencing_token IS NULL OR fencing_token>0)");
        assertThrows(IllegalStateException.class, () -> ProductComparisonStartSchema.verify(db, "PUBLIC"));
    }
    @Test void wrongOrMalformedSchemaCannotBorrowOtherSchemaConstraint() throws Exception {
        patch();
        assertThrows(IllegalStateException.class, () -> ProductComparisonStartSchema.verify(db, "OTHER"));
        assertThrows(IllegalStateException.class, () -> ProductComparisonStartSchema.verify(db, "PUBLIC;DROP"));
        assertThrows(IllegalStateException.class, () -> ProductComparisonStartSchema.verify(db, ""));
        ProductComparisonStartSchema.verify(db, "public");
    }
    @Test void actualDm8DictionaryNegationIsAcceptedButNotWeakerNullOrPositiveExpressions() {
        assertTrue(ProductComparisonStartSchema.requiredClause(" (ATTEMPT_ID IS NULL OR NOT (FENCING_TOKEN IS NULL))"));
        assertTrue(ProductComparisonStartSchema.requiredClause("ATTEMPT_ID IS NULL OR FENCING_TOKEN IS NOT NULL"));
        assertFalse(ProductComparisonStartSchema.requiredClause("ATTEMPT_ID IS NULL OR FENCING_TOKEN>0"));
        assertFalse(ProductComparisonStartSchema.requiredClause("ATTEMPT_ID IS NULL OR FENCING_TOKEN IS NULL"));
        assertFalse(ProductComparisonStartSchema.requiredClause("ATTEMPT_ID IS NULL OR NOT (FENCING_TOKEN IS NULL) OR 1=1"));
        assertFalse(ProductComparisonStartSchema.requiredClause(null));
    }
    @Test void startRequiresExplicitManagedPrimarySchema() throws Exception {
        patch();
        assertThrows(IllegalArgumentException.class, () -> new ProductComparisonStartConfiguration()
                .productComparisonStartCommands(db, new DataSourceTransactionManager(dataSource), new MockEnvironment()));
    }
}
