package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.xinji.agentplatform.security.product.ProductCollectedPatentContent.*;

/** Content-format adapter only. The existing product service must authorize current field use. */
final class ProductHupdSourceDocuments {
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    ProductHupdSourceDocuments(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files; this.mapper = mapper;
    }

    Capture read(ProductCollectedPatentTitles.CaptureSnapshot source, byte[] manifestBytes) {
        return read(source, manifestBytes, Set.of("TITLE"));
    }

    Capture read(ProductCollectedPatentTitles.CaptureSnapshot source, byte[] manifestBytes, Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        selected = Set.copyOf(selected);
        try {
            var verified = new ProductHupdSourceInventory(files, mapper).inspect(source.manifestRef(), manifestBytes);
            if (!source.sourceId().equals(verified.sourceId()) || !source.sourceVersion().equals(verified.sourceVersion())
                    || !source.manifestSha().equalsIgnoreCase(verified.recordsSha())
                    || !source.recordsSha().equalsIgnoreCase(verified.recordsSha()))
                throw new IllegalStateException("Native content differs from verified source");
            JsonNode manifest = mapper.readTree(manifestBytes);
            String parent = Path.of(source.manifestRef()).getParent() == null ? ""
                    : Path.of(source.manifestRef()).getParent().toString().replace('\\', '/') + "/";
            Instant collectedAt = null;
            for (JsonNode response : manifest.path("responses"))
                if (response.path("file").asText().equals("raw/archive-prefix.tar.gz"))
                    collectedAt = Instant.parse(text(response, "collectedAt"));
            if (collectedAt == null) throw new IllegalStateException("Native collection response missing");
            Map<String,Entry> entries = new LinkedHashMap<>();
            for (JsonNode item : manifest.path("records")) {
                String ref = parent + text(item, "file"), sha = text(item, "sha256");
                byte[] original = files.verified(ref, sha);
                JsonNode record = mapper.readTree(original);
                String publication = text(record, "publication_number");
                String id = publication.substring(0, publication.lastIndexOf('-'));
                var content = ProductCapturedPatentFields.read(ref, original, item, selected, mapper);
                Entry entry = new Entry(id, text(item, "documentVersion"), ref, sha, content,
                        LocalDate.parse(text(record, "date_published"), DateTimeFormatter.BASIC_ISO_DATE),
                        strings(record, "ipcr_labels"), strings(record, "cpc_labels"));
                if (entries.put(id, entry) != null) throw new IllegalStateException("Native document ID duplicates");
            }
            return new Capture(source, verified.provenance(), collectedAt,
                    Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        } catch (java.io.IOException | java.time.DateTimeException invalid) {
            throw new IllegalStateException("Native title content cannot be read", invalid);
        }
    }
    private static List<String> strings(JsonNode record, String field) {
        JsonNode values = record.path(field);
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalStateException("Native classification array required");
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Native classification required");
            result.add(value.asText());
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Native content field unavailable: " + field);
        return value.asText();
    }
}
