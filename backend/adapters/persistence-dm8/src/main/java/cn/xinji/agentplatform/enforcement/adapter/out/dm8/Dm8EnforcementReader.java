package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementStore.StoredWork;
import cn.xinji.agentplatform.enforcement.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Dm8EnforcementReader {
    private static final String SCOPE = "SYNTHETIC_V0";

    Optional<StoredWork> load(Connection c, String workId) throws SQLException {
        String sql = "SELECT clue_source_id, organization_ref, owner_ref, scope_code, created_at FROM enf_work_item WHERE work_item_id=?";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Map<String, SourceLocator> sources = loadSources(c);
                EnforcementWorkItem work = new EnforcementWorkItem(workId, source(sources, result.getString(1)), result.getString(2),
                        result.getTimestamp(5).toInstant(), result.getString(4));
                EnforcementSnapshot snapshot = new EnforcementSnapshot(work, loadClues(c, workId, sources), loadEvidence(c, workId, sources),
                        loadRelations(c, workId, sources), loadAnalyses(c, workId, sources), loadReviews(c, workId), loadImpacts(c, workId));
                return Optional.of(new StoredWork(result.getString(3), snapshot));
            }
        }
    }

    private List<ClueAnalysisVersion> loadClues(Connection c, String workId, Map<String, SourceLocator> sources) throws SQLException {
        List<ClueAnalysisVersion> values = new ArrayList<>();
        String sql = "SELECT analysis_id, analysis_version, input_clue_source_id, previous_analysis_id, revision_kind, actor_ref, priority_outcome, created_at "
                + "FROM enf_clue_analysis WHERE work_item_id=? ORDER BY analysis_version";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    Fields fields = loadFields(c, id, sources);
                    Issues issues = loadClueIssues(c, id, sources);
                    String previous = versionRef(c, "enf_clue_analysis", "analysis_id", "analysis_version", result.getString(4));
                    values.add(new ClueAnalysisVersion(id, result.getInt(2), source(sources, result.getString(3)), fields.subjects(), fields.facts(),
                            fields.times(), fields.places(), fields.mentions(), issues.missing(), issues.conflicts(), result.getString(7), previous,
                            result.getString(5), result.getString(6), result.getTimestamp(8).toInstant(), SCOPE));
                }
            }
        }
        return values;
    }

    private Fields loadFields(Connection c, String analysisId, Map<String, SourceLocator> sources) throws SQLException {
        List<ClueAnalysisVersion.SubjectCandidate> subjects = new ArrayList<>();
        List<ClueAnalysisVersion.SourcedValue> facts = new ArrayList<>(), times = new ArrayList<>(), places = new ArrayList<>(), mentions = new ArrayList<>();
        String sql = "SELECT field_kind, role_as_stated, value_text, normalized_value, time_kind, source_id FROM enf_clue_field WHERE analysis_id=? ORDER BY field_kind, item_order";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, analysisId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SourceLocator source = source(sources, result.getString(6));
                    if ("SUBJECT".equals(result.getString(1))) subjects.add(new ClueAnalysisVersion.SubjectCandidate(result.getString(2), result.getString(3), null, source));
                    else {
                        ClueAnalysisVersion.SourcedValue value = new ClueAnalysisVersion.SourcedValue(result.getString(3), result.getString(4), result.getString(5), source);
                        switch (result.getString(1)) {
                            case "SUSPECTED_FACT" -> facts.add(value);
                            case "TIME" -> times.add(value);
                            case "PLACE" -> places.add(value);
                            case "EVIDENCE_MENTION" -> mentions.add(value);
                            default -> throw corrupt("invalid clue field kind");
                        }
                    }
                }
            }
        }
        return new Fields(subjects, facts, times, places, mentions);
    }

    private Issues loadClueIssues(Connection c, String analysisId, Map<String, SourceLocator> sources) throws SQLException {
        List<String> missing = new ArrayList<>();
        List<ClueAnalysisVersion.Conflict> conflicts = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement("SELECT issue_id, issue_kind, field_name, description_text FROM enf_clue_issue WHERE analysis_id=? ORDER BY issue_kind, issue_order")) {
            statement.setString(1, analysisId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if ("MISSING".equals(result.getString(2))) missing.add(result.getString(4));
                    else if ("CONFLICT".equals(result.getString(2))) conflicts.add(new ClueAnalysisVersion.Conflict(result.getString(3), loadConflictCandidates(c, result.getString(1), sources)));
                    else throw corrupt("invalid clue issue kind");
                }
            }
        }
        return new Issues(missing, conflicts);
    }

    private List<ClueAnalysisVersion.SourcedValue> loadConflictCandidates(Connection c, String issueId, Map<String, SourceLocator> sources) throws SQLException {
        List<ClueAnalysisVersion.SourcedValue> values = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement("SELECT value_text, normalized_value, time_kind, source_id FROM enf_clue_conflict_candidate WHERE issue_id=? ORDER BY candidate_order")) {
            statement.setString(1, issueId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new ClueAnalysisVersion.SourcedValue(result.getString(1), result.getString(2), result.getString(3), source(sources, result.getString(4))));
            }
        }
        return values;
    }

    private List<EvidenceReference> loadEvidence(Connection c, String workId, Map<String, SourceLocator> sources) throws SQLException {
        List<EvidenceRow> rows = new ArrayList<>();
        String sql = "SELECT evidence_ref_id,evidence_version,source_id,related_source_id,controlled_resource_ref,collector_ref,collection_missing_reason,occurrence_time,collection_time,upload_time,availability,original_evidence_ref_id,derived_kind,recorded_at FROM enf_evidence_ref WHERE work_item_id=? ORDER BY item_order";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, workId);
            try (ResultSet r = statement.executeQuery()) { while (r.next()) rows.add(new EvidenceRow(r.getString(1), r.getInt(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getTimestamp(14).toInstant())); }
        }
        Map<String,Integer> versions = new HashMap<>(); rows.forEach(row -> versions.put(row.id(), row.version()));
        return rows.stream().map(row -> new EvidenceReference(row.id(), row.version(), source(sources,row.source()), row.controlled(), source(sources,row.related()).sourceId(), row.collector(), row.missing(), row.occurrence(), row.collection(), row.upload(), row.availability(), row.original()==null?null:row.original()+"@"+required(versions,row.original()), row.derived(), row.recordedAt(), SCOPE)).toList();
    }

    private List<EvidenceRelationSetVersion> loadRelations(Connection c, String workId, Map<String, SourceLocator> sources) throws SQLException {
        List<EvidenceRelationSetVersion> values = new ArrayList<>();
        String sql="SELECT relation_set_id,relation_version,clue_analysis_id,rule_source_id,previous_relation_set_id,completeness,created_at FROM enf_relation_set WHERE work_item_id=? ORDER BY relation_version";
        try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,workId);try(ResultSet r=s.executeQuery()){while(r.next()){
            String id=r.getString(1); values.add(new EvidenceRelationSetVersion(id,r.getInt(2),versionRef(c,"enf_clue_analysis","analysis_id","analysis_version",r.getString(3)),source(sources,r.getString(4)),
                    loadVersionRefs(c,"SELECT e.evidence_ref_id,e.evidence_version FROM enf_relation_input i JOIN enf_evidence_ref e ON e.evidence_ref_id=i.evidence_ref_id WHERE i.relation_set_id=? ORDER BY i.item_order",id),
                    loadRelationItems(c,id,sources),loadTimeline(c,id,sources),loadIssueTexts(c,id,"GAP"),loadIssueTexts(c,id,"CONFLICT"),loadIssueTexts(c,id,"UNPROCESSED"),r.getString(6),List.of(),r.getTimestamp(7).toInstant()));
        }}}
        return values;
    }

    private List<EvidenceRelationSetVersion.Relation> loadRelationItems(Connection c,String id,Map<String,SourceLocator> sources)throws SQLException{List<EvidenceRelationSetVersion.Relation> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT x.issue_code,e.evidence_ref_id,e.evidence_version,x.relation_type,x.reason_text,x.source_id FROM enf_evidence_relation x JOIN enf_evidence_ref e ON e.evidence_ref_id=x.evidence_ref_id WHERE x.relation_set_id=? ORDER BY x.item_order")){s.setString(1,id);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new EvidenceRelationSetVersion.Relation(r.getString(1),r.getString(2)+"@"+r.getInt(3),r.getString(4),r.getString(5),source(sources,r.getString(6))));}}return out;}
    private List<EvidenceRelationSetVersion.TimelineEntry> loadTimeline(Connection c,String id,Map<String,SourceLocator> sources)throws SQLException{List<EvidenceRelationSetVersion.TimelineEntry> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT time_kind,original_value,normalized_value,source_id FROM enf_timeline_entry WHERE relation_set_id=? ORDER BY item_order")){s.setString(1,id);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new EvidenceRelationSetVersion.TimelineEntry(r.getString(1),r.getString(2),r.getString(3),source(sources,r.getString(4))));}}return out;}
    private List<AuxiliaryAnalysisVersion> loadAnalyses(Connection c,String workId,Map<String,SourceLocator> sources)throws SQLException{List<AuxiliaryAnalysisVersion> out=new ArrayList<>();String sql="SELECT aux_analysis_id,analysis_version,clue_analysis_id,relation_set_id,rule_source_id,right_status_source_id,previous_aux_analysis_id,outcome_type,not_official_decision,created_at FROM enf_aux_analysis WHERE work_item_id=? ORDER BY analysis_version";try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,workId);try(ResultSet r=s.executeQuery()){while(r.next()){String id=r.getString(1);List<String> gaps=new ArrayList<>();gaps.addAll(loadAnalysisIssueTexts(c,id,false));out.add(new AuxiliaryAnalysisVersion(id,r.getInt(2),versionRef(c,"enf_clue_analysis","analysis_id","analysis_version",r.getString(3)),versionRef(c,"enf_relation_set","relation_set_id","relation_version",r.getString(4)),loadVersionRefs(c,"SELECT e.evidence_ref_id,e.evidence_version FROM enf_analysis_input i JOIN enf_evidence_ref e ON e.evidence_ref_id=i.evidence_ref_id WHERE i.aux_analysis_id=? ORDER BY i.item_order",id),source(sources,r.getString(5)),r.getString(6)==null?null:source(sources,r.getString(6)),loadAnalysisItems(c,id,sources),r.getString(8),gaps,loadAnalysisIssueTexts(c,id,true),List.of(),versionRef(c,"enf_aux_analysis","aux_analysis_id","analysis_version",r.getString(7)),"Y".equals(r.getString(9)),r.getTimestamp(10).toInstant(),SCOPE));}}}return out;}
    private List<AuxiliaryAnalysisVersion.AnalysisItem> loadAnalysisItems(Connection c,String id,Map<String,SourceLocator> sources)throws SQLException{List<AuxiliaryAnalysisVersion.AnalysisItem> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT content_kind,content_text,source_refs,uncertainty_text FROM enf_analysis_item WHERE aux_analysis_id=? ORDER BY item_order")){s.setString(1,id);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new AuxiliaryAnalysisVersion.AnalysisItem(r.getString(1),r.getString(2),parseSources(r.getString(3),sources),r.getString(4)));}}return out;}
    private List<String> loadAnalysisIssueTexts(Connection c,String id,boolean unprocessed)throws SQLException{List<String> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT issue_kind,description_text FROM enf_analysis_issue WHERE aux_analysis_id=? ORDER BY item_order")){s.setString(1,id);try(ResultSet r=s.executeQuery()){while(r.next())if(unprocessed=="UNPROCESSED".equals(r.getString(1)))out.add(r.getString(2));}}return out;}
    private List<HumanReviewRecord> loadReviews(Connection c,String workId)throws SQLException{List<HumanReviewRecord> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT review_record_id,subject_type,subject_id,subject_version,action_type,reason_text,actor_ref,acted_at,scope_code,not_official_decision FROM enf_review_record WHERE work_item_id=? ORDER BY item_order")){s.setString(1,workId);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new HumanReviewRecord(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getString(5),r.getString(6),r.getString(7),r.getTimestamp(8).toInstant(),r.getString(9),"Y".equals(r.getString(10))));}}return out;}
    private List<String> loadImpacts(Connection c,String workId)throws SQLException{List<String> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT reason_text FROM enf_review_impact WHERE work_item_id=? ORDER BY item_order")){s.setString(1,workId);try(ResultSet r=s.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;}
    private Map<String,SourceLocator> loadSources(Connection c)throws SQLException{Map<String,SourceLocator> out=new HashMap<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT source_id,source_system,source_object_id,version_ref,location_text FROM enf_source_locator")){while(r.next())out.put(r.getString(1),new SourceLocator(r.getString(2),r.getString(3),r.getString(4),r.getString(5)));}return out;}
    private List<String> loadVersionRefs(Connection c,String sql,String id)throws SQLException{List<String> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,id);try(ResultSet r=s.executeQuery()){while(r.next())out.add(r.getString(1)+"@"+r.getInt(2));}}return out;}
    private List<String> loadIssueTexts(Connection c,String id,String kind)throws SQLException{List<String> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT description_text FROM enf_relation_issue WHERE relation_set_id=? AND issue_kind=? ORDER BY item_order")){s.setString(1,id);s.setString(2,kind);try(ResultSet r=s.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;}
    private static List<SourceLocator> parseSources(String text,Map<String,SourceLocator> sources){if(text==null||text.isBlank())return List.of();return Arrays.stream(text.split(",",-1)).map(id->source(sources,id)).toList();}
    private static SourceLocator source(Map<String,SourceLocator> sources,String id){SourceLocator value=sources.get(id);if(value==null)throw corrupt("missing source locator "+id);return value;}
    private static String versionRef(Connection c,String table,String idColumn,String versionColumn,String id)throws SQLException{if(id==null)return null;try(PreparedStatement s=c.prepareStatement("SELECT "+versionColumn+" FROM "+table+" WHERE "+idColumn+"=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw corrupt("missing version target "+id);return id+"@"+r.getInt(1);}}}
    private static <K,V> V required(Map<K,V> map,K key){V value=map.get(key);if(value==null)throw corrupt("missing reference "+key);return value;}
    private static IllegalStateException corrupt(String message){return new IllegalStateException("DM8 enforcement data conflict: "+message);}

    private record Fields(List<ClueAnalysisVersion.SubjectCandidate> subjects,List<ClueAnalysisVersion.SourcedValue> facts,List<ClueAnalysisVersion.SourcedValue> times,List<ClueAnalysisVersion.SourcedValue> places,List<ClueAnalysisVersion.SourcedValue> mentions){}
    private record Issues(List<String> missing,List<ClueAnalysisVersion.Conflict> conflicts){}
    private record EvidenceRow(String id,int version,String source,String related,String controlled,String collector,String missing,String occurrence,String collection,String upload,String availability,String original,String derived,Instant recordedAt){}
}
