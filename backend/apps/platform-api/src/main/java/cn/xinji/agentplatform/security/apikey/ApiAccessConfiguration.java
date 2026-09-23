package cn.xinji.agentplatform.security.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApiAccessProperties.class)
class ApiAccessConfiguration {
    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    ApiCredentialRegistry apiCredentialRegistry(ApiAccessProperties properties) {
        return new ApiCredentialRegistry(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiCredentialRegistry registry,
                                                           ObjectMapper mapper, Clock clock) {
        return new ApiKeyAuthenticationFilter(registry, new ApiScopePolicy(),
                new ApiRateLimiter(), new Slf4jApiAccessAuditSink(), mapper, clock);
    }
}
