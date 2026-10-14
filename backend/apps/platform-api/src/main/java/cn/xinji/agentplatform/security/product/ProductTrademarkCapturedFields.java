package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class ProductTrademarkCapturedFields {
    private static final List<String> FIELDS = List.of("sourceRecordId", "registrationNumber", "name",
            "applicants", "applicantAddress", "classes", "registrationDate", "validUntil", "sourceStatus", "sourceUpdatedAt");
    record Mapped(Observation observation, List<FieldEvidence> fields, String normalizedSha, String relationSha, int arrayItems) { }
    private final ProductTrademarkCaptureJson json;
    ProductTrademarkCapturedFields(ProductTrademarkCaptureJson json) { this.json = json; }

    Mapped map(byte[] bytes, String rawRef, String rawSha, String sourceId, String stableId,
               String recordVersion, String sourceRef, NatureClaim nature) {
        JsonNode root = json.parse(bytes);
        require(text(root.get("sourceRecordId")).equals(stableId));
        int arrayItems = arrays(root);
        require(arrayItems <= MAX_ARRAY_ITEMS);
        List<String> applicants = strings(root.get("applicants"));
        List<ClassDetail> classes = classes(root.get("classes"));
        DateValue registered = date(root.get("registrationDate")), valid = date(root.get("validUntil"));
        String status = optionalText(root.get("sourceStatus"));
        Observation observation = new Observation(new RecordRef(sourceId, stableId, recordVersion),
                optionalText(root.get("registrationNumber")), optionalText(root.get("name")), null,
                applicants, optionalText(root.get("applicantAddress")), classes, registered, valid,
                status, status == null ? null : "UNMAPPED", optionalText(root.get("sourceUpdatedAt")), sourceRef);
        List<FieldEvidence> fields = evidence(bytes, root, rawRef, rawSha);
        ObjectNode normalized = json.object().put("schema", "TM-NORMALIZED-C14N-V1");
        normalized.set("ref", json.tree(observation.ref()));
        normalized.put("registrationNumber", observation.registrationNumber()).put("name", observation.name())
                .putNull("imageRef").put("address", observation.address()).put("sourceStatus", status)
                .put("displayStatus", observation.displayStatus()).put("sourceUpdatedAt", observation.sourceUpdatedAt())
                .put("sourceRef", sourceRef);
        normalized.set("applicants", json.tree(applicants)); normalized.set("classes", json.tree(classes));
        normalized.set("registered", dateNode(registered)); normalized.set("validUntil", dateNode(valid));
        normalized.set("valueStates", json.tree(fields.stream().map(field -> List.of(field.field(), field.state().name())).toList()));
        normalized.set("nature", json.tree(nature));
        ObjectNode relations = json.object().put("schema", "TM-RELATIONS-C14N-V1");
        relations.set("applicants", json.tree(applicants)); relations.set("classes", json.tree(classes));
        relations.set("fields", json.tree(fields));
        return new Mapped(observation, List.copyOf(fields), json.canonicalSha(normalized), json.canonicalSha(relations), arrayItems);
    }

    private JsonNode dateNode(DateValue value) {
        if (value == null) return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        return json.object().put("raw", value.raw()).put("precision", value.precision())
                .put("day", value.day() == null ? null : value.day().toString());
    }
    private List<ClassDetail> classes(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        require(node.isArray());
        List<ClassDetail> result = new ArrayList<>();
        for (JsonNode item : node) {
            require(item.isObject());
            result.add(new ClassDetail(text(item.get("code")), optionalText(item.get("name")),
                    strings(item.get("goodsServices")), strings(item.get("similarGroups"))));
        }
        return List.copyOf(result);
    }
    private List<String> strings(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        require(node.isArray());
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) { require(item.isTextual()); values.add(item.textValue()); }
        return List.copyOf(values);
    }
    private DateValue date(JsonNode node) {
        String raw = optionalText(node);
        if (raw == null) return null;
        try {
            require(raw.matches("[0-9]{4}(?:-[0-9]{2})?(?:-[0-9]{2})?"));
            require(Integer.parseInt(raw.substring(0, 4)) >= 1);
            if (raw.length() == 4) { Year.parse(raw); return new DateValue(raw, "YEAR", null); }
            if (raw.length() == 7) { YearMonth.parse(raw); return new DateValue(raw, "MONTH", null); }
            return new DateValue(raw, "DAY", LocalDate.parse(raw));
        } catch (Exception invalid) { throw new InvalidCapture(); }
    }
    private List<FieldEvidence> evidence(byte[] bytes, JsonNode root, String rawRef, String rawSha) {
        Map<String, int[]> ranges = json.ranges(bytes);
        List<FieldEvidence> result = new ArrayList<>();
        for (String field : FIELDS) {
            JsonNode value = root.get(field);
            if (value == null) {
                result.add(new FieldEvidence(field, "/" + field, ValueState.MISSING, rawRef, rawSha, null, null, null));
                continue;
            }
            int[] range = ranges.get(field);
            require(range != null && range[1] >= 0 && range[2] > range[1] && range[2] <= bytes.length);
            result.add(new FieldEvidence(field, "/" + field, value.isNull() ? ValueState.NULL : ValueState.PRESENT,
                    rawRef, rawSha, range[1], range[2], sha(Arrays.copyOfRange(bytes, range[1], range[2]))));
        }
        return List.copyOf(result);
    }
    private int arrays(JsonNode node) {
        int count = node.isArray() ? node.size() : 0;
        for (JsonNode item : node) {
            count = Math.addExact(count, arrays(item));
            require(count <= MAX_ARRAY_ITEMS);
        }
        return count;
    }
}
