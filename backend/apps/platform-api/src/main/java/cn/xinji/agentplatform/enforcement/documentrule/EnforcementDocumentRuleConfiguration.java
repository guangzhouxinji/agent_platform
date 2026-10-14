package cn.xinji.agentplatform.enforcement.documentrule;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name="enforcement.v2.enabled",havingValue="true")
public class EnforcementDocumentRuleConfiguration {
    @Bean DocumentRuleDependencyHeads documentRuleDependencyHeads(JdbcTemplate db,ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${enforcement.v2.document-rule.current-dependency-url:}")String url,
            @org.springframework.beans.factory.annotation.Value("${enforcement.v2.document-rule.current-dependency-user:}")String user,
            @org.springframework.beans.factory.annotation.Value("${enforcement.v2.document-rule.current-dependency-password:}")String password,
            @org.springframework.beans.factory.annotation.Value("${enforcement.v2.document-rule.current-catalog-file:}")String catalog) {
        JdbcTemplate current=db;
        if(!url.isBlank()){
            if(!url.startsWith("jdbc:dm://")||user.isBlank()||password.isBlank())throw new IllegalStateException("Current dependency DM8 configuration required");
            current=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,user,password));
        }
        return new DocumentRuleDependencyHeads(current,catalog.isBlank()?null:java.nio.file.Path.of(catalog),json);
    }
    @Bean Dm8DocumentRuleStore documentRuleStore(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,
            cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateFiles files) {
        return new Dm8DocumentRuleStore(db,new TransactionTemplate(manager),json,Clock.systemUTC(),files);
    }
}
