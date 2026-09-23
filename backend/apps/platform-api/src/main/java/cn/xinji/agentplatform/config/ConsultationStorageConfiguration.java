package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.application.ConsultationStore;
import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationStore;
import cn.xinji.agentplatform.consultation.adapter.out.memory.InMemoryConsultationStore;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsultationStorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation.storage", havingValue = "memory", matchIfMissing = true)
    ConsultationStore inMemoryConsultationStore() { return new InMemoryConsultationStore(); }

    @Bean
    @ConditionalOnProperty(name = "consultation.storage", havingValue = "dm8")
    ConsultationStore dm8ConsultationStore(DataSource dataSource) {
        return new Dm8ConsultationStore(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }
}
