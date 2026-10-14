package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.DateValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;

final class ProductTrademarkProjectionCodec {
    final ProductTrademarkCaptureJson json = new ProductTrademarkCaptureJson(new ObjectMapper());

    ObjectNode normalized(ProjectedTrademarkRecord record, NatureClaim nature) {
        var view = record.observation();
        ObjectNode node = json.object().put("schema", "TM-NORMALIZED-C14N-V1");
        node.set("ref", json.tree(view.ref()));
        node.put("registrationNumber", view.registrationNumber()).put("name", view.name()).putNull("imageRef")
                .put("address", view.address()).put("sourceStatus", view.sourceStatus())
                .put("displayStatus", view.displayStatus()).put("sourceUpdatedAt", view.sourceUpdatedAt())
                .put("sourceRef", view.sourceRef());
        node.set("applicants", json.tree(view.applicants())); node.set("classes", json.tree(view.classes()));
        node.set("registered", date(view.registered())); node.set("validUntil", date(view.validUntil()));
        node.set("valueStates", json.tree(record.fields().stream()
                .map(field -> java.util.List.of(field.field(), field.state().name())).toList()));
        node.set("nature", json.tree(nature));
        check(json.canonicalSha(node).equals(record.normalizedSha256()));
        ObjectNode relations = json.object().put("schema", "TM-RELATIONS-C14N-V1");
        relations.set("applicants", json.tree(view.applicants())); relations.set("classes", json.tree(view.classes()));
        relations.set("fields", json.tree(record.fields()));
        check(json.canonicalSha(relations).equals(record.relationSha256()));
        return node;
    }

    String locator(FieldEvidence field) { return json.tree(field).toString(); }
    String strings(java.util.List<String> values) { return json.tree(values).toString(); }
    JsonNode parse(String value) { return json.parse(value.getBytes(StandardCharsets.UTF_8)); }
    private JsonNode date(DateValue value) {
        return value == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                : json.object().put("raw", value.raw()).put("precision", value.precision())
                    .put("day", value.day() == null ? null : value.day().toString());
    }
    static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("TRADEMARK_PROJECTION_UNAVAILABLE");
    }
}
