package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementEvidenceStore;
import cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnforcementEvidenceConfiguration {
    @Bean
    EnforcementEvidenceService enforcementEvidenceService(
            ObjectProvider<DataSource> dataSources,
            ObjectProvider<Clock> clocks,
            @Value("${enforcement.evidence.allowed-media-types:application/pdf,image/png,image/jpeg,text/plain}") String allowed,
            @Value("${enforcement.evidence.previewable-media-types:application/pdf,image/png,image/jpeg,text/plain}") String previewable,
            @Value("${enforcement.evidence.max-bytes:10485760}") long maxBytes) {
        DataSource dataSource = dataSources.getIfAvailable();
        return new EnforcementEvidenceService(
                dataSource == null ? null : new Dm8EnforcementEvidenceStore(dataSource),
                clocks.getIfAvailable(Clock::systemUTC),
                new EnforcementEvidenceService.Policy(mediaTypes(allowed), mediaTypes(previewable), maxBytes));
    }

    private static Set<String> mediaTypes(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
