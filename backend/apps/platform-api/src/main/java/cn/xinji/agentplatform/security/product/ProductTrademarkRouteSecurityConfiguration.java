package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.trademark-read.enabled",havingValue="true")
class ProductTrademarkRouteSecurityConfiguration {
    @Bean @Order(3) SecurityFilterChain productTrademarkRoutes(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v2/consultation/tasks/*/trademarks","/v2/consultation/tasks/*/trademarks/**")
                .csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(r->r.anyRequest().authenticated())
                .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())).build();
    }
}
