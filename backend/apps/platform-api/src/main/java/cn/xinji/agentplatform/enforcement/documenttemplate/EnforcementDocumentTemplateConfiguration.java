package cn.xinji.agentplatform.enforcement.documenttemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public class EnforcementDocumentTemplateConfiguration {
    @Bean DocumentTemplateStore documentTemplateStore(JdbcTemplate db, PlatformTransactionManager manager,
                                                       ObjectMapper json) {
        return new Dm8DocumentTemplateStore(db, new TransactionTemplate(manager), json, Clock.systemUTC());
    }
    @Bean DocumentTemplateFiles documentTemplateFiles(
            @Value("${enforcement.document-template.file-root:var/enforcement-document-templates}") String root) {
        return new LocalDocumentTemplateFiles(Path.of(root));
    }
}
