package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Imports local snapshots only after an application-owned approval registry accepts the exact source contract. */
public final class PublicAdjudicationReferenceImporter {
    public static final String APPROVED_SOURCE_ID = "SPC_IP_COURT_SELECTED_JUDGMENTS";
    public static final String APPROVAL_ID = "ADJ-SPC-IP-COURT-INTERNAL-DEVELOPMENT-V1";
    public static final String APPROVAL_SHA256 = "bb23f00ce9662f6fa8a047929ef18133cedaf2184a4fadf11597b4e951101dc9";
    public static final String RETENTION_PERIOD = "UNTIL_DEVELOPMENT_PILOT_END";
    public static final String REQUIRED_ALLOWED_USES = "INTERNAL_DEVELOPMENT_PILOT,LOCAL_DM8_STORAGE,LOCAL_DM8_INDEXING";
    public static final String REQUIRED_ALLOWED_CATEGORIES = "PUBLIC_JUDGMENT_REFERENCE";
    public static final String REQUIRED_FORBIDDEN_USES = "EXTERNAL_MODEL_PROCESSING,PRODUCTION_USE,EXTERNAL_REDISTRIBUTION,FORMAL_CASE_INPUT,FORMAL_MATERIAL_INPUT,LEGAL_APPLICABILITY_INPUT,FORMAL_DRAFT_INPUT,ADJUDICATION_WORKFLOW_INPUT";
    public static final String REQUIRED_ATTRIBUTION = "Identify the Supreme People's Court Intellectual Property Court, preserve each original source URL, and preserve the source copyright notice: 中华人民共和国最高人民法院 版权所有.";
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final PublicAdjudicationSourceApprovalRegistry approvals;

