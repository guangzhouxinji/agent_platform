package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies captured bytes and locators, without granting any product use. */
public final class ProductSourceVerification {
    public record Input(String sourceId, String contractVersion, long expectedVersion,
                        String manifestRef, String manifestSha, String requestId) { }
    public record Receipt(String verificationId, String sourceId, String contractVersion,
                          String recordsSha, String inventorySha, long recordCount, long rawCount) { }
    private record Inventory(String recordsSha, String inventorySha, long records, long raw) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductSourceCandidates candidates;
    private final ProductSourceEvidenceFiles evidence;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProductSourceVerification(JdbcTemplate db, TransactionTemplate transactions,
            ProductSourceCandidates candidates, ProductSourceEvidenceFiles evidence,
            ObjectMapper mapper, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.candidates = candidates;
        this.evidence = evidence;
        this.mapper = mapper;
        this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_source_verification", Integer.class);
    }

    public Receipt verify(Input input, String actor) {
        if (input == null || input.sourceId() == null || input.contractVersion() == null
                || input.expectedVersion() < 1 || actor == null || actor.isBlank()
                || input.requestId() == null
                || !input.requestId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact source verification request required");
        var candidate = candidates.read(input.sourceId(), input.contractVersion());
        if (!"CANDIDATE".equals(candidate.status()) || candidate.version() != input.expectedVersion()
                || !candidate.declaration().validUntil().isAfter(clock.instant()))
            throw new IllegalStateException("Source candidate is no longer current");
        byte[] manifestBytes = evidence.verified(input.manifestRef(), input.manifestSha());
        Inventory inventory = inspect(input.manifestRef(), manifestBytes, candidate);
        String requestSha = digest((actor + "|" + input.sourceId() + "|" + input.contractVersion()
                + "|" + input.expectedVersion() + "|" + input.manifestRef() + "|"
                + input.manifestSha().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return transactions.execute(status -> {
            var current = candidates.read(input.sourceId(), input.contractVersion());
            if (!current.equals(candidate))
                throw new IllegalStateException("Source candidate changed during verification");
            List<Receipt> previous = db.query("SELECT r.request_sha,v.verification_id,v.source_id,"
                            + "v.contract_version,v.records_sha,v.inventory_sha,v.record_count,v.raw_count "
                            + "FROM con4_source_verification_request r JOIN con4_source_verification v "
                            + "ON v.verification_id=r.verification_id WHERE r.request_id=?",
                    (rs, row) -> {
                        if (!requestSha.equals(rs.getString(1)))
                            throw new IllegalStateException("Source verification request conflict");
                        return new Receipt(rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8));
                    }, input.requestId());
            if (!previous.isEmpty()) return previous.getFirst();
            Integer existing = db.queryForObject("SELECT COUNT(*) FROM con4_source_verification "
                    + "WHERE source_id=? AND contract_version=?", Integer.class,
                    input.sourceId(), input.contractVersion());
            if (existing != null && existing != 0)
                throw new IllegalStateException("Source contract already verified");
            String id = UUID.randomUUID().toString();
            Timestamp now = Timestamp.from(clock.instant());
            db.update("INSERT INTO con4_source_verification(verification_id,source_id,contract_version,"
                            + "source_version,candidate_row_version,manifest_ref,manifest_sha,records_sha,"
                            + "inventory_sha,record_count,raw_count,verifier_ref,verified_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", id, input.sourceId(),
                    input.contractVersion(), candidate.sourceVersion(), input.expectedVersion(),
                    input.manifestRef(), input.manifestSha().toLowerCase(java.util.Locale.ROOT),
                    inventory.recordsSha(), inventory.inventorySha(), inventory.records(),
                    inventory.raw(), actor, now);
            db.update("INSERT INTO con4_source_verification_request(request_id,request_sha,"
                    + "verification_id,created_at) VALUES(?,?,?,?)", input.requestId(), requestSha, id, now);
            return new Receipt(id, input.sourceId(), input.contractVersion(), inventory.recordsSha(),
                    inventory.inventorySha(), inventory.records(), inventory.raw());
        });
    }

    private Inventory inspect(String manifestRef, byte[] bytes, ProductSourceCandidates.Candidate candidate) {
        try {
            JsonNode manifest = mapper.readTree(bytes);
            String batchVersion = required(manifest.path("datasetId")) + ":"
                    + required(manifest.path("generatedAt"));
            if (!candidate.sourceVersion().equals(batchVersion)
                    || !candidate.declaration().provenance()
                            .equals(required(manifest.path("source").path("apiBaseUrl"))))
                throw new IllegalStateException("Captured source does not match the registered contract");
            String recordsFile = required(manifest.path("recordsFile"));
            String recordsSha = required(manifest.path("recordsSha256"));
            long count = manifest.path("recordCount").asLong(-1);
            JsonNode raw = manifest.path("rawResponses");
            if (count < 1 || !raw.isArray() || raw.isEmpty())
                throw new IllegalStateException("Source inventory is incomplete");
            String parent = Path.of(manifestRef).getParent() == null ? ""
                    : Path.of(manifestRef).getParent().toString().replace('\\', '/') + "/";
            JsonNode records = mapper.readTree(evidence.verified(parent + recordsFile, recordsSha));
            JsonNode rows = records.path("records");
            if (!rows.isArray() || rows.size() != count)
                throw new IllegalStateException("Source record count differs from the manifest");
            Set<String> keys = new HashSet<>();
            for (JsonNode row : rows) {
                if (row.hasNonNull("sourceId")
                        && !candidate.sourceId().equals(required(row.path("sourceId"))))
                    throw new IllegalStateException("Document belongs to another source");
                String id = required(row.path("sourceRecordId"));
                String version = required(row.path("sourceVersion"));
                required(row.path("capturedAt"));
                if ((!row.path("detailUrl").isTextual() || row.path("detailUrl").asText().isBlank())
                        && (!row.path("sourceRef").isTextual() || row.path("sourceRef").asText().isBlank()))
                    throw new IllegalStateException("Source locator is missing");
                if (!keys.add(id + "\u0000" + version))
                    throw new IllegalStateException("Duplicate observed document version");
            }
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            Set<String> files = new HashSet<>();
            for (JsonNode item : raw) {
                String file = required(item.path("file"));
                String sha = required(item.path("sha256"));
                if (!files.add(file)) throw new IllegalStateException("Duplicate original file");
                evidence.verified(parent + file, sha);
                hash.update((file.length() + ":" + file + sha.toLowerCase(java.util.Locale.ROOT))
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Inventory(recordsSha.toLowerCase(java.util.Locale.ROOT),
                    HexFormat.of().formatHex(hash.digest()), count, raw.size());
        } catch (java.io.IOException | NoSuchAlgorithmException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Source inventory cannot be verified", invalid);
        }
    }

    private static String required(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank())
            throw new IllegalStateException("Source inventory field is missing");
        return node.asText();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
