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
class ProductComparisonInputConfiguration {
    @Bean @DependsOn("productComparisonSchemaGuard") ProductComparisonInputApplication productComparisonInputApplication(JdbcTemplate db,ProductComparisonJson json,
            ProductTaskDirectory tasks,ProductConsultationAccess access,ProductComparisonTargetReader targets,
            ProductSourceAvailability sources,ProductCollectedPatentTitles originals,ProductSourceEvidenceFiles files,
            ProductGrantWatermark watermark,PlatformTransactionManager manager,ObjectMapper mapper,
            @Value("${product.identity.issuer:}") String issuer) {
        db.queryForObject("SELECT COUNT(*) FROM con5_comparison_input",Long.class);
        db.queryForObject("SELECT COUNT(*) FROM con5_comparison_candidate",Long.class);
        Clock clock=Clock.systemUTC();
        var documents=new ProductComparisonDocuments(sources,originals,files,new ProductPatentDocumentAccess(db,watermark,clock,issuer),db,mapper,clock);
        var loader=new ProductComparisonPreparationLoader(db,tasks,access,targets,documents,json,clock);
        return new ProductComparisonInputApplication(loader,new ProductComparisonStore(db,json),new ProductComparisonInputStore(db,json),
                new TransactionTemplate(manager),json,clock);
    }
    @Bean ProductComparisonWorkerIntentAccess productComparisonWorkerIntentAccess(JdbcTemplate db,
            ProductGrantWatermark watermark,ProductTaskDirectory tasks,ProductComparisonWorkerDelegations bindings,
            @Value("${product.identity.issuer:}") String issuer) {
        return new ProductComparisonWorkerIntentAccess(
                new ProductComparisonWorkerTaskAuthority(db,watermark,tasks,Clock.systemUTC(),issuer),bindings);
    }
    @Bean ProductComparisonCandidateExecution productComparisonCandidateExecution(JdbcTemplate db,
            PlatformTransactionManager manager,ProductComparisonWorkerMaterials materials,ProductComparisonJson json) {
        return new ProductComparisonCandidateExecution(db,new TransactionTemplate(manager),materials,json,Clock.systemUTC());
    }
    @Bean ProductComparisonWorkerMaterials productComparisonWorkerMaterials(JdbcTemplate db,ProductGrantWatermark watermark,
            ProductTaskDirectory tasks,ProductComparisonWorkerIntentAccess intents,ProductComparisonTargetReader targets,
            ProductSourceAvailability sources,ProductCollectedPatentTitles originals,ProductSourceEvidenceFiles files,
            ProductComparisonJson json,ObjectMapper mapper,@Value("${product.identity.issuer:}") String issuer) {
        Clock clock=Clock.systemUTC();
        var authority=new ProductComparisonWorkerTaskAuthority(db,watermark,tasks,clock,issuer);
        var documents=new ProductComparisonDocuments(sources,originals,files,
                new ProductPatentDocumentAccess(db,watermark,clock,issuer),db,mapper,clock);
        return new ProductComparisonWorkerMaterials(authority,intents,new ProductComparisonWorkerReadAuthority(db,watermark,tasks,clock,issuer),
                new ProductComparisonInputStore(db,json),targets,documents,json,clock);
    }
    @Bean ProductComparisonWorkerDelegations productComparisonWorkerDelegations(JdbcTemplate db,
            ProductWorkerDelegations delegations,ProductComparisonInputApplication comparisons,ProductComparisonJson json) {
        db.queryForObject("SELECT COUNT(*) FROM con5_comparison_delegation",Long.class);
        return new ProductComparisonWorkerDelegations(db,delegations,comparisons,
                new ProductComparisonInputStore(db,json),json,Clock.systemUTC());
    }
}
