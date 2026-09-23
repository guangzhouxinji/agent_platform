package cn.xinji.agentplatform.consultation.unified;

import cn.xinji.agentplatform.consultation.answer.adapter.out.openai.OpenAiConsultationAnswerAdapter.ModelEndpoint;
import cn.xinji.agentplatform.config.ConsultationAgentModelProperties;
import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Produces constrained intent and slot candidates; the service validates execution separately. */
@Service
final class ExternalModelIntentAdvisor {
    private final ManagedModelConfigurationService models;
    private final ObjectMapper mapper;
    private final DailyIntentContractCatalog contracts;
    private final List<String> fallbackModelChain;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ExternalModelIntentAdvisor(ManagedModelConfigurationService models, ObjectMapper mapper,
            DailyIntentContractCatalog contracts, ConsultationAgentModelProperties properties) {
        this.models = models;
        this.mapper = mapper;
        this.contracts = contracts;
        ConsultationAgentModelProperties.RuntimeSettings runtime = properties.getRuntime();
        this.fallbackModelChain = runtime != null && runtime.isEnabled() && runtime.getModelChain() != null
                ? List.copyOf(runtime.getModelChain()) : List.of();
    }

    List<DailyIntentContractCatalog.IntentAdvice> advise(String question, List<String> context, String traceId) {
        try {
            List<String> chain = models.runtimeModelChain(fallbackModelChain);
            if (chain.isEmpty()) return List.of();
            ModelEndpoint endpoint = models.endpoint(chain.getFirst());
            if (endpoint == null || endpoint.apiKey().isBlank()) return List.of();
            var payload = mapper.createObjectNode();
            payload.put("model", endpoint.modelName());
            payload.put("temperature", 0);
            payload.put("max_tokens", 300);
            payload.putObject("response_format").put("type", "json_object");
            var messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", "你是知识产权咨询的受控意图抽取器。只返回 JSON："
                    + "{\"intents\":[{\"intentId\":\"...\",\"slots\":{\"字段\":\"值\"}}]}。"
                    + "严格使用下列契约中的 intentId 和 allowedSlots；requiredSlotGroups 中每组至少提取一个字段。"
                    + "可返回多个意图；无法判断时 intents 为空。槽位值须来自用户文本或允许上下文，不得编造工具、链接、权限或结论。"
                    + "示例只用于区分意图和缺项，不得把示例值复制到当前答案。不得解释。契约："
                    + contractPrompt());
            String contextualQuestion = context == null || context.isEmpty() ? question
                    : "当前问题：" + question + "\n允许上下文：" + String.join("\n", context);
            messages.addObject().put("role", "user").put("content", contextualQuestion);
            HttpRequest request = HttpRequest.newBuilder(endpoint.uri()).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + endpoint.apiKey()).header("Content-Type", "application/json")
                    .header("X-Client-Trace-Id", traceId).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return List.of();
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode answer = mapper.readTree(content.replaceAll("(?s)^```(?:json)?\\s*|\\s*```$", ""));
            String allowedInput = question + "\n" + (context == null ? "" : String.join("\n", context));
            List<DailyIntentContractCatalog.IntentAdvice> result = new ArrayList<>();
            for (JsonNode value : answer.path("intents")) {
                String intentId = value.path("intentId").asText("").trim();
                Map<String, String> slots = new LinkedHashMap<>();
                value.path("slots").fields().forEachRemaining(entry -> {
                    String slotValue = entry.getValue().asText("").trim();
                    if (groundedIn(slotValue, allowedInput)) slots.put(entry.getKey(), slotValue);
                });
                DailyIntentContractCatalog.IntentAdvice sanitized = contracts.sanitize(intentId, slots);
                if (sanitized != null && result.stream().noneMatch(existing -> existing.intentId().equals(sanitized.intentId()))) result.add(sanitized);
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static boolean groundedIn(String value, String allowedInput) {
        String normalizedValue = normalize(value);
        return !normalizedValue.isBlank() && normalize(allowedInput).contains(normalizedValue);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private String contractPrompt() throws Exception {
        var catalog = mapper.createArrayNode();
        for (DailyIntentContractCatalog.Contract contract : contracts.contracts()) {
            var item = catalog.addObject();
            item.put("intentId", contract.intentId());
            item.put("label", contract.label());
            var allowedSlots = item.putArray("allowedSlots");
            contract.allowedSlots().forEach(allowedSlots::add);
            var requiredGroups = item.putArray("requiredSlotGroups");
            contract.requiredSlotGroups().forEach(group -> {
                var values = requiredGroups.addArray();
                group.forEach(values::add);
            });
            var examples = item.putArray("examples");
            contract.examples().forEach(example -> examples.addObject()
                    .put("kind", example.kind()).put("question", example.question()));
        }
        return mapper.writeValueAsString(catalog);
    }
}
