package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.require;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class ProductTrademarkCaptureJson {
    static final int MIB = 1024 * 1024;
    static final int MAX_RECORDS = 1000, MAX_ORIGINALS = 2000, MAX_ARRAY_ITEMS = 10000;
    static final int MAX_RELATIONS = 100000, MAX_TEXT_BYTES = 64 * 1024;
    static final long MAX_TOTAL_BYTES = 64L * MIB;
    private final ObjectMapper mapper;

    ProductTrademarkCaptureJson(ObjectMapper original) {
        java.util.Objects.requireNonNull(original);
        // Capture schemas must not inherit application modules, mixins or serializers.
        mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(JsonParser.Feature.ALLOW_COMMENTS, JsonParser.Feature.ALLOW_SINGLE_QUOTES,
                        JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, JsonParser.Feature.ALLOW_TRAILING_COMMA,
                        JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS)
                .disable(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII);
        mapper.disable(SerializationFeature.INDENT_OUTPUT, SerializationFeature.WRAP_ROOT_VALUE,
                SerializationFeature.WRITE_ENUMS_USING_INDEX, SerializationFeature.WRITE_ENUMS_USING_TO_STRING,
                SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
        mapper.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.LOWER_CAMEL_CASE);
        for (var feature : com.fasterxml.jackson.core.json.JsonReadFeature.values())
            mapper.getFactory().disable(feature.mappedFeature());
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(MAX_TEXT_BYTES).maxNumberLength(1000).build());
    }

    JsonNode parse(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            JsonNode node = mapper.readTree(bytes);
            require(node != null && node.isObject());
            validateStrings(node);
            return node;
        } catch (Exception invalid) { throw new ProductTrademarkCapture.InvalidCapture(); }
    }

    private void validateStrings(JsonNode node) {
        if (node.isTextual()) validateText(node.textValue());
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            validateText(field.getKey()); validateStrings(field.getValue());
        });
        else if (node.isArray()) node.forEach(this::validateStrings);
    }

    private void validateText(String text) {
        require(text.getBytes(StandardCharsets.UTF_8).length <= MAX_TEXT_BYTES);
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value))
                require(++i < text.length() && Character.isLowSurrogate(text.charAt(i)));
            else require(!Character.isLowSurrogate(value));
        }
    }

    byte[] verified(ProductTrademarkCapture.Artifact artifact, int maxBytes) {
        require(artifact != null);
        path(artifact.ref());
        String declared = digestText(artifact.sha256());
        byte[] bytes = artifact.bytes();
        require(bytes.length > 0 && bytes.length <= maxBytes && artifact.byteLength() == bytes.length);
        require(sha(bytes).equals(declared));
        return bytes;
    }

    Map<String, int[]> ranges(byte[] bytes) {
        try { return ProductCapturedJsonFields.ranges(bytes, mapper); }
        catch (Exception invalid) { throw new ProductTrademarkCapture.InvalidCapture(); }
    }

    String canonicalSha(JsonNode value) {
        try { return sha(mapper.writeValueAsBytes(sorted(value))); }
        catch (Exception invalid) { throw new ProductTrademarkCapture.InvalidCapture(); }
    }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue()));
            ObjectNode result = object(); fields.forEach((key, item) -> result.set(key, sorted(item))); return result;
        }
        if (value.isArray()) {
            ArrayNode result = array(); value.forEach(item -> result.add(sorted(item))); return result;
        }
        return value;
    }
    ObjectNode object() { return mapper.createObjectNode(); }
    ArrayNode array() { return mapper.createArrayNode(); }
    JsonNode tree(Object value) { return mapper.valueToTree(value); }
    ObjectNode instant(Instant value) { return object().put("epochSecond", value.getEpochSecond()).put("nano", value.getNano()); }
    static String text(JsonNode node) { require(node != null && node.isTextual() && !node.textValue().isBlank()); return node.textValue(); }
    static String optionalText(JsonNode node) { require(node == null || node.isNull() || node.isTextual()); return node == null || node.isNull() ? null : node.textValue(); }
    static long integer(JsonNode node) { require(node != null && node.isIntegralNumber() && node.canConvertToLong()); return node.longValue(); }
    static String digestText(String text) { require(text != null && text.matches("[0-9a-fA-F]{64}")); return text.toLowerCase(java.util.Locale.ROOT); }
    static void identity(String text) { require(text != null && !text.isBlank() && text.length() <= 300); }
    static void path(String ref) {
        require(ref != null && ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")
                && !ref.contains("..") && !ref.endsWith("/"));
        for (String part : ref.split("/", -1))
            require(!part.isEmpty() && !part.equals(".") && !part.endsWith("."));
    }
    static Instant time(String value) {
        try { return Instant.parse(value); } catch (Exception invalid) { throw new ProductTrademarkCapture.InvalidCapture(); }
    }
    static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
