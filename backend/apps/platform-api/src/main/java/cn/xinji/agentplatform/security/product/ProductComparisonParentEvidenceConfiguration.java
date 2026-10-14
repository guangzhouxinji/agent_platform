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

/** Internal read assembly remains independent of model execution and performs no startup lineage query. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled"},havingValue="true")
class ProductComparisonParentEvidenceConfiguration {
    @Bean ProductComparisonLineageProjection productComparisonLineageProjection(ProductComparisonParentEvidenceReader parents,
            ProductSourceEvidenceFiles files, ProductComparisonJson json, ProductSourceAvailability sources,
            ProductComparisonMatrixCalculation calculation, JdbcTemplate db, ProductGrantWatermark watermark,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.consultation.comparison.approval.ref:}") String reference,
            @Value("${product.consultation.comparison.approval.sha256:}") String sha) {
        Clock clock=Clock.systemUTC();
        var metadata=new ProductComparisonModelAdmission(files,json,sources,new ProductPatentDocumentAccess(db,watermark,clock,issuer),reference,sha,clock);
        return new ProductComparisonLineageProjection(parents,metadata,new ProductComparisonLineageFacts(json,calculation),calculation);
    }
    @Bean ProductComparisonParentEvidenceReader productComparisonParentEvidenceReader(ProductTaskDirectory tasks,
            ProductConsultationAccess access, ProductComparisonTargetReader targets, ProductSourceAvailability sources,
            ProductCollectedPatentTitles originals, ProductSourceEvidenceFiles files, ProductGrantWatermark watermark,
            ProductComparisonInputApplication inputs, ProductComparisonMatrixStore matrices, ProductComparisonMatrixCalculation calculation,
            JdbcTemplate db, ProductComparisonJson json, ObjectMapper mapper, PlatformTransactionManager manager,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.consultation.comparison.historical-approvals:[]}") String historicalReferences) {
        Clock clock=Clock.systemUTC();
        var documents=new ProductComparisonDocuments(sources,originals,files,new ProductPatentDocumentAccess(db,watermark,clock,issuer),db,mapper,clock);
        var loader=new ProductComparisonPreparationLoader(db,tasks,access,targets,documents,json,clock);
        return new ProductComparisonParentEvidenceReader(loader,inputs,new ProductComparisonInputStore(db,json),matrices,
                new ProductComparisonLineageStore(db,json),sources,db,new TransactionTemplate(manager),json,calculation,
                new ProductComparisonLineageFacts(json,calculation),clock,
                new ProductComparisonModelAdmission(files,json,sources,new ProductPatentDocumentAccess(db,watermark,clock,issuer),"","",clock,historicalReferences));
    }
}
