package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Pending source declarations only. No operation in this service can approve a source. */
public final class ProductSourceCandidates {
    public record Declaration(String sourceId, String contractVersion, String sourceVersion,
            String provenance, String owner, String licenseEvidenceRef, String licenseEvidenceSha256,
            Map<String, List<String>> coverage, List<String> requestedFields,
            Map<String, List<String>> requestedUses, List<String> searchableFields,
            boolean claimsAvailability, boolean fullTextAvailability, boolean queryCapability,
            boolean paginationCapability, boolean withdrawalCapability,
            Instant collectedAt, Instant validUntil) { }
    public record Candidate(String sourceId, String contractVersion, String sourceVersion,
            String status, long version, Declaration declaration,
            String retentionRef, long retentionVersion, String retentionEvidenceSha256) { }
    private record Receipt(String hash, String sourceId, String contractVersion,
            String operation, long resultingVersion) { }

    private static final Set<String> USES = Set.of("QUERY", "SAVE", "INDEX", "DISPLAY",
            "DOWNLOAD", "MODEL", "SHARE", "COMPARISON");
    private static final String ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,199}";
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ProductRetentionPolicy retention;
    private final Clock clock;

    public ProductSourceCandidates(JdbcTemplate db, TransactionTemplate transactions,
            ObjectMapper mapper, ProductRetentionPolicy retention, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.retention = retention;
        this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_source_contract", Integer.class);
    }

    public Candidate register(Declaration input, String requestId, String actor) {
        validate(input, requestId, actor);
        String json = json(input);
        String hash = digest(actor + "|REGISTER|" + input.sourceId() + "|" + input.contractVersion() + "|" + json);
        try {
            return transactions.execute(status -> {
                Receipt replay = receipt(requestId);
                if (replay != null) return replay(replay, hash, "REGISTER");
                if (find(input.sourceId(), input.contractVersion()) != null)
                    throw conflict();
                ProductSourceCaptureIdentity.lock(db,mapper,input,json);
                Instant now = clock.instant();
                db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,"
                        + "declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,retention_version,"
                        + "retention_evidence_sha,collected_at,valid_until,status,row_version,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'CANDIDATE',1,?)",
                        input.sourceId(), input.contractVersion(), input.sourceVersion(), json, hash,
                        input.licenseEvidenceRef(), input.licenseEvidenceSha256().toLowerCase(),
                        retention.reference(), retention.version(), retention.evidenceSha256(),
                        Timestamp.from(input.collectedAt()), Timestamp.from(input.validUntil()), Timestamp.from(now));
                event(input, actor, hash, "REGISTER", 0, 1, now);
                saveReceipt(requestId, hash, input, "REGISTER", 1, now);
                return find(input.sourceId(), input.contractVersion());
            });
        } catch (DataIntegrityViolationException collision) {
            if (receipt(requestId) != null || find(input.sourceId(), input.contractVersion()) != null
                    || !db.queryForList("SELECT source_version FROM con4_source_contract WHERE source_id=? "
                            + "AND source_version=?", String.class, input.sourceId(), input.sourceVersion()).isEmpty())
                throw conflict();
            throw collision;
        }
    }

    public Candidate withdraw(String sourceId, String contractVersion, long expectedVersion,
            String requestId, String actor) {
        validateKey(sourceId, contractVersion, requestId, actor);
        if (expectedVersion != 1) throw conflict();
        String hash = digest(actor + "|WITHDRAW|" + sourceId + "|" + contractVersion + "|" + expectedVersion);
        try {
            return transactions.execute(status -> {
                Receipt replay = receipt(requestId);
                if (replay != null) return replay(replay, hash, "WITHDRAW");
                Candidate current = find(sourceId, contractVersion);
                if (current == null || !"CANDIDATE".equals(current.status())
                        || current.version() != expectedVersion) throw conflict();
                int changed = db.update("UPDATE con4_source_contract SET status='WITHDRAWN',row_version=2 "
                        + "WHERE source_id=? AND contract_version=? AND status='CANDIDATE' AND row_version=1",
                        sourceId, contractVersion);
                if (changed != 1) throw conflict();
                Instant now = clock.instant();
                event(current.declaration(), actor, hash, "WITHDRAW", 1, 2, now);
                saveReceipt(requestId, hash, current.declaration(), "WITHDRAW", 2, now);
                return find(sourceId, contractVersion);
            });
        } catch (DataIntegrityViolationException collision) {
            if (receipt(requestId) != null) throw conflict();
            throw collision;
        }
    }

    public Candidate read(String sourceId, String contractVersion) {
        if (!valid(sourceId, ID) || !valid(contractVersion, ID)) throw invalid();
        Candidate candidate = find(sourceId, contractVersion);
        if (candidate == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return candidate;
    }

    private Candidate replay(Receipt receipt, String hash, String operation) {
        if (!receipt.hash().equals(hash) || !receipt.operation().equals(operation)) throw conflict();
        Candidate current = find(receipt.sourceId(), receipt.contractVersion());
        if (current == null || current.version() != receipt.resultingVersion()
                || !current.status().equals("REGISTER".equals(operation) ? "CANDIDATE" : "WITHDRAWN"))
            throw conflict();
        if ("REGISTER".equals(operation) && !current.declaration().validUntil().isAfter(clock.instant()))
            throw conflict();
        return current;
    }

    private Candidate find(String sourceId, String version) {
        List<Candidate> rows = db.query("SELECT source_version,status,row_version,declaration_json,"
                + "retention_ref,retention_version,retention_evidence_sha FROM con4_source_contract "
                + "WHERE source_id=? AND contract_version=?", (rs, row) -> {
                    try {
                        return new Candidate(sourceId, version, rs.getString(1), rs.getString(2),
                                rs.getLong(3), mapper.readValue(rs.getString(4), Declaration.class),
                                rs.getString(5), rs.getLong(6), rs.getString(7));
                    } catch (JsonProcessingException broken) {
                        throw new IllegalStateException("Source declaration is unreadable", broken);
                    }
                }, sourceId, version);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Receipt receipt(String requestId) {
        List<Receipt> rows = db.query("SELECT request_sha,source_id,contract_version,operation,resulting_version "
                + "FROM con4_source_request WHERE request_id=?", (rs, row) -> new Receipt(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)), requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void event(Declaration input, String actor, String hash, String operation,
            long before, long after, Instant now) {
        db.update("INSERT INTO con4_source_event(event_id,source_id,contract_version,operation,actor_ref,"
                + "request_sha,before_version,after_version,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), input.sourceId(), input.contractVersion(), operation,
                actor, hash, before, after, Timestamp.from(now));
    }

    private void saveReceipt(String requestId, String hash, Declaration input,
            String operation, long version, Instant now) {
        db.update("INSERT INTO con4_source_request(request_id,request_sha,source_id,contract_version,"
                + "operation,resulting_version,created_at) VALUES(?,?,?,?,?,?,?)", requestId, hash,
                input.sourceId(), input.contractVersion(), operation, version, Timestamp.from(now));
    }

    private void validate(Declaration value, String requestId, String actor) {
        if (value == null) throw invalid();
        validateKey(value.sourceId(), value.contractVersion(), requestId, actor);
        Instant now = clock.instant();
        if (!valid(value.sourceVersion(), "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") || !nonBlank(value.provenance()) || !nonBlank(value.owner())
                || !nonBlank(value.licenseEvidenceRef()) || !valid(value.licenseEvidenceSha256(), "[0-9a-fA-F]{64}")
                || value.collectedAt() == null || value.validUntil() == null
                || value.collectedAt().isAfter(now) || !value.validUntil().isAfter(now)
                || value.validUntil().isAfter(now.plus(retention.maxAge()))
                || value.coverage() == null || value.coverage().isEmpty()
                || value.coverage().entrySet().stream().anyMatch(e -> !nonBlank(e.getKey())
                    || "*".equals(e.getKey()) || !entries(e.getValue()))
                || !entries(value.requestedFields())
                || value.searchableFields() == null
                || (value.queryCapability() != !value.searchableFields().isEmpty())
                || (value.paginationCapability() && !value.queryCapability())
                || (!value.searchableFields().isEmpty() && !entries(value.searchableFields()))
                || !value.requestedFields().containsAll(value.searchableFields())
                || value.requestedUses() == null || value.requestedUses().isEmpty()
                || !Set.copyOf(value.requestedFields()).equals(value.requestedUses().keySet())
                || value.requestedUses().entrySet().stream().anyMatch(e -> !nonBlank(e.getKey())
                    || !entries(e.getValue()) || !USES.containsAll(e.getValue()))
                || value.searchableFields().stream().anyMatch(field ->
                    !value.requestedUses().getOrDefault(field, List.of()).contains("QUERY"))) throw invalid();
    }

    private void validateKey(String sourceId, String version, String requestId, String actor) {
        if (!valid(sourceId, ID) || !valid(version, ID) || !valid(requestId, ID) || !nonBlank(actor))
            throw invalid();
    }

    private static boolean entries(List<String> values) {
        return values != null && !values.isEmpty() && values.stream().allMatch(v -> nonBlank(v)
                && !"*".equals(v)) && values.size() == Set.copyOf(values).size();
    }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static boolean valid(String value, String pattern) {
        return value != null && value.matches(pattern);
    }
    private String json(Declaration value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid declaration", failure); }
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source declaration");
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Source candidate version conflict");
    }
}
