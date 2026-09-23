package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.answer.adapter.out.openai.OpenAiConsultationAnswerAdapter;
import cn.xinji.agentplatform.consultation.application.ConsultationAnswerGenerator;
import cn.xinji.agentplatform.consultation.application.ConsultationAnswerModelPort;
import cn.xinji.agentplatform.consultation.application.ModelChainConsultationAnswerGenerator;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry.RegisteredModel;
import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConsultationAgentModelProperties.class)
public class ConsultationAgentModelConfiguration {
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("OPENAI_CHAT_COMPLETIONS");
    private static final Set<String> ALLOWED_SWITCH_FAILURES = Set.of(
            "TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE");

    @Bean
    ConsultationAnswerModelPort consultationAnswerModelPort(
            ManagedModelConfigurationService managedModels, ObjectMapper mapper) {
        return new OpenAiConsultationAnswerAdapter(managedModels::endpoint, mapper);
    }

    @Bean
    ConsultationAnswerGenerator consultationAnswerGenerator(
            ConsultationAgentModelProperties properties,
            ConsultationAgentModelRegistry registry,
            ManagedModelConfigurationService managedModels,
            ConsultationAnswerModelPort modelPort) {
        ConsultationAgentModelProperties.RuntimeSettings settings = properties.getRuntime();
        if (settings != null && settings.isEnabled()) {
            if (managedModels.hasManagedModels()) {
                List<String> fallbackChain = normalized(settings.getModelChain(), "consultation-agent.runtime.model-chain");
                Set<String> switchOn = Set.copyOf(normalized(settings.getSwitchOn(), "consultation-agent.runtime.switch-on"));
                if (!ALLOWED_SWITCH_FAILURES.containsAll(switchOn)) {
                    throw new IllegalArgumentException("consultation-agent.runtime.switch-on contains a non-switchable failure");
                }
                return new ModelChainConsultationAnswerGenerator(
                        () -> managedModels.runtimeModelChain(fallbackChain),
                        modelPort, new ModelFailoverRouter(), switchOn);
            }
            RuntimeConfiguration runtime = validateRuntime(settings, registry.models());
            return new ModelChainConsultationAnswerGenerator(
                    () -> managedModels.runtimeModelChain(runtime.modelChain()),
                    modelPort, new ModelFailoverRouter(), runtime.switchOn());
        }
        ModelChainConsultationAnswerGenerator managedGenerator = new ModelChainConsultationAnswerGenerator(
                () -> managedModels.runtimeModelChain(List.of()), modelPort,
                new ModelFailoverRouter(), ALLOWED_SWITCH_FAILURES);
        ConsultationAnswerGenerator deterministic = ConsultationAnswerGenerator.deterministic();
        return (consultationId, question, candidates, actualScope) -> managedModels.runtimeModelChain(List.of()).isEmpty()
                ? deterministic.generate(consultationId, question, candidates, actualScope)
                : managedGenerator.generate(consultationId, question, candidates, actualScope);
    }

    static RuntimeConfiguration validateRuntime(
            ConsultationAgentModelProperties.RuntimeSettings runtime, List<RegisteredModel> registeredModels) {
        List<String> chain = normalized(runtime.getModelChain(), "consultation-agent.runtime.model-chain");
        if (new HashSet<>(chain).size() != chain.size()) {
            throw new IllegalArgumentException("consultation-agent.runtime.model-chain must not contain duplicates");
        }
        Set<String> switchOn = Set.copyOf(normalized(runtime.getSwitchOn(), "consultation-agent.runtime.switch-on"));
        if (!ALLOWED_SWITCH_FAILURES.containsAll(switchOn)) {
            throw new IllegalArgumentException("consultation-agent.runtime.switch-on contains a non-switchable failure");
        }
        Map<String, RegisteredModel> byId = registeredModels.stream().collect(
                java.util.stream.Collectors.toMap(RegisteredModel::modelId, model -> model));
        for (String modelId : chain) {
            RegisteredModel model = byId.get(modelId);
            if (model == null) throw new IllegalArgumentException("Runtime model is not registered: " + modelId);
            if (!model.enabled() || !model.configured()) {
                throw new IllegalArgumentException("Runtime model is not enabled and configured: " + modelId);
            }
        }
        return new RuntimeConfiguration(chain, switchOn);
    }

    static List<RegisteredModel> buildModels(ConsultationAgentModelProperties properties) {
        if (properties.getModels() == null || properties.getModels().isEmpty()) {
            throw new IllegalArgumentException("consultation-agent.models must contain at least one approved model");
        }
        Set<String> ids = new HashSet<>();
        return properties.getModels().entrySet().stream().map(entry -> {
            var value = entry.getValue();
            String prefix = "consultation-agent.models." + entry.getKey();
            String modelId = required(value.getModelId(), prefix + ".model-id");
            if (!ids.add(modelId)) throw new IllegalArgumentException("Duplicate consultation model id: " + modelId);
            String provider = required(value.getProvider(), prefix + ".provider");
            String modelName = required(value.getModelName(), prefix + ".model-name");
            String displayName = required(value.getDisplayName(), prefix + ".display-name");
            String protocol = required(value.getProtocol(), prefix + ".protocol").toUpperCase();
            String dataChannel = required(value.getDataChannel(), prefix + ".data-channel");
            String endpoint = value.getEndpoint() == null ? "" : value.getEndpoint().trim();
            if (value.isEnabled()) validateEndpoint(endpoint, prefix + ".endpoint");
            boolean protocolSupported = SUPPORTED_PROTOCOLS.contains(protocol);
            boolean credentialConfigured = value.getApiKey() != null && !value.getApiKey().isBlank();
            boolean configured = value.isEnabled() && protocolSupported && credentialConfigured;
            String availability = availability(value.isEnabled(), protocolSupported, credentialConfigured);
            return new RegisteredModel(modelId, provider, modelName, displayName, value.isEnabled(), configured,
                    dataChannel, availability);
        }).toList();
    }

    private static String availability(boolean enabled, boolean protocolSupported, boolean credentialConfigured) {
        if (!enabled) return "模型已停用";
        if (!protocolSupported) return "调用协议尚未支持";
        if (!credentialConfigured) return "服务端密钥尚未配置";
        return "后端配置完整";
    }

    private static void validateEndpoint(String endpoint, String field) {
        try {
            URI uri = URI.create(required(endpoint, field));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean localHttp = "http".equalsIgnoreCase(scheme)
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
            if (!("https".equalsIgnoreCase(scheme) || localHttp)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an https URL or a loopback http URL");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    private static List<String> normalized(List<String> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be empty");
        List<String> normalized = values.stream().map(value -> required(value, field)).toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return normalized;
    }

    record RuntimeConfiguration(List<String> modelChain, Set<String> switchOn) {}
}
