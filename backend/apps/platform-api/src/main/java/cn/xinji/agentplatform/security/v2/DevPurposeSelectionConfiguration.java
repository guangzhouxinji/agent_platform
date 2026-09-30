package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevPurposeSelectionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevPurposeSelectionStore;
import java.time.ZoneId;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class DevPurposeSelectionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.purpose-read-enabled", havingValue = "true")
    DevPurposeSelectionStore devPurposeSelectionStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || !properties.isInputPreviewEnabled()
                || !properties.isComparisonReconfirmEnabled() || !properties.isTextResultEnabled()
                || !properties.isFeatureConfirmEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 purpose read requires exact comparison input preview");
        }
        try (var connection = dataSource.getConnection()) {
            for (String name : new String[] { "CON2_PURPOSE_STATE", "CON2_PURPOSE_REVISION" }) {
                try (var tables = connection.getMetaData().getTables(null, null, name, null)) {
                    if (!tables.next()) throw new IllegalStateException("V2 purpose relation missing: " + name);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("V2 purpose relations unavailable", failure);
        }
        return new Dm8DevPurposeSelectionStore(dataSource, manager, ZoneId.of(zone));
    }
}
