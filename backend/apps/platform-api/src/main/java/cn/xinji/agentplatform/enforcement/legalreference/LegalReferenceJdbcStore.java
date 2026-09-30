package cn.xinji.agentplatform.enforcement.legalreference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;

public final class LegalReferenceJdbcStore implements LegalReferenceStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public LegalReferenceJdbcStore(JdbcTemplate db, TransactionTemplate tx,
                                   ObjectMapper json, Clock clock) {
        this.db = db; this.tx = tx; this.json = json; this.clock = clock;
    }

    @Override public Optional<SearchResult> search(String id, long version) {
        return db.query("SELECT payload FROM enf9_legal_search WHERE search_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), SearchResult.class), id, version).stream().findFirst();
    }

    @Override public Optional<ReferenceRevision> reference(String id, long version) {
        return db.query("SELECT payload FROM enf9_legal_reference WHERE reference_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), ReferenceRevision.class), id, version).stream().findFirst();
    }

    @Override public List<ReferenceRevision> history(String id) {
        return db.query("SELECT payload FROM enf9_legal_reference WHERE reference_id=? ORDER BY revision",
                (rs, row) -> decode(rs.getString(1), ReferenceRevision.class), id);
    }

    @Override public long latestReferenceVersion(String id) {
        Long value = db.queryForObject("SELECT MAX(revision) FROM enf9_legal_reference WHERE reference_id=?",
                Long.class, id);
        return value == null ? 0 : value;
    }

    @Override public void addSearch(SearchResult r) {
        db.update("INSERT INTO enf9_legal_search(search_id,revision,organization_id,target_type,target_id,"
                        + "target_revision,search_state,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                r.searchId(), r.revision(), r.organization(), r.targetType(), r.targetId(), r.targetRevision(),
                r.status().name(), encode(r), Timestamp.from(clock.instant()));
    }

    @Override public void addReference(ReferenceRevision r) {
        db.update("INSERT INTO enf9_legal_reference(reference_id,revision,organization_id,search_id,"
                        + "search_revision,source_id,source_revision,source_version,article_id,verbatim_sha256,"
                        + "review_state,binding_state,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.referenceId(), r.revision(), r.organization(), r.searchId(), r.searchRevision(), r.sourceId(),
                r.sourceRevision(), r.sourceVersion(), r.articleId(), r.verbatimSha256(), r.reviewState().name(),
                r.bindingState().name(), encode(r), Timestamp.from(clock.instant()));
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> action) {
        return tx.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf9_legal_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var old = db.query("SELECT fingerprint,actor_ref,operation,object_id,object_revision "
                            + "FROM enf9_legal_receipt WHERE request_key=?",
                    (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)), key);
            if (!old.isEmpty()) {
                var saved = old.getFirst();
                if (!fingerprint.equals(saved.fingerprint()) || !actor.equals(saved.actor()) ||
                        !operation.equals(saved.operation()))
                    throw new IllegalArgumentException("Legal reference receipt conflict");
                return new Receipt(key, operation, saved.id(), saved.version());
            }
            Receipt receipt = action.get();
            var now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf9_legal_receipt(request_key,fingerprint,actor_ref,operation,"
                            + "object_id,object_revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    key, fingerprint, actor, operation, receipt.resultId(), receipt.version(), now);
            db.update("INSERT INTO enf9_legal_event(event_id,request_key,actor_ref,operation,object_id,"
                            + "object_revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), key, actor, operation, receipt.resultId(), receipt.version(), now);
            return receipt;
        });
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid legal data", failure); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Legal data unavailable", failure); }
    }
    private record Stored(String fingerprint, String actor, String operation, String id, long version) { }
}
