package cn.xinji.agentplatform.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "consultation-agent")
public class ConsultationAgentModelProperties {
    private Map<String, ModelRegistration> models = defaultModels();
    private RuntimeSettings runtime = new RuntimeSettings();

    public Map<String, ModelRegistration> getModels() { return models; }
    public void setModels(Map<String, ModelRegistration> models) { this.models = models; }
    public RuntimeSettings getRuntime() { return runtime; }
    public void setRuntime(RuntimeSettings runtime) { this.runtime = runtime; }

    private static Map<String, ModelRegistration> defaultModels() {
        var model = new ModelRegistration();
        model.setModelId("deepseek:deepseek-chat");
        model.setProvider("deepseek");
        model.setModelName("deepseek-chat");
        model.setDisplayName("DeepSeek Chat");
        model.setEndpoint("https://api.deepseek.com/chat/completions");
        var values = new LinkedHashMap<String, ModelRegistration>();
        values.put("deepseek-chat", model);
        return values;
    }

    public static class ModelRegistration {
        private String modelId;
        private String provider;
        private String modelName;
        private String displayName;
        private String endpoint;
        private String apiKey;
        private boolean enabled = true;
        private String protocol = "OPENAI_CHAT_COMPLETIONS";
        private String dataChannel = "APPROVED_BACKEND_REFERENCE";

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getDataChannel() { return dataChannel; }
        public void setDataChannel(String dataChannel) { this.dataChannel = dataChannel; }
    }

    public static class RuntimeSettings {
        private boolean enabled;
        private List<String> modelChain = List.of("deepseek:deepseek-chat");
        private List<String> switchOn = List.of("TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getModelChain() { return modelChain; }
        public void setModelChain(List<String> modelChain) { this.modelChain = modelChain; }
        public List<String> getSwitchOn() { return switchOn; }
        public void setSwitchOn(List<String> switchOn) { this.switchOn = switchOn; }
    }
}
