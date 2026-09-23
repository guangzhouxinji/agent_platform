package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.patentassistant.adapter.out.deepseek.DeepSeekPatentAnalysisAdapter;
import cn.xinji.agentplatform.consultation.patentassistant.adapter.out.deepseek.DisabledPatentAnalysisAdapter;
import cn.xinji.agentplatform.consultation.patentassistant.adapter.out.publicpilot.PublicPilotPatentAssistantSourceAdapter;
import cn.xinji.agentplatform.consultation.patentassistant.adapter.out.surechembl.SureChemblPatentAssistantSourceAdapter;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSources;
import cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot.PublicPilotPatentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PatentAssistantConfiguration {
    @Bean
    PatentAssistantSources patentAssistantSources(
            @Value("${patent-assistant.source.provider:surechembl}") String provider,
            @Value("${patent-assistant.source.surechembl.api-base-url:https://surechembl.org/api}") String apiBaseUrl,
            @Value("${patent-assistant.source.surechembl.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${patent-assistant.source.surechembl.request-timeout-ms:15000}") int requestTimeoutMs,
            @Value("${patent-assistant.source.surechembl.result-limit:10}") int resultLimit,
            PublicPilotPatentLoader loader,
            ObjectMapper mapper) {
        PatentAssistantSourcePort analysisSource = new PublicPilotPatentAssistantSourceAdapter(loader);
        PatentAssistantSourcePort searchSource;
        if ("public-static".equalsIgnoreCase(provider)) {
            searchSource = new PublicPilotPatentAssistantSourceAdapter(loader);
        } else if ("surechembl".equalsIgnoreCase(provider)) {
            searchSource = new SureChemblPatentAssistantSourceAdapter(apiBaseUrl, connectTimeoutMs, requestTimeoutMs,
                    resultLimit, mapper);
        } else {
            throw new IllegalArgumentException("Unsupported patent-assistant.source.provider: " + provider);
        }
        return new PatentAssistantSources(analysisSource, searchSource);
    }

    @Bean
    PatentAnalysisPort patentAnalysisPort(
            @Value("${patent-assistant.model.provider:deepseek}") String provider,
            @Value("${patent-assistant.model.endpoint:https://api.deepseek.com/chat/completions}") String endpoint,
            @Value("${patent-assistant.model.api-key:}") String apiKey,
            @Value("${patent-assistant.model.name:deepseek-chat}") String model,
            ObjectMapper mapper) {
        if (!"deepseek".equalsIgnoreCase(provider)) return new DisabledPatentAnalysisAdapter(model);
        return new DeepSeekPatentAnalysisAdapter(endpoint, apiKey, model, mapper);
    }
}
