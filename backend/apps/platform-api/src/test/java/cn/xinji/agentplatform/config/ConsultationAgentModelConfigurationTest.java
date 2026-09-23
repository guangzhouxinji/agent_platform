package cn.xinji.agentplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultationAgentModelConfigurationTest {
    @Test void computesAvailabilityFromEachModelsOwnConfiguration() {
        var properties = new ConsultationAgentModelProperties();
        var models = new LinkedHashMap<String, ConsultationAgentModelProperties.ModelRegistration>();
        models.put("ready", model("deepseek:chat", "https://api.deepseek.com/chat", "key-a", true));
        models.put("missing-key", model("qwen:max", "https://example.invalid/chat", "", true));
        models.put("disabled", model("local:disabled", "", "key-b", false));
        properties.setModels(models);

        var result = ConsultationAgentModelConfiguration.buildModels(properties);

        assertThat(result).extracting(item -> item.modelId() + ":" + item.configured())
                .containsExactly("deepseek:chat:true", "qwen:max:false", "local:disabled:false");
        assertThat(result.get(1).availability()).isEqualTo("服务端密钥尚未配置");
        assertThat(result.get(2).availability()).isEqualTo("模型已停用");
    }

    @Test void rejectsDuplicateIdsAndUnsafeEndpoints() {
        var duplicate = new ConsultationAgentModelProperties();
        var duplicateModels = new LinkedHashMap<String, ConsultationAgentModelProperties.ModelRegistration>();
        duplicateModels.put("one", model("same:model", "https://one.invalid/chat", "key", true));
        duplicateModels.put("two", model("same:model", "https://two.invalid/chat", "key", true));
        duplicate.setModels(duplicateModels);
        assertThatThrownBy(() -> ConsultationAgentModelConfiguration.buildModels(duplicate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate consultation model id");

        var unsafe = new ConsultationAgentModelProperties();
        var unsafeModels = new LinkedHashMap<String, ConsultationAgentModelProperties.ModelRegistration>();
        unsafeModels.put("unsafe", model("unsafe:model", "http://models.example.com/chat", "key", true));
        unsafe.setModels(unsafeModels);
        assertThatThrownBy(() -> ConsultationAgentModelConfiguration.buildModels(unsafe))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be an https URL");
    }

    @Test void acceptsConfiguredModelsAndApprovedSwitchFailures() {
        var properties = runtimeProperties("deepseek:chat", "key", "EXTERNAL_MODEL_ALLOWED");
        var runtime = properties.getRuntime();
        runtime.setModelChain(List.of("deepseek:chat"));
        runtime.setSwitchOn(List.of("TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE"));

        var result = ConsultationAgentModelConfiguration.validateRuntime(
                runtime, ConsultationAgentModelConfiguration.buildModels(properties));

        assertThat(result.modelChain()).containsExactly("deepseek:chat");
        assertThat(result.switchOn()).containsExactlyInAnyOrder("TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE");
    }

    @Test void rejectsUnknownOrUnconfiguredRuntimeModels() {
        var valid = runtimeProperties("deepseek:chat", "key", "PUBLIC_LOCAL");
        valid.getRuntime().setModelChain(List.of("unknown:model"));
        assertThatThrownBy(() -> ConsultationAgentModelConfiguration.validateRuntime(
                valid.getRuntime(), ConsultationAgentModelConfiguration.buildModels(valid)))
                .hasMessageContaining("not registered");

        var unconfigured = runtimeProperties("deepseek:chat", "", "PUBLIC_LOCAL");
        assertThatThrownBy(() -> ConsultationAgentModelConfiguration.validateRuntime(
                unconfigured.getRuntime(), ConsultationAgentModelConfiguration.buildModels(unconfigured)))
                .hasMessageContaining("not enabled and configured");

        var externalChannel = runtimeProperties("deepseek:chat", "key", "EXTERNAL_MODEL_ALLOWED");
        assertThat(ConsultationAgentModelConfiguration.validateRuntime(
                externalChannel.getRuntime(), ConsultationAgentModelConfiguration.buildModels(externalChannel)).modelChain())
                .containsExactly("deepseek:chat");
    }

    @Test void rejectsDuplicateChainEntriesAndNonSwitchableFailures() {
        var properties = runtimeProperties("deepseek:chat", "key", "PUBLIC_LOCAL");
        properties.getRuntime().setModelChain(List.of("deepseek:chat", "deepseek:chat"));
        assertThatThrownBy(() -> ConsultationAgentModelConfiguration.validateRuntime(
                properties.getRuntime(), ConsultationAgentModelConfiguration.buildModels(properties)))
                .hasMessageContaining("must not contain duplicates");

        properties.getRuntime().setModelChain(List.of("deepseek:chat"));
        properties.getRuntime().setSwitchOn(List.of("OUTPUT_VALIDATION_FAILED"));
        assertThatThrownBy(() -> ConsultationAgentModelConfiguration.validateRuntime(
                properties.getRuntime(), ConsultationAgentModelConfiguration.buildModels(properties)))
                .hasMessageContaining("non-switchable failure");
    }

    private static ConsultationAgentModelProperties.ModelRegistration model(
            String id, String endpoint, String apiKey, boolean enabled) {
        var value = new ConsultationAgentModelProperties.ModelRegistration();
        value.setModelId(id);
        value.setProvider(id.substring(0, id.indexOf(':')));
        value.setModelName(id.substring(id.indexOf(':') + 1));
        value.setDisplayName(id);
        value.setEndpoint(endpoint);
        value.setApiKey(apiKey);
        value.setEnabled(enabled);
        return value;
    }

    private static ConsultationAgentModelProperties runtimeProperties(String id, String apiKey, String dataChannel) {
        var properties = new ConsultationAgentModelProperties();
        var value = model(id, "https://example.invalid/chat", apiKey, true);
        value.setDataChannel(dataChannel);
        properties.setModels(new LinkedHashMap<>(java.util.Map.of("runtime", value)));
        properties.getRuntime().setEnabled(true);
        properties.getRuntime().setModelChain(List.of(id));
        return properties;
    }
}
