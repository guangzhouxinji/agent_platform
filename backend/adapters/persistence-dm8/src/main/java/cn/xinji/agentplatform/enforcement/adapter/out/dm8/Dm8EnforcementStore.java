package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import cn.xinji.agentplatform.enforcement.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** DM8 repository for versioned, non-official enforcement work. */
public final class Dm8EnforcementStore implements EnforcementStore {
    private static final Set<String> SCOPES = Set.of("SYNTHETIC_V0", "AUTHORIZED_LOCAL");
    private final Supplier<Connection> connections;
    private final ObjectMapper json;

    public Dm8EnforcementStore(Supplier<Connection> connections) {
        this(connections, JsonMapper.builder().findAndAddModules().build());
    }

    Dm8EnforcementStore(Supplier<Connection> connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    @Override
    public Optional<StoredResult> findByIdempotency(String actorRef, String operation, String key) {
        String sql = "SELECT request_fingerprint, snapshot_payload FROM enf_idempotency_record "
                + "WHERE actor_ref=? AND operation_code=? AND idempotency_key=?";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, actorRef, operation, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new StoredResult(result.getString(1), decode(result.getString(2))));
            }
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
    public EnforcementSnapshot save(
            String actorRef, String operation, String key, String fingerprint, EnforcementSnapshot snapshot) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredResult> replay = findIdempotency(connection, actorRef, operation, key, true);
                if (replay.isPresent()) {
                    if (!replay.get().fingerprint().equals(fingerprint)) throw new IdempotencyConflict();
                    connection.commit();
                    return replay.get().snapshot();
                }

                WorkLock lock = lockWork(connection, snapshot.workItem().workItemId());
                Optional<StoredWork> stored = lock == null ? Optional.empty() : load(connection, snapshot.workItem().workItemId());
                if (stored.isPresent()) {
                    if (!stored.get().ownerRef().equals(lock.ownerRef())) throw corrupt("owner changed while locked");
                    requireAppendOnly(stored.get().snapshot(), snapshot);
                }

                Map<SourceLocator, String> sources = collectSources(snapshot);
                for (Map.Entry<SourceLocator, String> entry : sources.entrySet()) {
                    upsertSource(connection, entry.getKey(), entry.getValue(), snapshot.workItem().createdAt());
                }
                if (lock == null) insertWork(connection, actorRef, snapshot.workItem());
                appendSnapshot(connection, snapshot);
                int recordVersion = lock == null ? 1 : lock.recordVersion() + 1;
                if (lock != null) update(connection,
                        "UPDATE enf_work_item SET record_version=? WHERE work_item_id=? AND record_version=?",
                        recordVersion, snapshot.workItem().workItemId(), lock.recordVersion());
                insert(connection, "INSERT INTO enf_idempotency_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        actorRef, operation, key, fingerprint, snapshot.workItem().workItemId(), operation,
                        resultId(snapshot), recordVersion, encode(snapshot), Timestamp.from(Instant.now()));
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

    private void appendSnapshot(Connection connection, EnforcementSnapshot snapshot) throws SQLException {
        String workId = snapshot.workItem().workItemId();
        for (ClueAnalysisVersion analysis : snapshot.clueAnalyses()) appendClue(connection, workId, analysis);
        for (int i = 0; i < snapshot.evidenceReferences().size(); i++) appendEvidence(connection, workId, snapshot.workItem().clueRef(), snapshot.evidenceReferences().get(i), snapshot.evidenceReferences(), i + 1);
        for (EvidenceRelationSetVersion relations : snapshot.relationSets()) appendRelations(connection, workId, relations, snapshot.evidenceReferences(), snapshot.clueAnalyses(), snapshot.relationSets());
        for (AuxiliaryAnalysisVersion analysis : snapshot.auxiliaryAnalyses()) appendAnalysis(connection, workId, analysis, snapshot.evidenceReferences(), snapshot.clueAnalyses(), snapshot.relationSets());
        for (int i = 0; i < snapshot.reviews().size(); i++) appendReview(connection, workId, snapshot.reviews().get(i), i + 1);
        appendImpacts(connection, snapshot);
    }

