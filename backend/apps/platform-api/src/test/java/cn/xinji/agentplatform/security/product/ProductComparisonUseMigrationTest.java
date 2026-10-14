package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual forward/reverse scripts on mechanical H2, not DM8 physical evidence. */
class ProductComparisonUseMigrationTest {
    @Test void emptyUpgradeRollbackAndReupgradePreserveTheConstraint() throws Exception {
        var source=ProductSourceScopeGrantTest.database(); var db=new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_source_field_use(use_code VARCHAR(16),CONSTRAINT ck_con4_field_use "
                + "CHECK(use_code IN ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE')))");
        db.update("INSERT INTO con4_source_field_use VALUES('QUERY')");
        ProductSourceScopeGrantTest.script(source,"db/dm8/V092__product_source_comparison_use.sql");
        assertThatThrownBy(()->db.update("INSERT INTO con4_source_field_use VALUES('UNKNOWN')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        ProductSourceScopeGrantTest.script(source,"db/dm8/U092__product_source_comparison_use.sql");
        assertThatThrownBy(()->db.update("INSERT INTO con4_source_field_use VALUES('COMPARISON')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        ProductSourceScopeGrantTest.script(source,"db/dm8/V092__product_source_comparison_use.sql");
        db.update("INSERT INTO con4_source_field_use VALUES('COMPARISON')");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_field_use",Integer.class)).isEqualTo(2);
    }
    @Test void rollbackWithComparisonRefusesBeforeDroppingTheActiveConstraint() throws Exception {
        var source=ProductSourceScopeGrantTest.database(); var db=new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_source_field_use(use_code VARCHAR(16),CONSTRAINT ck_con4_field_use "
                + "CHECK(use_code IN ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE')))");
        ProductSourceScopeGrantTest.script(source,"db/dm8/V092__product_source_comparison_use.sql");
        db.update("INSERT INTO con4_source_field_use VALUES('COMPARISON')");
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(source,"db/dm8/U092__product_source_comparison_use.sql"))
                .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(db.queryForObject("SELECT use_code FROM con4_source_field_use",String.class)).isEqualTo("COMPARISON");
        db.update("INSERT INTO con4_source_field_use VALUES('COMPARISON')");
        assertThatThrownBy(()->db.update("INSERT INTO con4_source_field_use VALUES('UNKNOWN')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
