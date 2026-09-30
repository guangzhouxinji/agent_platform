package cn.xinji.agentplatform.security.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DevComparisonInputConfiguration {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.task-core-enabled", havingValue = "true")
    DevComparisonInputPolicy devComparisonInputPolicy(ObjectMapper mapper,
            DevelopmentBoundaryProperties properties, DevelopmentSubjectPolicy subjects) {
        if (!properties.isEnabled()) throw new IllegalArgumentException("V2 task core requires development boundary");
        String configured = properties.getComparisonInputManifestPath();
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("V2 comparison input manifest required for body display");
        }
        return new DevComparisonInputPolicy(mapper, Path.of(configured).normalize(), subjects);
    }
}
