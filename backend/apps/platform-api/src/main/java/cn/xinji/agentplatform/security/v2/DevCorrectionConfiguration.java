package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevCorrectionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevCorrectionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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
class DevCorrectionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.correction-input-manifest-path")
    DevCorrectionInputPolicy devCorrectionInputPolicy(ObjectMapper mapper,
            DevelopmentBoundaryProperties properties, DevelopmentSubjectPolicy subjects) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled()) {
            throw new IllegalArgumentException("V2 correction input requires task core");
        }
        String configured = properties.getCorrectionInputManifestPath();
        String comparison = properties.getComparisonInputManifestPath();
        if (configured == null || configured.isBlank() || comparison == null || comparison.isBlank()) {
            throw new IllegalArgumentException("V2 correction and comparison input manifests required");
        }
        return new DevCorrectionInputPolicy(mapper, Path.of(configured).normalize(),
                Path.of(comparison).normalize(), subjects);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.correction-enabled", havingValue = "true")
    DevCorrectionStore devCorrectionStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties, DevCorrectionInputPolicy policy,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 correction requires isolated task core and DB zone");
        }
        Boolean installed = new JdbcTemplate(dataSource).execute((ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_CORRECTION", null)) {
                return tables.next();
            }
        });
        if (!Boolean.TRUE.equals(installed)) throw new IllegalArgumentException("V030 correction migration required");
        return new Dm8DevCorrectionStore(dataSource, manager, ZoneId.of(zone));
    }
}
