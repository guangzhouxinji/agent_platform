package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit deployment opt-in; missing V102, TLS material or managed binding fails startup closed. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled","product.consultation.comparison.start.enabled"},havingValue="true")
class ProductComparisonStartConfiguration {
    @Bean ProductComparisonStartCommands productComparisonStartCommands(JdbcTemplate db,PlatformTransactionManager transactions,Environment env){
        ProductComparisonStartSchema.verify(db,required(env,"spring.datasource.hikari.schema"));
        return new ProductComparisonStartCommands(db,new TransactionTemplate(transactions),Clock.systemUTC());
    }
    @Bean ProductComparisonHandoffStore productComparisonHandoffStore(JdbcTemplate db,PlatformTransactionManager transactions,ProductComparisonStartCommands commands,ProductComparisonJson json){return new ProductComparisonHandoffStore(db,new TransactionTemplate(transactions),commands,json,Clock.systemUTC());}
    @Bean ProductComparisonStartApplication productComparisonStartApplication(ProductComparisonStartCommands commands,
            ProductComparisonInputApplication inputs,ProductComparisonWorkerDelegations delegations,ProductWorkerDelegations authority,
            ProductComparisonMatrixReader results,ProductTaskDirectory tasks,ProductConsultationAccess access,JdbcTemplate db,Environment env,ObjectMapper mapper,
            ObjectProvider<ProductComparisonModelAdmission> modelProvider,ProductComparisonHandoffStore handoffs) {
        String prefix="product.consultation.comparison.start.";
        String handoffVersion=required(env,prefix+"configuration-version");
        if(handoffVersion.length()>180||handoffVersion.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid managed handoff version");
        var models=modelProvider.getIfAvailable();
        if(models!=null&&models.approval().configuration().version().startsWith("HANDOFF_ONLY:"))throw new IllegalArgumentException("Reserved handoff namespace is not a model configuration version");
        Duration lease=Duration.ofMillis(Long.parseLong(required(env,prefix+"lease-millis")));
        if(models!=null&&lease.compareTo(models.approval().configuration().timeout())<=0)throw new IllegalArgumentException("Lease must exceed approved model timeout");
        var transport=ProductRetrievalMtls.create(URI.create(required(env,prefix+"product-origin")),
                Path.of(required(env,prefix+"worker-keystore")),required(env,prefix+"worker-password").toCharArray(),
                Path.of(required(env,prefix+"truststore")),required(env,prefix+"truststore-password").toCharArray(),mapper);
        var managed=new ProductComparisonStartCommands.ManagedBinding(required(env,prefix+"worker-subject"),models==null?"HANDOFF_ONLY:"+handoffVersion:models.approval().configuration().version());
        return new ProductComparisonStartApplication(commands,inputs,delegations,authority,results,tasks,access,db,transport,managed,lease,Clock.systemUTC(),mapper,handoffs);
    }
    private static String required(Environment env,String key){String value=env.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing managed start configuration: "+key);return value;}
}


