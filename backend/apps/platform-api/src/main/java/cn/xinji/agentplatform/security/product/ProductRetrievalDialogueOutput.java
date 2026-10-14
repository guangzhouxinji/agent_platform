package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Strict model structure and citation boundary; not a professional semantic review. */
final class ProductRetrievalDialogueOutput {
    private static final Pattern CONCLUSION = Pattern.compile(
            "构成侵权|具有新颖性|具备创造性|专利无效|应当授权|应予驳回|新颖性结论|创造性结论|"
            + "(?i)infringes|is patentable|patent is invalid");

    static JsonNode parse(ObjectMapper mapper, String raw, String kind, Set<String> members) {
        try {
            if (raw == null || raw.length() > 16000) throw invalid();
            JsonNode value = mapper.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(raw);
            if (value == null || !value.isObject()) throw invalid();
            // Some real providers echo the response_format tag inside the JSON object.
            // Normalize only this exact transport tag, never arbitrary business/permission fields.
            if (value.has("type")) {
                if (!value.path("type").isTextual() || !"json_object".equals(value.path("type").asText())) throw invalid();
                ((com.fasterxml.jackson.databind.node.ObjectNode)value).remove("type");
            }
            Set<String> allowed = "PROPOSAL".equals(kind) ? Set.of("query", "questions") : Set.of("answer", "citations");
            Set<String> keys = new HashSet<>(); value.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(allowed)) throw invalid();
            String textKey = "PROPOSAL".equals(kind) ? "query" : "answer";
            if (!value.path(textKey).isTextual() || value.path(textKey).asText().isBlank()
                    || value.path(textKey).asText().length() > ("PROPOSAL".equals(kind) ? 2000 : 12000)
                    || CONCLUSION.matcher(value.path(textKey).asText()).find()) throw invalid();
            JsonNode list = value.path("PROPOSAL".equals(kind) ? "questions" : "citations");
            if (!list.isArray() || list.size() > ("PROPOSAL".equals(kind) ? 4 : 20)) throw invalid();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : list) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 1000
                        || !seen.add(item.asText()) || CONCLUSION.matcher(item.asText()).find()) throw invalid();
                if (!"PROPOSAL".equals(kind) && !members.contains(item.asText())) throw invalid();
            }
            if (!"PROPOSAL".equals(kind) && !members.isEmpty() && list.isEmpty()) throw invalid();
            return value;
        } catch (ResponseStatusException invalid) { throw invalid; }
        catch (Exception invalid) { throw invalid(); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MODEL_OUTPUT_VALIDATION_FAILED");
    }
}
