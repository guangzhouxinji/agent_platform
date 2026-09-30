package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8AdjudicationStore;
import cn.xinji.agentplatform.adjudication.adapter.out.memory.InMemoryAdjudicationStore;
import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class AdjudicationStorageConfiguration {
    @Bean
    @Profile({"test-synthetic", "public-local-test", "dm8-integration"})
    @ConditionalOnProperty(name = "adjudication.storage", havingValue = "memory", matchIfMissing = true)
    AdjudicationStore inMemoryAdjudicationStore() {
        return new InMemoryAdjudicationStore();
    }

    @Bean
    @ConditionalOnProperty(name = "adjudication.storage", havingValue = "dm8")
    AdjudicationStore dm8AdjudicationStore(DataSource dataSource) {
        return new Dm8AdjudicationStore(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }
}
