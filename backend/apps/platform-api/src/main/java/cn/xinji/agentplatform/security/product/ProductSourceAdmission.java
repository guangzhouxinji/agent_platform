package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Accountable product-use admission; third-party rights remain an independent fact. */
public final class ProductSourceAdmission {
    public record Decision(String decisionVersion, String sourceId, String contractVersion,
            String verificationId, String organizationId, String taskId, String scopeId,
            String rightsEvidenceStatus, String rightsEvidenceRef, String rightsEvidenceSha,
            String capabilityRef, String capabilitySha, String retentionRef,
            long retentionVersion, String retentionEvidenceSha, String responsibleParty,
            Instant validUntil, Map<String, List<String>> fieldUses) { }
    public record Input(String sourceId, String contractVersion, long expectedVersion,
            String decisionRef, String decisionSha, String requestId) { }
    public record Receipt(String decisionId, String sourceId, String contractVersion,
            String productStatus, String rightsEvidenceStatus, long sourceEpoch) { }

    private static final Set<String> USES = Set.of("QUERY", "SAVE", "INDEX", "DISPLAY", "MODEL", "COMPARISON");
    static boolean allowedUse(String use) { return use != null && USES.contains(use); }
    private final JdbcTemplate db;
    private final ProductSourceCandidates candidates;
    private final ProductSourceEvidenceFiles evidence;
    private final ProductSourceWatermark watermark;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProductSourceAdmission(JdbcTemplate db, ProductSourceCandidates candidates,
            ProductSourceEvidenceFiles evidence, ProductSourceWatermark watermark,
            ObjectMapper mapper, Clock clock) {
        this.db = db;
        this.candidates = candidates;
        this.evidence = evidence;
        this.watermark = watermark;
        this.mapper = mapper;
        this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_source_product_decision", Integer.class);
    }

