package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.security.product.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;

/** Independently deployable ADJ runtime, no consultation/enforcement business component scan. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="adjudication.v2.enabled",havingValue="true")
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses=CaseWorkConfiguration.class)
@Import({cn.xinji.agentplatform.runtime.AgentRuntimeConfiguration.class,cn.xinji.agentplatform.runtime.AgentRunPersistence.class,ProductIssuerConfiguration.class,ProductGrantWatermarkConfiguration.class,ProductIdentityRouteSecurityConfiguration.class,
        ExistingRouteSecurityConfiguration.class,ProductIdentityTransportFilter.class,ProductClientController.class})
public class ProductAdjudicationApplication {
    public static void main(String[] args){SpringApplication.run(ProductAdjudicationApplication.class,args);}
}
