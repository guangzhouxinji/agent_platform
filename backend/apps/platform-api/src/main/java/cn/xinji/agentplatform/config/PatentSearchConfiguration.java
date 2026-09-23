package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.dm8.Dm8PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.memory.InMemoryPatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchApplicationService;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentCatalogPort;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.HttpMockCenterCatalogClient;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterCatalogClient;
import cn.xinji.agentplatform.consultation.adapter.out.mockcenter.MockCenterConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.consultation.TaskBackedConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8KnowledgeBaseJsonReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.memory.InMemoryTrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.dm8.Dm8TrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkSearchTaskRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;

@Configuration
public class PatentSearchConfiguration {
    @Bean("patentSearchCatalog")
    @Profile("test-synthetic")
    PatentCatalogPort testSyntheticPatentSearchCatalog(SyntheticPatentCatalogAdapter adapter) {
        return new PatentCatalogPort() {
            @Override public CatalogDescriptor descriptor() { return adapter.descriptor(); }
            @Override public CatalogResult search(cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand command) {
                return adapter.search(command);
            }
        };
    }

    @Bean("patentSearchCatalog")
    @Profile("!test-synthetic")
    @ConditionalOnProperty(name = "patent-search.catalog", havingValue = "synthetic")
    PatentCatalogPort syntheticPatentSearchCatalog(SyntheticPatentCatalogAdapter adapter) {
        return new PatentCatalogPort() {
            @Override public CatalogDescriptor descriptor() { return adapter.descriptor(); }
            @Override public CatalogResult search(cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand command) {
                return adapter.search(command);
            }
        };
    }

    @Bean("patentSearchCatalog")
    @ConditionalOnProperty(name = "patent-search.catalog", havingValue = "mock-center")
    PatentCatalogPort mockCenterPatentSearchCatalog(MockCenterPatentCatalogAdapter catalog) {
        return catalog;
    }

    @Bean
    @ConditionalOnProperty(name = "consultation.search.provider", havingValue = "mock-center")
    PatentSearchPort mockCenterConsultationPatentSearch(MockCenterPatentCatalogAdapter catalog, PatentAnalysisPort analysis) {
        return new MockCenterConsultationPatentSearchAdapter(catalog, analysis);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation.search.provider", havingValue = "database")
    PatentSearchPort databaseConsultationPatentSearch(
            Dm8KnowledgeBaseJsonReader reader,
            @Value("${knowledge-base.provider:dm8}") String knowledgeBaseProvider) {
        if (!"dm8".equalsIgnoreCase(knowledgeBaseProvider)) {
            throw new IllegalArgumentException(
                    "consultation.search.provider=database requires knowledge-base.provider=dm8");
        }
        return new Dm8ConsultationPatentSearchAdapter(reader);
    }

    @Bean
    @ConditionalOnProperty(name = "consultation.search.provider", havingValue = "task")
    PatentSearchPort taskBackedConsultationPatentSearch(PatentSearchApplicationService service) {
        return new TaskBackedConsultationPatentSearchAdapter(service);
    }

    @Bean
    MockCenterCatalogClient mockCenterCatalogClient(ObjectMapper mapper,
            @Value("${center.patent.base-url:http://127.0.0.1:18082}") String baseUrl,
            @Value("${center.patent.connect-timeout-ms:3000}") long connectTimeout,
            @Value("${center.patent.request-timeout-ms:10000}") long requestTimeout) {
        return new HttpMockCenterCatalogClient(baseUrl, mapper, Duration.ofMillis(connectTimeout), Duration.ofMillis(requestTimeout));
    }

    @Bean
    MockCenterPatentCatalogAdapter mockCenterPatentCatalogAdapter(MockCenterCatalogClient client) {
        return new MockCenterPatentCatalogAdapter(client);
    }

    @Bean(destroyMethod = "close")
    Executor patentSearchExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }

    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(name = "patent-search.storage", havingValue = "memory", matchIfMissing = true)
    PatentSearchTaskRepository inMemoryPatentSearchTaskRepository() {
        return new InMemoryPatentSearchTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "patent-search.storage", havingValue = "dm8")
    PatentSearchTaskRepository dm8PatentSearchTaskRepository(DataSource dataSource) {
        return new Dm8PatentSearchTaskRepository(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }

    @Bean
    @ConditionalOnProperty(name = "trademark-search.storage", havingValue = "memory", matchIfMissing = true)
    TrademarkSearchTaskRepository inMemoryTrademarkSearchTaskRepository() {
        return new InMemoryTrademarkSearchTaskRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "trademark-search.storage", havingValue = "dm8")
    TrademarkSearchTaskRepository dm8TrademarkSearchTaskRepository(DataSource dataSource) {
        return new Dm8TrademarkSearchTaskRepository(() -> {
            try { return dataSource.getConnection(); }
            catch (SQLException exception) { throw new IllegalStateException("DM8 connection unavailable", exception); }
        });
    }
}
