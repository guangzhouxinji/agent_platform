package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** DM8 repository for the synthetic adjudication V0 boundary. */
public final class Dm8AdjudicationStore implements AdjudicationStore {
    private static final String SOURCE_SYSTEM = "v0-synthetic-adjudication";
    private final Supplier<Connection> connections;
    private final ObjectMapper json;

    public Dm8AdjudicationStore(Supplier<Connection> connections) {
        this(connections, JsonMapper.builder().findAndAddModules().build());
    }

    Dm8AdjudicationStore(Supplier<Connection> connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    @Override
    public Optional<StoredResult> findByIdempotency(String actorRef, String operation, String key) {
        try (Connection connection = connections.get()) {
            return findIdempotency(connection, actorRef, operation, key);
        } catch (SQLException exception) {
            throw failure("read idempotency", exception);
        }
    }

    @Override
    public Optional<StoredWork> findByWorkItemId(String workItemId) {
        try (Connection connection = connections.get()) {
            return load(connection, workItemId);
        } catch (SQLException exception) {
            throw failure("read work item", exception);
        }
    }

    @Override
    public AdjudicationSnapshot save(String actorRef, String operation, String key, String fingerprint,
            long expectedPersistenceVersion, AdjudicationSnapshot snapshot) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredResult> replay = findIdempotency(connection, actorRef, operation, key);
                if (replay.isPresent()) {
                    if (!replay.get().fingerprint().equals(fingerprint)) throw new IdempotencyConflict();
                    connection.commit();
                    return replay.get().snapshot();
                }

                String workId = snapshot.work().workItemId();
                Optional<StoredWork> prior = load(connection, workId);
                if (prior.isEmpty()) {
                    if (expectedPersistenceVersion != 0) throw new VersionConflict();
                    insertSources(connection, snapshot);
                    insertWork(connection, actorRef, snapshot.work());
                } else {
                    if (prior.get().persistenceVersion() != expectedPersistenceVersion) throw new VersionConflict();
                    if (!prior.get().ownerRef().equals(actorRef) && expectedPersistenceVersion == 0) throw corrupt("owner mismatch");
                    requireAppendOnly(prior.get().snapshot(), snapshot);
                    int changed = update(connection,
                            "UPDATE adj_work_item SET row_version=row_version+1 WHERE work_item_id=? AND row_version=?",
                            workId, expectedPersistenceVersion);
                    if (changed != 1) throw new VersionConflict();
                    insertSources(connection, snapshot);
                }

                appendSnapshot(connection, snapshot);
                long resultVersion = expectedPersistenceVersion + 1;
                insert(connection, "INSERT INTO adj_idempotency_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        actorRef, operation, key, fingerprint, workId, operation, resultId(snapshot), resultVersion,
                        encode(snapshot), Timestamp.from(Instant.now()));
                connection.commit();
                return snapshot;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException) {
                    Optional<StoredResult> winner = findByIdempotency(actorRef, operation, key);
                    if (winner.isPresent()) {
                        if (!winner.get().fingerprint().equals(fingerprint)) throw new IdempotencyConflict();
                        return winner.get().snapshot();
                    }
                    throw failure("save snapshot", (SQLException) exception);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("open transaction", exception);
        }
    }

    private void appendSnapshot(Connection connection, AdjudicationSnapshot snapshot) throws SQLException {
        String workId = snapshot.work().workItemId();
        for (MaterialInspection value : snapshot.inspections()) appendInspection(connection, workId, value);
        for (LegalCandidateSet value : snapshot.candidates()) appendCandidateSet(connection, workId, value);
        for (Citation value : snapshot.citations()) appendCitation(connection, workId, value);
        for (Draft value : snapshot.drafts()) appendDraft(connection, workId, value);
        for (TestReview value : snapshot.reviews()) appendReview(connection, workId, value);
        if (!snapshot.reviewImpacts().isEmpty()) throw corrupt("review impacts have no V0 physical mapping");
    }

    private void appendInspection(Connection c, String workId, MaterialInspection value) throws SQLException {
        if (exists(c, "adj_material_inspection", "inspection_id", value.inspectionId())) return;
        insert(c, "INSERT INTO adj_material_inspection VALUES (?, ?, ?, ?, ?)", value.inspectionId(), workId,
                value.version(), refId(value.previousRef()), timestamp(value.createdAt()));
        appendInspectionItems(c, value.inspectionId(), "PARTY_STATEMENT", value.partyStatements());
        appendInspectionItems(c, value.inspectionId(), "UNRESOLVED_FACT", value.unresolvedFacts());
        appendInspectionItems(c, value.inspectionId(), "MISSING", value.missingItems());
        appendInspectionItems(c, value.inspectionId(), "CONFLICT", value.conflicts());
    }

    private void appendInspectionItems(Connection c, String inspectionId, String kind, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) insert(c, "INSERT INTO adj_inspection_item VALUES (?, ?, ?, ?, ?, ?)",
                childId(inspectionId, kind, i), inspectionId, i + 1, kind, values.get(i), null);
    }

    private void appendCandidateSet(Connection c, String workId, LegalCandidateSet value) throws SQLException {
        if (exists(c, "adj_legal_candidate_set", "candidate_set_id", value.candidateSetId())) return;
        insert(c, "INSERT INTO adj_legal_candidate_set VALUES (?, ?, ?, ?, ?, ?, ?)", value.candidateSetId(), workId,
                value.version(), refId(value.inspectionRef()), refId(value.previousRef()), encodeList(value.limitations()), timestamp(value.createdAt()));
        for (int i = 0; i < value.entries().size(); i++) {
            LegalCandidate entry = value.entries().get(i);
            insert(c, "INSERT INTO adj_legal_candidate VALUES (?, ?, ?, ?, ?, ?, ?)", childId(value.candidateSetId(), "candidate", i),
                    value.candidateSetId(), i + 1, entry.code(), sourceKey(entry.source()), entry.location(), encodeList(entry.doubts()));
        }
    }

    private void appendCitation(Connection c, String workId, Citation value) throws SQLException {
        if (exists(c, "adj_citation", "citation_id", value.citationId())) return;
        insert(c, "INSERT INTO adj_citation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", value.citationId(), workId,
                value.version(), sourceKey(value.legalSource()), value.location(), value.action(), value.reason(),
                refId(value.previousRef()), timestamp(value.createdAt()));
    }

    private void appendDraft(Connection c, String workId, Draft value) throws SQLException {
        if (!value.isDraft()) throw corrupt("official decision cannot be persisted");
        if (exists(c, "adj_draft", "draft_id", value.draftId())) return;
        insert(c, "INSERT INTO adj_draft VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", value.draftId(), workId,
                value.version(), refId(value.inspectionRef()), sourceKey(value.templateRef()), refId(value.previousRef()),
                encodeList(value.missingItems()), encodeList(value.conflicts()), "Y", timestamp(value.createdAt()));
        for (int i = 0; i < value.citationRefs().size(); i++) insert(c, "INSERT INTO adj_draft_citation VALUES (?, ?, ?)",
                value.draftId(), refId(value.citationRefs().get(i)), i + 1);
        for (int i = 0; i < value.sections().size(); i++) {
            Section section = value.sections().get(i); String sectionId = childId(value.draftId(), "section", i);
            insert(c, "INSERT INTO adj_draft_section VALUES (?, ?, ?, ?, ?)", sectionId, value.draftId(), i + 1,
                    section.anchor(), section.content());
            for (int source = 0; source < section.sources().size(); source++) insert(c,
                    "INSERT INTO adj_draft_section_source VALUES (?, ?, ?)", sectionId, sourceKey(section.sources().get(source)), source + 1);
        }
    }

    private void appendReview(Connection c, String workId, TestReview value) throws SQLException {
        if (!value.notOfficialDecision() || !"SYNTHETIC_PROCESS_TEST".equals(value.scopeLabel()))
            throw corrupt("official review cannot be persisted");
        if (exists(c, "adj_test_review", "review_id", value.reviewId())) return;
        insert(c, "INSERT INTO adj_test_review VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", value.reviewId(), workId,
                value.draftId(), value.draftVersion(), value.action(), value.reason(), value.actorRef(),
                timestamp(value.actedAt()), value.scopeLabel(), "Y");
    }

    private Optional<StoredWork> load(Connection c, String workId) throws SQLException {
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
                while (result.next()) {
                    values.add(new Citation(result.getString(1), result.getInt(2), source(sources, result.getString(3)),
                            result.getString(4), result.getString(5), result.getString(6),
                            versionRef(result.getString(7), values, Citation::citationId, Citation::version),
                            result.getTimestamp(8).toInstant()));
                }
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
                            decodeList(result.getString(7)),
                            versionRef(result.getString(5), values, Draft::draftId, Draft::version),
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
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT inspection_version FROM adj_material_inspection WHERE inspection_id=?")) {
            statement.setString(1, inspectionId); try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw corrupt("missing inspection reference");
                return inspectionId + "@" + result.getInt(1);
            }
        }
    }

    private void insertSources(Connection c, AdjudicationSnapshot snapshot) throws SQLException {
        LinkedHashSet<Source> sources = new LinkedHashSet<>();
        sources.add(snapshot.work().caseRef()); sources.addAll(snapshot.work().materialRefs());
        snapshot.inspections().forEach(value -> sources.addAll(value.inputs()));
        snapshot.candidates().forEach(value -> value.entries().forEach(entry -> sources.add(entry.source())));
        snapshot.citations().forEach(value -> sources.add(value.legalSource()));
        snapshot.drafts().forEach(value -> { sources.add(value.templateRef()); value.sections().forEach(section -> sources.addAll(section.sources())); });
        for (Source source : sources) upsertSource(c, source, snapshot.work().createdAt());
    }

    private void upsertSource(Connection c, Source source, Instant retrievedAt) throws SQLException {
        String key = sourceKey(source);
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT source_kind,source_system,source_object_id,version_ref,location_text FROM adj_source_locator WHERE source_id=?")) {
            statement.setString(1, key); try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!Objects.equals(source.kind(), result.getString(1)) || !SOURCE_SYSTEM.equals(result.getString(2))
                            || !Objects.equals(source.id(), result.getString(3)) || !Objects.equals(source.version(), result.getString(4))
                            || !Objects.equals(source.location(), result.getString(5))) throw corrupt("source locator collision");
                    return;
                }
            }
        }
        insert(c, "INSERT INTO adj_source_locator VALUES (?, ?, ?, ?, ?, ?, ?)", key, source.kind(), SOURCE_SYSTEM,
                source.id(), source.version(), source.location(), timestamp(retrievedAt));
    }

    private void insertWork(Connection c, String owner, Work work) throws SQLException {
        if (!"SYNTHETIC_V0".equals(work.scopeLabel())) throw corrupt("unsupported scope");
        insert(c, "INSERT INTO adj_work_item VALUES (?, ?, ?, ?, ?, ?, ?)", work.workItemId(), sourceKey(work.caseRef()),
                work.organizationRef(), owner, work.scopeLabel(), timestamp(work.createdAt()), 1);
        for (int i = 0; i < work.materialRefs().size(); i++) insert(c, "INSERT INTO adj_work_input VALUES (?, ?, ?)",
                work.workItemId(), i + 1, sourceKey(work.materialRefs().get(i)));
    }

    private Optional<StoredResult> findIdempotency(Connection c, String actor, String operation, String key) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT request_fingerprint,snapshot_payload FROM adj_idempotency_record WHERE actor_ref=? AND operation_code=? AND idempotency_key=?")) {
            bind(statement, actor, operation, key); try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new StoredResult(result.getString(1), decode(result.getString(2)))) : Optional.empty();
            }
        }
    }

    private void requireAppendOnly(AdjudicationSnapshot prior, AdjudicationSnapshot next) {
        if (!prior.work().equals(next.work()) || !prefix(prior.inspections(), next.inspections())
                || !prefix(prior.candidates(), next.candidates()) || !prefix(prior.citations(), next.citations())
                || !prefix(prior.drafts(), next.drafts()) || !prefix(prior.reviews(), next.reviews())
                || !prefix(prior.reviewImpacts(), next.reviewImpacts())) throw corrupt("snapshot is not append-only");
    }

    private static boolean prefix(List<?> prior, List<?> next) {
        return next.size() >= prior.size() && next.subList(0, prior.size()).equals(prior);
    }

    private String encode(AdjudicationSnapshot snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (Exception exception) { throw new IllegalStateException("Adjudication snapshot serialization failed", exception); }
    }

    private AdjudicationSnapshot decode(String payload) {
        try { return json.readValue(payload, AdjudicationSnapshot.class); }
        catch (Exception exception) { throw new IllegalStateException("Adjudication snapshot deserialization failed", exception); }
    }

    private String encodeList(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (Exception exception) { throw new IllegalStateException("Adjudication list serialization failed", exception); }
    }

    private List<String> decodeList(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        try { return List.copyOf(json.readValue(payload, json.getTypeFactory().constructCollectionType(List.class, String.class))); }
        catch (Exception exception) { throw new IllegalStateException("Adjudication list deserialization failed", exception); }
    }

    private static String resultId(AdjudicationSnapshot value) {
        if (!value.reviews().isEmpty()) return value.reviews().getLast().reviewId();
        if (!value.drafts().isEmpty()) return value.drafts().getLast().draftId();
        if (!value.citations().isEmpty()) return value.citations().getLast().citationId();
        if (!value.candidates().isEmpty()) return value.candidates().getLast().candidateSetId();
        if (!value.inspections().isEmpty()) return value.inspections().getLast().inspectionId();
        return value.work().workItemId();
    }

    private static String sourceKey(Source source) {
        return uuid(source.kind() + "|" + source.id() + "|" + source.version() + "|" + source.location());
    }

    private static String childId(String parent, String kind, int order) { return uuid(parent + "|" + kind + "|" + order); }
    private static String uuid(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static String refId(String ref) { return ref == null ? null : ref.substring(0, ref.lastIndexOf('@')); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static Source source(Map<String, Source> sources, String key) { Source value = sources.get(key); if (value == null) throw corrupt("missing source"); return value; }

    private static <T> String versionRef(String id, List<T> values, java.util.function.Function<T, String> idFn,
            java.util.function.ToIntFunction<T> versionFn) {
        if (id == null) return null;
        return values.stream().filter(value -> id.equals(idFn.apply(value))).findFirst()
                .map(value -> id + "@" + versionFn.applyAsInt(value)).orElseThrow(() -> corrupt("missing previous version"));
    }

    private static boolean exists(Connection c, String table, String column, String value) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement("SELECT 1 FROM " + table + " WHERE " + column + "=?")) {
            statement.setString(1, value); try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private static void insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) { bind(statement, values); statement.executeUpdate(); }
    }

    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) { bind(statement, values); return statement.executeUpdate(); }
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { original.addSuppressed(rollbackFailure); }
    }

    private static IllegalStateException failure(String action, SQLException exception) {
        return new IllegalStateException("Adjudication DM8 repository failed to " + action, exception);
    }

    private static IllegalStateException corrupt(String message) { return new IllegalStateException(message); }
}
