package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled",
        "product.source.semantic.enabled"}, havingValue = "true")
public class ProductSemanticSearchConfiguration {
    @Bean(destroyMethod = "close") PatentEmbeddingProvider patentEmbeddingProvider(
            @Value("${product.source.semantic.model.path:}") String modelPath,
            @Value("${product.source.semantic.tokenizer.path:}") String tokenizerPath,
            @Value("${product.source.semantic.model.id:sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2}") String modelId,
            @Value("${product.source.semantic.model.revision:}") String revision,
            @Value("${product.source.semantic.model.sha256:}") String modelSha,
            @Value("${product.source.semantic.tokenizer.sha256:}") String tokenizerSha,
            @Value("${product.source.semantic.dimension:384}") int dimension,
            @Value("${product.source.semantic.max-tokens:128}") int maxTokens,
            @Value("${product.source.semantic.license:Apache-2.0}") String license) {
        return new OnnxPatentEmbeddingProvider(Path.of(modelPath), Path.of(tokenizerPath),
                new PatentEmbeddingProvider.Model("ONNX_RUNTIME", modelId, revision, modelSha,
                        tokenizerSha, dimension, maxTokens, "MEAN_POOL_L2/v1", license));
    }

    @Bean ProductSemanticIndex productSemanticIndex(JdbcTemplate db,
            PlatformTransactionManager manager, ProductSourceAvailability availability,
            ProductSourceWatermark watermark, ProductCollectedPatentTitles titles,
            PatentEmbeddingProvider embeddings) {
        return new ProductSemanticIndex(db, new TransactionTemplate(manager), availability,
                watermark, titles, embeddings, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close") PatentReranker patentReranker(
            @Value("${product.source.reranker.model.path:}") String modelPath,
            @Value("${product.source.reranker.tokenizer.path:}") String tokenizerPath,
            @Value("${product.source.reranker.model.id:cross-encoder/ms-marco-MiniLM-L-6-v2}") String modelId,
            @Value("${product.source.reranker.model.revision:}") String revision,
            @Value("${product.source.reranker.model.sha256:}") String modelSha,
            @Value("${product.source.reranker.tokenizer.sha256:}") String tokenizerSha,
            @Value("${product.source.reranker.max-tokens:512}") int maxTokens,
            @Value("${product.source.reranker.license:Apache-2.0}") String license) {
        return new OnnxPatentReranker(Path.of(modelPath), Path.of(tokenizerPath),
                new PatentReranker.Model("ONNX_RUNTIME", modelId, revision, modelSha,
                        tokenizerSha, maxTokens, license));
    }
}
