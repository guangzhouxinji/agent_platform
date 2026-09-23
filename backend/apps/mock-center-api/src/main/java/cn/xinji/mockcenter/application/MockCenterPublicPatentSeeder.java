package cn.xinji.mockcenter.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Imports only the existing versioned public-pilot snapshot into a local development catalog. */
public final class MockCenterPublicPatentSeeder {
    private static final String CATALOG_VERSION = "mock-center-public-patent-v1";
    private final MockCenterPatentCatalogImporter importer;
    private final ObjectMapper json;

    public MockCenterPublicPatentSeeder(MockCenterPatentCatalogImporter importer, ObjectMapper json) { this.importer = importer; this.json = json; }

    public void seed() {
        List<MockCenterPatentCatalog.PatentRecord> records = load().stream().map(this::map).toList();
        String digest = digest(records.stream().map(value -> value.sourceId() + "|" + value.sourceRecordId() + "|" + value.sourceVersion() + "|" + value.contentDigest()).reduce("", (a, b) -> a + "\n" + b));
        importer.importBatch(new MockCenterPatentCatalogImporter.ImportBatch("public-pilot-" + digest.substring(0, 12), "public-pilot-v0.1", digest, records.size(), Instant.now().toString()), records);
    }

    private List<SeedRecord> load() {
        try (InputStream input = getClass().getResourceAsStream("/seed/public-patents-v0.1.json")) {
            if (input == null) throw new IllegalStateException("Mock center seed resource is missing");
            return json.readValue(input, new TypeReference<List<SeedRecord>>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read mock center seed", failure);
        }
    }

    private MockCenterPatentCatalog.PatentRecord map(SeedRecord value) {
        String fingerprint = digest("PUBLIC_PILOT|" + value.sourceRecordId() + "|public-pilot-v0.1|"
                + value.publicationNumber() + "|" + value.title() + "|" + value.abstractText());
        return new MockCenterPatentCatalog.PatentRecord("MCP-" + value.sourceRecordId(), "PUBLIC_PILOT", value.sourceRecordId(), "public-pilot-v0.1",
                value.publicationNumber(), value.applicationNumber(), value.title(), value.applicants(), value.inventors(), value.applicationDate(),
                value.publicationDate(), value.ipc(), value.abstractText(), value.abstractState(), value.sourceUrl(), "2026-09-16T00:00:00Z", fingerprint, CATALOG_VERSION);
    }
    private record SeedRecord(String sourceRecordId, String publicationNumber, String applicationNumber, String title,
                              List<String> applicants, List<String> inventors, LocalDate applicationDate,
                              LocalDate publicationDate, List<String> ipc, String sourceUrl,
                              @com.fasterxml.jackson.annotation.JsonProperty("abstract") String abstractText,
                              String abstractState, String topic) {}
    private static String digest(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString(); } catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); } }
}