    private void appendClue(Connection c, String workId, ClueAnalysisVersion value) throws SQLException {
        validateScope(value.scopeLabel());
        if (exists(c, "enf_clue_analysis", "analysis_id", value.analysisId())) return;
        insert(c, "INSERT INTO enf_clue_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                value.analysisId(), workId, value.version(), sourceKey(value.inputClueRef()), refId(value.previousVersionRef()),
                value.revisionKind(), value.actorRef(), value.priority(), null, timestamp(value.createdAt()));
        int order = 0;
        for (ClueAnalysisVersion.SubjectCandidate item : value.subjectCandidates()) {
            if (item.creditCode() != null) throw corrupt("credit code has no V0 physical mapping");
            insertField(c, value.analysisId(), "SUBJECT", ++order, item.roleAsStated(), item.name(), null, null, item.source());
        }
        insertValues(c, value.analysisId(), "SUSPECTED_FACT", value.suspectedFacts());
        insertValues(c, value.analysisId(), "TIME", value.timeCandidates());
        insertValues(c, value.analysisId(), "PLACE", value.placeCandidates());
        insertValues(c, value.analysisId(), "EVIDENCE_MENTION", value.evidenceMentions());
        for (int i = 0; i < value.missingItems().size(); i++) {
            String text = value.missingItems().get(i);
            insert(c, "INSERT INTO enf_clue_issue VALUES (?, ?, ?, ?, ?, ?, ?)", childId(value.analysisId(), "missing", i),
                    value.analysisId(), i + 1, "MISSING", text, text, null);
        }
        for (int i = 0; i < value.conflicts().size(); i++) {
            ClueAnalysisVersion.Conflict conflict = value.conflicts().get(i);
            String issueId = childId(value.analysisId(), "conflict", i);
            insert(c, "INSERT INTO enf_clue_issue VALUES (?, ?, ?, ?, ?, ?, ?)", issueId, value.analysisId(),
                    i + 1, "CONFLICT", conflict.field(), conflict.field(), joinSourceKeys(conflict.candidates().stream().map(ClueAnalysisVersion.SourcedValue::source).toList()));
            for (int candidate = 0; candidate < conflict.candidates().size(); candidate++) {
                ClueAnalysisVersion.SourcedValue item = conflict.candidates().get(candidate);
                insert(c, "INSERT INTO enf_clue_conflict_candidate VALUES (?, ?, ?, ?, ?, ?, ?)",
                        childId(issueId, "candidate", candidate), issueId, candidate + 1, item.value(), item.normalizedValue(),
                        item.timeKind(), sourceKey(item.source()));
            }
        }
    }

