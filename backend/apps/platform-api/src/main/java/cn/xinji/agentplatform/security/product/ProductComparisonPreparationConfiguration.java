package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
class ProductComparisonPreparationConfiguration {
    @Bean ProductComparisonJson productComparisonJson(ObjectMapper mapper) {return new ProductComparisonJson(mapper);}
    @Bean @DependsOn("productComparisonSchemaGuard") ProductComparisonPreparation productComparisonPreparation(JdbcTemplate db,ProductComparisonJson json,
            ProductTaskDirectory tasks,ProductConsultationAccess access,ProductComparisonTargetReader targets,
            ProductSourceAvailability sources,ProductCollectedPatentTitles originals,
            ProductSourceEvidenceFiles files,ProductGrantWatermark watermark,PlatformTransactionManager manager,ObjectMapper mapper,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.consultation.goal-retention.max-bytes:0}") int maxBytes) {
        if(maxBytes<1) throw new IllegalArgumentException("Configured confirmation text byte budget required");
        Clock clock=Clock.systemUTC();
        db.queryForObject("SELECT COUNT(*) FROM con5_preparation",Long.class);
        db.queryForObject("SELECT COUNT(*) FROM con5_feature_revision",Long.class);
        var documents=new ProductComparisonDocuments(sources,originals,files,
                new ProductPatentDocumentAccess(db,watermark,clock,issuer),db,mapper,clock);
        var loader=new ProductComparisonPreparationLoader(db,tasks,access,targets,documents,json,clock);
        return new ProductComparisonPreparation(loader,new ProductComparisonStore(db,json),
                new TransactionTemplate(manager),json,clock,maxBytes);
    }
}
