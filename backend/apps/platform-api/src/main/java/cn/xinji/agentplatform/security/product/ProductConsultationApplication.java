package cn.xinji.agentplatform.security.product;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Final consultation runtime, isolated from legacy development catalog wiring. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = ProductIssuerConfiguration.class,
        excludeFilters = @ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ANNOTATION,
                classes = SpringBootConfiguration.class))
@org.springframework.context.annotation.Import({cn.xinji.agentplatform.runtime.AgentRuntimeConfiguration.class,cn.xinji.agentplatform.runtime.AgentRunPersistence.class})
public class ProductConsultationApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(ProductConsultationApplication.class, args);
    }
}
