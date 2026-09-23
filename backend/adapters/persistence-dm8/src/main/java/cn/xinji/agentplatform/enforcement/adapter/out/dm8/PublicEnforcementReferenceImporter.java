package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.PublicEnforcementReferenceCatalogPort.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Imports only already-approved, local public-source snapshots. It never fetches a remote source. */
public final class PublicEnforcementReferenceImporter {
    public static final String APPROVED_SOURCE_ID = "SURECHEMBL_PUBLIC_SEARCH";
    public static final String APPROVAL_ID = "ENF-SURECHEMBL-INTERNAL-DEVELOPMENT-V1";
    public static final String APPROVAL_SHA256 = "0567dccfe43e65175c44e499132c5e37b56ba9b8321678cc7b2184a445c63838";
    public static final String RETENTION_PERIOD = "UNTIL_DEVELOPMENT_PILOT_END";
    public static final String REQUIRED_ALLOWED_USES = "INTERNAL_DEVELOPMENT_PILOT,LOCAL_DM8_STORAGE,LOCAL_DM8_INDEXING";
    public static final String REQUIRED_FORBIDDEN_USES = "EXTERNAL_MODEL_PROCESSING,PRODUCTION_USE,EXTERNAL_REDISTRIBUTION,FORMAL_ENFORCEMENT_EVIDENCE,CURRENT_OR_HISTORICAL_RIGHT_STATUS_DETERMINATION,ENFORCEMENT_WORKFLOW_INPUT";
    public static final String REQUIRED_ATTRIBUTION = "SureChEMBL and EMBL-EBI; CC BY 4.0; transformed internal-development catalog; https://www.surechembl.org";
    public static final String CNIPA_SOURCE_ID = "CNIPA_OFFICIAL_PUBLIC_CASES";
    public static final String CNIPA_APPROVAL_ID = "ENF-CNIPA-HISTORICAL-CASES-INTERNAL-DEVELOPMENT-V1";
    public static final String CNIPA_APPROVAL_SHA256 = "3a3170999aea58365a1f0dce641afbf2195dd4ffff55b9e80d89456abe32c4ed";
    public static final String CNIPA_ATTRIBUTION = "国家知识产权局公开行政保护案例；内部开发期历史参考；https://www.cnipa.gov.cn";
    private static final Map<String, ApprovalProfile> APPROVALS = Map.of(
            APPROVED_SOURCE_ID, new ApprovalProfile(APPROVAL_ID, APPROVAL_SHA256, REQUIRED_ATTRIBUTION,
                    "PUBLIC_RIGHT_STATUS_SNAPSHOT"),
            CNIPA_SOURCE_ID, new ApprovalProfile(CNIPA_APPROVAL_ID, CNIPA_APPROVAL_SHA256, CNIPA_ATTRIBUTION,
                    "PUBLIC_HISTORICAL_CASE_SAMPLE"));
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public PublicEnforcementReferenceImporter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public ImportResult importApprovedBatch(SourceRegistration source, Batch batch, List<Reference> records) {
        validateApproval(source);
        validateBatch(batch);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<String> payloads = new ArrayList<>();
                String previousKey = null;
                for (Reference record : records) {
                    if (!source.sourceId().equals(record.source().sourceSystem())) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    validateRecord(source, record);
                    String key = record.source().sourceId() + "\u0000" + record.source().version();
                    if (previousKey != null && previousKey.compareTo(key) >= 0) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    previousKey = key;
                    payloads.add(mapper.writeValueAsString(record));
                }
                String digest = EnforcementLocalSourceCodec.sha256(String.join("|", payloads));
                register(connection, source);
                if (batchExists(connection, batch.batchId())) {
                    verifyBatch(connection, batch.batchId(), digest, records.size());
                    connection.commit();
                    return new ImportResult(0, false);
                }
                int existing = verifyExistingSnapshots(connection, records, payloads);
                if (existing == records.size() && existing > 0) {
                    connection.commit();
                    return new ImportResult(0, false);
                }
                if (existing > 0) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                insertBatch(connection, source.sourceId(), batch, digest, records.size());
                for (int i = 0; i < records.size(); i++) insertSnapshot(connection, batch.batchId(), records.get(i), payloads.get(i));
                connection.commit();
                return new ImportResult(records.size(), true);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("UPSTREAM_FAILURE", failure);
        }
    }

    public void revokeSource(String sourceId, String reason, Instant revokedAt) {
        if (reason == null || reason.isBlank() || revokedAt == null) throw new IllegalArgumentException("revocation reason and time are required");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE enf_public_source_registration SET review_status='REVOKED',revoked_at=?,revocation_reason=? WHERE source_id=? AND review_status='APPROVED'")) {
            statement.setTimestamp(1, Timestamp.from(revokedAt));
            statement.setString(2, reason);
            statement.setString(3, sourceId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("SOURCE_NOT_FOUND");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("UPSTREAM_FAILURE", failure);
        }
    }

    public void withdraw(String referenceId, String sourceVersion, String reason, Instant withdrawnAt) {
        if (reason == null || reason.isBlank() || withdrawnAt == null) throw new IllegalArgumentException("withdrawal reason and time are required");
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE enf_public_reference_snapshot SET availability='WITHDRAWN',withdrawn_at=?,withdrawal_reason=? WHERE reference_id=? AND source_version=? AND availability='AVAILABLE'")) {
                statement.setTimestamp(1, Timestamp.from(withdrawnAt));
                statement.setString(2, reason);
                statement.setString(3, referenceId);
                statement.setString(4, sourceVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("SOURCE_NOT_FOUND");
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("UPSTREAM_FAILURE", failure);
        }
    }

    private void register(Connection c, SourceRegistration value) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT source_name,source_url,access_mode,site_restrictions,owner_ref,allowed_uses,forbidden_uses,external_model_allowed,review_status,approval_id,approval_sha256,retention_period,attribution FROM enf_public_source_registration WHERE source_id=?")) {
            s.setString(1, value.sourceId());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    if (!value.name().equals(r.getString(1)) || !value.url().equals(r.getString(2))
                            || !value.accessMode().equals(r.getString(3)) || !value.restrictions().equals(r.getString(4))
                            || !value.ownerRef().equals(r.getString(5)) || !value.allowedUses().equals(r.getString(6))
                            || !value.forbiddenUses().equals(r.getString(7)) || !"N".equals(r.getString(8))
                            || !value.approvalId().equals(r.getString(10)) || !value.approvalSha256().equals(r.getString(11))
                            || !value.retentionPeriod().equals(r.getString(12)) || !value.attribution().equals(r.getString(13))) {
                        throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    }
                    if (!"APPROVED".equals(r.getString(9))) throw new IllegalStateException("SOURCE_NOT_ALLOWED");
                    return;
                }
            }
        }
        execute(c, "INSERT INTO enf_public_source_registration VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", value.sourceId(), value.name(), value.url(),
                value.accessMode(), value.restrictions(), value.ownerRef(), value.allowedUses(), value.forbiddenUses(), "N", value.reviewStatus(), Timestamp.from(value.reviewedAt()),
                value.approvalId(), value.approvalSha256(), value.retentionPeriod(), value.attribution(), null, null);
    }

    private boolean batchExists(Connection c, String id) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM enf_public_reference_batch WHERE batch_id=?")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private void verifyBatch(Connection c, String id, String digest, int count) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT records_sha256,record_count FROM enf_public_reference_batch WHERE batch_id=?")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !digest.equals(r.getString(1)) || count != r.getInt(2)) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
            }
        }
    }

    private void insertBatch(Connection c, String sourceId, Batch batch, String digest, int count) throws Exception {
        execute(c, "INSERT INTO enf_public_reference_batch VALUES (?,?,?,?,?,?,?,?,?)", batch.batchId(), sourceId, batch.collectorVersion(),
                batch.requestSummary(), batch.rawResponseSha256(), batch.rawResponseLocator(), count, digest, Timestamp.from(batch.importedAt()));
    }

    private void insertSnapshot(Connection c, String batchId, Reference value, String payload) throws Exception {
        var primary = value.sourceTrace().sourceSnapshots().getLast();
        execute(c, "INSERT INTO enf_public_reference_snapshot VALUES (?,?,?,?,?,?,?,?,?,?,?)", value.referenceId(), value.source().version(), batchId,
                value.category(), value.availability(), payload, EnforcementLocalSourceCodec.sha256(payload),
                primary.rawResponseSha256(), primary.sourceFile(), null, null);
    }

    private int verifyExistingSnapshots(Connection c, List<Reference> records, List<String> payloads) throws Exception {
        int existing = 0;
        try (PreparedStatement s = c.prepareStatement(
                "SELECT payload_sha256 FROM enf_public_reference_snapshot WHERE reference_id=? AND source_version=?")) {
            for (int i = 0; i < records.size(); i++) {
                Reference record = records.get(i);
                s.setString(1, record.referenceId());
                s.setString(2, record.source().version());
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) continue;
                    existing++;
                    if (!EnforcementLocalSourceCodec.sha256(payloads.get(i)).equals(r.getString(1))) {
                        throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    }
                }
            }
        }
        return existing;
    }

    private static void validateApproval(SourceRegistration value) {
        ApprovalProfile profile = APPROVALS.get(value.sourceId());
        if (profile == null || !"APPROVED".equals(value.reviewStatus())
                || value.externalModelAllowed() || !profile.approvalId().equals(value.approvalId())
                || !profile.approvalSha256().equals(value.approvalSha256()) || !RETENTION_PERIOD.equals(value.retentionPeriod())
                || !REQUIRED_ALLOWED_USES.equals(value.allowedUses()) || !REQUIRED_FORBIDDEN_USES.equals(value.forbiddenUses())
                || !profile.attribution().equals(value.attribution()) || blank(value.name()) || blank(value.url())
                || blank(value.accessMode()) || blank(value.restrictions()) || blank(value.ownerRef()) || value.reviewedAt() == null) {
            throw new IllegalStateException("SOURCE_NOT_ALLOWED");
        }
    }

    private static void validateRecord(SourceRegistration source, Reference value) {
        ApprovalProfile profile = APPROVALS.get(source.sourceId());
        if (profile == null || !profile.category().equals(value.category())
                || value.sourceTrace() == null || blank(value.sourceTrace().sourceDatasetId())
                || !sha(value.sourceTrace().sourceRecordsSha256()) || !sha(value.sourceTrace().sourceRecordSha256())
                || value.sourceTrace().sourceSnapshotRefs().isEmpty() || !sha(value.sourcePayloadSha256())
                || value.sourceTrace().sourceSnapshots().isEmpty()
                || value.sourceTrace().sourceSnapshots().stream().anyMatch(trace -> blank(trace.ref())
                        || !sha(trace.rawResponseSha256()) || blank(trace.sourceFile())
                        || !value.sourceTrace().sourceSnapshotRefs().contains(trace.ref()))
                || blank(value.sourceUrl()) || blank(value.capturedAt()) || !"PUBLIC_PILOT".equals(value.dataMode())
                || !"NOT_CENTER_SOURCE".equals(value.authorityMarker()) || !"NOT_PROFESSIONAL_EVIDENCE".equals(value.professionalUse())
                || value.externalModelAllowed() || value.businessWorkflowInputAllowed()
                || !"APPROVED_FOR_INTERNAL_DEVELOPMENT".equals(value.importDecision())
                || !source.approvalId().equals(value.approvalId()) || !source.approvalSha256().equals(value.approvalSha256())
                || !source.retentionPeriod().equals(value.retentionPeriod()) || !source.attribution().equals(value.attribution())) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
        if ("PUBLIC_HISTORICAL_CASE_SAMPLE".equals(value.category())
                && (value.sourceStatusLabel() != null || blank(value.businessCategory())
                || value.sourcePageStart() == null || value.sourcePageStart() < 1
                || value.sourcePageEnd() == null || value.sourcePageEnd() < value.sourcePageStart()
                || blank(value.historicalCaseSummary())
                || !value.missingFields().containsAll(List.of("CURRENT_CASE_STATUS", "UNDERLYING_CASE_EVIDENCE",
                        "CURRENT_RIGHT_STATUS", "PROFESSIONAL_ACCEPTANCE")))) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
    }

    static boolean matchesStoredApproval(String sourceId, String approvalId, String approvalSha256,
                                         String retentionPeriod, String attribution) {
        ApprovalProfile profile = APPROVALS.get(sourceId);
        return profile != null && profile.approvalId().equals(approvalId)
                && profile.approvalSha256().equals(approvalSha256)
                && RETENTION_PERIOD.equals(retentionPeriod) && profile.attribution().equals(attribution);
    }

    private static void validateBatch(Batch value) {
        if (blank(value.batchId()) || blank(value.collectorVersion()) || blank(value.requestSummary())
                || !sha(value.rawResponseSha256()) || blank(value.rawResponseLocator()) || value.importedAt() == null) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }

    public record SourceRegistration(String sourceId, String name, String url, String accessMode, String restrictions, String ownerRef,
                                     String allowedUses, String forbiddenUses, boolean externalModelAllowed, String reviewStatus, Instant reviewedAt,
                                     String approvalId, String approvalSha256, String retentionPeriod, String attribution) {}
    public record Batch(String batchId, String collectorVersion, String requestSummary, String rawResponseSha256,
                        String rawResponseLocator, Instant importedAt) {}
    public record ImportResult(int importedRecords, boolean batchCreated) {}
    private record ApprovalProfile(String approvalId, String approvalSha256, String attribution, String category) {}
}