    public Receipt enable(Input input, String actor, String actorOrganization) {
        if (input == null || !id(input.sourceId()) || !id(input.contractVersion())
                || !id(input.requestId()) || input.expectedVersion() != 1
                || actor == null || actor.isBlank() || actorOrganization == null)
            throw new IllegalArgumentException("Exact product source request required");
        byte[] bytes = evidence.verified(input.decisionRef(), input.decisionSha());
        Decision decision;
        try { decision = mapper.readValue(bytes, Decision.class); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid product decision", invalid); }
        validate(input, decision, actorOrganization);
        evidence.verified(decision.rightsEvidenceRef(), decision.rightsEvidenceSha());
        evidence.verified(decision.capabilityRef(), decision.capabilitySha());
        String hash = sha(actor + "|ENABLE|" + input.sourceId() + "|" + input.contractVersion()
                + "|" + input.expectedVersion() + "|" + input.decisionSha());
        Receipt replay = replay(input.requestId(), hash);
        if (replay != null) return replay;
        var candidate = candidates.read(input.sourceId(), input.contractVersion());
        if (!"CANDIDATE".equals(candidate.status()) || candidate.version() != 1
                || !candidate.declaration().validUntil().isAfter(clock.instant())
                || !candidate.retentionRef().equals(decision.retentionRef())
                || candidate.retentionVersion() != decision.retentionVersion()
                || !candidate.retentionEvidenceSha256().equalsIgnoreCase(decision.retentionEvidenceSha())
                || decision.validUntil().isAfter(candidate.declaration().validUntil()))
            throw new IllegalStateException("Source candidate or retention changed");
        for (var field : decision.fieldUses().entrySet()) {
            if (!candidate.declaration().requestedFields().contains(field.getKey())
                    || !candidate.declaration().requestedUses().getOrDefault(field.getKey(), List.of())
                            .containsAll(field.getValue())
                    || ("CLAIMS".equals(field.getKey())
                        && !candidate.declaration().claimsAvailability())
                    || ("FULL_TEXT".equals(field.getKey())
                        && !candidate.declaration().fullTextAvailability()))
                throw new IllegalArgumentException("Product use exceeds source declaration");
        }
        var verified = db.queryForObject("SELECT verification_id,manifest_ref,manifest_sha "
                + "FROM con4_source_verification "
                + "WHERE source_id=? AND contract_version=? AND source_version=? "
                + "AND candidate_row_version=1", (rs, row) -> List.of(rs.getString(1),
                        rs.getString(2), rs.getString(3)), input.sourceId(),
                input.contractVersion(), candidate.sourceVersion());
        if (!decision.verificationId().equals(verified.getFirst()))
            throw new IllegalStateException("Source verification changed");
        evidence.verified(verified.get(1), verified.get(2));
        Integer scope = db.queryForObject("SELECT COUNT(*) FROM con3_data_scope s "
                + "JOIN con3_task t ON t.task_id=s.task_id AND t.organization_id=s.organization_id "
                + "JOIN con3_organization o ON o.organization_id=t.organization_id "
                + "WHERE s.scope_id=? AND s.task_id=? AND s.organization_id=? "
                + "AND s.kind='TASK_METADATA' AND t.expires_at>=? AND o.status='ACTIVE'",
                Integer.class, decision.scopeId(), decision.taskId(), decision.organizationId(),
                Timestamp.from(decision.validUntil()));
        if (scope == null || scope != 1) throw new IllegalStateException("Task scope is unavailable");
        return watermark.change(input.sourceId(), intent -> {
            if (!candidate.equals(candidates.read(input.sourceId(), input.contractVersion()))
                    || !verified.getFirst().equals(db.queryForObject("SELECT verification_id FROM con4_source_verification "
                        + "WHERE source_id=? AND contract_version=?", String.class,
                        input.sourceId(), input.contractVersion())))
                throw new IllegalStateException("Source changed during admission");
            evidence.verified(input.decisionRef(), input.decisionSha());
            evidence.verified(verified.get(1), verified.get(2));
            List<ProductSourceWatermark.Current> previous = db.query(
                    "SELECT source_epoch,status,row_version,contract_version,decision_id "
                    + "FROM con4_source_current WHERE source_id=?", (rs, row) ->
                    new ProductSourceWatermark.Current(rs.getLong(1), rs.getString(2),
                            rs.getLong(3), rs.getString(4), rs.getString(5)), input.sourceId());
            if (previous.size() > 1 || !previous.isEmpty()
                    && (!"WITHDRAWN".equals(previous.getFirst().status())
                        || previous.getFirst().contractVersion().equals(input.contractVersion())))
                throw new IllegalStateException("Previous source contract must be withdrawn");
            long beforeEpoch = previous.isEmpty() ? 0 : previous.getFirst().epoch();
            long beforeVersion = previous.isEmpty() ? 0 : previous.getFirst().rowVersion();
            String decisionId = UUID.randomUUID().toString();
            String eventId = UUID.randomUUID().toString();
            Timestamp now = Timestamp.from(clock.instant());
            Timestamp until = Timestamp.from(decision.validUntil());
            db.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,"
                    + "rights_ref,rights_sha,capability_ref,capability_sha,responsible_party,"
                    + "maintainer_ref,coverage_sha,valid_until,decided_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    decisionId, input.sourceId(), input.contractVersion(),
                    decision.rightsEvidenceRef(), decision.rightsEvidenceSha().toLowerCase(),
                    decision.capabilityRef(), decision.capabilitySha().toLowerCase(),
                    decision.responsibleParty(), actor, input.decisionSha().toLowerCase(), until, now);
            db.update("INSERT INTO con4_source_product_decision(decision_id,decision_version,"
                    + "decision_ref,decision_sha,rights_evidence_status,organization_id,task_id,"
                    + "scope_id,retention_ref,retention_version,retention_evidence_sha) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", decisionId, decision.decisionVersion(),
                    input.decisionRef(), input.decisionSha().toLowerCase(),
                    decision.rightsEvidenceStatus(), decision.organizationId(), decision.taskId(),
                    decision.scopeId(), decision.retentionRef(), decision.retentionVersion(),
                    decision.retentionEvidenceSha().toLowerCase());
            for (var field : decision.fieldUses().entrySet())
                for (String use : field.getValue())
                    db.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,"
                            + "valid_until) VALUES(?,?,?,?)", decisionId, field.getKey(), use, until);
            if (db.update("UPDATE con4_source_contract SET status='ACTIVE',row_version=2 "
                    + "WHERE source_id=? AND contract_version=? AND status='CANDIDATE' AND row_version=1",
                    input.sourceId(), input.contractVersion()) != 1)
                throw new IllegalStateException("Source candidate changed");
            if (previous.isEmpty()) {
                db.update("INSERT INTO con4_source_current(source_id,contract_version,decision_id,"
                        + "source_epoch,status,row_version,history_sha,updated_at) "
                        + "VALUES(?,?,?,1,'ACTIVE',1,?,?)", input.sourceId(), input.contractVersion(),
                        decisionId, "0".repeat(64), now);
            } else if (db.update("UPDATE con4_source_current SET contract_version=?,decision_id=?,"
                    + "source_epoch=?,status='ACTIVE',row_version=?,updated_at=? "
                    + "WHERE source_id=? AND contract_version=? AND decision_id=? "
                    + "AND source_epoch=? AND row_version=? AND status='WITHDRAWN'",
                    input.contractVersion(), decisionId, beforeEpoch + 1, beforeVersion + 1,
                    now, input.sourceId(), previous.getFirst().contractVersion(),
                    previous.getFirst().decisionId(), beforeEpoch, beforeVersion) != 1)
                throw new IllegalStateException("Previous source contract changed");
            event(input.sourceId(), input.contractVersion(), decisionId, "ADMIT",
                    beforeEpoch, beforeEpoch + 1, beforeVersion, beforeVersion + 1,
                    intent, actor, hash, eventId, now);
            db.update("INSERT INTO con4_source_authority_request(request_id,request_sha,event_id,"
                    + "created_at) VALUES(?,?,?,?)", input.requestId(), hash, eventId, now);
            return new Receipt(decisionId, input.sourceId(), input.contractVersion(),
                    "PRODUCT_ENABLED", decision.rightsEvidenceStatus(), beforeEpoch + 1);
        });
    }

    public Receipt withdraw(String sourceId, long expectedEpoch, String requestId, String actor) {
        if (!id(sourceId) || !id(requestId) || expectedEpoch < 1
                || actor == null || actor.isBlank())
            throw new IllegalArgumentException("Exact withdrawal request required");
        String hash = sha(actor + "|WITHDRAW|" + sourceId + "|" + expectedEpoch);
        Receipt replay = replay(requestId, hash);
        if (replay != null) return replay;
        var current = watermark.current(sourceId);
        if (!"ACTIVE".equals(current.status()) || current.epoch() != expectedEpoch)
            throw new IllegalStateException("Source is not enabled at expected epoch");
        return watermark.change(sourceId, intent -> {
            var before = db.queryForObject("SELECT source_epoch,row_version,contract_version,decision_id "
                    + "FROM con4_source_current WHERE source_id=?", (rs, row) ->
                    new ProductSourceWatermark.Current(rs.getLong(1), "ACTIVE", rs.getLong(2),
                            rs.getString(3), rs.getString(4)), sourceId);
            if (!current.equals(before)) throw new IllegalStateException("Source changed");
            Timestamp now = Timestamp.from(clock.instant());
            db.update("UPDATE con4_source_current SET source_epoch=?,row_version=?,"
                    + "status='WITHDRAWN',updated_at=? WHERE source_id=?", current.epoch() + 1,
                    current.rowVersion() + 1, now, sourceId);
            db.update("UPDATE con4_source_contract SET status='WITHDRAWN',row_version=3 "
                    + "WHERE source_id=? AND contract_version=? AND status='ACTIVE'",
                    sourceId, current.contractVersion());
            String eventId = UUID.randomUUID().toString();
            event(sourceId, current.contractVersion(), current.decisionId(), "WITHDRAW",
                    current.epoch(), current.epoch() + 1, current.rowVersion(),
                    current.rowVersion() + 1, intent, actor, hash, eventId, now);
            db.update("INSERT INTO con4_source_authority_request(request_id,request_sha,event_id,"
                    + "created_at) VALUES(?,?,?,?)", requestId, hash, eventId, now);
            return new Receipt(current.decisionId(), sourceId, current.contractVersion(),
                    "REVOKED", rightsStatus(current.decisionId()), current.epoch() + 1);
        });
    }

    private void event(String sourceId, String version, String decisionId, String operation,
            long beforeEpoch, long afterEpoch, long beforeVersion, long afterVersion,
            ProductSourceWatermark.Intent intent, String actor, String hash, String eventId,
            Timestamp now) {
        db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,"
                + "decision_id,operation,before_epoch,after_epoch,before_version,after_version,"
                + "intent_id,before_history_sha,actor_ref,request_sha,occurred_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", eventId, sourceId, version, decisionId,
                operation, beforeEpoch, afterEpoch, beforeVersion, afterVersion, intent.id(),
                intent.beforeHistorySha(), actor, hash, now);
    }

    private Receipt replay(String requestId, String hash) {
        List<Receipt> rows = db.query("SELECT e.request_sha,a.decision_id,e.source_id,"
                + "e.contract_version,e.operation,e.after_epoch,d.rights_evidence_status "
                + "FROM con4_source_authority_request r JOIN con4_source_authority_event e "
                + "ON e.event_id=r.event_id JOIN con4_source_admission a ON a.decision_id=e.decision_id "
                + "JOIN con4_source_product_decision d ON d.decision_id=a.decision_id "
                + "WHERE r.request_id=?", (rs, row) -> {
                    if (!hash.equals(rs.getString(1))) throw new IllegalStateException("Request conflict");
                    return new Receipt(rs.getString(2), rs.getString(3), rs.getString(4),
                            "ADMIT".equals(rs.getString(5)) ? "PRODUCT_ENABLED" : "REVOKED",
                            rs.getString(7), rs.getLong(6));
                }, requestId);
        if (rows.isEmpty()) return null;
        Receipt receipt = rows.getFirst();
        var current = watermark.current(receipt.sourceId());
        if (current.epoch() != receipt.sourceEpoch()
                || !current.decisionId().equals(receipt.decisionId())
                || ("PRODUCT_ENABLED".equals(receipt.productStatus())
                    != "ACTIVE".equals(current.status())))
            throw new IllegalStateException("Old source receipt is no longer current");
        return receipt;
    }

    private String rightsStatus(String decisionId) {
        return db.queryForObject("SELECT rights_evidence_status FROM con4_source_product_decision "
                + "WHERE decision_id=?", String.class, decisionId);
    }

    private void validate(Input input, Decision value, String organization) {
        if (value == null || !id(value.decisionVersion()) || !input.sourceId().equals(value.sourceId())
                || !input.contractVersion().equals(value.contractVersion())
                || !id(value.verificationId()) || !organization.equals(value.organizationId())
                || !id(value.taskId()) || !id(value.scopeId())
                || !"UNRESOLVED".equals(value.rightsEvidenceStatus())
                || !id(value.responsibleParty()) || value.validUntil() == null
                || !value.validUntil().isAfter(clock.instant())
                || value.retentionVersion() < 1 || !id(value.retentionRef())
                || !sha256(value.retentionEvidenceSha())
                || value.fieldUses() == null || value.fieldUses().isEmpty()
                || value.fieldUses().entrySet().stream().anyMatch(e -> !id(e.getKey())
                    || e.getValue() == null || e.getValue().isEmpty()
                    || !USES.containsAll(e.getValue())
                    || Set.copyOf(e.getValue()).size() != e.getValue().size()))
            throw new IllegalArgumentException("Invalid product decision scope or uses");
    }

    private static boolean id(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    }
    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
