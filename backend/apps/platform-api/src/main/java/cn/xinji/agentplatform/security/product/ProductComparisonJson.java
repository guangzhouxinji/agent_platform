package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared strict request decoding and deterministic package hashing, never a business inference. */
final class ProductComparisonJson {
    private final ObjectMapper mapper;
    ProductComparisonJson(ObjectMapper mapper) {
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    <T> T request(String raw,Class<T> type) {
        try { T value=mapper.readValue(raw,type); if(value==null) throw new IllegalArgumentException("Null request"); return value; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_COMPARISON_JSON"); }
    }
    <T> T stored(String raw,Class<T> type) {
        try { return mapper.readValue(raw,type); }
        catch (Exception e) { throw new IllegalStateException("Comparison persisted JSON is invalid",e); }
    }
    String canonical(Object value) {
        try { return mapper.writeValueAsString(sorted(mapper.valueToTree(value))); }
        catch (Exception e) { throw new IllegalStateException("Comparison canonical encoding failed",e); }
    }
    String sha(Object value) { return hash(canonical(value)); }
    static String hash(String text) { return ProductCapturedArchive.digest(text.getBytes(StandardCharsets.UTF_8)); }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var keys=new TreeMap<String,JsonNode>(); value.fields().forEachRemaining(e->keys.put(e.getKey(),e.getValue()));
            ObjectNode node=mapper.createObjectNode(); keys.forEach((key,item)->node.set(key,sorted(item))); return node;
        }
        if (value.isArray()) { ArrayNode node=mapper.createArrayNode(); value.forEach(item->node.add(sorted(item))); return node; }
        return value;
    }
}
