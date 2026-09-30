package cn.xinji.agentplatform.enforcement.procedure;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ProcedureStore;
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
public class EnforcementProcedureConfiguration {
    @Bean ProcedureStore procedureStore(JdbcTemplate db, PlatformTransactionManager manager,
                                        ObjectMapper json) {
        return new Dm8ProcedureStore(db, new TransactionTemplate(manager), json, Clock.systemUTC());
    }
}
