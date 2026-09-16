package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.dm8.Dm8PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.memory.InMemoryPatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.memory.InMemoryTrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.dm8.Dm8TrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkSearchTaskRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;

@Configuration
public class PatentSearchConfiguration {
    @Bean(destroyMethod = "close")
    Executor patentSearchExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }

    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(name = "patent-search.storage", havingValue = "memory", matchIfMissing = true)
    PatentSearchTaskRepository inMemoryPatentSearchTaskRepository() {
        return new InMemoryPatentSearchTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "patent-search.storage", havingValue = "dm8")
    PatentSearchTaskRepository dm8PatentSearchTaskRepository(DataSource dataSource) {
        return new Dm8PatentSearchTaskRepository(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }

    @Bean
    @ConditionalOnProperty(name = "trademark-search.storage", havingValue = "memory", matchIfMissing = true)
    TrademarkSearchTaskRepository inMemoryTrademarkSearchTaskRepository() {
        return new InMemoryTrademarkSearchTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "trademark-search.storage", havingValue = "dm8")
    TrademarkSearchTaskRepository dm8TrademarkSearchTaskRepository(DataSource dataSource) {
        return new Dm8TrademarkSearchTaskRepository(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }
}
