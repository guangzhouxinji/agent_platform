package cn.xinji.agentplatform.security.product;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

/** Engineering evidence: same physical store, distinct logical accounts, unchanged current checks. */
class ProductSingleInstanceAuthorityTest {
    @Test void actualSpringBindingUsesDedicatedAccountAndRejectsPendingAndHistoryDrift() throws Exception {
        String url="jdbc:h2:mem:single-authority-"+UUID.randomUUID();
        var admin=new JdbcDataSource(); admin.setURL(url+";DB_CLOSE_DELAY=-1"); admin.setUser("SA");
        try(var connection=admin.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA BUSINESS");
            connection.setSchema("BUSINESS");
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("db/dm8/V032__product_identity.sql"));
            connection.createStatement().execute("CREATE SCHEMA AUTHORITY"); connection.setSchema("AUTHORITY");
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("db/identity-authority/V001__grant_watermark.sql"));
            connection.createStatement().execute("INSERT INTO id2_authority_watermark VALUES('https://single-issuer',0,'READY',NULL,'"
                    +"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"+"')");
            connection.createStatement().execute("CREATE USER AUTH_SVC PASSWORD 'bounded-password'");
            connection.createStatement().execute("GRANT SELECT, UPDATE ON AUTHORITY.id2_authority_watermark TO AUTH_SVC");
            connection.createStatement().execute("GRANT SELECT ON AUTHORITY.id2_maintainer TO AUTH_SVC");
            connection.createStatement().execute("INSERT INTO AUTHORITY.id2_maintainer VALUES(1,'component-maintainer','component-org','"
                    +"a".repeat(64)+"','GRANTS','ACTIVE',1)");
        }
        var business=new JdbcDataSource();business.setURL(url+";SCHEMA=BUSINESS");business.setUser("SA");
        var pools=new java.util.ArrayList<HikariDataSource>();
        new ApplicationContextRunner().withUserConfiguration(ProductGrantWatermarkConfiguration.class)
                .withBean(JdbcTemplate.class,()->new JdbcTemplate(business))
                .withPropertyValues("product.identity.enabled=true","product.identity.issuer=https://single-issuer",
                        "spring.datasource.url="+url,"spring.datasource.username=SA","spring.datasource.hikari.schema=BUSINESS",
                        "product.identity.authority.topology=shared-instance","product.identity.authority.schema=AUTHORITY",
                        "product.identity.authority.username=AUTH_SVC","product.identity.authority.password=bounded-password")
                .run(context->{
                    assertThat(context).hasNotFailed();
                    var watermark=context.getBean(ProductGrantWatermark.class);
                    var pool=(HikariDataSource)watermark.independentDatabase().getDataSource();pools.add(pool);
                    assertThat(pool.getJdbcUrl()).isEqualTo(url);assertThat(pool.getUsername()).isEqualTo("AUTH_SVC");
                    assertThat(watermark.currentGeneration()).isZero();
                    assertThatThrownBy(()->watermark.independentDatabase().queryForObject("SELECT COUNT(*) FROM BUSINESS.id2_grant",Integer.class))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    var db=new JdbcTemplate(admin);
                    db.update("UPDATE AUTHORITY.id2_authority_watermark SET state='PENDING',intent_id='unfinished'");
                    assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
                    db.update("UPDATE AUTHORITY.id2_authority_watermark SET state='READY',intent_id=NULL,history_sha=?","0".repeat(64));
                    assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
                    db.update("UPDATE AUTHORITY.id2_authority_watermark SET history_sha=?","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                    assertThat(watermark.currentGeneration()).isZero();
                });
        assertThat(pools).hasSize(1);assertThat(pools.getFirst().isClosed()).isTrue();
        assertThat(new JdbcTemplate(business).queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isZero();
    }
    @Test void applicationRejectsMissingPrimaryBoundaryBeforeConnecting() {
        new ApplicationContextRunner().withUserConfiguration(ProductGrantWatermarkConfiguration.class)
                .withBean(JdbcTemplate.class,()->new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:invalid:never-connect")))
                .withPropertyValues("product.identity.enabled=true","spring.datasource.url=jdbc:invalid:never-connect",
                        "product.identity.authority.topology=shared-instance","product.identity.authority.schema=AUTHORITY",
                        "product.identity.authority.username=AUTH_SVC")
                .run(context->{ assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Invalid authority or primary schema"); });
    }
}