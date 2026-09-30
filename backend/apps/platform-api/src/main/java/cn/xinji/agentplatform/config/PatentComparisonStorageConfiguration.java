package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.patentcomparison.adapter.out.dm8.Dm8PatentComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.patentcomparison.adapter.out.memory.InMemoryPatentComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonTaskRepository;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class PatentComparisonStorageConfiguration {
    @Bean
    @Profile({"test-synthetic", "public-local-test", "dm8-integration"})
    @ConditionalOnProperty(name = "patent-comparison.storage", havingValue = "memory", matchIfMissing = true)
    PatentComparisonTaskRepository inMemoryPatentComparisonTaskRepository() {
        return new InMemoryPatentComparisonTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "patent-comparison.storage", havingValue = "dm8")
    PatentComparisonTaskRepository dm8PatentComparisonTaskRepository(DataSource dataSource) {
        return new Dm8PatentComparisonTaskRepository(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }
}
