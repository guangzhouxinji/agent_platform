package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.LocalKnowledgeBaseImporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeBaseConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "knowledge-base.import.enabled", havingValue = "true")
    LocalKnowledgeBaseImporter localKnowledgeBaseImporter(DataSource dataSource, ObjectMapper mapper) {
        return new LocalKnowledgeBaseImporter(dataSource, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "knowledge-base.import.enabled", havingValue = "true")
    ApplicationRunner localKnowledgeBaseImportRunner(LocalKnowledgeBaseImporter importer) {
        return args -> {
            var result = importer.importAll();
            log.info("Local knowledge base import complete: patents={}, trademarks={}, cases={}",
                    result.patentsImported(), result.trademarksImported(), result.casesImported());
        };
    }
}
