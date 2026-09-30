package cn.xinji.agentplatform.security.product;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Final consultation runtime, isolated from legacy development catalog wiring. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = ProductIssuerConfiguration.class)
public class ProductConsultationApplication { }
