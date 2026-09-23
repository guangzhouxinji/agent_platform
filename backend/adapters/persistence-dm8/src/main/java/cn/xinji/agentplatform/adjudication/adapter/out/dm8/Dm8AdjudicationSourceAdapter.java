package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.LegalData;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.MaterialData;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Citation;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.MaterialInspection;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Section;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

public final class Dm8AdjudicationSourceAdapter implements SourcePort {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Dm8AdjudicationSourceAdapter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override public Optional<Source> findCase(String id, String version) {
        return source("SELECT payload_json,payload_sha256,availability FROM adj_local_case WHERE source_id=? AND source_version=?", id, version);
    }

    @Override public Optional<Source> findMaterial(String id) {
        return source("SELECT payload_json,payload_sha256,availability FROM adj_local_material WHERE source_id=? ORDER BY source_version DESC", id);
    }

    @Override public Optional<MaterialData> materialData(String id) {
        return one("SELECT payload_json,payload_sha256,availability FROM adj_local_material WHERE source_id=? ORDER BY source_version DESC", MaterialCatalogItem.class, id)
                .map(MaterialCatalogItem::data);
    }

    @Override public List<LegalData> legalCandidates(MaterialInspection inspection) {
        return many("SELECT payload_json,payload_sha256,availability FROM adj_local_legal_snapshot ORDER BY legal_code", LegalData.class);
    }

    @Override public Source template() {
        return one("SELECT payload_json,payload_sha256,availability FROM adj_local_template_snapshot ORDER BY source_id", Source.class)
                .orElseThrow(() -> new IllegalStateException("SOURCE_UNAVAILABLE"));
    }

    @Override public List<Section> draftSections(MaterialInspection inspection, List<Citation> citations) {
        return List.of(new Section("facts", "合成本地草稿：待核实事项和来源映射已保留。", inspection.inputs()),
                new Section("legal", "合成本地法规候选引用数：" + citations.size(), citations.stream().map(Citation::legalSource).toList()));
    }

    @Override public List<Source> cases() {
        return many("SELECT payload_json,payload_sha256,availability FROM adj_local_case ORDER BY source_id", Source.class, false);
    }

    @Override public List<MaterialCatalogItem> materialItems() {
        return many("SELECT payload_json,payload_sha256,availability FROM adj_local_material ORDER BY source_id", MaterialCatalogItem.class, false);
    }

    @Override public String dataMode() { return "SYNTHETIC_LOCAL"; }
    @Override public String catalogVersion() { return AdjudicationLocalSourceImporter.CATALOG_VERSION; }
    @Override public String limitation() { return "本地 DM8 开发夹具；非中心来源、非专业证据、不得形成正式裁决。"; }

    private Optional<Source> source(String sql, Object... parameters) { return one(sql, Source.class, parameters); }

    private <T> Optional<T> one(String sql, Class<T> type, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(read(result, type, true)) : Optional.empty(); }
        } catch (Exception failure) { throw sourceFailure(failure); }
    }

    private <T> List<T> many(String sql, Class<T> type) { return many(sql, type, true); }

    private <T> List<T> many(String sql, Class<T> type, boolean rejectUnavailable) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<T> values = new ArrayList<>();
            while (result.next()) values.add(read(result, type, rejectUnavailable));
            return List.copyOf(values);
        } catch (Exception failure) { throw sourceFailure(failure); }
    }

    private <T> T read(ResultSet result, Class<T> type, boolean rejectUnavailable) throws Exception {
        String json = result.getString(1);
        if (!AdjudicationLocalSourceCodec.sha256(json).equals(result.getString(2))) throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        if (rejectUnavailable && !"AVAILABLE".equals(result.getString(3))) throw new IllegalStateException("SOURCE_UNAVAILABLE");
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("SOURCE_UNAVAILABLE", failure); }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws Exception {
        for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
    }

    private static IllegalStateException sourceFailure(Exception failure) {
        if (failure instanceof IllegalStateException state) return state;
        return new IllegalStateException("UPSTREAM_FAILURE", failure);
    }
}
