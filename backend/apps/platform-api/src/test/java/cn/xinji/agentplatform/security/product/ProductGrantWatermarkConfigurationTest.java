package cn.xinji.agentplatform.security.product;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

/** Connection lifecycle C; current authority checks must still run on every borrowed connection. */
class ProductGrantWatermarkConfigurationTest {
    @Test void pooledAuthorityKeepsSchemaCurrentChecksAndClosesOnlyItsOwnConnections() throws Exception {
        String primaryUrl="jdbc:h2:mem:primary-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        String authorityUrl="jdbc:h2:mem:authority-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var primary=new JdbcDataSource();primary.setURL(primaryUrl);primary.setUser("sa");
        var independent=new JdbcDataSource();independent.setURL(authorityUrl);independent.setUser("sa");
        try(var connection=primary.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try(var connection=independent.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA AUTHORITY");connection.setSchema("AUTHORITY");
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            connection.createStatement().execute("INSERT INTO id2_authority_watermark VALUES('https://issuer',0,'READY',NULL,'"
                    +"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"+"')");
        }
        var configuration=new ProductGrantWatermarkConfiguration();
        try {
            var watermark=configuration.productGrantWatermark(new JdbcTemplate(primary),primaryUrl,
                    authorityUrl,"authority","sa","","https://issuer");
            var pool=(HikariDataSource)watermark.independentDatabase().getDataSource();
            assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
            assertThat(pool.getMinimumIdle()).isEqualTo(1);
            assertThat(watermark.currentGeneration()).isZero();
            try(var connection=pool.getConnection()) { assertThat(connection.getSchema()).isEqualTo("AUTHORITY"); }
            watermark.independentDatabase().update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unfinished'");
            assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
            watermark.independentDatabase().update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL");
            assertThat(watermark.currentGeneration()).isZero();
            configuration.destroy();assertThat(pool.isClosed()).isTrue();
            assertThat(new JdbcTemplate(primary).queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isZero();
        } finally { configuration.destroy(); }
    }
    @Test void primaryReuseAndInvalidSchemaRemainRejectedBeforeConnecting() {
        var configuration=new ProductGrantWatermarkConfiguration();
        assertThatThrownBy(()->configuration.productGrantWatermark(null,"same","same","AUTHORITY","user","secret","issuer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->configuration.productGrantWatermark(null,"primary","independent","bad;schema","user","secret","issuer"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectedCurrentHistoryClosesEveryNewAuthoritySessionWithoutClosingPrimary() throws Exception {
        var primary=new JdbcDataSource();primary.setURL("jdbc:h2:mem:failed-primary-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");primary.setUser("sa");
        var independent=new JdbcDataSource();independent.setURL("jdbc:h2:mem:failed-authority-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");independent.setUser("sa");
        try(var connection=primary.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try(var connection=independent.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA AUTHORITY");connection.setSchema("AUTHORITY");
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            connection.createStatement().execute("INSERT INTO id2_authority_watermark VALUES('https://issuer',0,'READY',NULL,'"+"0".repeat(64)+"')");
        }
        var configuration=new ProductGrantWatermarkConfiguration();
        assertThatThrownBy(()->configuration.productGrantWatermark(new JdbcTemplate(primary),primary.getURL(),
                independent.getURL(),"AUTHORITY","sa","","https://issuer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authority datasource initialization failed");
        configuration.destroy();
        assertThat(new JdbcTemplate(independent).queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS",Integer.class))
                .isEqualTo(1); // Only this verification connection; the rejected pool has no surviving sessions.
        assertThat(new JdbcTemplate(primary).queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isZero();
    }
}
