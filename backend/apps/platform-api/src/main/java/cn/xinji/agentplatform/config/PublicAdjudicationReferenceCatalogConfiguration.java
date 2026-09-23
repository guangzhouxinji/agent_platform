package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8PublicAdjudicationReferenceCatalogAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.PublicAdjudicationSourceApprovalRegistry;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublicAdjudicationReferenceCatalogConfiguration {
    @Bean
    @ConditionalOnMissingBean(PublicAdjudicationSourceApprovalRegistry.class)
    PublicAdjudicationSourceApprovalRegistry publicAdjudicationSourceApprovalRegistry() {
        return PublicAdjudicationSourceApprovalRegistry.approvedPublicJudgmentPilot();
    }

    @Bean
    @ConditionalOnMissingBean(PublicAdjudicationReferenceCatalogPort.class)
    PublicAdjudicationReferenceCatalogPort publicAdjudicationReferenceCatalog(
            ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> mappers,
            PublicAdjudicationSourceApprovalRegistry approvals) {
        DataSource dataSource = dataSources.getIfAvailable();
        if (dataSource == null) return new PublicAdjudicationReferenceCatalogPort() {};
        return new Dm8PublicAdjudicationReferenceCatalogAdapter(
                dataSource, mappers.getIfAvailable(ObjectMapper::new), approvals);
    }
}
