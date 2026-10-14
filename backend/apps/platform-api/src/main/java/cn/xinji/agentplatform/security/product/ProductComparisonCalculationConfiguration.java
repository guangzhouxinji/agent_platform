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

/** Computation opt-in is separate from input, reading and human handoff. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled","product.consultation.comparison.calculation.enabled"},havingValue="true")
class ProductComparisonCalculationConfiguration {
    @Bean ProductComparisonModelAdmission productComparisonModelAdmission(ProductSourceEvidenceFiles files,ProductComparisonJson json,
            ProductSourceAvailability sources,JdbcTemplate db,ProductGrantWatermark watermark,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.consultation.comparison.approval.ref:}") String reference,
            @Value("${product.consultation.comparison.approval.sha256:}") String sha) {
        var admission=new ProductComparisonModelAdmission(files,json,sources,new ProductPatentDocumentAccess(db,watermark,Clock.systemUTC(),issuer),reference,sha,Clock.systemUTC());
        admission.validateDeployment();return admission;
    }
    @Bean ProductComparisonMatrixExecution productComparisonMatrixExecution(ProductComparisonCandidateExecution leases,ProductComparisonWorkerMaterials materials,
            ProductComparisonModelAdmission admission,ProductComparisonMatrixCalculation calculation,ProductComparisonMatrixStore store,
            ObjectMapper mapper,@Value("${product.consultation.model.api-key:}") String credential){
        store.requireLineageSchema();
        if(credential==null||credential.isBlank())throw new IllegalArgumentException("Approved model credential required");
        ModelGateway gateway=new OpenAiModelGateway(id->{var config=admission.approval().configuration();
            if(!config.id().equals(id)||!"https".equalsIgnoreCase(config.endpoint().getScheme()))throw new IllegalStateException("APPROVED_MODEL_TLS_CONFIGURATION_REQUIRED");
            return new OpenAiModelGateway.Connection(config,credential);},mapper);
        return new ProductComparisonMatrixExecution(leases,materials,admission,calculation,store,gateway,Clock.systemUTC());
    }
}
