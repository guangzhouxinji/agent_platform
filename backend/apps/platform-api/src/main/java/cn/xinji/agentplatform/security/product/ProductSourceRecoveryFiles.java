package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Checks every persisted verification against the restored original bytes. */
final class ProductSourceRecoveryFiles {
    private record Verification(String source, String version, String manifest, String manifestSha,
                                String recordsSha, String inventorySha, long records, long originals) { }

    private final JdbcTemplate db;
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper = new ObjectMapper();

    ProductSourceRecoveryFiles(JdbcTemplate db, Path root, long maxBytes) {
        this.db = db;
        this.files = new ProductSourceEvidenceFiles(root, maxBytes);
    }

    void verify() {
        references();
    }

    Set<String> references() {
        Set<String> references = new LinkedHashSet<>();
        db.query("SELECT source_id,source_version,manifest_ref,manifest_sha,records_sha,"
                        + "inventory_sha,record_count,raw_count FROM con4_source_verification",
                (RowCallbackHandler) rs -> references.addAll(verify(new Verification(rs.getString(1),
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getLong(7), rs.getLong(8)))));
        return Set.copyOf(references);
    }

    private Set<String> verify(Verification row) {
        try {
            Set<String> references = new LinkedHashSet<>();
            byte[] manifestBytes = files.verified(row.manifest(), row.manifestSha());
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (ProductPatentsViewSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText())) {
                var capture = new ProductPatentsViewSourceInventory(files,mapper).inspect(row.manifest(),manifestBytes);
                if (!row.source().equals(capture.sourceId()) || !row.version().equals(capture.sourceVersion())
                        || !row.recordsSha().equals(capture.recordsSha()) || !row.inventorySha().equals(capture.inventorySha())
                        || row.records() != capture.records() || row.originals() != capture.originals())
                    throw new IllegalStateException("Restored native source inventory differs");
                return capture.references();
            }
            if (ProductHupdSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText())) {
                var nativeCapture = new ProductHupdSourceInventory(files, mapper).inspect(row.manifest(), manifestBytes);
                if (!row.source().equals(nativeCapture.sourceId()) || !row.version().equals(nativeCapture.sourceVersion())
                        || !row.recordsSha().equals(nativeCapture.recordsSha()) || !row.inventorySha().equals(nativeCapture.inventorySha())
                        || row.records() != nativeCapture.records() || row.originals() != nativeCapture.originals())
                    throw new IllegalStateException("Restored native source inventory differs");
                return nativeCapture.references();
            }
            references.add(row.manifest());
            String batchVersion = text(manifest.path("datasetId")) + ":"
                    + text(manifest.path("generatedAt"));
            if (!row.version().equals(batchVersion)
                    || manifest.path("recordCount").asLong(-1) != row.records())
                throw new IllegalStateException("Restored source version or record count differs");
            String parent = Path.of(row.manifest()).getParent() == null ? ""
                    : Path.of(row.manifest()).getParent().toString().replace('\\', '/') + "/";
            String recordsSha = text(manifest.path("recordsSha256"));
            if (!recordsSha.equalsIgnoreCase(row.recordsSha()))
                throw new IllegalStateException("Restored records digest differs");
            String recordsRef = parent + text(manifest.path("recordsFile"));
            JsonNode records = mapper.readTree(files.verified(recordsRef, recordsSha)).path("records");
            references.add(recordsRef);
            if (!records.isArray() || records.size() != row.records())
                throw new IllegalStateException("Restored record inventory differs");
            for (JsonNode record : records)
                if (record.hasNonNull("sourceId") && !row.source().equals(text(record.path("sourceId"))))
                    throw new IllegalStateException("Restored record belongs to another source");
            JsonNode raw = manifest.path("rawResponses");
            if (!raw.isArray() || raw.size() != row.originals())
                throw new IllegalStateException("Restored original count differs");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            Set<String> seen = new HashSet<>();
            for (JsonNode item : raw) {
                String name = text(item.path("file"));
                String sha = text(item.path("sha256"));
                if (!seen.add(name)) throw new IllegalStateException("Duplicate restored original");
                String rawRef = parent + name;
                files.verified(rawRef, sha);
                references.add(rawRef);
                hash.update((name.length() + ":" + name + sha.toLowerCase(java.util.Locale.ROOT))
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (!HexFormat.of().formatHex(hash.digest()).equals(row.inventorySha()))
                throw new IllegalStateException("Restored original inventory differs");
            return references;
        } catch (java.io.IOException | NoSuchAlgorithmException invalid) {
            throw new IllegalStateException("Restored source files cannot be verified", invalid);
        }
    }

    private static String text(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank())
            throw new IllegalStateException("Restored source manifest is incomplete");
        return node.asText();
    }
}
