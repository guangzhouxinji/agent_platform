package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Field projection of the existing format-neutral capture contract, not source authorization. */
final class ProductCapturedSourceFields {
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    ProductCapturedSourceFields(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files; this.mapper = mapper;
    }
    ProductCollectedPatentContent.Capture read(ProductCollectedPatentTitles.CaptureSnapshot source,
            byte[] manifestBytes, Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        try {
            if (!ProductCapturedArchive.digest(manifestBytes).equals(source.manifestSha()))
                throw new IllegalStateException("Capture manifest digest differs");
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (manifest.has("schemaVersion")) throw new IllegalStateException("Unsupported capture format");
            String dataset = text(manifest, "datasetId"), generated = text(manifest, "generatedAt");
            if (!(dataset + ":" + generated).equals(source.sourceVersion())
                    || !source.recordsSha().equals(text(manifest, "recordsSha256")))
                throw new IllegalStateException("Capture version/digest differs");
            String recordsRef = sibling(source.manifestRef(), text(manifest, "recordsFile"));
            JsonNode records = mapper.readTree(files.verified(recordsRef, source.recordsSha()));
            if (!dataset.equals(text(records, "datasetId")) || !generated.equals(text(records, "generatedAt"))
                    || !records.path("records").isArray() || manifest.path("recordCount").asLong(-1) < 1
                    || records.path("records").size() != manifest.path("recordCount").asLong(-1))
                throw new IllegalStateException("Capture inventory differs");
            Map<String,String> originals = new LinkedHashMap<>();
            if (!manifest.path("rawResponses").isArray() || manifest.path("rawResponses").isEmpty())
                throw new IllegalStateException("Original inventory missing");
            for (JsonNode item : manifest.path("rawResponses")) {
                String name = text(item, "file"), sha = text(item, "sha256");
                if (originals.putIfAbsent(name, sha) != null) throw new IllegalStateException("Duplicate original");
                files.verified(sibling(source.manifestRef(), name), sha);
            }
            Map<String,ProductCollectedPatentContent.Entry> entries = new LinkedHashMap<>();
            for (JsonNode row : records.path("records")) {
                if (row.hasNonNull("sourceId") && !source.sourceId().equals(text(row, "sourceId")))
                    throw new IllegalStateException("Wrong source binding");
                String id = text(row, "sourceRecordId"), name = text(row, "detailRawFile");
                String sha = originals.get(name), ref = sibling(source.manifestRef(), name);
                if (sha == null || !("RAW_SHA256:" + sha).equals(text(row, "sourceVersion")))
                    throw new IllegalStateException("Exact original version missing");
                var content = ProductCapturedPatentFields.readEnvelope(ref, files.verified(ref, sha), sha,
                        selected, Map.of("TITLE", "title", "ABSTRACT", "abstract", "CLAIMS", "claimsText",
                                "DESCRIPTION", "full_description"), mapper);
                var entry = new ProductCollectedPatentContent.Entry(id, text(row, "sourceVersion"), ref, sha,
                        content, row.hasNonNull("publicationDate") ? LocalDate.parse(text(row, "publicationDate")) : null,
                        strings(row, "ipcClassifications"), strings(row, "cpcClassifications"));
                if (entries.putIfAbsent(id, entry) != null) throw new IllegalStateException("Ambiguous document");
            }
            return new ProductCollectedPatentContent.Capture(source, text(manifest.path("source"), "apiBaseUrl"),
                    Instant.parse(generated), Collections.unmodifiableMap(entries));
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Capture cannot be read", invalid); }
    }
    private static String sibling(String manifest, String name) {
        if (name.contains("..") || name.contains("\\") || Path.of(name).isAbsolute())
            throw new IllegalStateException("Unsafe capture reference");
        Path parent = Path.of(manifest).getParent();
        return (parent == null ? Path.of(name) : parent.resolve(name)).toString().replace('\\', '/');
    }
    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Capture field missing: " + name);
        return value.asText();
    }
    private static List<String> strings(JsonNode node, String name) {
        if (!node.has(name)) return List.of();
        if (!node.path(name).isArray()) throw new IllegalStateException("Capture classifications invalid");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node.path(name)) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Invalid classification");
            values.add(value.asText());
        }
        return List.copyOf(values);
    }
}
