package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanOpinionStore;
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
class V2HumanOpinionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.human-request-enabled", havingValue = "true")
    DevHumanOpinionStore devHumanOpinionStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 human opinion requires isolated task core and DB zone");
        }
        boolean installed = Boolean.TRUE.equals(new JdbcTemplate(dataSource)
                .execute((ConnectionCallback<Boolean>) connection -> {
                    try (var requests = connection.getMetaData().getTables(null, null, "CON2_HUMAN_REQUEST", null);
                         var accepted = connection.getMetaData().getTables(null, null, "CON2_HUMAN_ACCEPTANCE", null);
                         var opinions = connection.getMetaData().getTables(null, null, "CON2_HUMAN_OPINION", null)) {
                        return requests.next() && accepted.next() && opinions.next();
                    }
                }));
        if (properties.isHumanOpinionEnabled() && !installed) {
            throw new IllegalArgumentException("V028 human opinion migration required");
        }
        return installed ? new Dm8DevHumanOpinionStore(dataSource, manager, ZoneId.of(zone)) : null;
    }
}
