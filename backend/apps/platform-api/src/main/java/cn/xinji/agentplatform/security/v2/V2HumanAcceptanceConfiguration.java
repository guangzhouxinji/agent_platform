package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanAcceptanceStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanAcceptanceStore;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class V2HumanAcceptanceConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.human-accept-enabled", havingValue = "true")
    DevHumanAcceptanceStore devHumanAcceptanceStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || !properties.isHumanRequestEnabled()
                || zone.isBlank()) throw new IllegalArgumentException("V2 human acceptance requires requests and DB zone");
        boolean installed = Boolean.TRUE.equals(new JdbcTemplate(dataSource)
                .execute((ConnectionCallback<Boolean>) connection -> {
                    try (var requests = connection.getMetaData().getTables(null, null, "CON2_HUMAN_REQUEST", null);
                         var accepted = connection.getMetaData().getTables(null, null, "CON2_HUMAN_ACCEPTANCE", null)) {
                        return requests.next() && accepted.next();
                    }
                }));
        if (!installed) throw new IllegalArgumentException("V026/V027 human request migrations required");
        return new Dm8DevHumanAcceptanceStore(dataSource, manager, ZoneId.of(zone));
    }
}
