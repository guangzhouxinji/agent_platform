package cn.xinji.agentplatform.security.product;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public class ProductGrantWatermarkConfiguration {
    @Bean ProductGrantWatermark productGrantWatermark(JdbcTemplate db,
            @Value("${spring.datasource.url:}") String primaryUrl,
            @Value("${product.identity.authority.jdbc-url:}") String authorityUrl,
            @Value("${product.identity.authority.schema:}") String authoritySchema,
            @Value("${product.identity.authority.username:}") String authorityUser,
            @Value("${product.identity.authority.password:}") String authorityPassword,
            @Value("${product.identity.issuer:}") String issuer) {
        if (authorityUrl.isBlank() || authorityUrl.equals(primaryUrl) || authorityUser.isBlank()
                || authoritySchema == null || !authoritySchema.matches("[A-Za-z][A-Za-z0-9_]{2,63}"))
            throw new IllegalArgumentException("Independent authorization datasource is required");
        DriverManagerDataSource source = new DriverManagerDataSource(authorityUrl, authorityUser, authorityPassword) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection = super.getConnection();
                try {
                    connection.setSchema(authoritySchema.toUpperCase(java.util.Locale.ROOT));
                    return connection;
                } catch (java.sql.SQLException failure) {
                    connection.close();
                    throw failure;
                }
            }
        };
        return new ProductGrantWatermark(db, new JdbcTemplate(source), issuer);
    }

    @Bean ProductMaintenanceProof productMaintenanceProof(ProductGrantWatermark watermark) {
        JdbcTemplate independent = watermark.independentDatabase();
        return new ProductMaintenanceProof(independent,
                new TransactionTemplate(new DataSourceTransactionManager(independent.getDataSource())));
    }
}
