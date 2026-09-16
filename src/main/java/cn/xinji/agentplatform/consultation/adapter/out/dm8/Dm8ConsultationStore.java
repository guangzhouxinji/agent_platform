package cn.xinji.agentplatform.consultation.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.application.ConsultationStore;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;
import cn.xinji.agentplatform.consultation.domain.ConsultationSourceLink;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** DM8 adapter for the synthetic V0 consultation path only. */
public final class Dm8ConsultationStore implements ConsultationStore {
    private final Supplier<Connection> connections;

    public Dm8ConsultationStore(Supplier<Connection> connections) { this.connections = connections; }

    @Override public Optional<StoredResult> findByIdempotency(String requester, String key) {
        String sql = "SELECT request_fingerprint, consultation_id, answer_id FROM con_idempotency_record WHERE requester_ref=? AND idempotency_key=?";
        try (Connection c = connections.get(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, requester); p.setString(2, key); ResultSet r = p.executeQuery();
            return r.next() ? load(c, requester, r.getString(1), r.getString(2), r.getString(3)) : Optional.empty();
        } catch (SQLException e) { throw new IllegalStateException("DM8 consultation read failed", e); }
    }

    @Override public Optional<StoredResult> findByConsultationId(String id) {
        try (Connection c = connections.get(); PreparedStatement p = c.prepareStatement("SELECT requester_ref FROM con_consultation WHERE consultation_id=?")) {
            p.setString(1, id); ResultSet r = p.executeQuery();
            return r.next() ? load(c, r.getString(1), "", id, null) : Optional.empty();
        } catch (SQLException e) { throw new IllegalStateException("DM8 consultation read failed", e); }
    }

    @Override public Optional<StoredResult> findByAnswerId(String consultationId, String answerId) {
        try (Connection c=connections.get(); PreparedStatement p=c.prepareStatement("SELECT requester_ref FROM con_consultation WHERE consultation_id=?")) {
            p.setString(1, consultationId); ResultSet r=p.executeQuery();
            if(!r.next()) return Optional.empty();
            return load(c,r.getString(1),"",consultationId,answerId);
        } catch(SQLException e) { throw new IllegalStateException("DM8 answer read failed",e); }
    }

    @Override public void save(String requester, String key, String question, String purpose, ConsultationOutcome result) {
        try (Connection c = connections.get()) {
            c.setAutoCommit(false); Timestamp now = Timestamp.from(Instant.now());
            insert(c, "INSERT INTO con_consultation VALUES (?, ?, ?, ?)", result.consultationId(), requester, "PATENT_TECHNICAL_REFERENCE", now);
            String inputSource = UUID.randomUUID().toString();
            source(c, inputSource, "USER_MESSAGE", "consultation", result.consultationId()+"/input-1", "v0", "message:1", now);
            insert(c, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), result.consultationId(), 1, question, inputSource, now);
            String requestId = null;
            if (!"NEEDS_CLARIFICATION".equals(result.type()) && !"OUT_OF_SCOPE".equals(result.type())) {
                requestId = UUID.randomUUID().toString(); String decision = UUID.randomUUID().toString();
                boolean denied = "ACCESS_DENIED".equals(result.type());
                source(c, decision, "ACCESS_DECISION", "v0-auth", denied ? "denied" : "allowed", "v0", "decision", now);
                insert(c, "INSERT INTO con_retrieval_request VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", requestId, result.consultationId(), question, purpose, null, denied ? "DENIED" : "ALLOWED", decision, now, denied ? "NOT_EXECUTED" : "COMPLETED", denied ? null : "V0 合成来源", denied ? "ACCESS_DENIED" : null, now);
            }
            String answerId = result.answerId();
            insert(c, "INSERT INTO con_assisted_answer VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", answerId, result.consultationId(), requestId, result.type(), result.content(), result.limitation(), "N", null, result.answerVersion(), now);
            for (String issue : result.issues()) {
                insert(c, "INSERT INTO con_information_issue VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), result.consultationId(), issue.contains("冲突") ? "CONFLICT" : "MISSING", issue, inputSource, null);
            }
            if (requestId != null) for (int index = 0; index < result.candidates().size(); index++) {
                ConsultationCandidate candidate = result.candidates().get(index);
                String sourceId = UUID.randomUUID().toString(), candidateId = UUID.randomUUID().toString();
                source(c, sourceId, "PATENT_DOCUMENT", "v0-synthetic-patent", candidate.sourceId(), candidate.version(), candidate.location(), now);
                insert(c, "INSERT INTO con_retrieval_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?)", candidateId, requestId, sourceId, candidate.sourceId(), candidate.matchBasis(), "synthetic-match", "USABLE", null);
                insert(c, "INSERT INTO con_answer_candidate VALUES (?, ?)", answerId, candidateId);
                String linkId = index < result.sourceLinks().size() ? result.sourceLinks().get(index).sourceLinkId() : UUID.randomUUID().toString();
                insert(c, "INSERT INTO con_answer_source_link VALUES (?, ?, ?, ?)", linkId, answerId, sourceId, candidate.location());
            }
            insert(c, "INSERT INTO con_idempotency_record VALUES (?, ?, ?, ?, ?, ?)", requester, key, result.fingerprint(), result.consultationId(), answerId, now);
            c.commit();
        } catch (SQLException e) { throw new IllegalStateException("DM8 consultation write failed", e); }
    }

