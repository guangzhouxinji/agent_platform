package cn.xinji.agentplatform.security.product;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
class ProductComparisonSchemaConfiguration {
    @Bean ProductComparisonSchemaGuard productComparisonSchemaGuard(JdbcTemplate db,
            @Value("${product.consultation.comparison.enabled:false}") boolean enabled) {
        return new ProductComparisonSchemaGuard(db,enabled);
    }
}
