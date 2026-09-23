package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.application.AdjudicationStore.StoredWork;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

final class Dm8AdjudicationReader {
    private final ObjectMapper json;

    Dm8AdjudicationReader(ObjectMapper json) {
        this.json = json;
    }

    Optional<StoredWork> load(Connection c, String workId) throws SQLException {
        String sql = "SELECT case_source_id,organization_ref,owner_ref,scope_code,created_at,row_version FROM adj_work_item WHERE work_item_id=?";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Map<String, Source> sources = loadSources(c);
                List<Source> inputs = loadWorkInputs(c, workId, sources);
                Work work = new Work(workId, source(sources, result.getString(1)), inputs, result.getString(2),
                        result.getTimestamp(5).toInstant(), result.getString(4));
                AdjudicationSnapshot snapshot = new AdjudicationSnapshot(work, loadInspections(c, workId, inputs),
                        loadCandidateSets(c, workId, sources), loadCitations(c, workId, sources),
                        loadDrafts(c, workId, sources), loadReviews(c, workId), List.of());
                return Optional.of(new StoredWork(result.getString(3), result.getLong(6), snapshot));
            }
        }
    }

    private Map<String, Source> loadSources(Connection c) throws SQLException {
        Map<String, Source> values = new HashMap<>();
        try (Statement statement = c.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT source_id,source_kind,source_object_id,version_ref,location_text FROM adj_source_locator")) {
            while (result.next()) values.put(result.getString(1), new Source(result.getString(2), result.getString(3),
                    result.getString(4), result.getString(5)));
        }
        return values;
    }

    private List<Source> loadWorkInputs(Connection c, String workId, Map<String, Source> sources) throws SQLException {
        List<Source> values = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT material_source_id FROM adj_work_input WHERE work_item_id=? ORDER BY input_order")) {
            statement.setString(1, workId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(source(sources, result.getString(1)));
            }
        }
        return List.copyOf(values);
    }

    private List<MaterialInspection> loadInspections(Connection c, String workId, List<Source> inputs) throws SQLException {
        List<MaterialInspection> values = new ArrayList<>();
        String sql = "SELECT inspection_id,inspection_version,previous_inspection_id,created_at FROM adj_material_inspection WHERE work_item_id=? ORDER BY inspection_version";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    values.add(new MaterialInspection(id, result.getInt(2), inputs,
                            loadItems(c, id, "PARTY_STATEMENT"), loadItems(c, id, "UNRESOLVED_FACT"),
                            loadItems(c, id, "MISSING"), loadItems(c, id, "CONFLICT"),
                            versionRef(result.getString(3), values, MaterialInspection::inspectionId, MaterialInspection::version),
                            result.getTimestamp(4).toInstant()));
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> loadItems(Connection c, String inspectionId, String kind) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT content_text FROM adj_inspection_item WHERE inspection_id=? AND item_kind=? ORDER BY item_order")) {
            statement.setString(1, inspectionId); statement.setString(2, kind); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private List<LegalCandidateSet> loadCandidateSets(Connection c, String workId, Map<String, Source> sources) throws SQLException {
        List<LegalCandidateSet> values = new ArrayList<>();
        String sql = "SELECT candidate_set_id,candidate_set_version,inspection_id,previous_candidate_set_id,limitations,created_at FROM adj_legal_candidate_set WHERE work_item_id=? ORDER BY candidate_set_version";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    values.add(new LegalCandidateSet(id, result.getInt(2), inspectionRef(c, result.getString(3)),
                            loadCandidates(c, id, sources), decodeList(result.getString(5)),
                            versionRef(result.getString(4), values, LegalCandidateSet::candidateSetId, LegalCandidateSet::version),
                            result.getTimestamp(6).toInstant()));
                }
            }
        }
        return List.copyOf(values);
    }

    private List<LegalCandidate> loadCandidates(Connection c, String setId, Map<String, Source> sources) throws SQLException {
        List<LegalCandidate> values = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT candidate_code,legal_source_id,location_text,doubts FROM adj_legal_candidate WHERE candidate_set_id=? ORDER BY candidate_order")) {
            statement.setString(1, setId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new LegalCandidate(result.getString(1), source(sources, result.getString(2)),
                        result.getString(3), decodeList(result.getString(4))));
            }
        }
        return List.copyOf(values);
    }

    private List<Citation> loadCitations(Connection c, String workId, Map<String, Source> sources) throws SQLException {
        List<Citation> values = new ArrayList<>();
        String sql = "SELECT citation_id,citation_version,legal_source_id,location_text,reason_text,action_type,previous_citation_id,created_at FROM adj_citation WHERE work_item_id=? ORDER BY citation_version";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new Citation(result.getString(1), result.getInt(2), source(sources, result.getString(3)),
                        result.getString(4), result.getString(5), result.getString(6),
                        versionRef(result.getString(7), values, Citation::citationId, Citation::version), result.getTimestamp(8).toInstant()));
            }
        }
        return List.copyOf(values);
    }

    private List<Draft> loadDrafts(Connection c, String workId, Map<String, Source> sources) throws SQLException {
        List<Draft> values = new ArrayList<>();
        String sql = "SELECT draft_id,draft_version,inspection_id,template_source_id,previous_draft_id,missing_items,conflicts,is_draft,created_at FROM adj_draft WHERE work_item_id=? ORDER BY draft_version";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    values.add(new Draft(id, result.getInt(2), inspectionRef(c, result.getString(3)), loadDraftCitationRefs(c, id),
                            source(sources, result.getString(4)), loadSections(c, id, sources), decodeList(result.getString(6)),
                            decodeList(result.getString(7)), versionRef(result.getString(5), values, Draft::draftId, Draft::version),
                            result.getTimestamp(9).toInstant(), "Y".equals(result.getString(8))));
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> loadDraftCitationRefs(Connection c, String draftId) throws SQLException {
        List<String> values = new ArrayList<>();
        String sql = "SELECT x.citation_id,c.citation_version FROM adj_draft_citation x JOIN adj_citation c ON c.citation_id=x.citation_id WHERE x.draft_id=? ORDER BY x.citation_order";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, draftId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1) + "@" + result.getInt(2));
            }
        }
        return List.copyOf(values);
    }

    private List<Section> loadSections(Connection c, String draftId, Map<String, Source> sources) throws SQLException {
        List<Section> values = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT section_id,section_anchor,content_text FROM adj_draft_section WHERE draft_id=? ORDER BY section_order")) {
            statement.setString(1, draftId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new Section(result.getString(2), result.getString(3),
                        loadSectionSources(c, result.getString(1), sources)));
            }
        }
        return List.copyOf(values);
    }

    private List<Source> loadSectionSources(Connection c, String sectionId, Map<String, Source> sources) throws SQLException {
        List<Source> values = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT source_id FROM adj_draft_section_source WHERE section_id=? ORDER BY source_order")) {
            statement.setString(1, sectionId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(source(sources, result.getString(1)));
            }
        }
        return List.copyOf(values);
    }

    private List<TestReview> loadReviews(Connection c, String workId) throws SQLException {
        List<TestReview> values = new ArrayList<>();
        String sql = "SELECT review_id,draft_id,draft_version,action_type,reason_text,actor_ref,acted_at,scope_code,not_official_decision FROM adj_test_review WHERE work_item_id=? ORDER BY acted_at,review_id";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new TestReview(result.getString(1), result.getString(2), result.getInt(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getTimestamp(7).toInstant(),
                        result.getString(8), "Y".equals(result.getString(9))));
            }
        }
        return List.copyOf(values);
    }

    private String inspectionRef(Connection c, String inspectionId) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement("SELECT inspection_version FROM adj_material_inspection WHERE inspection_id=?")) {
            statement.setString(1, inspectionId); try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw corrupt("missing inspection reference");
                return inspectionId + "@" + result.getInt(1);
            }
        }
    }

    private List<String> decodeList(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        try { return List.copyOf(json.readValue(payload, json.getTypeFactory().constructCollectionType(List.class, String.class))); }
        catch (Exception exception) { throw new IllegalStateException("Adjudication list deserialization failed", exception); }
    }

    private static Source source(Map<String, Source> sources, String key) {
        Source value = sources.get(key);
        if (value == null) throw corrupt("missing source");
        return value;
    }

    private static <T> String versionRef(String id, List<T> values, java.util.function.Function<T, String> idFn,
            java.util.function.ToIntFunction<T> versionFn) {
        if (id == null) return null;
        return values.stream().filter(value -> id.equals(idFn.apply(value))).findFirst()
                .map(value -> id + "@" + versionFn.applyAsInt(value)).orElseThrow(() -> corrupt("missing previous version"));
    }

    private static IllegalStateException corrupt(String message) { return new IllegalStateException(message); }
}
