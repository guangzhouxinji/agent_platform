package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Optional CREATE assembly; SAVE has its own independent activation switch. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.prompt.create-enabled",havingValue="true")
class ProductPromptCreateConfiguration {
    @Bean ProductPromptCreateApplication productPromptCreateApplication(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark existingWatermark,ObjectMapper mapper){
        var c=ProductPromptWriteComponents.assemble(env,db,manager,existingWatermark,mapper);
        return new ProductPromptCreateApplication(c.store(),c.authority(),c.requests(),c.authorization(),c.input(),c.registry(),
                c.mainManager(),c.authorityManager(),c.clock());
    }
}
