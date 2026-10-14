package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonInputs.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Immutable input/receipt with native relation and event closure; never a Worker result. */
final class ProductComparisonInputStore {
    private final JdbcTemplate db;
    private final ProductComparisonJson json;
    ProductComparisonInputStore(JdbcTemplate db,ProductComparisonJson json) {this.db=db;this.json=json;}
    String current(String task) {
        var rows=db.query("SELECT comparison_id FROM con5_comparison_current WHERE task_id=?",(rs,row)->rs.getString(1),task);
        return rows.isEmpty()?null:rows.getFirst();
    }
    View replay(String task,String issuer,String subject,String key,String sha) {
        var rows=db.query("SELECT comparison_id,request_sha FROM con5_comparison_input WHERE task_id=? AND actor_issuer=? "
                + "AND actor_subject=? AND request_id=?",(rs,row)->{
            if(!sha.equals(rs.getString(2))) throw ProductComparisonPreparationLoader.conflict();return rs.getString(1);},task,issuer,subject,key);
        return rows.isEmpty()?null:read(task,rows.getFirst());
    }
    View save(Envelope e) {
        String sha=json.sha(e);
        db.update("INSERT INTO con5_comparison_input VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",e.comparisonId(),e.taskId(),
                e.command().featureRevisionId(),e.command().featureRevisionNumber(),e.preparationId(),e.taskVersion(),e.previousComparisonId(),
                json.canonical(e),sha,e.candidates().size(),e.actorIssuer(),e.actorSubject(),e.requestId(),e.requestSha(),
                Timestamp.from(e.createdAt()),Timestamp.from(e.expiresAt()));
        for(var candidate:e.candidates()) db.update("INSERT INTO con5_comparison_candidate VALUES(?,?,'CANDIDATE',?,?,'QUEUED')",
                e.comparisonId(),e.preparationId(),candidate.candidateId(),candidate.ordinal());
        if(e.previousComparisonId()==null) db.update("INSERT INTO con5_comparison_current VALUES(?,?)",e.taskId(),e.comparisonId());
        else if(db.update("UPDATE con5_comparison_current SET comparison_id=? WHERE task_id=? AND comparison_id=?",
                e.comparisonId(),e.taskId(),e.previousComparisonId())!=1) throw ProductComparisonPreparationLoader.conflict();
        db.update("INSERT INTO con5_comparison_event VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),e.taskId(),e.comparisonId(),
                e.actorIssuer(),e.actorSubject(),e.requestSha(),sha,Timestamp.from(e.createdAt()));
        return read(e.taskId(),e.comparisonId());
    }
    View read(String task,String id) {
        var rows=db.query("SELECT input_json,input_sha,task_version,feature_revision_id,feature_revision_number,preparation_id,"
                + "previous_comparison_id,candidate_count,actor_issuer,actor_subject,request_id,request_sha,created_at,expires_at "
                + "FROM con5_comparison_input WHERE task_id=? AND comparison_id=?",(rs,row)->{
            var e=json.stored(rs.getString(1),Envelope.class);String sha=rs.getString(2);
            if(!id.equals(e.comparisonId()) || !task.equals(e.taskId()) || !sha.equals(json.sha(e)) || e.taskVersion()!=rs.getLong(3)
                    || !e.command().featureRevisionId().equals(rs.getString(4)) || e.command().featureRevisionNumber()!=rs.getLong(5)
                    || !e.preparationId().equals(rs.getString(6)) || !Objects.equals(e.previousComparisonId(),rs.getString(7))
                    || e.candidates().size()!=rs.getLong(8) || !e.actorIssuer().equals(rs.getString(9)) || !e.actorSubject().equals(rs.getString(10))
                    || !e.requestId().equals(rs.getString(11)) || !e.requestSha().equals(rs.getString(12))
                    || !e.createdAt().equals(rs.getTimestamp(13).toInstant()) || !e.expiresAt().equals(rs.getTimestamp(14).toInstant())) throw corrupt();
            return new View(e,sha,List.of());
        },task,id);
        if(rows.size()!=1) throw ProductComparisonPreparationLoader.conflict();var view=rows.getFirst();var e=view.input();
        var nativeCandidates=db.query("SELECT candidate_id,ordinal,preparation_id,document_role FROM con5_comparison_candidate "
                + "WHERE comparison_id=? ORDER BY ordinal",(rs,row)->{
            if(!e.preparationId().equals(rs.getString(3)) || !"CANDIDATE".equals(rs.getString(4))) throw corrupt();
            return new Candidate(rs.getString(1),rs.getLong(2));},id);
        if(!e.candidates().equals(nativeCandidates)) throw corrupt();
        var event=db.query("SELECT actor_issuer,actor_subject,request_sha,input_sha,occurred_at FROM con5_comparison_event "
                + "WHERE task_id=? AND comparison_id=?",(rs,row)-> e.actorIssuer().equals(rs.getString(1)) && e.actorSubject().equals(rs.getString(2))
                && e.requestSha().equals(rs.getString(3)) && view.inputSha().equals(rs.getString(4))
                && e.createdAt().equals(rs.getTimestamp(5).toInstant()),task,id);
        if(event.size()!=1 || !event.getFirst()) throw corrupt();
        var progress=db.query("SELECT candidate_id,status FROM con5_comparison_candidate WHERE comparison_id=? ORDER BY ordinal",
                (rs,row)->new Progress(rs.getString(1),rs.getString(2)),id);
        return new View(e,view.inputSha(),List.copyOf(progress));
    }
    static IllegalStateException corrupt() {return new IllegalStateException("Comparison input closure or digest invalid");}
}
