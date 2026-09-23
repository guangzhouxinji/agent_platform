package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.PublicEnforcementReferenceCatalogPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

public final class Dm8PublicEnforcementReferenceCatalogAdapter implements PublicEnforcementReferenceCatalogPort {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Dm8PublicEnforcementReferenceCatalogAdapter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public List<Reference> list() {
        String sql = "SELECT r.payload_json,r.payload_sha256,r.availability,s.review_status,s.external_model_allowed,"
                + "s.source_id,s.approval_id,s.approval_sha256,s.retention_period,s.attribution,r.raw_response_sha256,r.raw_response_locator FROM enf_public_reference_snapshot r "
                + "JOIN enf_public_reference_batch b ON b.batch_id=r.batch_id "
                + "JOIN enf_public_source_registration s ON s.source_id=b.source_id "
                + "ORDER BY r.reference_id,r.source_version";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<Reference> references = new ArrayList<>();
            boolean found = false;
            while (result.next()) {
                found = true;
                if (!"APPROVED".equals(result.getString(4))) throw new IllegalStateException("SOURCE_NOT_ALLOWED");
                if (!"N".equals(result.getString(5))
                        || !PublicEnforcementReferenceImporter.matchesStoredApproval(result.getString(6),
                        result.getString(7), result.getString(8), result.getString(9), result.getString(10))) {
                    throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                }
                String payload = result.getString(1);
                if (!EnforcementLocalSourceCodec.sha256(payload).equals(result.getString(2))) {
                    throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                }
                if (!"AVAILABLE".equals(result.getString(3))) continue;
                try {
                    Reference reference = mapper.readValue(payload, Reference.class);
                    if (reference.sourceTrace() == null || reference.sourceTrace().sourceSnapshots().isEmpty()) {
                        throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    }
                    var primary = reference.sourceTrace().sourceSnapshots().getLast();
                    if (!result.getString(11).equals(primary.rawResponseSha256())
                            || !result.getString(12).equals(primary.sourceFile())
                            || reference.externalModelAllowed()
                            || reference.businessWorkflowInputAllowed()
                            || !result.getString(7).equals(reference.approvalId())
                            || !result.getString(8).equals(reference.approvalSha256())
                            || !result.getString(9).equals(reference.retentionPeriod())
                            || !result.getString(10).equals(reference.attribution())) {
                        throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
                    }
                    references.add(reference);
                } catch (JsonProcessingException failure) {
                    throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE", failure);
                }
            }
            if (found && references.isEmpty()) throw new IllegalStateException("SOURCE_UNAVAILABLE");
            return List.copyOf(references);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("UPSTREAM_FAILURE", failure);
        }
    }

    @Override public String dataMode() { return "PUBLIC_PILOT"; }
    @Override public String catalogVersion() { return "enforcement-public-reference-v1"; }
    @Override public String limitation() { return "公开开发参考目录；非中心来源、非执法线索、非专业证据、不得形成正式决定。"; }
}
