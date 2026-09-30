package cn.xinji.agentplatform.security.product;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.annotation.Order;

/** Existing routes retain their domain filters while product identity routes remain closed by the V2 boundary. */
@Configuration
public class ExistingRouteSecurityConfiguration {
    @Bean
    @Order(5)
    SecurityFilterChain existingRoutes(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
