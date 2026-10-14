package cn.xinji.agentplatform.enforcement.documentdraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.nio.file.Path;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name="enforcement.v2.enabled",havingValue="true")
public class EnforcementDocumentDraftConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(cn.xinji.agentplatform.enforcement.conduct.ConductStore.class)
    cn.xinji.agentplatform.enforcement.conduct.ConductStore documentDraftConductReads(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json) {
        return new cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ConductStore(db,new TransactionTemplate(manager),json,Clock.systemUTC());
    }
    @Bean Dm8DocumentDraftStore documentDraftStore(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${enforcement.v2.document-draft.file-root:var/enforcement-document-draft}")String root) {
        return new Dm8DocumentDraftStore(db,new TransactionTemplate(manager),json,Clock.systemUTC(),Path.of(root));
    }
    @Bean DocumentProfileCatalog documentProfileCatalog(ObjectMapper json,
            @Value("${enforcement.v2.document-draft.profile-catalog:}")String file) {
        var reader=json.copy().findAndRegisterModules();
        return file.isBlank()?DocumentProfileCatalog.builtin(reader):new DocumentProfileCatalog(Path.of(file),reader);
    }
}
