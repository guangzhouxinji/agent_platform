package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanContextStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanContextStore;
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
class V2HumanContextConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.human-context-enabled", havingValue = "true")
    DevHumanContextStore devHumanContextStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled()
                || !properties.isHumanRequestEnabled() || !properties.isHumanAcceptEnabled()
                || !properties.isHumanOpinionEnabled() || !properties.isHumanReturnEnabled()
                || zone.isBlank()) throw new IllegalArgumentException("V2 human context requires complete human tables");
        boolean installed = Boolean.TRUE.equals(new JdbcTemplate(dataSource)
                .execute((ConnectionCallback<Boolean>) connection -> {
                    for (String name : new String[] {"CON2_MESSAGE", "CON2_HUMAN_REQUEST_STATE",
                            "CON2_HUMAN_REQUEST", "CON2_HUMAN_ACCEPTANCE", "CON2_HUMAN_OPINION",
                            "CON2_HUMAN_RETURN"}) {
                        try (var tables = connection.getMetaData().getTables(null, null, name, null)) {
                            if (!tables.next()) return false;
                        }
                    }
                    return true;
                }));
        if (!installed) throw new IllegalArgumentException("V023/V026-V029 human context migrations required");
        return new Dm8DevHumanContextStore(dataSource, manager, ZoneId.of(zone));
    }
}
