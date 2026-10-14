package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;

/** Fresh verified capture bytes projected to citation metadata, never cached source authority. */
final class ProductPatentCitationRecords {
    private static final Set<String> FIELDS = Set.of("sourceRecordId", "sourceVersion",
            "detailRawFile", "detailUrl");
    private ProductPatentCitationRecords() { }

    static JsonNode read(ObjectMapper mapper, byte[] verifiedBytes) throws IOException {
        try (var parser = mapper.getFactory().createParser(verifiedBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT)
                throw new IllegalStateException("Citation record root must be an object");
            var root = mapper.createObjectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME)
                    throw new IllegalStateException("Invalid citation record field");
                String field = parser.currentName();
                parser.nextToken();
                if (field.equals("datasetId") || field.equals("generatedAt")) {
                    root.set(field, mapper.readTree(parser));
                    continue;
                }
                if (!field.equals("records")) {
                    parser.skipChildren();
                    continue;
                }
                if (parser.currentToken() != JsonToken.START_ARRAY) {
                    root.set(field, mapper.readTree(parser));
                    continue;
                }
                var records = mapper.createArrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() == null)
                        throw new IllegalStateException("Incomplete citation record array");
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        records.add((JsonNode) mapper.readTree(parser));
                        continue;
                    }
                    var record = mapper.createObjectNode();
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        if (parser.currentToken() != JsonToken.FIELD_NAME)
                            throw new IllegalStateException("Invalid citation document field");
                        String key = parser.currentName();
                        parser.nextToken();
                        if (FIELDS.contains(key)) record.set(key, mapper.readTree(parser));
                        else parser.skipChildren();
                    }
                    records.add(record);
                }
                root.set(field, records);
            }
            return root;
        }
    }
}
