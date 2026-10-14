package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
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
public class ProductConsultationTaskConfiguration {
    @Bean ProductHumanTaskTransaction productHumanTaskTransaction(JdbcTemplate db,
            PlatformTransactionManager manager, ProductGrantWatermark watermark,
            ProductConsultationAccess access, org.springframework.security.oauth2.jwt.JwtDecoder decoder,
            @Value("${product.identity.issuer:}") String issuer) {
        return new ProductHumanTaskTransaction(db, manager, watermark, access, decoder, issuer, Clock.systemUTC());
    }

    @Bean ProductWorkerDelegations productWorkerDelegations(JdbcTemplate db,
            ProductGrantWatermark watermark, ProductConsultationAccess access,
            ProductTaskDirectory directory, PlatformTransactionManager manager,
            @Value("${product.identity.issuer:}") String issuer) {
        return new ProductWorkerDelegations(db, new TransactionTemplate(manager), watermark,
                access, directory, Clock.systemUTC(), issuer);
    }

    @Bean ProductConsultationAccess productConsultationAccess(JdbcTemplate db,
            ProductGrantWatermark watermark, @Value("${product.identity.issuer:}") String issuer) {
        return new ProductConsultationAccess(db, watermark, issuer, Clock.systemUTC());
    }

    @Bean ProductTaskDirectory productTaskDirectory(JdbcTemplate db) {
        return new JdbcProductTaskDirectory(db, Clock.systemUTC());
    }

    @Bean ProductTaskGoalService productTaskGoalService(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, PlatformTransactionManager manager, ObjectMapper mapper,
            @Value("${product.consultation.goal-retention.ref:}") String retentionRef,
            @Value("${product.consultation.goal-retention.version:0}") long retentionVersion,
            @Value("${product.consultation.goal-retention.evidence-sha256:}") String evidenceSha,
            @Value("${product.consultation.goal-retention.max-age:}") String maxAge,
            @Value("${product.consultation.goal-retention.max-bytes:0}") int maxBytes) {
        if (maxAge.isBlank()) throw new IllegalArgumentException("Goal retention policy is required");
        return new ProductTaskGoalService(db, directory, access, new TransactionTemplate(manager),
                new ProductRetentionPolicy(retentionRef, retentionVersion, evidenceSha,
                Duration.parse(maxAge)), mapper, Clock.systemUTC(), maxBytes);
    }

    @Bean ProductTaskGoalReader productTaskGoalReader(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, ObjectMapper mapper,
            @Value("${product.consultation.goal-retention.ref:}") String retentionRef,
            @Value("${product.consultation.goal-retention.version:0}") long retentionVersion,
            @Value("${product.consultation.goal-retention.evidence-sha256:}") String evidenceSha,
            @Value("${product.consultation.goal-retention.max-age:}") String maxAge) {
        return new ProductTaskGoalReader(db, directory, access,
                new ProductRetentionPolicy(retentionRef, retentionVersion, evidenceSha,
                Duration.parse(maxAge)), mapper, Clock.systemUTC());
    }

    @Bean ProductTaskCancelService productTaskCancelService(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, PlatformTransactionManager manager) {
        return new ProductTaskCancelService(db, directory, access,
                new TransactionTemplate(manager), Clock.systemUTC());
    }

    @Bean ProductTaskSessionService productTaskSessionService(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, ProductTaskGoalReader goals, PlatformTransactionManager manager) {
        return new ProductTaskSessionService(db, directory, access, goals,
                new TransactionTemplate(manager), Clock.systemUTC());
    }

    @Bean ProductInitialTaskService productInitialTaskService(ProductTaskDirectory directory,
            ProductGrants grants, ProductGrantWatermark watermark, PlatformTransactionManager manager,
            ObjectMapper mapper, @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.consultation.retention.ref:}") String retentionRef,
            @Value("${product.consultation.retention.version:0}") long retentionVersion,
            @Value("${product.consultation.retention.evidence-sha256:}") String evidenceSha,
            @Value("${product.consultation.retention.max-age:}") String maxAge) {
        if (maxAge.isBlank()) throw new IllegalArgumentException("Task retention policy is required");
        var retention = new ProductRetentionPolicy(retentionRef, retentionVersion, evidenceSha,
                Duration.parse(maxAge));
        return new ProductInitialTaskService(directory, grants, watermark, new TransactionTemplate(manager),
                mapper, Clock.systemUTC(), issuer, retention);
    }

    @Bean ProductTaskCreationService productTaskCreationService(JdbcTemplate db,
            ProductTaskDirectory directory, ProductConsultationAccess access, ProductGrants grants,
            ProductGrantWatermark watermark, PlatformTransactionManager manager, ObjectMapper mapper,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.consultation.retention.ref:}") String retentionRef,
            @Value("${product.consultation.retention.version:0}") long retentionVersion,
            @Value("${product.consultation.retention.evidence-sha256:}") String evidenceSha,
            @Value("${product.consultation.retention.max-age:}") String maxAge) {
        if (maxAge.isBlank()) throw new IllegalArgumentException("Task retention policy is required");
        return new ProductTaskCreationService(db, directory, access, grants, watermark,
                new TransactionTemplate(manager), new ProductRetentionPolicy(retentionRef,
                retentionVersion, evidenceSha, Duration.parse(maxAge)), mapper, Clock.systemUTC(), issuer);
    }
}
