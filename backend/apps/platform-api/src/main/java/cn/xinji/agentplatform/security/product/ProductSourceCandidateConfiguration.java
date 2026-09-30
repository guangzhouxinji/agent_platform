package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public class ProductSourceCandidateConfiguration {
    @Bean ProductSourceCandidates productSourceCandidates(JdbcTemplate db,
            PlatformTransactionManager manager, ObjectMapper mapper,
            @Value("${product.source.registry.retention.ref:}") String reference,
            @Value("${product.source.registry.retention.version:0}") long version,
            @Value("${product.source.registry.retention.evidence-sha256:}") String evidence,
            @Value("${product.source.registry.retention.max-age:}") String maxAge) {
        if (maxAge.isBlank()) throw new IllegalArgumentException("Source registry retention policy is required");
        return new ProductSourceCandidates(db, new TransactionTemplate(manager), mapper,
                new ProductRetentionPolicy(reference, version, evidence, Duration.parse(maxAge)),
                Clock.systemUTC());
    }
}
