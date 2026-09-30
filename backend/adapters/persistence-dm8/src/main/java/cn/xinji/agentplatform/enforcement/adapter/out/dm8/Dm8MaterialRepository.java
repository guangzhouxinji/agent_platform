package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.MaterialWork;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** All command effects and their receipts commit in the same business transaction. */
public final class Dm8MaterialRepository implements MaterialRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Dm8MaterialSyncRecords syncRecords;

    public Dm8MaterialRepository(JdbcTemplate db, TransactionTemplate tx, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.syncRecords = new Dm8MaterialSyncRecords(db);
    }

    @Override public Optional<MaterialWork.Source> source(String id) {
        return db.query("SELECT source_id,organization_id,rights_ref,source_epoch,expires_at,allowed_uses,active,credential_ref,config_version "
                        + "FROM enf3_source WHERE source_id=?", (rs, n) -> new MaterialWork.Source(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5).toInstant(), Arrays.asList(rs.getString(6).split(",")),
                        "Y".equals(rs.getString(7)), rs.getString(8), rs.getLong(9)), id).stream().findFirst();
    }

    @Override public String syncCursor(String sourceId) {
        return db.queryForObject("SELECT cursor_value FROM enf3_sync_cursor WHERE source_id=?",
                String.class, sourceId);
    }

    @Override public void beginSyncAttempt(String id, String sourceId, String actor, String start) {
        long version = source(sourceId).orElseThrow().configVersion();
        db.update("INSERT INTO enf3_sync_attempt(attempt_id,source_id,actor_ref,start_cursor,processed_count,"
                        + "config_version,state,started_at) VALUES(?,?,?,?,0,?,'RUNNING',?)",
                id, sourceId, actor, start, version, Timestamp.from(clock.instant()));
    }

    @Override public void finishSyncAttempt(String id, String cursor, int processed, String state,
                                             String failureCode, String failureCursor) {
        int changed = db.update("UPDATE enf3_sync_attempt SET end_cursor=?,processed_count=?,state=?,"
                        + "failure_code=?,failure_cursor=?,finished_at=? WHERE attempt_id=? AND state='RUNNING'",
                cursor, processed, state, failureCode, failureCursor, Timestamp.from(clock.instant()), id);
        if (changed != 1) throw new IllegalStateException("Sync attempt was not running");
    }

    @Override public void recordSyncRecord(SyncRecord record) {
        syncRecords.record(record);
    }

    @Override public List<SyncRecord> syncRecords(String attemptId, String sourceId) {
        return syncRecords.read(attemptId, sourceId);
    }

    @Override public int successfulSyncRecords(String attemptId) {
        return syncRecords.successful(attemptId);
    }

    @Override public MaterialWork.Receipt atSyncCursor(String sourceId, String expected, String next,
                                                        MaterialCommand body) {
        return tx.execute(status -> {
            String current = db.queryForObject("SELECT cursor_value FROM enf3_sync_cursor "
                    + "WHERE source_id=? FOR UPDATE", String.class, sourceId);
            if (!expected.equals(current) || next == null || next.isBlank() || next.length() > 200
                    || expected.equals(next)) throw new IllegalStateException("Source cursor changed");
            var result = body.apply();
            db.update("UPDATE enf3_sync_cursor SET cursor_value=?,updated_at=? WHERE source_id=?",
                    next, Timestamp.from(clock.instant()), sourceId);
            return result;
        });
    }

    @Override public Optional<MaterialWork.Observation> observation(String id) {
        return db.query("SELECT observation_id,source_id,record_id,source_version,organization_id,source_text,"
                        + "text_sha256,material_mention,revision FROM enf3_observation WHERE observation_id=?",
                (rs, n) -> new MaterialWork.Observation(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getLong(9)), id).stream().findFirst();
    }

    @Override public Optional<MaterialWork.Rule> rule(String id) {
        return db.query("SELECT rule_id,revision,organization_id,source_id,literal_value,basis_ref,state,publisher "
                        + "FROM enf3_rule WHERE rule_id=? ORDER BY revision DESC",
                (rs, n) -> new MaterialWork.Rule(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                id).stream().findFirst();
    }

    @Override public List<MaterialWork.ActorMention> actors(String observationId) {
        return db.query("SELECT observation_id,ordinal_no,role_code,literal_value,source_locator,business_code "
                        + "FROM enf3_actor_mention WHERE observation_id=? ORDER BY ordinal_no",
                (rs, n) -> new MaterialWork.ActorMention(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)), observationId);
    }

    @Override public Optional<MaterialWork.Clue> clue(String id) {
        return db.query("SELECT clue_id,observation_id,rule_id,rule_revision,organization_id,state,revision "
                        + "FROM enf3_clue WHERE clue_id=?", (rs, n) -> new MaterialWork.Clue(rs.getString(1),
                        rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                rs.getLong(7)), id).stream().findFirst();
    }

    @Override public List<MaterialWork.RuleRef> clueRules(String clueId) {
        return db.query("SELECT rule_id,rule_revision FROM enf3_clue_rule WHERE clue_id=? "
                        + "ORDER BY rule_id,rule_revision",
                (rs, n) -> new MaterialWork.RuleRef(rs.getString(1), rs.getLong(2)), clueId);
    }

    @Override public Optional<MaterialWork.Material> material(String id) {
        return db.query("SELECT material_id,clue_id,organization_id,sha256,file_key,rights_ref,expires_at,"
                        + "allowed_uses,collector,collected_at,state,revision FROM enf3_material WHERE material_id=?",
                (rs, n) -> new MaterialWork.Material(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        Arrays.asList(rs.getString(8).split(",")), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getLong(12)), id).stream().findFirst();
    }

    @Override public List<MaterialWork.MaterialAction> materialActions(String materialId) {
        return db.query("SELECT material_id,revision,action_code,actor_ref,source_ref,reason,file_sha256,"
                        + "occurred_at FROM enf3_material_action WHERE material_id=? ORDER BY revision",
                (rs, n) -> new MaterialWork.MaterialAction(rs.getString(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getTimestamp(8).toInstant()), materialId);
    }

    @Override public List<MaterialWork.Revision> revisions(String clueId) {
        return db.query("SELECT clue_id,revision,actor_ref,reason,original_text,corrected_text,material_gap "
                        + "FROM enf3_revision WHERE clue_id=? ORDER BY revision",
                (rs, n) -> new MaterialWork.Revision(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), clueId);
    }

    @Override public MaterialWork.Receipt command(String requestId, String fingerprint, String actor,
                                                    String operation, MaterialCommand body) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 200
                || fingerprint == null || actor == null || actor.isBlank())
            throw new IllegalArgumentException("Command identity required");
        return tx.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf3_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, requestId.hashCode() & 15);
            var existing = db.query("SELECT request_sha,actor_ref,operation,result_id,result_version "
                            + "FROM enf3_receipt WHERE request_id=?",
                    (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)}, requestId);
            if (!existing.isEmpty()) {
                String[] row = existing.getFirst();
                if (!fingerprint.equals(row[0]) || !actor.equals(row[1]) || !operation.equals(row[2]))
                    throw new IllegalArgumentException("Request id conflict");
                return new MaterialWork.Receipt(requestId, operation, row[3], Long.parseLong(row[4]));
            }
            MaterialWork.Receipt result = body.apply();
            db.update("INSERT INTO enf3_receipt(request_id,request_sha,actor_ref,operation,result_id,"
                            + "result_version,created_at) VALUES(?,?,?,?,?,?,?)", requestId, fingerprint, actor,
                    operation, result.resultId(), result.resultVersion(), Timestamp.from(clock.instant()));
            db.update("INSERT INTO enf3_event(event_id,request_id,operation,actor_ref,result_id,occurred_at) "
                            + "VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), requestId, operation,
                    actor, result.resultId(), Timestamp.from(clock.instant()));
            return result;
        });
    }

    @Override public void addSource(MaterialWork.Source source) {
        db.update("INSERT INTO enf3_source(source_id,organization_id,rights_ref,source_epoch,expires_at,"
                        + "allowed_uses,active,credential_ref,config_version) VALUES(?,?,?,?,?,?,?,?,?)",
                source.id(), source.organization(),
                source.rightsRef(), source.epoch(), Timestamp.from(source.expiresAt()),
                String.join(",", source.uses()), source.active() ? "Y" : "N", source.credentialRef(),
                source.configVersion());
        db.update("INSERT INTO enf3_source_config_revision(source_id,config_version,credential_ref,active,"
                        + "actor_ref,changed_at) VALUES(?,?,?,?,?,?)", source.id(), source.configVersion(),
                source.credentialRef(), "Y", "SOURCE_REGISTRATION", Timestamp.from(clock.instant()));
        db.update("INSERT INTO enf3_sync_cursor(source_id,cursor_value,updated_at) VALUES(?,?,?)",
                source.id(), "START", Timestamp.from(clock.instant()));
    }

    @Override public void changeSourceConfiguration(String id, long expectedVersion, String credentialRef,
                                                     boolean enabled, String actor) {
        String cursor = db.queryForObject("SELECT cursor_value FROM enf3_sync_cursor WHERE source_id=? FOR UPDATE",
                String.class, id);
        if (cursor == null) throw new IllegalStateException("Source cursor unavailable");
        int changed = db.update("UPDATE enf3_source SET credential_ref=?,active=?,config_version=? "
                        + "WHERE source_id=? AND config_version=?", credentialRef, enabled ? "Y" : "N",
                expectedVersion + 1, id, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Source configuration version changed");
        db.update("INSERT INTO enf3_source_config_revision(source_id,config_version,credential_ref,active,"
                        + "actor_ref,changed_at) VALUES(?,?,?,?,?,?)", id, expectedVersion + 1, credentialRef,
                enabled ? "Y" : "N", actor, Timestamp.from(clock.instant()));
    }

    @Override public void addObservation(MaterialWork.Observation observation) {
        db.update("INSERT INTO enf3_observation(observation_id,source_id,record_id,source_version,organization_id,"
                        + "source_text,text_sha256,material_mention,revision) VALUES(?,?,?,?,?,?,?,?,?)",
                observation.id(), observation.sourceId(), observation.recordId(), observation.sourceVersion(),
                observation.organization(), observation.text(), observation.textSha256(),
                observation.materialMention(), observation.version());
    }

    @Override public void addActors(List<MaterialWork.ActorMention> actors) {
        for (var actor : actors) db.update("INSERT INTO enf3_actor_mention(observation_id,ordinal_no,role_code,"
                        + "literal_value,source_locator,business_code) VALUES(?,?,?,?,?,?)",
                actor.observationId(), actor.ordinal(), actor.role(), actor.literal(), actor.locator(),
                actor.businessCode());
    }

    @Override public void addRule(MaterialWork.Rule rule) {
        db.update("INSERT INTO enf3_rule(rule_id,revision,organization_id,source_id,literal_value,basis_ref,state,"
                        + "publisher) VALUES(?,?,?,?,?,?,?,?)", rule.id(), rule.revision(), rule.organization(),
                rule.sourceId(), rule.literal(), rule.basisRef(), rule.state(), rule.publisher());
    }

    @Override public void changeRule(MaterialWork.Rule rule, String actor, MaterialWork.RuleReview review) {
        String old = "ACTIVE".equals(rule.state()) ? "DRAFT" : "ACTIVE";
        int changed = db.update("UPDATE enf3_rule SET state=?,publisher=? WHERE rule_id=? AND revision=? AND state=?",
                rule.state(), rule.publisher(), rule.id(), rule.revision(), old);
        if (changed != 1) throw new IllegalStateException("Rule version changed");
        if ("ACTIVE".equals(rule.state())) {
            if (review == null) throw new IllegalArgumentException("Publication review required");
            db.update("INSERT INTO enf3_rule_review(rule_id,revision,positive_observation_id,"
                            + "negative_observation_id,positive_sha256,negative_sha256,reviewer,reviewed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?)", rule.id(), rule.revision(), review.positiveObservationId(),
                    review.negativeObservationId(), review.positiveSha256(), review.negativeSha256(), actor,
                    Timestamp.from(clock.instant()));
        }
    }

    @Override public void addClue(MaterialWork.Clue clue) {
        db.update("INSERT INTO enf3_clue(clue_id,observation_id,rule_id,rule_revision,organization_id,state,"
                        + "revision) VALUES(?,?,?,?,?,?,?)", clue.id(), clue.observationId(), clue.ruleId(),
                clue.ruleRevision(), clue.organization(), clue.state(), clue.version());
    }

    @Override public void lockObservation(String observationId) {
        db.queryForObject("SELECT observation_id FROM enf3_observation WHERE observation_id=? FOR UPDATE",
                String.class, observationId);
    }

    @Override public void addClueRule(String clueId, String ruleId, long revision) {
        db.update("INSERT INTO enf3_clue_rule(clue_id,rule_id,rule_revision) VALUES(?,?,?)",
                clueId, ruleId, revision);
    }

    @Override public void addRevision(MaterialWork.Revision revision) {
        int changed = db.update("UPDATE enf3_clue SET revision=? WHERE clue_id=? AND revision=?",
                revision.version(), revision.clueId(), revision.version() - 1);
        if (changed != 1) throw new IllegalStateException("Clue version changed");
        db.update("INSERT INTO enf3_revision(clue_id,revision,actor_ref,reason,original_text,corrected_text,"
                        + "material_gap) VALUES(?,?,?,?,?,?,?)", revision.clueId(), revision.version(),
                revision.actor(), revision.reason(), revision.originalText(), revision.correctedText(),
                revision.materialGap());
    }

    @Override public void addMaterial(MaterialWork.Material material) {
        db.update("INSERT INTO enf3_material(material_id,clue_id,organization_id,sha256,file_key,rights_ref,"
                        + "expires_at,allowed_uses,collector,collected_at,state,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                material.id(), material.clueId(), material.organization(), material.sha256(), material.fileKey(),
                material.rightsRef(), Timestamp.from(material.expiresAt()), String.join(",", material.uses()),
                material.collector(), material.collectedAt(), material.state(), material.version());
    }

    @Override public void appendMaterialAction(MaterialWork.MaterialAction action, String nextState) {
        if ("UPLOAD".equals(action.action())) {
            db.update("INSERT INTO enf3_material_action VALUES(?,?,?,?,?,?,?,?)", action.materialId(),
                    action.revision(), action.action(), action.actor(), action.sourceRef(), action.reason(),
                    action.fileSha256(), Timestamp.from(action.occurredAt()));
            return;
        }
        int changed = db.update("UPDATE enf3_material SET revision=?,state=? WHERE material_id=? "
                        + "AND revision=? AND state='PENDING_VERIFICATION'", action.revision(), nextState,
                action.materialId(), action.revision() - 1);
        if (changed != 1) throw new IllegalStateException("Material version changed");
        db.update("INSERT INTO enf3_material_action VALUES(?,?,?,?,?,?,?,?)", action.materialId(),
                action.revision(), action.action(), action.actor(), action.sourceRef(), action.reason(),
                action.fileSha256(), Timestamp.from(action.occurredAt()));
    }

    @Override public boolean fileReferenced(String fileKey) {
        return db.queryForObject("SELECT COUNT(*) FROM enf3_material WHERE file_key=?", Integer.class, fileKey) > 0;
    }

    @Override public void recordCleanup(String fileKey, String state) {
        int changed = db.update("UPDATE enf3_cleanup_event SET state=?,updated_at=? WHERE file_key=?",
                state, Timestamp.from(clock.instant()), fileKey);
        if (changed == 0) db.update("INSERT INTO enf3_cleanup_event(file_key,state,updated_at) VALUES(?,?,?)",
                fileKey, state, Timestamp.from(clock.instant()));
    }
}
