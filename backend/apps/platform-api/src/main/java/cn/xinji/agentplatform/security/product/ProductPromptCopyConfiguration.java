package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** COPY column probes do not certify installation of the V010 constraints. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.prompt.copy-enabled",havingValue="true")
class ProductPromptCopyConfiguration {
    @Bean ProductPromptCopyApplication productPromptCopyApplication(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark existingWatermark,ObjectMapper mapper){
        return ProductPromptWriteComponents.assembleCopy(env,db,manager,existingWatermark,mapper);
    }
}
