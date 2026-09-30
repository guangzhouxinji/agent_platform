package cn.xinji.agentplatform.enforcement.jobpack;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8JobPackStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8JobPackEvidenceStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8RealBusinessCollectionTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public class EnforcementJobPackConfiguration {
    @Bean JobPackStore jobPackStore(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json) {
        return new Dm8JobPackStore(db, new TransactionTemplate(manager), json, Clock.systemUTC());
    }
    @Bean JobPackEvidenceStore jobPackEvidenceStore(JdbcTemplate db) {
        return new Dm8JobPackEvidenceStore(db, Clock.systemUTC());
    }
    @Bean RealBusinessCollectionTaskStore realBusinessCollectionTaskStore(
            JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json) {
        return new Dm8RealBusinessCollectionTaskStore(db, new TransactionTemplate(manager), json, Clock.systemUTC());
    }
}
