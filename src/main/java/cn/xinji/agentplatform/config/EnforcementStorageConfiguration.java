package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.memory.InMemoryEnforcementStore;
import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnforcementStorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "enforcement.storage", havingValue = "memory", matchIfMissing = true)
    EnforcementStore inMemoryEnforcementStore() {
        return new InMemoryEnforcementStore();
    }

    @Bean
    @ConditionalOnProperty(name = "enforcement.storage", havingValue = "dm8")
    EnforcementStore dm8EnforcementStore(DataSource dataSource) {
        return new Dm8EnforcementStore(() -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException exception) {
                throw new IllegalStateException("DM8 connection unavailable", exception);
            }
        });
    }
}
