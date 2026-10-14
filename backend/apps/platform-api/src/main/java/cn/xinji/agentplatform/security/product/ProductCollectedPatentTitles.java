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

/** Explicit field projection from verified real-capture bytes; old overloads remain TITLE only. */
final class ProductCollectedPatentTitles {
    record CaptureSnapshot(String sourceId, String sourceVersion, String manifestRef,
                           String manifestSha, String recordsSha) { }
    static final class Unavailable extends IllegalStateException {
        Unavailable(Throwable cause) { super("SOURCE_CONTENT_UNAVAILABLE", cause); }
    }
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;

    ProductCollectedPatentTitles(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    List<ProductPatentSearchCitations.Citation> citations(List<ProductPatentSearchExecution.Member> members,
            Map<String,ProductSourceAvailability.Active> currentSources) {
        return new ProductPatentSearchCitations(files, mapper).read(members, currentSources);
    }

    List<ProductPatentSearchCitations.Citation> fieldCitations(List<ProductPatentSearchExecution.Member> members,
            Map<String,ProductSourceAvailability.Active> currentSources, java.util.Set<String> selected) {
        return new ProductPatentSearchCitations(files,mapper).readFields(members,currentSources,selected);
    }

    List<PatentLexicalQuery.Document> loadField(ProductSourceAvailability.Active active, String field) {
        if (field == null) throw new IllegalArgumentException("Exact selected content field required");
        return loadFields(active,ProductPatentSearchFilters.none(),java.util.Set.of(field));
    }

    List<PatentLexicalQuery.Document> loadFields(ProductSourceAvailability.Active active,
            ProductPatentSearchFilters filters, java.util.Set<String> selected) {
        return loadFieldEvidence(active,filters,selected).stream().map(
                cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence::document).toList();
    }
    List<cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence> loadFieldEvidence(
            ProductSourceAvailability.Active active, ProductPatentSearchFilters filters, java.util.Set<String> selected) {
        return loadFieldEvidence(new CaptureSnapshot(active.sourceId(),active.sourceVersion(),active.manifestRef(),
                active.manifestSha(),active.recordsSha()),filters,selected);
    }
    List<cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence> loadFieldEvidence(
            CaptureSnapshot active, ProductPatentSearchFilters filters, java.util.Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        var fields = java.util.Set.copyOf(selected);
        if (fields.equals(java.util.Set.of("TITLE"))) return load(active,filters).stream().map(document ->
                new cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence(
                        document,Map.of("TITLE",document.locatorRef()))).toList();
        try {
            byte[] manifestBytes = files.verified(active.manifestRef(), active.manifestSha());
            JsonNode manifest = mapper.readTree(manifestBytes);
            ProductCollectedPatentContent.Capture capture;
            if (ProductHupdSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText()))
                capture = new ProductHupdSourceDocuments(files,mapper).read(active,manifestBytes,fields);
            else if (ProductPatentsViewSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText()))
                capture = new ProductPatentsViewSourceDocuments(files,mapper).read(active,manifestBytes,fields);
            else if (!manifest.has("schemaVersion"))
                capture = new ProductCapturedSourceFields(files,mapper).read(active,manifestBytes,fields);
            else throw new IllegalStateException("Source format does not supply the selected field");
            return capture.documents(filters).stream().map(document ->
                    new cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence(
                            document,capture.entries().get(document.documentId()).content().locators())).toList();
        } catch (Exception failure) { throw new Unavailable(failure); }
    }

    List<PatentLexicalQuery.Document> load(ProductSourceAvailability.Active active) {
        return load(active, ProductPatentSearchFilters.none());
    }

    List<PatentLexicalQuery.Document> load(ProductSourceAvailability.Active active,
            ProductPatentSearchFilters filters) {
        return load(new CaptureSnapshot(active.sourceId(),active.sourceVersion(),active.manifestRef(),
                active.manifestSha(),active.recordsSha()),filters);
    }
    List<PatentLexicalQuery.Document> load(CaptureSnapshot active, ProductPatentSearchFilters filters) {
        try {
            byte[] manifestBytes = files.verified(active.manifestRef(), active.manifestSha());
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (ProductPatentsViewSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText()))
                return new ProductPatentsViewSourceDocuments(files,mapper).read(active,manifestBytes).documents(filters);
            if (ProductHupdSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText()))
                return new ProductHupdSourceDocuments(files, mapper).read(active, manifestBytes).documents(filters);
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
            List<ProductCitationFileVerification.Reference> originals = new ArrayList<>();
            for (JsonNode record : capture.path("records")) {
                String rawName = required(record, "detailRawFile");
                String rawRef = sibling(active.manifestRef(), rawName);
                String rawSha = rawDigests.get(rawName);
                if (rawSha == null) throw new IllegalStateException("Captured title has no raw response");
                originals.add(new ProductCitationFileVerification.Reference(rawRef, rawSha));
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
            verifyOriginals(files, originals);
            return List.copyOf(documents);
        } catch (Exception failure) {
            throw new Unavailable(failure);
        }
    }

    /** Every original, including filtered-out documents, passes before any source result is returned. */
    static void verifyOriginals(ProductSourceEvidenceFiles files,
            List<ProductCitationFileVerification.Reference> references) {
        // Reuse the existing bounded kernel without imposing the page's 100-item cap on a source.
        for (int start = 0; start < references.size(); start += 100)
            ProductCitationFileVerification.verify(files,
                    references.subList(start, Math.min(references.size(), start + 100)));
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
