package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"},
        havingValue = "true")
public class ProductSourceEngineConfiguration {
    @Bean ProductSourceWatermark productSourceWatermark(JdbcTemplate db,
            ProductGrantWatermark grantWatermark, PlatformTransactionManager manager) {
        return new ProductSourceWatermark(db, grantWatermark.independentDatabase(),
                new TransactionTemplate(manager));
    }

    @Bean ProductSourceScopeGrants productSourceScopeGrants(JdbcTemplate db,
            PlatformTransactionManager manager, ProductTaskDirectory tasks, ProductGrants grants,
            ProductGrantWatermark grantWatermark, ProductSourceWatermark sourceWatermark,
            ProductMaintenanceProof maintenance, @Value("${product.identity.issuer:}") String issuer) {
        db.queryForObject("SELECT COUNT(*) FROM con4_task_source_current", Integer.class);
        return new ProductSourceScopeGrants(db, new TransactionTemplate(manager), tasks, grants,
                grantWatermark, sourceWatermark, maintenance, Clock.systemUTC(), issuer);
    }

    @Bean ProductSourceEvidenceFiles productSourceEvidenceFiles(
            @Value("${product.source.evidence.root:}") String root,
            @Value("${product.source.evidence.max-bytes:0}") long maxBytes) {
        if (root.isBlank()) throw new IllegalArgumentException("Controlled source evidence root is required");
        return new ProductSourceEvidenceFiles(Path.of(root), maxBytes);
    }

    @Bean ProductSourceVerification productSourceVerification(JdbcTemplate db,
            PlatformTransactionManager manager, ProductSourceCandidates candidates,
            ProductSourceEvidenceFiles evidence, ObjectMapper mapper) {
        return new ProductSourceVerification(db, new TransactionTemplate(manager), candidates,
                evidence, mapper, Clock.systemUTC());
    }

    @Bean ProductSourceAvailability productSourceAvailability(JdbcTemplate db,
            ProductSourceWatermark sourceWatermark) {
        return new ProductSourceAvailability(db, sourceWatermark, Clock.systemUTC());
    }

    @Bean ProductSourceAdmission productSourceAdmission(JdbcTemplate db,
            ProductSourceCandidates candidates, ProductSourceEvidenceFiles evidence,
            ProductSourceWatermark sourceWatermark, ObjectMapper mapper) {
        return new ProductSourceAdmission(db, candidates, evidence, sourceWatermark,
                mapper, Clock.systemUTC());
    }

    @Bean ProductWorkerAccess productWorkerAccess(JdbcTemplate db,
            ProductGrantWatermark grants, ProductSourceAvailability sources,
            ProductSourceWatermark sourceWatermark, ProductTaskDirectory directory,
            ObjectMapper mapper,
            @Value("${product.identity.issuer:}") String issuer) {
        return new ProductWorkerAccess(db, grants, sources, sourceWatermark,
                directory, mapper, Clock.systemUTC(), issuer);
    }

    @Bean ProductTaskWorkerExecution productTaskWorkerExecution(JdbcTemplate db,
            ProductWorkerAccess access, PlatformTransactionManager manager) {
        var transactions = new TransactionTemplate(manager);
        return new ProductTaskWorkerExecution(access,
                new ProductTaskExecutionFence(db, transactions, Clock.systemUTC()), transactions);
    }

    @Bean ProductWorkerExecutionRequests productWorkerExecutionRequests(JdbcTemplate db,
            PlatformTransactionManager manager, ProductWorkerAccess access,
            ProductTaskWorkerExecution execution) {
        return new ProductWorkerExecutionRequests(db, new TransactionTemplate(manager),
                access, execution, Clock.systemUTC());
    }

    @Bean ProductCollectedPatentTitles productCollectedPatentTitles(ProductSourceEvidenceFiles files,
            ObjectMapper mapper) {
        return new ProductCollectedPatentTitles(files, mapper);
    }

    @Bean ProductPatentSearchCursor productPatentSearchCursor(JdbcTemplate db,
            @Value("${product.source.results.cursor.hmac-key-base64:}") String encodedKey,
            @Value("${product.source.results.cursor.max-age:PT15M}") String maxAge) {
        if (encodedKey.isBlank()) throw new IllegalArgumentException("Search cursor signing key is required");
        try {
            return new ProductPatentSearchCursor(db, Base64.getDecoder().decode(encodedKey),
                    Duration.parse(maxAge), Clock.systemUTC());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Search cursor configuration is invalid", failure);
        }
    }

    @Bean ProductPatentSearchExecution productPatentSearchExecution(JdbcTemplate db,
            ProductWorkerAccess workers, ProductTaskWorkerExecution execution,
            ProductCollectedPatentTitles titles, ProductSourceAvailability availability,
            ProductSourceWatermark sourceWatermark, ProductTaskDirectory tasks,
            ProductConsultationAccess access, ObjectMapper mapper, ProductPatentSearchCursor cursors,
            org.springframework.beans.factory.ObjectProvider<ProductSemanticIndex> semantic,
            org.springframework.beans.factory.ObjectProvider<
                    cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker> reranker,
            @Value("${product.source.results.retention.ref:}") String retentionRef,
            @Value("${product.source.results.retention.version:0}") long retentionVersion,
            @Value("${product.source.results.retention.evidence-sha256:}") String evidenceSha,
            @Value("${product.source.results.retention.max-age:}") String maxAge,
            @Value("${product.source.results.max-results:100}") int maxResults) {
        if (maxAge.isBlank()) throw new IllegalArgumentException("Search result retention policy is required");
        return new ProductPatentSearchExecution(db, workers, execution, titles, availability,
                sourceWatermark, tasks, access, new ProductRetentionPolicy(retentionRef,
                        retentionVersion, evidenceSha, Duration.parse(maxAge)), mapper, cursors,
                semantic.getIfAvailable(), reranker.getIfAvailable(), Clock.systemUTC(), maxResults);
    }
}
