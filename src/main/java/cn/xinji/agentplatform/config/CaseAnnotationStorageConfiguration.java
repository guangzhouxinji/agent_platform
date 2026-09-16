package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.dm8.Dm8CaseAnnotationWorkflowStore;
import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.memory.InMemoryCaseAnnotationWorkflowStore;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowStore;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CaseAnnotationStorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "case-annotation.storage", havingValue = "memory", matchIfMissing = true)
    CaseAnnotationWorkflowStore inMemoryCaseAnnotationWorkflowStore() { return new InMemoryCaseAnnotationWorkflowStore(); }

    @Bean
    @ConditionalOnProperty(name = "case-annotation.storage", havingValue = "dm8")
    CaseAnnotationWorkflowStore dm8CaseAnnotationWorkflowStore(DataSource dataSource, Clock clock) {
        Dm8CaseAnnotationWorkflowStore store = new Dm8CaseAnnotationWorkflowStore(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
        store.failInterruptedTasks(clock.instant());
        return store;
    }
}
