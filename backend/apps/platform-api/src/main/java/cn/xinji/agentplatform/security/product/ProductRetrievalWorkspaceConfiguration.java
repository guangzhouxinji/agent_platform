package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled",
        "product.consultation.workspace.enabled"},havingValue="true")
class ProductRetrievalWorkspaceConfiguration {
    @Bean ProductRetrievalWorkspace productRetrievalWorkspace(JdbcTemplate db,ProductTaskDirectory tasks,
            ProductConsultationAccess access,ProductTaskGoalReader goals,ProductSourceAvailability sources,
            ProductPatentSearchExecution searches,ProductRetrievalDialogue dialogue,Environment env) {
        return new ProductRetrievalWorkspace(db,tasks,access,goals,sources,searches,Clock.systemUTC(),
                required(env,"product.consultation.goal-retention.ref"),
                Long.parseLong(required(env,"product.consultation.goal-retention.version")),dialogue);
    }
    @Bean @ConditionalOnProperty(name="product.consultation.model.enabled",havingValue="true")
    ProductRetrievalWorkflow productRetrievalWorkflow(ProductRetrievalWorkspace workspace,
            ProductWorkerDelegations delegations,JdbcTemplate db,ObjectMapper mapper,Environment env) {
        URI origin=URI.create(required(env,"product.consultation.workspace.product-origin"));
        var maintenance=transport("maintenance",origin,env,mapper);
        var worker=transport("worker",origin,env,mapper);
        return new ProductRetrievalWorkflow(workspace,delegations,maintenance,worker,db,
                required(env,"product.consultation.workspace.worker-subject"),Clock.systemUTC());
    }
    private static ProductRetrievalMtls.Transport transport(String role,URI origin,Environment env,ObjectMapper mapper) {
        String prefix="product.consultation.workspace.";
        return ProductRetrievalMtls.create(origin,Path.of(required(env,prefix+role+"-keystore")),
                required(env,prefix+role+"-password").toCharArray(),Path.of(required(env,prefix+"truststore")),
                required(env,prefix+"truststore-password").toCharArray(),mapper);
    }
    private static String required(Environment env,String key) {
        String value=env.getProperty(key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing final workspace setting: "+key);
        return value;
    }
}
