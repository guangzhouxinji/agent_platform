package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceFactory;
import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public class ProductGrantWatermarkConfiguration implements DisposableBean {
    private final AuthorityDataSourceFactory authorityConnections = new AuthorityDataSourceFactory();

    @Bean ProductGrantWatermark productGrantWatermark(JdbcTemplate db,
            @Value("${spring.datasource.url:}") String primaryUrl,
            @Value("${spring.datasource.hikari.schema:}") String primarySchema,
            @Value("${spring.datasource.username:}") String primaryUser,
            @Value("${product.identity.authority.topology:independent}") String topology,
            @Value("${product.identity.authority.jdbc-url:}") String authorityUrl,
            @Value("${product.identity.authority.schema:}") String authoritySchema,
            @Value("${product.identity.authority.username:}") String authorityUser,
            @Value("${product.identity.authority.password:}") String authorityPassword,
            @Value("${product.identity.issuer:}") String issuer) {
        var settings = AuthorityDataSourceSettings.resolve(topology, primaryUrl, primarySchema,
                primaryUser, authorityUrl, authoritySchema, authorityUser, authorityPassword);
        return authorityConnections.open(settings,
                source -> new ProductGrantWatermark(db, new JdbcTemplate(source), issuer));
    }

    // Preserve existing explicit independent-mode callers; only the annotated method is a bean.
    ProductGrantWatermark productGrantWatermark(JdbcTemplate db, String primaryUrl,
            String authorityUrl, String authoritySchema, String authorityUser,
            String authorityPassword, String issuer) {
        return productGrantWatermark(db, primaryUrl, "", "", "independent", authorityUrl,
                authoritySchema, authorityUser, authorityPassword, issuer);
    }

    @Bean ProductMaintenanceProof productMaintenanceProof(ProductGrantWatermark watermark) {
        JdbcTemplate authority = watermark.independentDatabase();
        return new ProductMaintenanceProof(authority,
                new TransactionTemplate(new DataSourceTransactionManager(authority.getDataSource())));
    }
    @Override public void destroy() { authorityConnections.close(); }
}
