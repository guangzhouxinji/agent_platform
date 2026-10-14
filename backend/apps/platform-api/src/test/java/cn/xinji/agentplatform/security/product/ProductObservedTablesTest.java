package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Physical schema C only; it never supplies product identities, source rights or query results. */
class ProductObservedTablesTest {
    @Test void absentTablesAreRediscoveredAndDifferentDatabasesDoNotShareFacts() {
        var db = database();
        var known = new ProductObservedTables(db);
        assertThat(known.present("LATER_TABLE")).isFalse();
        assertThat(known.present("LATER_TABLE")).isFalse();
        db.execute("CREATE TABLE later_table(id INT PRIMARY KEY)");
        assertThat(known.present("LATER_TABLE")).isTrue();
        db.update("INSERT INTO later_table VALUES(1)");
        assertThat(known.present("LATER_TABLE")).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM later_table",Integer.class)).isEqualTo(1);
        assertThat(new ProductObservedTables(database()).present("LATER_TABLE")).isFalse();
        db.execute("DROP TABLE later_table");
        assertThat(known.present("LATER_TABLE")).isTrue();
        assertThatThrownBy(() -> db.queryForObject("SELECT COUNT(*) FROM later_table",Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> known.present("LATER_TABLE'"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void grantHistoryNeverDowngradesAfterObservedWorkloadSchemaDisappears() throws Exception {
        var primary=database(); var authority=database();
        script(primary,"db/dm8/V032__product_identity.sql");
        script(authority,"db/identity-authority/V001__grant_watermark.sql");
        String issuer="https://observed-schema-component.example";
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        var watermark=new ProductGrantWatermark(primary,authority,issuer);
        assertThat(watermark.currentGeneration()).isZero();
        script(primary,"db/dm8/V046__product_workload_subject.sql");
        assertThat(watermark.currentGeneration()).isZero();
        assertThat(watermark.currentGeneration()).isZero();
        primary.execute("DROP TABLE id2_workload_request");
        primary.execute("DROP TABLE id2_workload_event");
        primary.execute("DROP TABLE id2_workload");
        assertThatThrownBy(watermark::assertCurrent)
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static JdbcTemplate database() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:observed-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(source);
    }
    private static void script(JdbcTemplate db,String path) throws Exception {
        try(var connection=db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
        }
    }
}
