package cn.xinji.agentplatform.enforcement.legalreference;

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
public class EnforcementLegalReferenceConfiguration {
    @Bean LegalReferenceStore legalReferenceStore(JdbcTemplate db, PlatformTransactionManager manager,
                                                   ObjectMapper json) {
        return new LegalReferenceJdbcStore(db, new TransactionTemplate(manager), json, Clock.systemUTC());
    }
    @Bean LegalKnowledgePort legalKnowledgePort(
            @Value("${enforcement.v2.legal-reference.catalog-file:}") String file, ObjectMapper json) {
        return new FileLegalKnowledgeSource(file.isBlank() ? null : Path.of(file), json);
    }
    @Bean LegalReferenceService.Target legalReferenceTarget(JdbcTemplate db, ObjectMapper json) {
        return new LegalReferenceTargetStore(db, json);
    }
}
