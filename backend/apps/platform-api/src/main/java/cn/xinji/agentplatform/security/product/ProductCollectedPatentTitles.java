package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads only TITLE metadata from a verified real-capture package. */
final class ProductCollectedPatentTitles {
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;

    ProductCollectedPatentTitles(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    List<PatentLexicalQuery.Document> load(ProductSourceAvailability.Active active) {
        return load(active, ProductPatentSearchFilters.none());
    }

    List<PatentLexicalQuery.Document> load(ProductSourceAvailability.Active active,
            ProductPatentSearchFilters filters) {
        try {
            JsonNode manifest = mapper.readTree(files.verified(active.manifestRef(), active.manifestSha()));
            String expectedVersion = required(manifest, "datasetId") + ":" + required(manifest, "generatedAt");
            if (!active.sourceVersion().equals(expectedVersion))
                throw new IllegalStateException("Captured source version changed");
            String recordsName = required(manifest, "recordsFile");
            String recordsRef = sibling(active.manifestRef(), recordsName);
            JsonNode capture = mapper.readTree(files.verified(recordsRef, active.recordsSha()));
            if (!required(capture, "datasetId").equals(required(manifest, "datasetId"))
                    || !required(capture, "generatedAt").equals(required(manifest, "generatedAt")))
                throw new IllegalStateException("Captured record set is not bound to its manifest");
            Map<String, String> rawDigests = rawDigests(manifest);
            List<PatentLexicalQuery.Document> documents = new ArrayList<>();
            for (JsonNode record : capture.path("records")) {
                String rawName = required(record, "detailRawFile");
                String rawRef = sibling(active.manifestRef(), rawName);
                String rawSha = rawDigests.get(rawName);
                if (rawSha == null) throw new IllegalStateException("Captured title has no raw response");
                files.verified(rawRef, rawSha);
                if (!filters.matches(optionalDate(record, "publicationDate"),
                        strings(record, "ipcClassifications"), strings(record, "cpcClassifications")))
                    continue;
                documents.add(new PatentLexicalQuery.Document(active.sourceId(),
                        required(record, "sourceRecordId"), required(record, "sourceVersion"),
                        active.sourceVersion(), rawSha, rawRef + "#title",
                        Map.of("TITLE", required(record, "title"))));
            }
            if (!capture.path("records").isArray()
                    || capture.path("records").size() != manifest.path("recordCount").asInt(-1))
                throw new IllegalStateException("Captured title inventory is incomplete");
            return List.copyOf(documents);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Captured title source is unreadable", failure);
        }
    }

    private static LocalDate optionalDate(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : LocalDate.parse(value);
    }

    private static List<String> strings(JsonNode node, String field) {
        if (!node.path(field).isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : node.path(field)) if (!value.asText().isBlank()) result.add(value.asText());
        return List.copyOf(result);
    }

    private static Map<String, String> rawDigests(JsonNode manifest) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode item : manifest.path("rawResponses")) {
            if (!"DETAIL".equals(item.path("type").asText())) continue;
            String file = required(item, "file");
            if (result.put(file, required(item, "sha256")) != null)
                throw new IllegalStateException("Duplicate captured raw response");
        }
        return result;
    }

    private static String sibling(String manifestRef, String child) {
        if (child.contains("..") || Path.of(child).isAbsolute())
            throw new IllegalStateException("Captured source reference is invalid");
        int separator = manifestRef.lastIndexOf('/');
        return (separator < 0 ? "" : manifestRef.substring(0, separator + 1)) + child.replace('\\', '/');
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Captured source field is missing: " + field);
        return value;
    }
}