    private void insertValues(Connection c, String analysisId, String kind, List<ClueAnalysisVersion.SourcedValue> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ClueAnalysisVersion.SourcedValue item = values.get(i);
            insertField(c, analysisId, kind, i + 1, null, item.value(), item.normalizedValue(), item.timeKind(), item.source());
        }
    }

    private void insertField(Connection c, String analysisId, String kind, int order, String role, String value,
                             String normalized, String timeKind, SourceLocator source) throws SQLException {
        insert(c, "INSERT INTO enf_clue_field VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                childId(analysisId, kind, order), analysisId, kind, order, role, value, normalized, timeKind, sourceKey(source));
    }

    private void appendEvidence(Connection c, String workId, SourceLocator clue, EvidenceReference value,
                                List<EvidenceReference> allEvidence, int order) throws SQLException {
        validateScope(value.scopeLabel());
        if (exists(c, "enf_evidence_ref", "evidence_ref_id", value.evidenceRefId())) return;
        String originalId = value.originalRef() == null ? null : allEvidence.stream()
                .filter(item -> item.evidenceRefId().equals(refId(value.originalRef()))).findFirst()
                .map(EvidenceReference::evidenceRefId).orElseThrow(() -> corrupt("unknown original evidence reference"));
        insert(c, "INSERT INTO enf_evidence_ref VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                value.evidenceRefId(), workId, order, value.version(), sourceKey(value.source()), sourceKey(clue),
                value.controlledResourceRef(), value.collector(), value.collectionMissingReason(), value.occurrenceTime(),
                value.collectionTime(), value.uploadTime(), value.availability(), originalId, value.derivedKind(), timestamp(value.recordedAt()));
    }

    private void appendRelations(Connection c, String workId, EvidenceRelationSetVersion value,
                                 List<EvidenceReference> evidence, List<ClueAnalysisVersion> clues,
                                 List<EvidenceRelationSetVersion> allRelations) throws SQLException {
        if (!value.needsReviewReasons().isEmpty()) throw corrupt("relation review reasons have no V0 physical mapping");
        if (exists(c, "enf_relation_set", "relation_set_id", value.relationSetId())) return;
        String clueId = requireVersionRef(value.clueAnalysisRef(), clues, ClueAnalysisVersion::analysisId, ClueAnalysisVersion::version);
        insert(c, "INSERT INTO enf_relation_set VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", value.relationSetId(), workId,
                value.version(), clueId, sourceKey(value.ruleRef()), previousRelationId(value, allRelations), value.completeness(), null, timestamp(value.createdAt()));
        for (int i = 0; i < value.evidenceVersionRefs().size(); i++) insert(c, "INSERT INTO enf_relation_input VALUES (?, ?, ?)",
                value.relationSetId(), requireVersionRef(value.evidenceVersionRefs().get(i), evidence, EvidenceReference::evidenceRefId, EvidenceReference::version), i + 1);
        for (int i = 0; i < value.relations().size(); i++) {
            EvidenceRelationSetVersion.Relation item = value.relations().get(i);
            insert(c, "INSERT INTO enf_evidence_relation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    childId(value.relationSetId(), "relation", i), value.relationSetId(), i + 1, item.issue(),
                    requireVersionRef(item.evidenceVersionRef(), evidence, EvidenceReference::evidenceRefId, EvidenceReference::version),
                    item.type(), item.reason(), sourceKey(item.source()), "Y");
        }
        for (int i = 0; i < value.timeline().size(); i++) {
            EvidenceRelationSetVersion.TimelineEntry item = value.timeline().get(i);
            insert(c, "INSERT INTO enf_timeline_entry VALUES (?, ?, ?, ?, ?, ?, ?)", childId(value.relationSetId(), "timeline", i),
                    value.relationSetId(), i + 1, item.kind(), item.originalValue(), item.normalizedValue(), sourceKey(item.source()));
        }
        appendRelationIssues(c, value.relationSetId(), "GAP", value.gaps());
        appendRelationIssues(c, value.relationSetId(), "CONFLICT", value.conflicts());
        appendRelationIssues(c, value.relationSetId(), "UNPROCESSED", value.unprocessedItems());
    }

    private void appendRelationIssues(Connection c, String relationId, String kind, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) insert(c, "INSERT INTO enf_relation_issue VALUES (?, ?, ?, ?, ?, ?, ?)",
                childId(relationId, kind, i), relationId, i + 1, kind, null, values.get(i), null);
    }

    private void appendAnalysis(Connection c, String workId, AuxiliaryAnalysisVersion value,
                                List<EvidenceReference> evidence, List<ClueAnalysisVersion> clues,
                                List<EvidenceRelationSetVersion> relationSets) throws SQLException {
        validateScope(value.scopeLabel());
        if (!value.notOfficialDecision()) throw corrupt("official decision cannot be persisted");
        if (!value.needsReviewReasons().isEmpty()) throw corrupt("analysis review reasons have no V0 physical mapping");
        if (exists(c, "enf_aux_analysis", "aux_analysis_id", value.analysisResultId())) return;
        String clueId = requireVersionRef(value.clueAnalysisRef(), clues, ClueAnalysisVersion::analysisId, ClueAnalysisVersion::version);
        String relationId = requireVersionRef(value.relationSetRef(), relationSets, EvidenceRelationSetVersion::relationSetId, EvidenceRelationSetVersion::version);
        insert(c, "INSERT INTO enf_aux_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", value.analysisResultId(), workId,
                value.version(), clueId, relationId, sourceKey(value.ruleRef()), value.rightStatusRef() == null ? null : sourceKey(value.rightStatusRef()),
                refId(value.previousVersionRef()), value.auxiliaryOutcome(), "Y", null, timestamp(value.createdAt()));
        for (int i = 0; i < value.evidenceVersionRefs().size(); i++) insert(c, "INSERT INTO enf_analysis_input VALUES (?, ?, ?)", value.analysisResultId(),
                requireVersionRef(value.evidenceVersionRefs().get(i), evidence, EvidenceReference::evidenceRefId, EvidenceReference::version), i + 1);
        for (int i = 0; i < value.items().size(); i++) {
            AuxiliaryAnalysisVersion.AnalysisItem item = value.items().get(i);
            insert(c, "INSERT INTO enf_analysis_item VALUES (?, ?, ?, ?, ?, ?, ?)", childId(value.analysisResultId(), "item", i),
                    value.analysisResultId(), i + 1, item.contentKind(), item.content(), joinSourceKeys(item.sources()), item.uncertainty());
        }
        appendAnalysisIssues(c, value.analysisResultId(), value.gaps(), false, 0);
        appendAnalysisIssues(c, value.analysisResultId(), value.unprocessedItems(), true, value.gaps().size());
    }

    private void appendAnalysisIssues(Connection c, String analysisId, List<String> values, boolean unprocessed, int offset) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            String kind = unprocessed ? "UNPROCESSED" : value.contains("HISTORICAL") ? "HISTORICAL_STATUS_MISSING" : "MATERIALS_INSUFFICIENT";
            insert(c, "INSERT INTO enf_analysis_issue VALUES (?, ?, ?, ?, ?, ?)", childId(analysisId, kind, i), analysisId, offset + i + 1, kind, value, null);
        }
    }

    private void appendReview(Connection c, String workId, HumanReviewRecord value, int order) throws SQLException {
        if (exists(c, "enf_review_record", "review_record_id", value.reviewRecordId())) return;
        insert(c, "INSERT INTO enf_review_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", value.reviewRecordId(), workId, order,
                value.subjectType(), value.subjectId(), value.subjectVersion(), value.action(), null, null, value.reason(), value.actorRef(),
                timestamp(value.actedAt()), value.scopeLabel(), value.notOfficialDecision() ? "Y" : "N");
    }

    private void appendImpacts(Connection c, EnforcementSnapshot snapshot) throws SQLException {
        if (!snapshot.reviewImpacts().isEmpty() && snapshot.auxiliaryAnalyses().isEmpty()) throw corrupt("review impact has no affected analysis");
        for (int i = 0; i < snapshot.reviewImpacts().size(); i++) {
            AuxiliaryAnalysisVersion affected = snapshot.auxiliaryAnalyses().getLast();
            String id = childId(snapshot.workItem().workItemId(), "impact", i);
            if (exists(c, "enf_review_impact", "impact_id", id)) continue;
            insert(c, "INSERT INTO enf_review_impact VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, snapshot.workItem().workItemId(), i + 1,
                    "SOURCE_CHANGE_REQUIRES_REVIEW", null, null, null, "AUXILIARY_ANALYSIS", affected.analysisResultId(),
                    affected.version(), snapshot.reviewImpacts().get(i), timestamp(affected.createdAt()));
        }
    }

    private Optional<StoredWork> load(Connection c, String workId) throws SQLException {
        return new Dm8EnforcementReader().load(c, workId);
    }

    private void insertWork(Connection c,String owner,EnforcementWorkItem work)throws SQLException{validateScope(work.scopeLabel());insert(c,"INSERT INTO enf_work_item VALUES (?, ?, ?, ?, ?, ?, ?)",work.workItemId(),sourceKey(work.clueRef()),work.organizationRef(),owner,work.scopeLabel(),timestamp(work.createdAt()),1);}
    private WorkLock lockWork(Connection c,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT owner_ref,record_version FROM enf_work_item WHERE work_item_id=? FOR UPDATE")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?new WorkLock(r.getString(1),r.getInt(2)):null;}}}
    private Optional<StoredResult> findIdempotency(Connection c,String actor,String operation,String key,boolean lock)throws SQLException{String sql="SELECT request_fingerprint,snapshot_payload FROM enf_idempotency_record WHERE actor_ref=? AND operation_code=? AND idempotency_key=?"+(lock?" FOR UPDATE":"");try(PreparedStatement s=c.prepareStatement(sql)){bind(s,actor,operation,key);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(new StoredResult(r.getString(1),decode(r.getString(2)))):Optional.empty();}}}
    private void upsertSource(Connection c,SourceLocator source,String kind,Instant at)throws SQLException{String key=sourceKey(source);try(PreparedStatement s=c.prepareStatement("SELECT source_kind,source_system,source_object_id,version_ref,location_text FROM enf_source_locator WHERE source_id=?")){s.setString(1,key);try(ResultSet r=s.executeQuery()){if(r.next()){if(!Objects.equals(kind,r.getString(1))||!Objects.equals(source.sourceSystem(),r.getString(2))||!Objects.equals(source.sourceId(),r.getString(3))||!Objects.equals(source.version(),r.getString(4))||!Objects.equals(source.location(),r.getString(5)))throw corrupt("source locator collision");return;}}}insert(c,"INSERT INTO enf_source_locator VALUES (?, ?, ?, ?, ?, ?, ?)",key,kind,source.sourceSystem(),source.sourceId(),source.version(),source.location(),timestamp(at));}
    private Map<SourceLocator,String> collectSources(EnforcementSnapshot snapshot){Map<SourceLocator,String> out=new LinkedHashMap<>();putSource(out,snapshot.workItem().clueRef(),"CLUE");snapshot.clueAnalyses().forEach(a->{putSource(out,a.inputClueRef(),"CLUE");a.subjectCandidates().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));a.suspectedFacts().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));a.timeCandidates().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));a.placeCandidates().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));a.evidenceMentions().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));a.conflicts().forEach(x->x.candidates().forEach(v->putSource(out,v.source(),kindFor(snapshot,v.source()))));});snapshot.evidenceReferences().forEach(e->putSource(out,e.source(),"EVIDENCE"));snapshot.relationSets().forEach(r->{putSource(out,r.ruleRef(),"RULE");r.relations().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));r.timeline().forEach(x->putSource(out,x.source(),kindFor(snapshot,x.source())));});snapshot.auxiliaryAnalyses().forEach(a->{putSource(out,a.ruleRef(),"RULE");putSource(out,a.rightStatusRef(),"RIGHT_STATUS");a.items().forEach(x->x.sources().forEach(s->putSource(out,s,kindFor(snapshot,s))));});return out;}
    private static String kindFor(EnforcementSnapshot snapshot,SourceLocator source){return snapshot.evidenceReferences().stream().anyMatch(e->e.source().equals(source))?"EVIDENCE":"CLUE";}
    private static void putSource(Map<SourceLocator,String> map,SourceLocator source,String kind){if(source==null)return;String old=map.putIfAbsent(source,kind);if(old!=null&&!old.equals(kind))throw corrupt("source kind conflict");}
    private static String sourceKey(SourceLocator source){return UUID.nameUUIDFromBytes((source.sourceSystem()+"\u001f"+source.sourceId()+"\u001f"+source.version()+"\u001f"+Objects.toString(source.location(),"")).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String joinSourceKeys(List<SourceLocator> values){return String.join(",",values.stream().map(Dm8EnforcementStore::sourceKey).toList());}
    private static List<SourceLocator> parseSources(String text,Map<String,SourceLocator> sources){if(text==null||text.isBlank())return List.of();return Arrays.stream(text.split(",",-1)).map(id->source(sources,id)).toList();}
    private static SourceLocator source(Map<String,SourceLocator> sources,String id){SourceLocator value=sources.get(id);if(value==null)throw corrupt("missing source locator "+id);return value;}

    private void requireAppendOnly(EnforcementSnapshot oldValue,EnforcementSnapshot next){if(!oldValue.workItem().equals(next.workItem()))throw corrupt("work item mutation");prefix(oldValue.clueAnalyses(),next.clueAnalyses(),"clue analysis");prefix(oldValue.evidenceReferences(),next.evidenceReferences(),"evidence");prefix(oldValue.relationSets(),next.relationSets(),"relation set");prefix(oldValue.auxiliaryAnalyses(),next.auxiliaryAnalyses(),"analysis");prefix(oldValue.reviews(),next.reviews(),"review");prefix(oldValue.reviewImpacts(),next.reviewImpacts(),"impact");}
    private static void prefix(List<?> oldValue,List<?> next,String name){if(next.size()<oldValue.size()||!oldValue.equals(next.subList(0,oldValue.size())))throw corrupt(name+" history is not append-only");}
    private static String previousRelationId(EvidenceRelationSetVersion value,List<EvidenceRelationSetVersion> allRelations){if(value.version()<=1)return null;return allRelations.stream().filter(item->item.version()==value.version()-1).map(EvidenceRelationSetVersion::relationSetId).findFirst().orElseThrow(()->corrupt("missing previous relation set"));}
    private static <T> String requireVersionRef(String ref,List<T> values,java.util.function.Function<T,String> id,java.util.function.ToIntFunction<T> version){Ref parsed=parseRef(ref);return values.stream().filter(v->id.apply(v).equals(parsed.id())&&version.applyAsInt(v)==parsed.version()).findFirst().map(id).orElseThrow(()->corrupt("unknown version reference "+ref));}
    private static Ref parseRef(String ref){if(ref==null)throw corrupt("version reference is required");int at=ref.lastIndexOf('@');if(at<1||at==ref.length()-1)throw corrupt("invalid version reference "+ref);try{return new Ref(ref.substring(0,at),Integer.parseInt(ref.substring(at+1)));}catch(NumberFormatException e){throw corrupt("invalid version reference "+ref);}}
    private static String refId(String ref){return ref==null?null:parseRef(ref).id();}
    private static String versionRef(Connection c,String table,String idColumn,String versionColumn,String id)throws SQLException{if(id==null)return null;try(PreparedStatement s=c.prepareStatement("SELECT "+versionColumn+" FROM "+table+" WHERE "+idColumn+"=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw corrupt("missing version target "+id);return id+"@"+r.getInt(1);}}}
    private static String resultId(EnforcementSnapshot snapshot){if(!snapshot.reviews().isEmpty())return snapshot.reviews().getLast().reviewRecordId();if(!snapshot.auxiliaryAnalyses().isEmpty())return snapshot.auxiliaryAnalyses().getLast().analysisResultId();if(!snapshot.clueAnalyses().isEmpty())return snapshot.clueAnalyses().getLast().analysisId();return snapshot.workItem().workItemId();}
    private String encode(EnforcementSnapshot snapshot){try{return json.writeValueAsString(snapshot);}catch(Exception e){throw new IllegalStateException("Enforcement snapshot serialization failed",e);}}
    private EnforcementSnapshot decode(String value){try{return json.readValue(value,EnforcementSnapshot.class);}catch(Exception e){throw new IllegalStateException("Stored enforcement snapshot is unreadable",e);}}
    private static void validateScope(String value){if(!SCOPES.contains(value))throw corrupt("unsupported scope");}
    private static Timestamp timestamp(Instant value){return Timestamp.from(Objects.requireNonNull(value));}
    private static boolean exists(Connection c,String table,String column,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM "+table+" WHERE "+column+"=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next();}}}
    private static void insert(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,values);s.executeUpdate();}}
    private static void update(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,values);if(s.executeUpdate()!=1)throw new SQLException("Concurrent work item update");}}
    private static void bind(PreparedStatement s,Object... values)throws SQLException{for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);}
    private static void rollback(Connection c,Exception original){try{c.rollback();}catch(SQLException rollback){original.addSuppressed(rollback);}}
    private static IllegalStateException failure(String action,SQLException cause){return new IllegalStateException("DM8 enforcement "+action+" failed",cause);}
    private static IllegalStateException corrupt(String message){return new IllegalStateException("DM8 enforcement data conflict: "+message);}
    private static <K,V> V required(Map<K,V> map,K key){V value=map.get(key);if(value==null)throw corrupt("missing reference "+key);return value;}
    private static String childId(String parent,String kind,int order){return UUID.nameUUIDFromBytes((parent+"|"+kind+"|"+order).getBytes(StandardCharsets.UTF_8)).toString();}

    private record WorkLock(String ownerRef,int recordVersion){}
    private record Ref(String id,int version){}
    private record Fields(List<ClueAnalysisVersion.SubjectCandidate> subjects,List<ClueAnalysisVersion.SourcedValue> facts,List<ClueAnalysisVersion.SourcedValue> times,List<ClueAnalysisVersion.SourcedValue> places,List<ClueAnalysisVersion.SourcedValue> mentions){}
    private record Issues(List<String> missing,List<ClueAnalysisVersion.Conflict> conflicts){}
    private record EvidenceRow(String id,int version,String source,String related,String controlled,String collector,String missing,String occurrence,String collection,String upload,String availability,String original,String derived,Instant recordedAt){}
}