    public PublicAdjudicationReferenceImporter(DataSource dataSource, ObjectMapper mapper,
                                               PublicAdjudicationSourceApprovalRegistry approvals) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.approvals = approvals;
    }

    public ImportResult importApprovedBatch(SourceRegistration source, Batch batch, List<Reference> records) {
        PublicAdjudicationReferencePolicy.validateSource(approvals, source);
        validateBatch(batch);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<String> payloads = serializeAndValidate(source, records);
                String digest = AdjudicationLocalSourceCodec.sha256(String.join("|", payloads));
                register(connection, source);
                if (batchExists(connection, batch.batchId())) {
                    verifyBatch(connection, batch.batchId(), digest, records.size());
                    connection.commit();
                    return new ImportResult(0, false);
                }
                int existing = verifyExisting(connection, records, payloads);
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

    public void withdraw(String referenceId, String sourceVersion, String reason, Instant withdrawnAt) {
        if (PublicAdjudicationReferencePolicy.blank(reason) || withdrawnAt == null) throw new IllegalArgumentException("withdrawal reason and time are required");
        updateOne("UPDATE adj_public_reference_snapshot SET availability='WITHDRAWN',withdrawn_at=?,withdrawal_reason=? "
                        + "WHERE reference_id=? AND source_version=? AND availability='AVAILABLE'",
                Timestamp.from(withdrawnAt), reason, referenceId, sourceVersion);
    }

    public void revokeSource(String sourceId, String reason, Instant revokedAt) {
        if (PublicAdjudicationReferencePolicy.blank(reason) || revokedAt == null) throw new IllegalArgumentException("revocation reason and time are required");
        updateOne("UPDATE adj_public_source_registration SET review_status='REVOKED',revoked_at=?,revocation_reason=? "
                        + "WHERE source_id=? AND review_status='APPROVED'", Timestamp.from(revokedAt), reason, sourceId);
    }

    private List<String> serializeAndValidate(SourceRegistration source, List<Reference> records) throws Exception {
        List<String> payloads = new ArrayList<>();
        String previous = null;
        for (Reference record : records) {
            PublicAdjudicationReferencePolicy.validateRecord(source, record);
            String key = record.source().sourceId() + "\u0000" + record.source().version();
            if (previous != null && previous.compareTo(key) >= 0) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
            previous = key;
            payloads.add(mapper.writeValueAsString(record));
        }
        return payloads;
    }

    private void register(Connection c, SourceRegistration v) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT source_name,source_url,access_mode,site_restrictions,owner_ref,allowed_uses,allowed_categories,forbidden_uses,external_model_allowed,review_status,approval_id,approval_sha256,retention_period,attribution FROM adj_public_source_registration WHERE source_id=?")) {
            s.setString(1, v.sourceId());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    if (!v.name().equals(r.getString(1)) || !v.url().equals(r.getString(2)) || !v.accessMode().equals(r.getString(3))
                            || !v.restrictions().equals(r.getString(4)) || !v.ownerRef().equals(r.getString(5))
                            || !v.allowedUses().equals(r.getString(6)) || !v.allowedCategories().equals(r.getString(7))
                            || !v.forbiddenUses().equals(r.getString(8)) || !"N".equals(r.getString(9))
                            || !"APPROVED".equals(r.getString(10)) || !v.approvalId().equals(r.getString(11))
                            || !v.approvalSha256().equals(r.getString(12)) || !v.retentionPeriod().equals(r.getString(13))
                            || !v.attribution().equals(r.getString(14))) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    return;
                }
            }
        }
        execute(c, "INSERT INTO adj_public_source_registration VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                v.sourceId(), v.name(), v.url(), v.accessMode(), v.restrictions(), v.ownerRef(), v.allowedUses(),
                v.allowedCategories(), v.forbiddenUses(), "N", v.reviewStatus(), Timestamp.from(v.reviewedAt()),
                v.approvalId(), v.approvalSha256(), v.retentionPeriod(), v.attribution(), null, null);
    }

    private boolean batchExists(Connection c, String id) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM adj_public_reference_batch WHERE batch_id=?")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private void verifyBatch(Connection c, String id, String digest, int count) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT records_sha256,record_count FROM adj_public_reference_batch WHERE batch_id=?")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !digest.equals(r.getString(1)) || count != r.getInt(2)) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
            }
        }
    }

    private int verifyExisting(Connection c, List<Reference> records, List<String> payloads) throws Exception {
        int existing = 0;
        try (PreparedStatement s = c.prepareStatement("SELECT payload_sha256 FROM adj_public_reference_snapshot WHERE reference_id=? AND source_version=?")) {
            for (int i = 0; i < records.size(); i++) {
                s.setString(1, records.get(i).referenceId()); s.setString(2, records.get(i).source().version());
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) continue;
                    existing++;
                    if (!AdjudicationLocalSourceCodec.sha256(payloads.get(i)).equals(r.getString(1))) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                }
            }
        }
        return existing;
    }

    private void insertBatch(Connection c, String sourceId, Batch b, String digest, int count) throws Exception {
        execute(c, "INSERT INTO adj_public_reference_batch VALUES (?,?,?,?,?,?,?,?,?)", b.batchId(), sourceId,
                b.collectorVersion(), b.requestSummary(), b.rawResponseSha256(), b.rawResponseLocator(), count, digest, Timestamp.from(b.importedAt()));
    }

    private void insertSnapshot(Connection c, String batchId, Reference v, String payload) throws Exception {
        var primary = v.sourceTrace().sourceSnapshots().getLast();
        execute(c, "INSERT INTO adj_public_reference_snapshot VALUES (?,?,?,?,?,?,?,?,?,?,?)", v.referenceId(),
                v.source().version(), batchId, v.category(), v.availability(), payload,
                AdjudicationLocalSourceCodec.sha256(payload), primary.rawResponseSha256(), primary.sourceFile(), null, null);
    }

    private void updateOne(String sql, Object... values) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            if (s.executeUpdate() != 1) throw new IllegalStateException("SOURCE_NOT_FOUND");
        } catch (IllegalStateException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("UPSTREAM_FAILURE", failure); }
    }

    private static void validateBatch(Batch v) {
        if (PublicAdjudicationReferencePolicy.blank(v.batchId()) || PublicAdjudicationReferencePolicy.blank(v.collectorVersion())
                || PublicAdjudicationReferencePolicy.blank(v.requestSummary()) || !PublicAdjudicationReferencePolicy.sha(v.rawResponseSha256())
                || PublicAdjudicationReferencePolicy.blank(v.rawResponseLocator()) || v.importedAt() == null) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
    }
    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }

    public record SourceRegistration(String sourceId, String name, String url, String accessMode, String restrictions,
                                     String ownerRef, String allowedUses, String allowedCategories, String forbiddenUses,
                                     boolean externalModelAllowed, String reviewStatus, Instant reviewedAt, String approvalId,
                                     String approvalSha256, String retentionPeriod, String attribution) {}

    public static SourceRegistration approvedPublicJudgmentPilot() {
        return new SourceRegistration(APPROVED_SOURCE_ID, "最高人民法院知识产权法庭 - 精品裁判",
                "https://ipc.court.gov.cn/zh-cn/news/more-5-25.html", "PUBLIC_WEB_PAGES",
                "Internal development only; locator metadata only; remove from indexes and stop use after withdrawal",
                "PROJECT_OWNER_AND_DATA_LEGAL_RESPONSIBLE_PARTY", REQUIRED_ALLOWED_USES,
                REQUIRED_ALLOWED_CATEGORIES, REQUIRED_FORBIDDEN_USES, false, "APPROVED",
                Instant.parse("2026-09-20T00:00:00Z"), APPROVAL_ID, APPROVAL_SHA256,
                RETENTION_PERIOD, REQUIRED_ATTRIBUTION);
    }
    public record Batch(String batchId, String collectorVersion, String requestSummary, String rawResponseSha256,
                        String rawResponseLocator, Instant importedAt) {}
    public record ImportResult(int importedRecords, boolean batchCreated) {}
}
