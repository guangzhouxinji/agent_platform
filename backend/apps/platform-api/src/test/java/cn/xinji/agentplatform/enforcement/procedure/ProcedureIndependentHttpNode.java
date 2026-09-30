package cn.xinji.agentplatform.enforcement.procedure;

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
        EnforcementProcedureConfiguration.class, EnforcementProcedureController.class})
public class ProcedureIndependentHttpNode {
    public static void main(String[] args) { SpringApplication.run(ProcedureIndependentHttpNode.class, args); }
}
