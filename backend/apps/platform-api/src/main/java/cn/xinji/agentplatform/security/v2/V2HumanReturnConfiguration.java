package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanReturnStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanReturnStore;
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
class V2HumanReturnConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.human-request-enabled", havingValue = "true")
    DevHumanReturnStore devHumanReturnStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 human return requires isolated task core and DB zone");
        }
        boolean installed = Boolean.TRUE.equals(new JdbcTemplate(dataSource)
                .execute((ConnectionCallback<Boolean>) connection -> {
                    try (var requests = connection.getMetaData().getTables(null, null, "CON2_HUMAN_REQUEST", null);
                         var accepted = connection.getMetaData().getTables(null, null, "CON2_HUMAN_ACCEPTANCE", null);
                         var opinions = connection.getMetaData().getTables(null, null, "CON2_HUMAN_OPINION", null);
                         var returns = connection.getMetaData().getTables(null, null, "CON2_HUMAN_RETURN", null)) {
                        return requests.next() && accepted.next() && opinions.next() && returns.next();
                    }
                }));
        if (properties.isHumanReturnEnabled() && (!properties.isHumanRequestEnabled()
                || !properties.isHumanAcceptEnabled() || !properties.isHumanOpinionEnabled() || !installed)) {
            throw new IllegalArgumentException("V029 human return and request/accept/opinion routes required");
        }
        return installed ? new Dm8DevHumanReturnStore(dataSource, manager, ZoneId.of(zone)) : null;
    }
}
