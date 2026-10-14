package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.runtime.ModelGateway;
import cn.xinji.agentplatform.model.gateway.OpenAiModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Final product feature deployment gate; no development or test substitute beans. */
@Configuration
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled"},havingValue="true")
class ProductComparisonMatrixConfiguration {
    @Bean ProductComparisonMatrixReader productComparisonMatrixReader(cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory tasks,
            ProductConsultationAccess access,ProductComparisonTargetReader targets,ProductSourceAvailability sources,ProductCollectedPatentTitles originals,
            ProductSourceEvidenceFiles files,ProductGrantWatermark watermark,ProductComparisonMatrixStore store,ProductComparisonMatrixCalculation calculation,
            JdbcTemplate db,ProductComparisonJson json,ObjectMapper mapper,@Value("${product.identity.issuer:}") String issuer) {
        Clock clock=Clock.systemUTC();var documents=new ProductComparisonDocuments(sources,originals,files,new ProductPatentDocumentAccess(db,watermark,clock,issuer),db,mapper,clock);
        return new ProductComparisonMatrixReader(tasks,access,new ProductComparisonInputStore(db,json),documents,targets,sources,store,calculation,json,db,clock);
    }
    @Bean ProductComparisonMatrixCalculation productComparisonMatrixCalculation(ProductComparisonJson json){return new ProductComparisonMatrixCalculation(json);}
    @Bean @DependsOn("productComparisonSchemaGuard") ProductComparisonMatrixStore productComparisonMatrixStore(JdbcTemplate db,ProductComparisonJson json,PlatformTransactionManager manager){
        return new ProductComparisonMatrixStore(db,json,new TransactionTemplate(manager),Clock.systemUTC());
    }
}
