package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.core.JsonToken;

/** Original JSON token ranges, not decoded-text offsets or normalized professional evidence. */
final class ProductCapturedJsonFields {
    static void verify(byte[] bytes, JsonNode record, JsonNode item, ObjectMapper mapper) throws java.io.IOException {
        if (!record.isObject() || !item.path("fields").isArray() || !item.path("locators").isArray()
                || record.size() != item.path("fields").size() || record.size() != item.path("locators").size())
            throw new IllegalStateException("Captured JSON field inventory differs");
        Set<String> fields = new HashSet<>();
        for (JsonNode field : item.path("fields"))
            if (!field.isTextual() || !record.has(field.asText()) || !fields.add(field.asText()))
                throw new IllegalStateException("Invalid captured field name");
        Set<String> located = new HashSet<>();
        Map<String, int[]> actualRanges = ranges(bytes, mapper);
        for (JsonNode locator : item.path("locators")) {
            String field = text(locator.path("field"));
            if (!fields.contains(field) || !located.add(field)
                    || !text(locator.path("jsonPointer")).equals("/" + field.replace("~", "~0").replace("/", "~1"))
                    || !text(locator.path("rawSha256")).equals(item.path("sha256").asText())
                    || !text(locator.path("encoding")).equals("UTF-8"))
                throw new IllegalStateException("Captured field locator binding differs");
            JsonNode key = value(bytes, locator, "keyUtf8Start", "keyUtf8EndExclusive", mapper);
            JsonNode value = value(bytes, locator, "valueUtf8Start", "valueUtf8EndExclusive", mapper);
            if (!key.isTextual() || !field.equals(key.asText()) || !value.equals(record.path(field)))
                throw new IllegalStateException("Captured JSON field value differs");
            int keyEnd = integer(locator.path("keyUtf8EndExclusive"));
            int valueStart = integer(locator.path("valueUtf8Start"));
            if (valueStart <= keyEnd) throw new IllegalStateException("Captured key/value order differs");
            int[] actual = actualRanges.get(field);
            if (actual == null || actual[0] != integer(locator.path("keyUtf8Start"))
                    || actual[1] != integer(locator.path("valueUtf8Start"))
                    || actual[2] != integer(locator.path("valueUtf8EndExclusive")))
                throw new IllegalStateException("Captured locator is not the original top-level field");
            String between = new String(bytes, keyEnd, valueStart - keyEnd, java.nio.charset.StandardCharsets.US_ASCII);
            if (!between.matches("\\s*:\\s*")) throw new IllegalStateException("Captured field token relation differs");
        }
        JsonNode identifiers = mapper.createObjectNode();
        var values = record.fields();
        while (values.hasNext()) {
            var field = values.next();
            if (field.getKey().matches(".*(?:publication|application|patent).*number.*|patent_id"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) identifiers).set(field.getKey(), field.getValue());
        }
        if (!identifiers.equals(item.path("identifiers"))) throw new IllegalStateException("Captured identifiers differ");
    }

    static Map<String,int[]> ranges(byte[] bytes, ObjectMapper mapper) throws java.io.IOException {
        Map<String,int[]> ranges = new LinkedHashMap<>();
        try (var parser = mapper.getFactory().createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalStateException("Original JSON object required");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IllegalStateException("Original JSON field required");
                String field = parser.currentName();
                int key = Math.toIntExact(parser.currentTokenLocation().getByteOffset());
                JsonToken value = parser.nextToken();
                if (value == null) throw new IllegalStateException("Original JSON value required");
                int start = Math.toIntExact(parser.currentTokenLocation().getByteOffset());
                if (value == JsonToken.VALUE_STRING) parser.getText();
                else if (value.isStructStart()) parser.skipChildren();
                int end = Math.toIntExact(parser.currentLocation().getByteOffset());
                if (ranges.put(field, new int[]{key, start, end}) != null) throw new IllegalStateException("Duplicate original JSON field");
            }
            if (parser.nextToken() != null) throw new IllegalStateException("Trailing original JSON content");
        }
        return ranges;
    }

    private static JsonNode value(byte[] bytes, JsonNode locator, String startKey, String endKey,
            ObjectMapper mapper) throws java.io.IOException {
        int start = integer(locator.path(startKey)), end = integer(locator.path(endKey));
        if (start < 0 || end <= start || end > bytes.length) throw new IllegalStateException("Captured field range invalid");
        return mapper.readTree(bytes, start, end - start);
    }
    private static int integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalStateException("Exact field byte offset required");
        return node.intValue();
    }
    private static String text(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) throw new IllegalStateException("Captured locator text required");
        return node.asText();
    }
}
