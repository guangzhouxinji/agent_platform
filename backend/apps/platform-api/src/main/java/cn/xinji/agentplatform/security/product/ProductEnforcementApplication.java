package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.enforcement.jobpack.EnforcementJobPackConfiguration;
import cn.xinji.agentplatform.enforcement.documenttemplate.EnforcementDocumentTemplateConfiguration;
import cn.xinji.agentplatform.enforcement.legalreference.EnforcementLegalReferenceConfiguration;
import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.enforcement.procedure.EnforcementProcedureConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** Final enforcement runtime, isolated from consultation and legacy development wiring. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {
        EnforcementMaterialConfiguration.class,
        EnforcementJobPackConfiguration.class,
        EnforcementDocumentTemplateConfiguration.class,
        EnforcementProcedureConfiguration.class,
        cn.xinji.agentplatform.enforcement.evidence.EnforcementEvidenceRuleConfiguration.class,
        cn.xinji.agentplatform.enforcement.conduct.EnforcementConductConfiguration.class,
        EnforcementLegalReferenceConfiguration.class,
        cn.xinji.agentplatform.enforcement.documentrule.EnforcementDocumentRuleConfiguration.class,
        cn.xinji.agentplatform.enforcement.documentdraft.EnforcementDocumentDraftConfiguration.class,
        cn.xinji.agentplatform.enforcement.penalty.EnforcementPenaltyConfiguration.class
})
@Import({cn.xinji.agentplatform.runtime.AgentRuntimeConfiguration.class,cn.xinji.agentplatform.runtime.AgentRunPersistence.class,
        cn.xinji.agentplatform.enforcement.deployment.EnforcementControlledReadDeployment.class,
        ProductIssuerConfiguration.class,
        ProductGrantWatermarkConfiguration.class,
        ProductConsultationTaskConfiguration.class,
        ProductIdentityRouteSecurityConfiguration.class,
        ExistingRouteSecurityConfiguration.class,
        ProductIdentityTransportFilter.class,
        ProductIdentityController.class,
        ProductClientController.class,
        cn.xinji.agentplatform.enforcement.deployment.entry.ProductEnforcementWorkspacePageController.class,
        cn.xinji.agentplatform.enforcement.deployment.entry.ProductEnforcementEntryConfiguration.class
})
public class ProductEnforcementApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(ProductEnforcementApplication.class, args);
    }
}