    @Override public void saveSupplement(String requester, String key, String content, String accumulatedInput, String purpose, ConsultationOutcome result) {
        try (Connection c = connections.get()) {
            c.setAutoCommit(false); Timestamp now = Timestamp.from(Instant.now());
            String inputSource = UUID.randomUUID().toString();
            source(c, inputSource, "USER_MESSAGE", "consultation", result.consultationId()+"/input-"+result.answerVersion(), "v0", "message:"+result.answerVersion(), now);
            insert(c, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), result.consultationId(), nextInputSequence(c, result.consultationId()), content, inputSource, now);
            try (PreparedStatement p = c.prepareStatement("UPDATE con_information_issue SET resolved_by_source_id=? WHERE consultation_id=? AND resolved_by_source_id IS NULL")) {
                p.setString(1, inputSource); p.setString(2, result.consultationId()); p.executeUpdate();
            }
            String requestId = null;
            if (!"NEEDS_CLARIFICATION".equals(result.type())) {
                requestId = UUID.randomUUID().toString(); String decisionId = UUID.randomUUID().toString();
                source(c, decisionId, "ACCESS_DECISION", "v0-auth", "allowed-supplement", "v0", "decision", now);
                insert(c, "INSERT INTO con_retrieval_request VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", requestId, result.consultationId(), accumulatedInput, purpose, null, "ALLOWED", decisionId, now, "COMPLETED", "V0 合成来源", null, now);
            }
            String answerId = result.answerId();
            insert(c, "INSERT INTO con_assisted_answer VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", answerId, result.consultationId(), requestId, result.type(), result.content(), result.limitation(), "N", null, result.answerVersion(), now);
            if (requestId != null) persistCandidates(c, requestId, answerId, result, now);
            insert(c, "INSERT INTO con_idempotency_record VALUES (?, ?, ?, ?, ?, ?)", requester, key, result.fingerprint(), result.consultationId(), answerId, now);
            c.commit();
        } catch (SQLException e) { throw new IllegalStateException("DM8 consultation supplement write failed", e); }
    }

    @Override public void saveRefresh(String requester, ConsultationOutcome result) {
        try (Connection c = connections.get()) {
            c.setAutoCommit(false); Timestamp now = Timestamp.from(Instant.now());
            String confirmedInput = lastConfirmedInput(c, result.consultationId());
            String requestId = UUID.randomUUID().toString(), decisionId = UUID.randomUUID().toString();
            source(c, decisionId, "ACCESS_DECISION", "v0-auth", "allowed-refresh", "v0", "decision", now);
            String failure = "SOURCE_UNAVAILABLE".equals(result.type()) ? "SOURCE_UNAVAILABLE" : null;
            insert(c, "INSERT INTO con_retrieval_request VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", requestId, result.consultationId(), confirmedInput, "TECHNICAL_REFERENCE", null, "ALLOWED", decisionId, now, "SOURCE_UNAVAILABLE".equals(result.type()) ? "PARTIAL" : "COMPLETED", "V0 合成来源", failure, now);
            String answerId = result.answerId();
            insert(c, "INSERT INTO con_assisted_answer VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", answerId, result.consultationId(), requestId, result.type(), result.content(), result.limitation(), "N", null, result.answerVersion(), now);
            for (int index = 0; index < result.candidates().size(); index++) {
                ConsultationCandidate candidate = result.candidates().get(index);
                String sourceId = UUID.randomUUID().toString(), candidateId = UUID.randomUUID().toString();
                source(c, sourceId, "PATENT_DOCUMENT", "v0-synthetic-patent", candidate.sourceId(), candidate.version(), candidate.location(), now);
                insert(c, "INSERT INTO con_retrieval_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?)", candidateId, requestId, sourceId, candidate.sourceId(), candidate.matchBasis(), "synthetic-match", "USABLE", null);
                insert(c, "INSERT INTO con_answer_candidate VALUES (?, ?)", answerId, candidateId);
                String linkId = index < result.sourceLinks().size() ? result.sourceLinks().get(index).sourceLinkId() : UUID.randomUUID().toString();
                insert(c, "INSERT INTO con_answer_source_link VALUES (?, ?, ?, ?)", linkId, answerId, sourceId, candidate.location());
            }
            c.commit();
        } catch (SQLException e) { throw new IllegalStateException("DM8 consultation refresh write failed", e); }
    }

    private static String lastConfirmedInput(Connection c, String consultationId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT confirmed_input FROM con_retrieval_request WHERE consultation_id=? ORDER BY created_at DESC")) {
            p.setString(1, consultationId); ResultSet r = p.executeQuery();
            if (!r.next()) throw new SQLException("consultation has no prior retrieval request");
            return r.getString(1);
        }
    }

    private static int nextAnswerVersion(Connection c, String consultationId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(MAX(answer_version), 0) + 1 FROM con_assisted_answer WHERE consultation_id=?")) {
            p.setString(1, consultationId); ResultSet r = p.executeQuery(); r.next(); return r.getInt(1);
        }
    }

    private Optional<StoredResult> load(Connection c, String requester, String fingerprint, String consultationId, String requiredAnswerId) throws SQLException {
        String sql=requiredAnswerId==null ? "SELECT answer_id,answer_version,outcome_type,content,limitations FROM con_assisted_answer WHERE consultation_id=? ORDER BY answer_version DESC" : "SELECT answer_id,answer_version,outcome_type,content,limitations FROM con_assisted_answer WHERE consultation_id=? AND answer_id=?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, consultationId); if(requiredAnswerId!=null)p.setString(2,requiredAnswerId); ResultSet r=p.executeQuery(); if (!r.next()) return Optional.empty(); String answerId=r.getString(1);
            List<ConsultationCandidate> candidates=new ArrayList<>();
            try (PreparedStatement q=c.prepareStatement("SELECT s.source_object_id,s.version_ref,s.location_text,rc.match_basis FROM con_answer_candidate ac JOIN con_retrieval_candidate rc ON ac.candidate_id=rc.candidate_id JOIN con_source_locator s ON rc.source_id=s.source_id WHERE ac.answer_id=?")) {
                q.setString(1, answerId); ResultSet cr=q.executeQuery(); while(cr.next()) candidates.add(new ConsultationCandidate(cr.getString(1),cr.getString(2),cr.getString(3),cr.getString(4)));
            }
            List<ConsultationSourceLink> links = new ArrayList<>();
            try (PreparedStatement q = c.prepareStatement("SELECT asl.answer_source_link_id,s.source_object_id,s.version_ref,s.location_text FROM con_answer_source_link asl JOIN con_source_locator s ON asl.source_id=s.source_id WHERE asl.answer_id=?")) {
                q.setString(1, answerId); ResultSet lr = q.executeQuery(); while (lr.next()) links.add(new ConsultationSourceLink(lr.getString(1), lr.getString(2), lr.getString(3), lr.getString(4)));
            }
            return Optional.of(new StoredResult(requester, fingerprint, accumulatedInput(c, consultationId), new ConsultationOutcome(consultationId, answerId, r.getInt(2), fingerprint, r.getString(3), List.of(), candidates, links, r.getString(4), r.getString(5))));
        }
    }
    private static int nextInputSequence(Connection c, String consultationId) throws SQLException {
        try (PreparedStatement p=c.prepareStatement("SELECT COALESCE(MAX(input_sequence),0)+1 FROM con_user_input WHERE consultation_id=?")) { p.setString(1,consultationId); ResultSet r=p.executeQuery(); r.next(); return r.getInt(1); }
    }
    private static String accumulatedInput(Connection c, String consultationId) throws SQLException {
        StringBuilder value=new StringBuilder();
        try (PreparedStatement p=c.prepareStatement("SELECT content FROM con_user_input WHERE consultation_id=? ORDER BY input_sequence")) { p.setString(1,consultationId); ResultSet r=p.executeQuery(); while(r.next()){if(!value.isEmpty())value.append('\n');value.append(r.getString(1));} }
        return value.toString();
    }
    private static void persistCandidates(Connection c, String requestId, String answerId, ConsultationOutcome result, Timestamp now) throws SQLException {
        for (int index=0; index<result.candidates().size(); index++) {
            ConsultationCandidate candidate=result.candidates().get(index); String sourceId=UUID.randomUUID().toString(), candidateId=UUID.randomUUID().toString();
            source(c,sourceId,"PATENT_DOCUMENT","v0-synthetic-patent",candidate.sourceId(),candidate.version(),candidate.location(),now);
            insert(c,"INSERT INTO con_retrieval_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?)",candidateId,requestId,sourceId,candidate.sourceId(),candidate.matchBasis(),"synthetic-match","USABLE",null);
            insert(c,"INSERT INTO con_answer_candidate VALUES (?, ?)",answerId,candidateId);
            String linkId=index<result.sourceLinks().size()?result.sourceLinks().get(index).sourceLinkId():UUID.randomUUID().toString();
            insert(c,"INSERT INTO con_answer_source_link VALUES (?, ?, ?, ?)",linkId,answerId,sourceId,candidate.location());
        }
    }
    private static void source(Connection c,Object... v) throws SQLException { insert(c,"INSERT INTO con_source_locator VALUES (?, ?, ?, ?, ?, ?, ?)",v); }
    private static void insert(Connection c,String sql,Object... v) throws SQLException { try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<v.length;i++)p.setObject(i+1,v[i]);p.executeUpdate();} }
}
