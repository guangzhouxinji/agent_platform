package cn.xinji.mockcenter;

import cn.xinji.mockcenter.adapter.out.dm8.Dm8MockCenterPatentCatalog;
import cn.xinji.mockcenter.application.MockCenterPatentCatalogImporter;
import cn.xinji.mockcenter.application.MockCenterPublicPatentSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test-synthetic")
@ConditionalOnProperty(name = "mock-center.service.enabled", havingValue = "true")
public class MockCenterConfiguration {
    @Bean
    Dm8MockCenterPatentCatalog mockCenterPatentCatalog(DataSource dataSource, ObjectMapper mapper) {
        return new Dm8MockCenterPatentCatalog(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException failure) { throw new IllegalStateException("DM8 connection unavailable", failure); }
        }, mapper);
    }

    @Bean
    MockCenterPublicPatentSeeder mockCenterPublicPatentSeeder(MockCenterPatentCatalogImporter importer, ObjectMapper mapper) {
        return new MockCenterPublicPatentSeeder(importer, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "mock-center.seed.enabled", havingValue = "true")
    ApplicationRunner seedMockCenterPublicPatentCatalog(MockCenterPublicPatentSeeder seeder) {
        return ignored -> seeder.seed();
    }
}
