package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.AdjudicationLocalSourceImporter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8AdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.V0AdjudicationAuthorizationAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AuthorizationPort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

@Configuration
public class AdjudicationApplicationConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthorizationPort.class)
    AuthorizationPort adjudicationAuthorizationPort() {
        return new V0AdjudicationAuthorizationAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SourcePort.class)
    @Profile("test-synthetic")
    SyntheticAdjudicationSourceAdapter adjudicationSourcePort() {
        return new SyntheticAdjudicationSourceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SourcePort.class)
    @Profile("!test-synthetic")
    @ConditionalOnProperty(name = "adjudication.source", havingValue = "synthetic")
    SyntheticAdjudicationSourceAdapter developmentAdjudicationSourcePort() {
        return new SyntheticAdjudicationSourceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SourcePort.class)
    @Profile("!test-synthetic")
    @ConditionalOnProperty(name = "adjudication.source", havingValue = "database", matchIfMissing = true)
    SourcePort databaseAdjudicationSourcePort(ObjectProvider<DataSource> dataSources, ObjectMapper mapper) {
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? unavailableSource() : new Dm8AdjudicationSourceAdapter(dataSource, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(SourcePort.class)
    @Profile("!test-synthetic")
    @ConditionalOnProperty(name = "adjudication.source", havingValue = "unavailable")
    SourcePort unavailableAdjudicationSourcePort() {
        return unavailableSource();
    }

    private static SourcePort unavailableSource() {
        return new SourcePort() {
            @Override public Optional<cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source> findCase(String id, String version) { return Optional.empty(); }
            @Override public Optional<cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source> findMaterial(String id) { return Optional.empty(); }
            @Override public Optional<AdjudicationApplicationService.MaterialData> materialData(String id) { return Optional.empty(); }
            @Override public List<AdjudicationApplicationService.LegalData> legalCandidates(cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.MaterialInspection inspection) { return List.of(); }
            @Override public cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source template() { throw new IllegalStateException("LOCAL_SOURCE_NOT_CONFIGURED"); }
            @Override public List<cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Section> draftSections(
                    cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.MaterialInspection inspection,
                    List<cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Citation> citations) { return List.of(); }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "adjudication.source-import.enabled", havingValue = "true")
    AdjudicationLocalSourceImporter adjudicationLocalSourceImporter(DataSource dataSource, ObjectMapper mapper) {
        return new AdjudicationLocalSourceImporter(dataSource, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "adjudication.source-import.enabled", havingValue = "true")
    ApplicationRunner adjudicationLocalSourceImportRunner(AdjudicationLocalSourceImporter importer) {
        return args -> importer.importSyntheticBaseline();
    }

    @Bean
    AdjudicationApplicationService adjudicationApplicationService(
            AdjudicationStore store, AuthorizationPort authorization, SourcePort sources) {
        return new AdjudicationApplicationService(store, authorization, sources);
    }
}
