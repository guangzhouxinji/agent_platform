package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.application.ConsultationRetrievalModelPort;
import cn.xinji.agentplatform.consultation.answer.adapter.out.openai.OpenAiRetrievalDialogueAdapter;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"}, havingValue = "true")
public class ProductRetrievalDialogueConfiguration {
    @Bean @ConditionalOnProperty(name="product.consultation.model.enabled",havingValue="true")
    ConsultationRetrievalModelPort productRetrievalModel(
            @Value("${product.consultation.model.endpoint:}") String endpoint,
            @Value("${product.consultation.model.api-key:}") String apiKey,
            @Value("${product.consultation.model.model-id:}") String model,
            @Value("${product.consultation.model.input-policy:}") String inputPolicy, ObjectMapper mapper) {
        if (!"PUBLIC_NONCONFIDENTIAL/v1".equals(inputPolicy))
            throw new IllegalArgumentException("Explicit public nonconfidential model policy required");
        return new OpenAiRetrievalDialogueAdapter(URI.create(endpoint),apiKey,model,mapper);
    }

    @Bean @ConditionalOnExpression("${product.consultation.model.enabled:false} or ${product.consultation.workspace.enabled:false}")
    ProductRetrievalDialogue productRetrievalDialogue(JdbcTemplate db, ProductTaskDirectory tasks,
            ProductConsultationAccess access, ProductTaskGoalReader goals, ProductPatentSearchExecution searches,
            ProductSourceAvailability sources, java.util.Optional<ConsultationRetrievalModelPort> model,
            PlatformTransactionManager manager, ObjectMapper mapper,
            @Value("${product.consultation.goal-retention.ref:}") String ref,
            @Value("${product.consultation.goal-retention.version:0}") long version,
            @Value("${product.consultation.goal-retention.evidence-sha256:}") String sha,
            @Value("${product.consultation.goal-retention.max-age:}") String age) {
        db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class);
        db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class);
        return new ProductRetrievalDialogue(db,tasks,access,goals,searches,sources,model.orElse(null),
                new TransactionTemplate(manager),new ProductRetentionPolicy(ref,version,sha,Duration.parse(age)),
                mapper,Clock.systemUTC());
    }
}
