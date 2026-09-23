package cn.xinji.agentplatform.consultation.unified;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Loads the development intent contract file and exposes only validated executable metadata. */
@Service
final class DailyIntentContractCatalog {
    private static final String RESOURCE = "consultation-intent-contracts.dev-v1.json";
    private final String version;
    private final List<Contract> contracts;

    DailyIntentContractCatalog(ObjectMapper mapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = mapper.readTree(input);
            version = required(root.path("version").asText(), "version");
            List<Contract> loaded = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode node : root.path("contracts")) {
                Contract contract = new Contract(required(node.path("intentId").asText(), "intentId"),
                        required(node.path("label").asText(), "label"), required(node.path("category").asText(), "category"),
                        required(node.path("toolId").asText(), "toolId"), required(node.path("route").asText(), "route"),
                        groups(node.path("requiredSlotGroups")), node.path("humanConfirmationRequired").asBoolean(false),
                        examples(node.path("examples")));
                if (!ids.add(contract.intentId())) throw new IllegalStateException("意图契约编号重复：" + contract.intentId());
                if (contract.examples().stream().noneMatch(value -> "POSITIVE".equals(value.kind()))
                        || contract.examples().stream().noneMatch(value -> "NEGATIVE".equals(value.kind()))
                        || contract.examples().stream().noneMatch(value -> "MISSING_SLOT".equals(value.kind()))
                        || contract.examples().stream().noneMatch(value -> "MULTI_INTENT".equals(value.kind()))
                        || contract.examples().stream().noneMatch(value -> "CONTEXT".equals(value.kind()))) {
                    throw new IllegalStateException("意图契约示例不完整：" + contract.intentId());
                }
                loaded.add(contract);
            }
            if (loaded.isEmpty()) throw new IllegalStateException("意图契约不能为空");
            contracts = List.copyOf(loaded);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载咨询意图契约目录", exception);
        }
    }

    String version() { return version; }
    List<Contract> contracts() { return contracts; }
    List<String> intentIds() { return contracts.stream().map(Contract::intentId).toList(); }
    Optional<Contract> find(String intentId) { return contracts.stream().filter(value -> value.intentId().equals(intentId)).findFirst(); }

    IntentAdvice sanitize(String intentId, Map<String, String> slots) {
        Contract contract = find(intentId).orElse(null);
        if (contract == null) return null;
        Map<String, String> requested = slots == null ? Map.of() : slots;
        java.util.LinkedHashMap<String, String> accepted = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (contract.allowedSlots().contains(name) && !value.isBlank() && value.length() <= 200) accepted.put(name, value);
        }
        return new IntentAdvice(contract.intentId(), Map.copyOf(accepted));
    }

    List<String> missing(Contract contract, Set<String> availableSlots) {
        return contract.requiredSlotGroups().stream().filter(group -> group.stream().noneMatch(availableSlots::contains))
                .map(group -> group.getFirst()).toList();
    }

    private static List<List<String>> groups(JsonNode node) {
        List<List<String>> groups = new ArrayList<>();
        for (JsonNode group : node) {
            List<String> names = new ArrayList<>();
            for (JsonNode name : group) names.add(required(name.asText(), "requiredSlotGroups"));
            if (names.isEmpty()) throw new IllegalStateException("必填槽位组不能为空");
            groups.add(List.copyOf(names));
        }
        return List.copyOf(groups);
    }

    private static List<Example> examples(JsonNode node) {
        List<Example> examples = new ArrayList<>();
        for (JsonNode value : node) examples.add(new Example(required(value.path("kind").asText(), "example.kind"),
                required(value.path("question").asText(), "example.question")));
        return List.copyOf(examples);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException("意图契约缺少 " + label);
        return value.trim();
    }

    record Contract(String intentId, String label, String category, String toolId, String route,
            List<List<String>> requiredSlotGroups, boolean humanConfirmationRequired, List<Example> examples) {
        Set<String> allowedSlots() { return requiredSlotGroups.stream().flatMap(List::stream).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    }
    record Example(String kind, String question) {}
    record IntentAdvice(String intentId, Map<String, String> slots) {}
}
