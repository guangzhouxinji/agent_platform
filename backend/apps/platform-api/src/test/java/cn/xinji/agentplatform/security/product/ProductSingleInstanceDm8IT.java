package cn.xinji.agentplatform.security.product;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit owned DM8 engineering fixture, never a production migration or business acceptance. */
class ProductSingleInstanceDm8IT {
    private static final String URL="jdbc:dm://127.0.0.1:5491";
    private static final String EMPTY="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final List<String> OWNERS=List.of("CONPROD","CONAUTH","ENFPROD","ENFAUTH","ADJPROD","ADJAUTH");
    private static String secret(String name) {
        String value=System.getenv("SINGLE_DB_ENG_"+name+"_PASSWORD");
        if(value==null || !value.matches("[A-Za-z0-9_]{32,48}")) throw new IllegalArgumentException("Isolated managed secret required");
        return value;
    }
    private static DataSource source(String username,String schema) {
        return new DriverManagerDataSource(URL,username,secret(username)) {
            @Override public Connection getConnection() throws SQLException {
                var c=super.getConnection();try {c.setSchema(schema);return c;}
                catch(SQLException failed){c.close();throw failed;}
            }
        };
    }
    @BeforeAll static void initializeOwnedEmptyInstanceOnly() throws Exception {
        if(!URL.equals(System.getenv("SINGLE_DB_ENG_URL"))) throw new IllegalArgumentException("Exact isolated URL required");
        if(!"VER-AGENT-SINGLE-DB-ISOLATED-01".equals(System.getenv("SINGLE_DB_ENG_OWNER")))
            throw new IllegalArgumentException("Isolated ownership confirmation required");
        try(var admin=DriverManager.getConnection(URL,"SYSDBA",secret("SYSDBA"))) {
            for(String owner:OWNERS) {
                try(var q=admin.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME IN (?,?)")) {
                    q.setString(1,owner);q.setString(2,owner+"_SVC");try(var r=q.executeQuery()){
                        r.next();assertThat(r.getInt(1)).as("Refuse existing or partially initialized accounts").isZero();
                    }
                }
            }
            for(String owner:OWNERS) {
                try(var sql=admin.createStatement()) {
                    sql.execute("CREATE USER "+owner+" IDENTIFIED BY \""+secret(owner)+"\"");
                    sql.execute("CREATE USER "+owner+"_SVC IDENTIFIED BY \""+secret(owner+"_SVC")+"\"");
                    sql.execute("GRANT RESOURCE TO "+owner);
                }
                String file=owner.endsWith("AUTH")?"db/identity-authority/V001__grant_watermark.sql":"db/dm8/V032__product_identity.sql";
                try(var connection=source(owner,owner).getConnection()) {
                    ScriptUtils.executeSqlScript(connection,new FileSystemResource(file));
                    var tables=Pattern.compile("(?im)^CREATE TABLE\\s+([a-z0-9_]+)").matcher(Files.readString(Path.of(file)));
                    while(tables.find()) {
                        String table=tables.group(1);
                        String rights=owner.endsWith("AUTH")?(table.equals("id2_maintainer")?"SELECT":
                                table.equals("id2_authority_watermark")?"SELECT,UPDATE":"SELECT,INSERT"):"SELECT,INSERT,UPDATE,DELETE";
                        connection.createStatement().execute("GRANT "+rights+" ON "+table+" TO "+owner+"_SVC");
                    }
                    if(owner.endsWith("AUTH")) new JdbcTemplate(source(owner,owner)).update(
                            "INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",issuer(owner),EMPTY);
                }
            }
        }
    }
    private static String issuer(String owner){return "https://single-engineering.test/"+owner.substring(0,3).toLowerCase(Locale.ROOT);}
    @Test void threeIssuersUseOneDm8ButDedicatedSchemasAccountsAndCurrentChecks() throws Exception {
        for(int index=0;index<OWNERS.size();index+=2) {
            String business=OWNERS.get(index),authority=OWNERS.get(index+1),iss=issuer(business);
            var primarySource=source(business+"_SVC",business);var primary=new JdbcTemplate(primarySource);
            var configuration=new ProductGrantWatermarkConfiguration();HikariDataSource pool;
            try {
                var watermark=configuration.productGrantWatermark(primary,URL,business,business+"_SVC",
                        "shared-instance","",authority,authority+"_SVC",secret(authority+"_SVC"),iss);
                pool=(HikariDataSource)watermark.independentDatabase().getDataSource();
                assertThat(watermark.currentGeneration()).isZero();
                try(var connection=pool.getConnection()) {assertThat(connection.getSchema()).isEqualTo(authority);}
                for(String foreign:OWNERS) if(!foreign.equals(business)) {
                    String table=foreign.endsWith("AUTH")?"id2_authority_watermark":"id2_subject";
                    assertThatThrownBy(()->primary.queryForObject("SELECT COUNT(*) FROM "+foreign+"."+table,Integer.class))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThatThrownBy(()->primary.update("UPDATE "+foreign+"."+table+" SET issuer=issuer WHERE 1=0"))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
                assertThatThrownBy(()->watermark.independentDatabase().queryForObject("SELECT COUNT(*) FROM "+business+".id2_subject",Integer.class))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->primary.execute("CREATE TABLE "+business+".forbidden_ddl(id INTEGER)"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                var owner=new JdbcTemplate(source(authority,authority));
                owner.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unfinished' WHERE issuer=?",iss);
                assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
                owner.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL,history_sha=? WHERE issuer=?","0".repeat(64),iss);
                assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
                owner.update("UPDATE id2_authority_watermark SET history_sha=? WHERE issuer=?",EMPTY,iss);
                assertThat(watermark.currentGeneration()).isZero();
                // Existing two-connection protocol executes even though both pools reach one instance.
                assertThat(watermark.change(()->"bounded engineering operation")).isEqualTo("bounded engineering operation");
                assertThat(watermark.currentGeneration()).isEqualTo(1);
                assertThatThrownBy(()->new ProductGrantWatermark(primary,watermark.independentDatabase(),"https://wrong-issuer.test"))
                        .isInstanceOf(IllegalStateException.class);
                configuration.destroy();assertThat(pool.isClosed()).isTrue();
                assertThat(primary.queryForObject("SELECT COUNT(*) FROM id2_subject",Integer.class)).isZero();
            } finally {configuration.destroy();}
        }
    }
}