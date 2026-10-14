package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Product bean graph test; ports are explicit doubles, not a live policy or database acceptance. */
class ProductComparisonAdmissionLayersConfigurationTest {
    ApplicationContextRunner reader(){return new ApplicationContextRunner()
        .withUserConfiguration(ProductComparisonMatrixConfiguration.class,ProductComparisonCalculationConfiguration.class)
        .withPropertyValues("product.identity.enabled=true","product.source.engine.enabled=true","product.consultation.comparison.enabled=true")
        .withBean("productComparisonSchemaGuard",Object.class,Object::new)
        .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class))
        .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
        .withBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules())
        .withBean(ProductComparisonJson.class,()->new ProductComparisonJson(new ObjectMapper().findAndRegisterModules()))
        .withBean(ProductTaskDirectory.class,()->mock(ProductTaskDirectory.class))
        .withBean(ProductConsultationAccess.class,()->mock(ProductConsultationAccess.class))
        .withBean(ProductComparisonTargetReader.class,()->mock(ProductComparisonTargetReader.class))
        .withBean(ProductSourceAvailability.class,()->mock(ProductSourceAvailability.class))
        .withBean(ProductCollectedPatentTitles.class,()->mock(ProductCollectedPatentTitles.class))
        .withBean(ProductSourceEvidenceFiles.class,()->{var files=mock(ProductSourceEvidenceFiles.class);when(files.verified(anyString(),anyString())).thenThrow(new IllegalStateException("MODEL_APPROVAL_EVIDENCE_REQUIRED"));return files;})
        .withBean(ProductGrantWatermark.class,()->mock(ProductGrantWatermark.class))
        .withBean(ProductComparisonCandidateExecution.class,()->mock(ProductComparisonCandidateExecution.class))
        .withBean(ProductComparisonWorkerMaterials.class,()->mock(ProductComparisonWorkerMaterials.class));}
    @Test void defaultGraphReadsAndValidatesWithoutModelCredentialOrApproval(){reader().run(c->{
        assertThat(c).hasNotFailed().hasSingleBean(ProductComparisonMatrixReader.class).hasSingleBean(ProductComparisonMatrixStore.class)
            .hasSingleBean(ProductComparisonMatrixCalculation.class).doesNotHaveBean(ProductComparisonModelAdmission.class).doesNotHaveBean(ProductComparisonMatrixExecution.class);
    });}
    @Test void explicitCalculationOptInKeepsRequiredModelDeploymentValidation(){reader().withPropertyValues("product.consultation.comparison.calculation.enabled=true").run(c->{assertThat(c).hasFailed();});}
}
