package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevComparisonInputPreviewStore;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class DevComparisonInputPreviewConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.input-preview-enabled", havingValue = "true")
    DevComparisonInputPreviewStore devComparisonInputPreviewStore(DataSource dataSource,
            PlatformTransactionManager manager, DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || !properties.isTextResultEnabled()
                || !properties.isFeatureConfirmEnabled() || !properties.isComparisonReconfirmEnabled()
                || zone.isBlank()) throw new IllegalArgumentException("V2 input preview requires signed task dependencies");
        return new Dm8DevComparisonInputPreviewStore(dataSource, manager, ZoneId.of(zone));
    }
}
