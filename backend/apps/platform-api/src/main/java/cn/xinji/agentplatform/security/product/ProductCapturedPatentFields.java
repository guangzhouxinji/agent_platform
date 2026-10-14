package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Exact original field projection. Permission and source admission remain in the product chain. */
final class ProductCapturedPatentFields {
    private static final List<String> ORDER = List.of("TITLE", "ABSTRACT", "CLAIMS", "DESCRIPTION");
    private static final Map<String,String> ORIGINAL = Map.of("TITLE", "title", "ABSTRACT", "abstract",
            "CLAIMS", "claims", "DESCRIPTION", "full_description");
    record Content(Map<String,String> values, Map<String,String> locators) {
        Content {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            locators = Collections.unmodifiableMap(new LinkedHashMap<>(locators));
            if (values.isEmpty() || !values.keySet().equals(locators.keySet()))
                throw new IllegalArgumentException("Exact nonempty field content and locators required");
        }
        String anchor() { return locators.values().iterator().next(); }
    }

    static void validate(Set<String> selected) {
        if (selected == null || selected.isEmpty() || selected.stream().anyMatch(f -> f == null || !ORDER.contains(f)))
            throw new IllegalArgumentException("Supported selected patent fields required");
    }

    /** Explicit native-envelope mapping; byte locators come from its actual JSON token stream. */
    static Content readEnvelope(String rawRef, byte[] bytes, String sha, Set<String> selected,
            Map<String,String> mapping, ObjectMapper mapper) {
        return readEnvelope(rawRef,bytes,sha,selected,mapping,mapper,null);
    }

    /** Only the current request's inventory-verified original; never a cached authorization. */
    static Content readVerifiedEnvelope(ProductPatentsViewSourceInventory.Original original,
            Set<String> selected, Map<String,String> mapping, ObjectMapper mapper) {
        return readEnvelope(original.ref(),original.bytes(),original.sha(),selected,mapping,mapper,original.record());
    }

    private static Content readEnvelope(String rawRef, byte[] bytes, String sha, Set<String> selected,
            Map<String,String> mapping, ObjectMapper mapper, JsonNode verifiedRecord) {
        validate(selected);
        if (rawRef == null || !rawRef.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}") || rawRef.contains("..")
                || !ProductCapturedArchive.digest(bytes).equals(sha))
            throw new IllegalStateException("Native original reference/digest differs");
        try {
            JsonNode record = verifiedRecord == null ? mapper.readTree(bytes) : verifiedRecord;
            var ranges = ProductCapturedJsonFields.ranges(bytes,mapper);
            Map<String,String> values = new LinkedHashMap<>(), locators = new LinkedHashMap<>();
            for (String field : ORDER) {
                if (!selected.contains(field)) continue;
                String original = mapping.get(field);
                if (original == null || !record.path(original).isTextual() || record.path(original).asText().isBlank())
                    throw new IllegalStateException("Selected native field unavailable: " + field);
                int[] range = ranges.get(original);
                if (range == null || !mapper.readTree(bytes,range[1],range[2]-range[1]).equals(record.path(original)))
                    throw new IllegalStateException("Native field token differs");
                values.put(field,record.path(original).asText());
                locators.put(field,rawRef + "#json-pointer=/" + original.replace("~","~0").replace("/","~1")
                        + "&utf8=" + range[1] + ":" + range[2]);
            }
            return new Content(values,locators);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Native field cannot be read",failure); }
    }

    static Content read(String rawRef, byte[] bytes, JsonNode item, Set<String> selected, ObjectMapper mapper) {
        validate(selected);
        if (rawRef == null || !rawRef.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}") || rawRef.contains(".."))
            throw new IllegalArgumentException("Original field reference required");
        try {
            if (!ProductCapturedArchive.digest(bytes).equals(item.path("sha256").asText()))
                throw new IllegalStateException("Selected content original digest differs");
            JsonNode record = mapper.readTree(bytes);
            ProductCapturedJsonFields.verify(bytes, record, item, mapper);
            Map<String,String> values = new LinkedHashMap<>(), locators = new LinkedHashMap<>();
            for (String field : ORDER) {
                if (!selected.contains(field)) continue;
                String original = ORIGINAL.get(field);
                JsonNode value = record.path(original);
                if (!value.isTextual() || value.asText().isBlank())
                    throw new IllegalStateException("Selected original content unavailable: " + field);
                JsonNode location = null;
                for (JsonNode candidate : item.path("locators"))
                    if (candidate.path("field").asText().equals(original)) location = candidate;
                if (location == null) throw new IllegalStateException("Selected original locator unavailable");
                values.put(field, value.asText());
                locators.put(field, rawRef + "#json-pointer=" + location.path("jsonPointer").asText()
                        + "&utf8=" + location.path("valueUtf8Start").longValue()
                        + ":" + location.path("valueUtf8EndExclusive").longValue());
            }
            return new Content(values, locators);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Selected original content cannot be read", invalid);
        }
    }
}
