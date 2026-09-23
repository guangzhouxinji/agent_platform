package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8PublicEnforcementReferenceCatalogAdapter;
import cn.xinji.agentplatform.enforcement.application.PublicEnforcementReferenceCatalogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublicEnforcementReferenceCatalogConfiguration {
    @Bean
    @ConditionalOnMissingBean(PublicEnforcementReferenceCatalogPort.class)
    PublicEnforcementReferenceCatalogPort publicEnforcementReferenceCatalog(
            ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> mappers) {
        DataSource dataSource = dataSources.getIfAvailable();
        if (dataSource == null) return new PublicEnforcementReferenceCatalogPort() {};
        ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
        return new Dm8PublicEnforcementReferenceCatalogAdapter(dataSource, mapper);
    }
}
