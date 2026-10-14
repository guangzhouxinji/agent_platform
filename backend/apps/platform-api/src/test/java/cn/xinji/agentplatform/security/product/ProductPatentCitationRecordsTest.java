package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parser equivalence evidence only; no source activation, permissions or business acceptance. */
class ProductPatentCitationRecordsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> FIELDS = List.of("sourceRecordId", "sourceVersion",
            "detailRawFile", "detailUrl");

    @Test void projectsEveryRealCollectedCitationWithoutRetainingUnneededFields() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("data/patents/public-pilot-v1/records.json"));
        JsonNode original = mapper.readTree(bytes);
        JsonNode projected = ProductPatentCitationRecords.read(mapper, bytes);
        assertThat(projected).isEqualTo(project(original));
        assertThat(projected.path("records").size()).isEqualTo(original.path("records").size()).isPositive();
        for (var row : projected.path("records")) {
            var names = new java.util.ArrayList<String>();
            row.fieldNames().forEachRemaining(names::add);
            assertThat(names).containsOnlyElementsOf(FIELDS);
        }
    }

    @Test void keepsOriginalSelectedValueTypesAndLastDuplicatePropertySemantics() throws Exception {
        for (var json : List.of(
                "{\"datasetId\":\"first\",\"datasetId\":\"last\",\"generatedAt\":null,"
                        + "\"ignored\":{\"nested\":[1,true,null]},\"records\":[{"
                        + "\"sourceRecordId\":\"a\",\"sourceRecordId\":\"b\",\"sourceVersion\":123,"
                        + "\"detailRawFile\":null,\"detailUrl\":false,\"unused\":[{\"a\":1}]}]}",
                "{\"records\":[{\"sourceRecordId\":\"first\"}],\"records\":[]}",
                "{\"records\":[null,1,\"bad\",{}]}")) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            assertThat(ProductPatentCitationRecords.read(mapper, bytes)).isEqualTo(project(mapper.readTree(bytes)));
        }
        // Projection itself does not grant acceptance to a malformed business record shape.
        byte[] scalar = "{\"records\":\"not-an-array\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(ProductPatentCitationRecords.read(mapper, scalar).path("records").isArray()).isFalse();
    }

    @Test void truncatedOrMalformedIgnoredContentStillFailsAndBytesAreNeverReused() throws Exception {
        for (var bad : List.of("", "[]", "{", "{\"records\":[", "{\"ignored\":[1,]}",
                "{\"records\":[{\"unused\":[1,]}]}"))
            assertThatThrownBy(() -> ProductPatentCitationRecords.read(mapper, bad.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(Exception.class);
        var first = "{\"datasetId\":\"first\",\"records\":[]}".getBytes(StandardCharsets.UTF_8);
        var second = "{\"datasetId\":\"second\",\"records\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThat(ProductPatentCitationRecords.read(mapper, first).path("datasetId").asText()).isEqualTo("first");
        assertThat(ProductPatentCitationRecords.read(mapper, second).path("datasetId").asText()).isEqualTo("second");
    }

    private JsonNode project(JsonNode original) {
        var root = mapper.createObjectNode();
        for (var key : List.of("datasetId", "generatedAt")) if (original.has(key)) root.set(key, original.get(key));
        var records = mapper.createArrayNode();
        for (var row : original.path("records")) {
            if (!row.isObject()) { records.add(row); continue; }
            var record = mapper.createObjectNode();
            for (var key : FIELDS) if (row.has(key)) record.set(key, row.get(key));
            records.add(record);
        }
        root.set("records", records);
        return root;
    }
}
