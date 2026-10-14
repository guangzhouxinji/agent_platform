package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Capture integrity tests only: no identity or source-use grant is manufactured. */
class ProductCapturedSourceFieldsTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode manifest, records;
    ProductCollectedPatentTitles.CaptureSnapshot snapshot;
    void setup() throws Exception {
        byte[] raw = mapper.writeValueAsBytes(Map.of("claimsText", "1. Exact source claim."));
        Files.write(root.resolve("original.json"), raw);
        String sha = ProductCapturedArchive.digest(raw);
        records = mapper.createObjectNode().put("datasetId", "capture").put("generatedAt", "2026-10-08T06:00:00Z");
        records.putArray("records").addObject().put("sourceId", "source").put("sourceRecordId", "P1")
                .put("sourceVersion", "RAW_SHA256:" + sha).put("detailRawFile", "original.json");
        manifest = mapper.createObjectNode().put("datasetId", "capture").put("generatedAt", "2026-10-08T06:00:00Z")
                .put("recordsFile", "records.json").put("recordCount", 1);
        manifest.putObject("source").put("apiBaseUrl", "urn:owned:capture");
        manifest.putArray("rawResponses").addObject().put("file", "original.json").put("sha256", sha);
        refresh();
    }
    void refresh() throws Exception {
        byte[] rows = mapper.writeValueAsBytes(records); Files.write(root.resolve("records.json"), rows);
        manifest.put("recordsSha256", ProductCapturedArchive.digest(rows));
        byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        snapshot = new ProductCollectedPatentTitles.CaptureSnapshot("source", "capture:2026-10-08T06:00:00Z",
                "manifest.json", ProductCapturedArchive.digest(bytes), ProductCapturedArchive.digest(rows));
    }
    List<cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence> load() {
        return new ProductCollectedPatentTitles(new ProductSourceEvidenceFiles(root, 100000), mapper)
                .loadFieldEvidence(snapshot, ProductPatentSearchFilters.none(), Set.of("CLAIMS"));
    }
    @Test void nonemptyExactClaimAndByteLocator() throws Exception {
        setup(); var evidence = load().getFirst();
        assertEquals(Map.of("CLAIMS", "1. Exact source claim."), evidence.document().fields());
        assertTrue(evidence.fieldLocators().get("CLAIMS").contains("#json-pointer=/claimsText&utf8="));
        assertEquals("RAW_SHA256:" + evidence.document().rawSha(), evidence.document().documentVersion());
        assertEquals("1. Exact source claim.", new ProductPatentClaimOriginal("original.json",
                Files.readAllBytes(root.resolve("original.json")), evidence.document().rawSha(),
                evidence.document().documentVersion(), "claimsText", mapper).text());
    }
    @Test void explicitUnknownFormatFailsClosed() throws Exception {
        setup(); manifest.put("schemaVersion", "unknown"); refresh(); assertThrows(IllegalStateException.class, this::load);
    }
    @Test void wrongRecordSourceFailsClosed() throws Exception {
        setup(); ((ObjectNode) records.path("records").get(0)).put("sourceId", "other"); refresh();
        assertThrows(IllegalStateException.class, this::load);
    }
    @Test void wrongVersionFailsClosed() throws Exception {
        setup(); ((ObjectNode) records.path("records").get(0)).put("sourceVersion", "old"); refresh();
        assertThrows(IllegalStateException.class, this::load);
    }
    @Test void duplicateDocumentFailsClosed() throws Exception {
        setup(); ((com.fasterxml.jackson.databind.node.ArrayNode) records.path("records"))
                .add(records.path("records").get(0).deepCopy()); manifest.put("recordCount", 2); refresh();
        assertThrows(IllegalStateException.class, this::load);
    }
    @Test void wrongInventoryCountFailsClosed() throws Exception {
        setup(); manifest.put("recordCount", 2); refresh(); assertThrows(IllegalStateException.class, this::load);
    }
    @Test void missingOrTamperedOriginalFailsClosed() throws Exception {
        setup(); Files.writeString(root.resolve("original.json"), "{}"); assertThrows(IllegalStateException.class, this::load);
        Files.delete(root.resolve("original.json")); assertThrows(IllegalStateException.class, this::load);
    }
    @Test void unselectedInventoryTamperStillFailsClosed() throws Exception {
        setup(); Files.writeString(root.resolve("extra.json"), "{}");
        ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.path("rawResponses"))
                .addObject().put("file", "extra.json").put("sha256", "0".repeat(64)); refresh();
        assertThrows(IllegalStateException.class, this::load);
    }
    @Test void recordsAndManifestBindingFailsClosed() throws Exception {
        setup(); records.put("datasetId", "other"); refresh(); assertThrows(IllegalStateException.class, this::load);
    }
}
