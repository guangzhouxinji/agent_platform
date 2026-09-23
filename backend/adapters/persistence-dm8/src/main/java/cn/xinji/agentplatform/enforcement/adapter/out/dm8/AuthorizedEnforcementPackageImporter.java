package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.ClueData;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.EvidenceData;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;

public final class AuthorizedEnforcementPackageImporter {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final Path packageDirectory;
    private final Clock clock;

    public AuthorizedEnforcementPackageImporter(DataSource dataSource, ObjectMapper mapper, Path packageDirectory) {
        this(dataSource, mapper, packageDirectory, Clock.systemUTC());
    }

    AuthorizedEnforcementPackageImporter(DataSource dataSource, ObjectMapper mapper, Path packageDirectory, Clock clock) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.packageDirectory = packageDirectory;
        this.clock = clock;
    }

    public ImportResult importPackage() {
        AuthorizedEnforcementPackage value = new AuthorizedEnforcementPackageValidator(mapper).load(packageDirectory);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ImportResult replay = existing(connection, value);
                if (replay != null) {
                    connection.commit();
                    return replay;
                }
                rejectDatasetConflict(connection, value);
                insertBatch(connection, value);
                insertAuthorization(connection, value);
                for (ClueData clue : value.clues()) insertClue(connection, value.manifest().batchId(), clue);
                for (EvidenceData evidence : value.evidence()) insertEvidence(connection, value.manifest().batchId(), evidence);
                for (AuthorizedEnforcementPackage.RuleRecord rule : value.rules()) insertRule(connection, value.manifest().batchId(), rule);
                for (AuthorizedEnforcementPackage.RightStatusRecord right : value.rightStatuses()) {
                    insertRight(connection, value.manifest().batchId(), right);
                }
                verifyRows(connection, value);
                connection.commit();
                return new ImportResult(value.manifest().batchId(), value.recordCount(), true, false);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            if (failure instanceof AuthorizedImportException known) throw known;
            throw new AuthorizedImportException("AUTHORIZED_IMPORT_FAILED", failure);
        }
    }
    public void withdrawBatch(String batchId, String reason, Instant withdrawnAt) {
        if (batchId == null || batchId.isBlank() || reason == null || reason.isBlank() || withdrawnAt == null) {
            throw new AuthorizedImportException("WITHDRAWAL_INPUT_INVALID");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed = update(connection, "UPDATE enf_local_clue SET availability='WITHDRAWN' WHERE batch_id=?", batchId);
                changed += update(connection, "UPDATE enf_local_evidence SET availability='WITHDRAWN' WHERE batch_id=?", batchId);
                changed += update(connection, "UPDATE enf_local_right_snapshot SET availability='WITHDRAWN' WHERE batch_id=?", batchId);
                changed += update(connection, "UPDATE enf_local_rule_release SET release_status='WITHDRAWN',withdrawn_at=?,withdrawal_reason=? WHERE batch_id=? AND release_status='PUBLISHED'",
                        Timestamp.from(withdrawnAt), reason, batchId);
                if (changed == 0) throw new AuthorizedImportException("AUTHORIZED_BATCH_NOT_FOUND");
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            if (failure instanceof AuthorizedImportException known) throw known;
            throw new AuthorizedImportException("AUTHORIZED_WITHDRAWAL_FAILED", failure);
        }
    }
    private ImportResult existing(Connection c, AuthorizedEnforcementPackage value) throws Exception {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT records_sha256,record_count,data_mode FROM enf_local_source_batch WHERE batch_id=?")) {
            s.setString(1, value.manifest().batchId());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                if (!value.packageSha256().equals(r.getString(1)) || value.recordCount() != r.getInt(2)
                        || !"AUTHORIZED_LOCAL".equals(r.getString(3))) {
                    throw new AuthorizedImportException("AUTHORIZED_PACKAGE_CONFLICT");
                }
                return new ImportResult(value.manifest().batchId(), value.recordCount(), false, true);
            }
        }
    }
    private void rejectDatasetConflict(Connection c, AuthorizedEnforcementPackage value) throws Exception {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT batch_id,records_sha256 FROM enf_local_source_batch WHERE dataset_id=? AND dataset_version=?")) {
            s.setString(1, value.manifest().datasetId());
            s.setString(2, value.manifest().datasetVersion());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) throw new AuthorizedImportException("AUTHORIZED_DATASET_VERSION_CONFLICT");
            }
        }
    }
    private void insertBatch(Connection c, AuthorizedEnforcementPackage value) throws Exception {
        var m = value.manifest();
        execute(c, "INSERT INTO enf_local_source_batch VALUES (?,?,?,?,?,?,?,?,?,?)",
                m.batchId(), m.datasetId(), m.datasetVersion(), "AUTHORIZED_LOCAL", value.recordCount(),
                value.packageSha256(), m.approvalDecisionId(), "N", "COMPLETED", Timestamp.from(clock.instant()));
    }
    private void insertAuthorization(Connection c, AuthorizedEnforcementPackage value) throws Exception {
        var m = value.manifest();
        execute(c, "INSERT INTO enf_local_authorization "
                        + "(batch_id,approval_decision_id,approved_by,approved_at,source_name,source_type,allowed_use,"
                        + "record_scope,retention_policy,retention_until,withdrawal_contact,withdrawal_process,"
                        + "manifest_json,manifest_sha256,package_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                m.batchId(), m.approvalDecisionId(), m.approvedBy(), Timestamp.from(m.approvedAt()),
                m.sourceName(), m.sourceType(), m.allowedUse(), m.recordScope(), m.retentionPolicy(),
                m.retentionUntil() == null ? null : Timestamp.from(m.retentionUntil()),
                m.withdrawalContact(), m.withdrawalProcess(), value.manifestJson(), value.manifestSha256(), value.packageSha256());
    }
    private void insertClue(Connection c, String batchId, ClueData clue) throws Exception {
        Payload payload = payload(clue);
        SourceLocator source = clue.source();
        execute(c, "INSERT INTO enf_local_clue VALUES (?,?,?,?,?,?,?,?)", source.sourceId(), source.version(),
                batchId, source.sourceSystem(), source.location(), "AVAILABLE", payload.json(), payload.sha256());
    }
    private void insertEvidence(Connection c, String batchId, EvidenceData evidence) throws Exception {
        Payload payload = payload(evidence);
        SourceLocator source = evidence.source();
        execute(c, "INSERT INTO enf_local_evidence VALUES (?,?,?,?,?,?,?,?,?,?)", source.sourceId(), source.version(),
                batchId, evidence.relatedClueId(), source.sourceSystem(), source.location(), evidence.availability(),
                evidence.controlledResourceRef(), payload.json(), payload.sha256());
    }

    private void insertRule(Connection c, String batchId, AuthorizedEnforcementPackage.RuleRecord rule) throws Exception {
        SourceLocator source = rule.source();
        execute(c, "INSERT INTO enf_local_rule_snapshot VALUES (?,?,?,?,?)", source.sourceId(), source.version(),
                batchId, source.sourceSystem(), source.location());
        execute(c, "INSERT INTO enf_local_rule_release (rule_id,rule_version,batch_id,release_status,approved_by,"
                        + "approved_at,effective_from,effectivity_status,effectivity_basis,applicability,rule_type,"
                        + "content_sha256,withdrawn_at,withdrawal_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", source.sourceId(),
                source.version(), batchId, rule.status(), rule.approvedBy(), Timestamp.from(rule.approvedAt()),
                rule.effectiveFrom() == null ? null : Timestamp.from(rule.effectiveFrom()), rule.effectivityStatus(),
                rule.effectivityBasis(), rule.applicability(), rule.ruleType(), rule.contentSha256(), null, null);
    }

    private void insertRight(Connection c, String batchId, AuthorizedEnforcementPackage.RightStatusRecord right) throws Exception {
        SourceLocator source = right.source();
        execute(c, "INSERT INTO enf_local_right_snapshot VALUES (?,?,?,?,?,?,?)", source.sourceId(), right.clueId(),
                source.version(), batchId, source.sourceSystem(), source.location(), right.availability());
    }

    private Payload payload(Object value) throws Exception {
        String json = mapper.writeValueAsString(value);
        return new Payload(json, EnforcementLocalSourceCodec.sha256(json));
    }

    private void verifyRows(Connection c, AuthorizedEnforcementPackage value) throws Exception {
        String batchId = value.manifest().batchId();
        requireCount(c, "enf_local_clue", batchId, value.clues().size());
        requireCount(c, "enf_local_evidence", batchId, value.evidence().size());
        requireCount(c, "enf_local_rule_snapshot", batchId, value.rules().size());
        requireCount(c, "enf_local_rule_release", batchId, value.rules().size());
        requireCount(c, "enf_local_right_snapshot", batchId, value.rightStatuses().size());
        requireCount(c, "enf_local_authorization", batchId, 1);
    }

    private static void requireCount(Connection c, String table, String batchId, int expected) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE batch_id=?")) {
            s.setString(1, batchId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getInt(1) != expected) throw new AuthorizedImportException("AUTHORIZED_IMPORT_INCOMPLETE");
            }
        }
    }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }

    private static int update(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            return s.executeUpdate();
        }
    }

    private record Payload(String json, String sha256) {}
    public record ImportResult(String batchId, int recordCount, boolean batchCreated, boolean replayed) {}

    public static final class AuthorizedImportException extends RuntimeException {
        public AuthorizedImportException(String code) { super(code); }
        public AuthorizedImportException(String code, Throwable cause) { super(code, cause); }
    }
}
