package cn.xinji.agentplatform.enforcement.evidence;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public class EnforcementEvidenceRuleConfiguration {
    @Bean EvidenceRuleStore evidenceRuleStore(JdbcTemplate db, PlatformTransactionManager manager) {
        return new Dm8EvidenceRuleStore(db, new TransactionTemplate(manager), Clock.systemUTC());
    }
}
