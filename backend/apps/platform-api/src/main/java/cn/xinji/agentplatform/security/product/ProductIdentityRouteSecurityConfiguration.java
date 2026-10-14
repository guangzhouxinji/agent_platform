package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public class ProductIdentityRouteSecurityConfiguration {
    @Bean
    @Order(2)
    SecurityFilterChain comparisonWorker(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v2/consultation/tasks/*/patent-comparisons/*/candidates/*/executions",
                "/v2/consultation/tasks/*/executions/*/patent-comparison-result",
                        "/v2/consultation/tasks/*/patent-comparisons/*/candidates/*/start-commands/*/handoff-receipts")
                .csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(r->r.anyRequest().permitAll()).build();
    }
    @Bean
    @Order(3)
    SecurityFilterChain identitySelf(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v2/identity/me", "/v2/identity/logout",
                        "/v2/consultation/tasks", "/v2/consultation/tasks/*", "/v2/consultation/tasks/*/goals/confirm",
                        "/v2/consultation/tasks/*/goals/current", "/v2/consultation/tasks/*/cancel",
                        "/v2/consultation/tasks/*/human-requests",
                        "/v2/consultation/tasks/*/dialogue/**",
                        "/v2/consultation/tasks/*/retrieval-workspace", "/v2/consultation/tasks/*/patent-comparisons/**",
                        "/v2/consultation/tasks/*/agent-runs", "/v2/consultation/tasks/*/agent-runs/**",
                        "/v2/consultation/tasks/*/retrievals/execute",
                        "/v2/consultation/tasks/*/sessions",
                        "/v2/consultation/tasks/*/worker-delegations",
                        "/v2/consultation/tasks/*/worker-delegations/*/revoke",
                        "/v2/consultation/tasks/*/patent-searches/*/results",
                        "/v2/consultation/tasks/*/patent-searches/*/selections",
                        "/v2/consultation/tasks/*/patent-searches/*/selections/*",
                        "/v2/consultation/tasks/*/documents/*/*/versions/*/content")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain identityMaintenance(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v2/identity/**", "/v2/consultation/source-contracts/**",
                        "/v2/consultation/tasks/*/executions",
                        "/v2/consultation/tasks/*/executions/reconciliation",
                        "/v2/consultation/tasks/*/executions/*/patent-search-result")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
