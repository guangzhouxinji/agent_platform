package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

final class Dm8DocumentTemplateStore implements DocumentTemplateStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    Dm8DocumentTemplateStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.db = db; this.tx = tx; this.json = json; this.clock = clock;
    }

    @Override public Optional<Revision> latest(String id) {
        return db.query("SELECT payload FROM enf9_document_template_revision WHERE template_id=? "
                        + "AND revision=(SELECT MAX(revision) FROM enf9_document_template_revision WHERE template_id=?)",
                (rs, row) -> read(rs.getString(1), Revision.class), id, id).stream().findFirst();
    }

    @Override public Optional<Revision> revision(String id, long revision) {
        return db.query("SELECT payload FROM enf9_document_template_revision WHERE template_id=? AND revision=?",
                (rs, row) -> read(rs.getString(1), Revision.class), id, revision).stream().findFirst();
    }

    @Override public List<Summary> list(String organization) {
        return db.query("SELECT r.payload FROM enf9_document_template_revision r WHERE r.organization_id=? "
                        + "AND r.revision=(SELECT MAX(x.revision) FROM enf9_document_template_revision x WHERE x.template_id=r.template_id) "
                        + "ORDER BY r.created_at DESC",
                (rs, row) -> { Revision v = read(rs.getString(1), Revision.class); return new Summary(v.templateId(),
                        v.revision(), v.state(), v.name(), v.scope().organization(), v.fileSha256()); }, organization);
    }

    @Override public void add(Revision value) {
        db.update("INSERT INTO enf9_document_template_revision(template_id,revision,state,organization_id,name,"
                        + "file_sha256,file_key,payload,transition_evidence_ref,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                value.templateId(), value.revision(), value.state().name(), value.scope().organization(), value.name(),
                value.fileSha256(), value.fileKey(), write(value), value.transitionEvidenceRef(), value.createdBy(),
                Timestamp.from(value.createdAt()));
    }

    @Override public void transition(String id, long revision, State expected, State target, String evidenceRef) {
        Revision old = revision(id, revision).orElseThrow(() -> new IllegalArgumentException("Template unavailable"));
        Revision changed = new Revision(old.templateId(), old.revision(), target, old.name(), old.stage(), old.scope(),
                old.approvalRef(), old.fields(), old.fontNames(), old.fileKey(), old.fileName(), old.fileSha256(),
                old.createdBy(), old.createdAt(), evidenceRef);
        int count = db.update("UPDATE enf9_document_template_revision SET state=?,payload=?,transition_evidence_ref=? "
                        + "WHERE template_id=? AND revision=? AND state=?", target.name(), write(changed), evidenceRef,
                id, revision, expected.name());
        if (count != 1) throw new IllegalStateException("Template state conflict");
    }

    @Override public void addPreview(PreviewArtifact value) {
        PreviewReceipt receipt = value.receipt();
        db.update("INSERT INTO enf9_document_template_preview(preview_id,template_id,template_revision,template_sha256,"
                        + "artifact_sha256,file_key,payload,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                receipt.previewId(), receipt.templateId(), receipt.revision(), receipt.templateSha256(),
                receipt.artifactSha256(), value.fileKey(), write(value), value.createdBy(), Timestamp.from(value.createdAt()));
    }

    @Override public Optional<PreviewArtifact> preview(String id, String previewId) {
        return db.query("SELECT payload FROM enf9_document_template_preview WHERE template_id=? AND preview_id=?",
                (rs, row) -> read(rs.getString(1), PreviewArtifact.class), id, previewId).stream().findFirst();
    }

    @Override public <T> T command(String key, String fingerprint, String actor, String operation,
                                   Supplier<T> action, Class<T> type) {
        if (key == null || key.isBlank() || key.length() > 120) throw new IllegalArgumentException("Idempotency-Key required");
        return tx.execute(status -> {
            int slot = Math.floorMod(key.hashCode(), 8);
            db.queryForObject("SELECT lock_id FROM enf9_document_template_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, slot);
            var prior = db.query("SELECT fingerprint,actor_ref,operation,result_payload FROM enf9_document_template_receipt "
                            + "WHERE request_key=?", (rs, row) -> new Stored(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4)), key).stream().findFirst();
            if (prior.isPresent()) {
                Stored stored = prior.get();
                if (!stored.fingerprint().equals(fingerprint) || !stored.actor().equals(actor)
                        || !stored.operation().equals(operation)) throw new IllegalArgumentException("Idempotency receipt conflict");
                return read(stored.payload(), type);
            }
            T result = action.get(); String payload = write(result); Timestamp now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf9_document_template_receipt(request_key,fingerprint,actor_ref,operation,result_type,"
                            + "result_payload,created_at) VALUES(?,?,?,?,?,?,?)", key, fingerprint, actor, operation,
                    type.getName(), payload, now);
            db.update("INSERT INTO enf9_document_template_event(event_id,request_key,actor_ref,operation,created_at) "
                    + "VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), key, actor, operation, now);
            return result;
        });
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Document template serialization failed", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Document template data unreadable", e); }
    }
    private record Stored(String fingerprint, String actor, String operation, String payload) { }
}
