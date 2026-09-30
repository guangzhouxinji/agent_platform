package cn.xinji.agentplatform.enforcement.evidence;

import cn.xinji.agentplatform.enforcement.material.EnforcementMaterialConfiguration;
import cn.xinji.agentplatform.security.product.ExistingRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductGrantWatermarkConfiguration;
import cn.xinji.agentplatform.security.product.ProductIdentityRouteSecurityConfiguration;
import cn.xinji.agentplatform.security.product.ProductIdentityTransportFilter;
import cn.xinji.agentplatform.security.product.ProductIssuerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
        ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
        ProductIdentityTransportFilter.class, EnforcementMaterialConfiguration.class,
        EnforcementEvidenceRuleConfiguration.class, EnforcementEvidenceRuleController.class,
        EnforcementEvidenceOrganizationController.class})
public class EvidenceIndependentHttpNode {
    public static void main(String[] args) { SpringApplication.run(EvidenceIndependentHttpNode.class, args); }
}
