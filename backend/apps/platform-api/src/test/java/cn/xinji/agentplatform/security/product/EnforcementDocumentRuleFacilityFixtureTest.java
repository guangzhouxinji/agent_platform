package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;

/** Dedicated enforcement infrastructure initialization, not a product execution assertion. */
class EnforcementDocumentRuleFacilityFixtureTest {
    @Test void initializeApprovedAttemptMigrationWithoutChangingBusinessRows() throws Exception {
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));
        var ds=new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var db=new JdbcTemplate(ds);int count=db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('ENF10_DOCUMENT_RULE_ATTEMPT','ENF10_DOCUMENT_RULE_ATTEMPT_EVENT')",Integer.class);
        assertTrue(count==0||count==2,"Partial migration is an actual blocker");
        Path file=Path.of(required("ENF_DOCUMENT_RULE_MIGRATION_FILE"));assertTrue(Files.isRegularFile(file));
        if(count==0)try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(file));}
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('ENF10_DOCUMENT_RULE_ATTEMPT','ENF10_DOCUMENT_RULE_ATTEMPT_EVENT')",Integer.class));
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException(key+" required");return value;}
}
