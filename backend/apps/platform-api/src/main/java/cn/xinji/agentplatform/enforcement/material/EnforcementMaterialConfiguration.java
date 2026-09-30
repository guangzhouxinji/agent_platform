package cn.xinji.agentplatform.enforcement.material;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public class EnforcementMaterialConfiguration {
    @Bean MaterialRepository materialRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        return new Dm8MaterialRepository(db, new TransactionTemplate(manager), Clock.systemUTC());
    }

    @Bean MaterialFiles materialFiles(@Value("${enforcement.v2.file-root:}") String fileRoot) {
        if (fileRoot.isBlank()) throw new IllegalArgumentException("Private enforcement file root required");
        return new LocalMaterialFiles(Path.of(fileRoot));
    }

    @Bean MaterialPageSource materialPageSource(Environment environment, ObjectMapper json) {
        Map<String, String> endpoints = Binder.get(environment)
                .bind("enforcement.v2.source-endpoints", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        Map<String, String> credentials = Binder.get(environment)
                .bind("enforcement.v2.source-credentials", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        return new HttpMaterialPageSource(endpoints, credentials, HttpClient.newHttpClient(), json);
    }

    @Bean @Order(2) SecurityFilterChain enforcementProductSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v2/enforcement/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
    }

    @Bean EnforcementMaterialTransportFilter enforcementMaterialTransportFilter(ProductGrantWatermark watermark,
            @Value("${product.identity.enabled:false}") boolean identityEnabled) {
        if (!identityEnabled) throw new IllegalStateException("Product identity required for enforcement V2");
        return new EnforcementMaterialTransportFilter(watermark);
    }
}
