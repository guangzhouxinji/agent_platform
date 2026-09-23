package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

public final class Dm8EnforcementSourceAdapter implements EnforcementSourcePort {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Dm8EnforcementSourceAdapter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public Optional<ClueData> findClue(String clueId, String version) {
        return one("SELECT payload_json,payload_sha256,availability FROM enf_local_clue WHERE source_id=? AND source_version=?",
                ClueData.class, clueId, version);
    }

    @Override
    public Optional<EvidenceData> findEvidence(String evidenceId) {
        return one("SELECT payload_json,payload_sha256,availability FROM enf_local_evidence WHERE source_id=? ORDER BY source_version DESC",
                EvidenceData.class, evidenceId);
    }

    @Override
    public SourceLocator activeRule() {
        BatchMetadata metadata = batchMetadata();
        String sql = "AUTHORIZED_LOCAL".equals(metadata.dataMode())
                ? "SELECT r.source_system,r.rule_id,r.rule_version,r.source_location FROM enf_local_rule_snapshot r "
                    + "JOIN enf_local_rule_release p ON p.rule_id=r.rule_id AND p.rule_version=r.rule_version "
                    + "WHERE r.batch_id=? AND p.release_status='PUBLISHED' "
                    + "ORDER BY CASE WHEN p.effective_from IS NULL THEN 1 ELSE 0 END,"
                    + "p.effective_from DESC,p.approved_at DESC,r.rule_id"
                : "SELECT source_system,rule_id,rule_version,source_location FROM enf_local_rule_snapshot WHERE batch_id=? ORDER BY rule_id";
        return locator(sql, metadata.batchId()).orElseThrow(() -> new IllegalStateException("SOURCE_UNAVAILABLE"));
    }

    @Override
    public Optional<SourceLocator> historicalRightStatus(String clueId) {
        String sql = "SELECT source_system,right_id,right_version,source_location FROM enf_local_right_snapshot WHERE clue_id=? AND availability='AVAILABLE' ORDER BY right_version DESC";
        return locator(sql, clueId);
    }

    @Override public List<ClueData> listClues() {
        BatchMetadata metadata = batchMetadata();
        return many("SELECT payload_json,payload_sha256,availability FROM enf_local_clue "
                + "WHERE batch_id=? AND availability<>'WITHDRAWN' ORDER BY source_id", ClueData.class, metadata.batchId());
    }
    @Override public List<EvidenceData> listEvidence() {
        BatchMetadata metadata = batchMetadata();
        return many("SELECT payload_json,payload_sha256,availability FROM enf_local_evidence "
                + "WHERE batch_id=? AND availability<>'WITHDRAWN' ORDER BY source_id", EvidenceData.class, metadata.batchId());
    }
    @Override public String dataMode() { return batchMetadata().dataMode(); }
    @Override public String catalogVersion() { return batchMetadata().datasetVersion(); }
    @Override public String limitation() {
        BatchMetadata metadata = batchMetadata();
        if (!"AUTHORIZED_LOCAL".equals(metadata.dataMode())) {
            return "本地 DM8 开发夹具；非中心来源、非专业证据、不得形成正式决定。";
        }
        String sql = "SELECT allowed_use FROM enf_local_authorization WHERE batch_id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, metadata.batchId());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getString(1) + "；辅助分析须人工复核，不形成正式决定。"
                        : "AUTHORIZED_LOCAL；授权元数据不可用。";
            }
        } catch (Exception failure) {
            throw sourceFailure(failure);
        }
    }

    private BatchMetadata batchMetadata() {
        String sql = "SELECT batch_id,dataset_version,data_mode FROM enf_local_source_batch "
                + "WHERE import_status='COMPLETED' ORDER BY CASE WHEN data_mode='AUTHORIZED_LOCAL' THEN 0 ELSE 1 END,"
                + "imported_at DESC,batch_id DESC";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql);
             ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new IllegalStateException("SOURCE_UNAVAILABLE");
            return new BatchMetadata(r.getString(1), r.getString(2), r.getString(3));
        } catch (Exception failure) {
            throw sourceFailure(failure);
        }
    }

    private <T> Optional<T> one(String sql, Class<T> type, Object... parameters) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(read(r, type, true));
            }
        } catch (Exception failure) {
            throw sourceFailure(failure);
        }
    }

    private <T> List<T> many(String sql, Class<T> type, Object... parameters) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            try (ResultSet r = s.executeQuery()) {
            List<T> values = new ArrayList<>();
            while (r.next()) values.add(read(r, type, false));
            return List.copyOf(values);
            }
        } catch (Exception failure) {
            throw sourceFailure(failure);
        }
    }

    private <T> T read(ResultSet result, Class<T> type, boolean rejectWithdrawn) throws Exception {
        String json = result.getString(1);
        if (!EnforcementLocalSourceCodec.sha256(json).equals(result.getString(2))) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
        if (rejectWithdrawn && "WITHDRAWN".equals(result.getString(3))) {
            throw new IllegalStateException("SOURCE_UNAVAILABLE");
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("SOURCE_UNAVAILABLE", failure);
        }
    }

    private Optional<SourceLocator> locator(String sql, Object... parameters) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new SourceLocator(r.getString(1), r.getString(2), r.getString(3), r.getString(4))) : Optional.empty();
            }
        } catch (Exception failure) {
            throw sourceFailure(failure);
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws Exception {
        for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
    }

    private static IllegalStateException sourceFailure(Exception failure) {
        if (failure instanceof IllegalStateException state) return state;
        return new IllegalStateException("UPSTREAM_FAILURE", failure);
    }

    private record BatchMetadata(String batchId, String datasetVersion, String dataMode) {}
}
