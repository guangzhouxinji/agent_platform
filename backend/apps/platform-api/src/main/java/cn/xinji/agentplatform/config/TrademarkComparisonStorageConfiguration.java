package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.out.dm8.Dm8TrademarkComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.out.memory.InMemoryTrademarkComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.trademarkcomparison.application.TrademarkComparisonTaskRepository;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TrademarkComparisonStorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "trademark-comparison.storage", havingValue = "memory", matchIfMissing = true)
    TrademarkComparisonTaskRepository inMemoryTrademarkComparisonTaskRepository() {
        return new InMemoryTrademarkComparisonTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "trademark-comparison.storage", havingValue = "dm8")
    TrademarkComparisonTaskRepository dm8TrademarkComparisonTaskRepository(DataSource dataSource) {
        return new Dm8TrademarkComparisonTaskRepository(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }
}
