package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.enforcement.jobpack.EnforcementJobPackConfiguration;
import cn.xinji.agentplatform.enforcement.documenttemplate.EnforcementDocumentTemplateConfiguration;
import cn.xinji.agentplatform.enforcement.legalreference.EnforcementLegalReferenceConfiguration;
import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.web.ReactFrontendController;
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
        EnforcementLegalReferenceConfiguration.class
})
@Import({
        ProductIssuerConfiguration.class,
        ProductGrantWatermarkConfiguration.class,
        ProductConsultationTaskConfiguration.class,
        ProductIdentityRouteSecurityConfiguration.class,
        ExistingRouteSecurityConfiguration.class,
        ProductIdentityTransportFilter.class,
        ProductIdentityController.class,
        ProductClientController.class,
        ReactFrontendController.class
})
public class ProductEnforcementApplication { }
