package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.EnforcementLocalSourceImporter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.AuthorizedEnforcementPackageImporter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceManagementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementDiscoveryRuleStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementClueDiscoveryStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementClueParsingStore;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.DevelopmentEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.V0EnforcementAuthorizationAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementAuthorizationPort;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService;
import cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService;
import cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.time.Clock;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class EnforcementApplicationConfiguration {
    @Bean
    @ConditionalOnMissingBean(EnforcementAuthorizationPort.class)
    EnforcementAuthorizationPort enforcementAuthorizationPort() {
        return new V0EnforcementAuthorizationAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(EnforcementSourcePort.class)
    @Profile("test-synthetic")
    SyntheticEnforcementSourceAdapter enforcementSourcePort() {
        return new SyntheticEnforcementSourceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(EnforcementSourcePort.class)
    @Profile("!test-synthetic")
    @ConditionalOnProperty(name = "enforcement.source", havingValue = "database", matchIfMissing = true)
    EnforcementSourcePort databaseEnforcementSourcePort(
            ObjectProvider<DataSource> dataSources, ObjectMapper mapper) {
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? unavailableSource() : new Dm8EnforcementSourceAdapter(dataSource, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(EnforcementSourcePort.class)
    @Profile("!test-synthetic")
    @ConditionalOnProperty(name = "enforcement.source", havingValue = "unavailable")
    EnforcementSourcePort unavailableEnforcementSourcePort() {
        return unavailableSource();
    }

    private static EnforcementSourcePort unavailableSource() {
        return new EnforcementSourcePort() {
            @Override public Optional<ClueData> findClue(String clueId, String version) { return Optional.empty(); }
            @Override public Optional<EvidenceData> findEvidence(String evidenceId) { return Optional.empty(); }
            @Override public cn.xinji.agentplatform.enforcement.domain.SourceLocator activeRule() { throw unavailable(); }
            @Override public Optional<cn.xinji.agentplatform.enforcement.domain.SourceLocator> historicalRightStatus(String clueId) { return Optional.empty(); }
            private IllegalStateException unavailable() { return new IllegalStateException("LOCAL_SOURCE_NOT_CONFIGURED"); }
        };
    }

    @Bean
    @Profile("test-synthetic")
    @ConditionalOnProperty(name = "enforcement.source-import.enabled", havingValue = "true")
    EnforcementLocalSourceImporter enforcementLocalSourceImporter(DataSource dataSource, ObjectMapper mapper) {
        return new EnforcementLocalSourceImporter(dataSource, mapper);
    }

    @Bean
    @Profile("test-synthetic")
    @ConditionalOnProperty(name = "enforcement.source-import.enabled", havingValue = "true")
    ApplicationRunner enforcementLocalSourceImportRunner(EnforcementLocalSourceImporter importer) {
        return args -> importer.importSyntheticBaseline();
    }

    @Bean
    @ConditionalOnProperty(name = "enforcement.authorized-import.enabled", havingValue = "true")
    AuthorizedEnforcementPackageImporter authorizedEnforcementPackageImporter(
            DataSource dataSource, ObjectMapper mapper,
            @Value("${enforcement.authorized-import.directory:}") String directory) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("enforcement.authorized-import.directory is required");
        }
        return new AuthorizedEnforcementPackageImporter(dataSource, mapper, Path.of(directory));
    }

    @Bean
    @ConditionalOnProperty(name = "enforcement.authorized-import.enabled", havingValue = "true")
    ApplicationRunner authorizedEnforcementPackageImportRunner(AuthorizedEnforcementPackageImporter importer) {
        return args -> importer.importPackage();
    }

    @Bean
    EnforcementApplicationService enforcementApplicationService(
            EnforcementStore store,
            EnforcementAuthorizationPort authorization,
            EnforcementSourcePort sources) {
        return new EnforcementApplicationService(store, authorization, sources);
    }

    @Bean
    @Profile("test-synthetic")
    EnforcementSourceManagementService enforcementSourceManagementService(
            ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> mappers,
            ObjectProvider<Clock> clocks) {
        DataSource dataSource = dataSources.getIfAvailable();
        ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
        return new EnforcementSourceManagementService(
                dataSource == null ? null : new Dm8EnforcementSourceManagementStore(dataSource, mapper),
                new DevelopmentEnforcementSourceAdapter(), clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    EnforcementDiscoveryRuleService enforcementDiscoveryRuleService(
            ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> mappers,
            ObjectProvider<Clock> clocks) {
        DataSource dataSource = dataSources.getIfAvailable();
        ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
        return new EnforcementDiscoveryRuleService(
                dataSource == null ? null : new Dm8EnforcementDiscoveryRuleStore(dataSource, mapper),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    EnforcementClueDiscoveryService enforcementClueDiscoveryService(
            ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> mappers,
            ObjectProvider<Clock> clocks) {
        DataSource dataSource = dataSources.getIfAvailable();
        ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
        return new EnforcementClueDiscoveryService(
                dataSource == null ? null : new Dm8EnforcementClueDiscoveryStore(dataSource, mapper),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    EnforcementClueParsingService enforcementClueParsingService(
            ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> mappers,
            ObjectProvider<Clock> clocks) {
        DataSource dataSource = dataSources.getIfAvailable();
        ObjectMapper mapper = mappers.getIfAvailable(ObjectMapper::new);
        return new EnforcementClueParsingService(
                dataSource == null ? null : new Dm8EnforcementClueParsingStore(dataSource, mapper),
                clocks.getIfAvailable(Clock::systemUTC));
    }

}
