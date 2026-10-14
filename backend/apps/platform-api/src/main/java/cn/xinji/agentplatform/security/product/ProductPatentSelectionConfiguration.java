package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"}, havingValue = "true")
class ProductPatentSelectionConfiguration {
    @Bean ProductPatentSelection productPatentSelection(JdbcTemplate db, PlatformTransactionManager manager,
            ProductPatentSearchExecution searches, ProductTaskDirectory tasks, ProductConsultationAccess access) {
        return new ProductPatentSelection(db, new TransactionTemplate(manager), searches, tasks, access,
                Clock.systemUTC());
    }
}
