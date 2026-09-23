package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

public final class Dm8PublicAdjudicationReferenceCatalogAdapter implements PublicAdjudicationReferenceCatalogPort {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final PublicAdjudicationSourceApprovalRegistry approvals;

    public Dm8PublicAdjudicationReferenceCatalogAdapter(DataSource dataSource, ObjectMapper mapper,
                                                        PublicAdjudicationSourceApprovalRegistry approvals) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.approvals = approvals;
    }

    @Override
    public List<Reference> list() {
        String sql = "SELECT r.payload_json,r.payload_sha256,r.availability,r.raw_response_sha256,r.raw_response_locator,"
                + "s.source_id,s.source_name,s.source_url,s.access_mode,s.site_restrictions,s.owner_ref,s.allowed_uses,"
                + "s.allowed_categories,s.forbidden_uses,s.external_model_allowed,s.review_status,s.reviewed_at,"
                + "s.approval_id,s.approval_sha256,s.retention_period,s.attribution FROM adj_public_reference_snapshot r "
                + "JOIN adj_public_reference_batch b ON b.batch_id=r.batch_id "
                + "JOIN adj_public_source_registration s ON s.source_id=b.source_id ORDER BY r.reference_id,r.source_version";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<Reference> references = new ArrayList<>();
            boolean found = false;
            while (result.next()) {
                found = true;
                PublicAdjudicationReferenceImporter.SourceRegistration source = source(result);
                if (!"APPROVED".equals(source.reviewStatus()) || !approvals.approves(source)) {
                    throw new IllegalStateException("SOURCE_NOT_ALLOWED");
                }
                String payload = result.getString(1);
                if (!AdjudicationLocalSourceCodec.sha256(payload).equals(result.getString(2))) {
                    throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                }
                if (!"AVAILABLE".equals(result.getString(3))) continue;
                Reference reference = parse(payload);
                validateStoredTrace(result, source, reference);
                references.add(reference);
            }
            if (found && references.isEmpty()) throw new IllegalStateException("SOURCE_UNAVAILABLE");
            return List.copyOf(references);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("UPSTREAM_FAILURE", failure);
        }
    }

    private Reference parse(String payload) {
        try {
            return mapper.readValue(payload, Reference.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE", failure);
        }
    }

    private static PublicAdjudicationReferenceImporter.SourceRegistration source(ResultSet r) throws Exception {
        return new PublicAdjudicationReferenceImporter.SourceRegistration(
                r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11),
                r.getString(12), r.getString(13), r.getString(14), "Y".equals(r.getString(15)), r.getString(16),
                r.getTimestamp(17).toInstant(), r.getString(18), r.getString(19), r.getString(20), r.getString(21));
    }

    private static void validateStoredTrace(ResultSet result,
                                            PublicAdjudicationReferenceImporter.SourceRegistration source,
                                            Reference reference) throws Exception {
        if (reference.sourceTrace() == null || reference.sourceTrace().sourceSnapshots().isEmpty()) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
        var primary = reference.sourceTrace().sourceSnapshots().getLast();
        if (!result.getString(4).equals(primary.rawResponseSha256()) || !result.getString(5).equals(primary.sourceFile())
                || reference.externalModelAllowed() || !source.approvalId().equals(reference.approvalId())
                || !source.approvalSha256().equals(reference.approvalSha256())
                || !source.retentionPeriod().equals(reference.retentionPeriod())
                || !source.attribution().equals(reference.attribution())) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
    }

    @Override public String dataMode() { return "PUBLIC_PILOT"; }
    @Override public String catalogVersion() { return "adjudication-public-reference-v1"; }
    @Override public String limitation() { return "公开/授权开发参考目录；非案件、非材料、非法律适用、非正式草稿输入。"; }
}
