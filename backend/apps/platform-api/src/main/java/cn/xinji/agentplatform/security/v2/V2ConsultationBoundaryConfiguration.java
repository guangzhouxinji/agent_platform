package cn.xinji.agentplatform.security.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevCurrentWorkStateStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTextResultStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevCurrentWorkStateStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanRequestStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableConfigurationProperties(DevelopmentBoundaryProperties.class)
class V2ConsultationBoundaryConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.enabled", havingValue = "true")
    DevelopmentSubjectPolicy developmentSubjectPolicy(DevelopmentBoundaryProperties properties,
                                                       Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalArgumentException("V2 development identity cannot run in production profile");
        }
        if (!environment.acceptsProfiles(Profiles.of("test-synthetic"))) {
            throw new IllegalArgumentException("V2 historical identity is available only in the test profile");
        }
        return new DevelopmentSubjectPolicy(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.enabled", havingValue = "true")
    SyntheticUsePolicy syntheticUsePolicy(ObjectMapper mapper, DevelopmentBoundaryProperties properties,
                                          DevelopmentSubjectPolicy subjects) {
        String configured = properties.getManifestPath();
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("V2 development requires an explicit synthetic manifest path");
        }
        Path path = Path.of(configured).normalize();
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("V2 synthetic manifest must be an existing absolute file");
        }
        return new SyntheticUsePolicy(mapper, path, subjects);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.task-core-enabled", havingValue = "true")
    DevTaskInputPolicy devTaskInputPolicy(ObjectMapper mapper, DevelopmentBoundaryProperties properties,
                                          DevelopmentSubjectPolicy subjects) {
        if (!properties.isEnabled()) throw new IllegalArgumentException("V2 task core requires development boundary");
        String configured = properties.getTaskInputManifestPath();
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException("V2 task input manifest required");
        Path path = Path.of(configured).normalize();
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("V2 task input manifest must be an existing absolute file");
        }
        return new DevTaskInputPolicy(mapper, path, subjects);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.task-core-enabled", havingValue = "true")
    DevTaskCoreStore devTaskCoreStore(DataSource dataSource, PlatformTransactionManager manager,
                                      @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (zone.isBlank()) throw new IllegalArgumentException("V2 task core requires explicit DB session zone");
        return new Dm8DevTaskCoreStore(dataSource, manager, ZoneId.of(zone));
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.text-result-enabled", havingValue = "true")
    DevTextResultStore devTextResultStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 text results require isolated task core and DB zone");
        }
        return new Dm8DevTextResultStore(dataSource, manager, ZoneId.of(zone));
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.feature-confirm-enabled", havingValue = "true")
    DevFeatureCandidatePolicy devFeatureCandidatePolicy(ObjectMapper mapper,
            DevelopmentBoundaryProperties properties, DevelopmentSubjectPolicy subjects) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled()) {
            throw new IllegalArgumentException("V2 feature confirmation requires task core");
        }
        String configured = properties.getFeatureCandidateManifestPath();
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("V2 feature candidate manifest required");
        }
        Path path = Path.of(configured).normalize();
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("V2 feature candidate manifest must be an existing absolute file");
        }
        String input = properties.getTaskInputManifestPath();
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("V2 feature confirmation requires task input manifest");
        }
        return new DevFeatureCandidatePolicy(mapper, path, Path.of(input).normalize(), subjects);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.feature-confirm-enabled", havingValue = "true")
    DevFeatureConfirmationStore devFeatureConfirmationStore(DataSource dataSource,
            PlatformTransactionManager manager, DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 feature confirmation requires isolated task core and DB zone");
        }
        return new Dm8DevFeatureConfirmationStore(dataSource, manager, ZoneId.of(zone));
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.work-state-enabled", havingValue = "true")
    DevCurrentWorkStateStore devCurrentWorkStateStore(DataSource dataSource,
            PlatformTransactionManager manager, DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || !properties.isTextResultEnabled()
                || !properties.isFeatureConfirmEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 work state requires task, result, feature and DB zone");
        }
        return new Dm8DevCurrentWorkStateStore(dataSource, manager, ZoneId.of(zone));
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.human-request-enabled", havingValue = "true")
    DevHumanGapPolicy devHumanGapPolicy(ObjectMapper mapper, DevelopmentBoundaryProperties properties,
                                       DevelopmentSubjectPolicy subjects) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled()) {
            throw new IllegalArgumentException("V2 human requests require task core");
        }
        String configured = properties.getHumanRequestManifestPath();
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException("Human input manifest required");
        Path path = Path.of(configured).normalize();
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Human input manifest must be an existing absolute file");
        }
        return new DevHumanGapPolicy(mapper, path, subjects);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.human-request-enabled", havingValue = "true")
    DevHumanRequestStore devHumanRequestStore(DataSource dataSource, PlatformTransactionManager manager,
            DevelopmentBoundaryProperties properties,
            @Value("${consultation-v2.development.db-session-zone:}") String zone) {
        if (!properties.isEnabled() || !properties.isTaskCoreEnabled() || zone.isBlank()) {
            throw new IllegalArgumentException("V2 human requests require isolated task core and DB zone");
        }
        return new Dm8DevHumanRequestStore(dataSource, manager, ZoneId.of(zone));
    }

    @Bean
    V2ConsultationBoundaryFilter v2ConsultationBoundaryFilter(DevelopmentBoundaryProperties properties,
                                                              ObjectProvider<DevelopmentSubjectPolicy> subjects,
                                                              Environment environment, Clock clock) {
        return new V2ConsultationBoundaryFilter(properties, subjects.getIfAvailable(), environment, clock);
    }
}
