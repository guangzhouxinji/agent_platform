package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.prompt.enabled",havingValue="true")
class PromptRouteSecurityConfiguration {
    @Bean @Order(3)
    SecurityFilterChain promptReadSecurity(HttpSecurity http,ProductGrantWatermark watermark)throws Exception{
        return http.securityMatcher("/v2/consultation/prompts/**","/v2/consultation/prompt-workspaces/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests->requests.anyRequest().authenticated())
                .addFilterBefore(new ProductPromptTransportFilter(watermark),BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth->oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
