package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only immutable preparation/confirmation data; no Source, Grant, Worker or official state writes. */
final class ProductComparisonStore {
    record DocumentRow(String role,long ordinal,String key,String sourceId,String contractVersion,String decisionId,
                       long sourceEpoch,long sourceRowVersion,String documentId,String documentVersion,String sourceVersion,
                       String rawSha,String originalRef,String jsonPointer,String branchJson,String displayGrantId,
                       long displayGeneration,String compareGrantId,long compareGeneration,Instant expiresAt) { }
    record Preparation(String id,String taskId,long taskVersion,Prepare command,String snapshotJson,String sha,
                       String materialSha,Instant expiresAt) { }
    record Current(String revisionId,long number) { }
    private final JdbcTemplate db;
    private final ProductComparisonJson json;
    ProductComparisonStore(JdbcTemplate db,ProductComparisonJson json) {this.db=db;this.json=json;}
    static DocumentRow documentRow(String role,long ordinal,ProductComparisonDocuments.Loaded loaded,ProductComparisonJson json) {
        var material=loaded.material();var authority=loaded.authority();var source=authority.source();
        return new DocumentRow(role,ordinal,material.key(),source.sourceId(),source.contractVersion(),source.decisionId(),source.epoch(),
                source.rowVersion(),material.documentId(),material.documentVersion(),material.sourceVersion(),material.rawSha(),material.originalRef(),
                material.jsonPointer(),json.canonical(material.branches()),authority.display().grantId(),authority.display().grantGeneration(),
                authority.comparison().grantId(),authority.comparison().grantGeneration(),authority.expiresAt());
    }
    void lockTask(String task) {
        if(db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",(rs,row)->rs.getString(1),task).size()!=1)
            throw ProductComparisonPreparationLoader.conflict();
    }
    void savePreparation(String id,ProductComparisonPreparationLoader.Loaded loaded,String issuer,String subject,Instant now) {
        var c=loaded.command();var task=loaded.task();var retention=loaded.retention();
        db.update("INSERT INTO con5_preparation(preparation_id,task_id,organization_id,task_version,goal_id,goal_revision,input_id,"
                + "target_message_id,command_json,snapshot_json,prepare_sha,material_sha,actor_issuer,actor_subject,created_at,expires_at,"
                + "retention_ref,retention_version,retention_evidence_sha) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,task.id(),task.organization(),task.version(),c.goalId(),c.goalRevision(),c.inputId(),c.target().messageId(),json.canonical(c),
                loaded.snapshotJson(),loaded.prepareSha(),loaded.materialSha(),issuer,subject,Timestamp.from(now),Timestamp.from(loaded.expiresAt()),
                retention.ref(),retention.version(),retention.sha());
        for(var row:loaded.documents()) db.update("INSERT INTO con5_prepared_document(preparation_id,document_role,ordinal,document_key,"
                + "source_id,contract_version,decision_id,source_epoch,source_row_version,document_id,document_version,source_version,raw_sha,"
                + "original_ref,json_pointer,branch_json,display_grant_id,display_generation,compare_grant_id,compare_generation,expires_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,row.role(),row.ordinal(),row.key(),row.sourceId(),row.contractVersion(),
                row.decisionId(),row.sourceEpoch(),row.sourceRowVersion(),row.documentId(),row.documentVersion(),row.sourceVersion(),row.rawSha(),
                row.originalRef(),row.jsonPointer(),row.branchJson(),row.displayGrantId(),row.displayGeneration(),row.compareGrantId(),
                row.compareGeneration(),Timestamp.from(row.expiresAt()));
    }
    Preparation preparation(String task,String id,Instant now) {
        var rows=db.query("SELECT task_version,command_json,snapshot_json,prepare_sha,material_sha,expires_at FROM con5_preparation "
                + "WHERE task_id=? AND preparation_id=? AND expires_at>?",(rs,row)->new Preparation(id,task,rs.getLong(1),
                json.stored(rs.getString(2),Prepare.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()),
                task,id,Timestamp.from(now));
        if(rows.size()!=1) throw ProductComparisonPreparationLoader.conflict();
        var prepared=rows.getFirst();
        if(!ProductComparisonJson.hash(prepared.snapshotJson()).equals(prepared.sha())) throw corrupt();
        var nativeRows=db.query("SELECT document_role,ordinal,document_key,source_id,contract_version,decision_id,source_epoch,"
                + "source_row_version,document_id,document_version,source_version,raw_sha,original_ref,json_pointer,branch_json,display_grant_id,"
                + "display_generation,compare_grant_id,compare_generation,expires_at FROM con5_prepared_document WHERE preparation_id=? "
                + "ORDER BY CASE WHEN document_role='TARGET' THEN 0 ELSE 1 END,ordinal",(rs,row)->new DocumentRow(rs.getString(1),rs.getLong(2),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getLong(8),rs.getString(9),rs.getString(10),
                rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),rs.getLong(17),
                rs.getString(18),rs.getLong(19),rs.getTimestamp(20).toInstant()),id);
        var snapshot=json.stored(prepared.snapshotJson(),com.fasterxml.jackson.databind.JsonNode.class);
        if(!task.equals(snapshot.path("taskId").asText()) || prepared.taskVersion()!=snapshot.path("taskVersion").asLong()
                || !json.canonical(prepared.command()).equals(json.canonical(snapshot.get("command")))
                || !prepared.materialSha().equals(json.sha(snapshot.get("material")))
                || !prepared.expiresAt().equals(Instant.parse(snapshot.path("expiresAt").asText()))) throw corrupt();
        var scalarChecks=db.query("SELECT goal_id,goal_revision,input_id,target_message_id,retention_ref,retention_version,"
                + "retention_evidence_sha FROM con5_preparation WHERE task_id=? AND preparation_id=?",(rs,row)->{
            var command=prepared.command();var retention=snapshot.path("retention");
            return Objects.equals(command.goalId(),rs.getString(1)) && command.goalRevision()==rs.getLong(2)
                    && Objects.equals(command.inputId(),rs.getString(3)) && Objects.equals(command.target().messageId(),rs.getString(4))
                    && Objects.equals(retention.path("ref").asText(),rs.getString(5))
                    && retention.path("version").asLong()==rs.getLong(6) && Objects.equals(retention.path("sha").asText(),rs.getString(7));
        },task,id);
        if(scalarChecks.size()!=1 || !scalarChecks.getFirst()) throw corrupt();
        if(!json.canonical(nativeRows).equals(json.canonical(snapshot.get("documentRows")))) throw corrupt();
        return prepared;
    }
    record PrepareRequest(String id,String key,String sha) { }
    PrepareRequest preparationRequest(String task,String issuer,String subject,String key) {
        var rows=db.query("SELECT preparation_id,request_id,request_sha FROM con5_preparation_request WHERE task_id=? AND actor_issuer=? AND actor_subject=? AND request_id=?",
                (rs,n)->new PrepareRequest(rs.getString(1),rs.getString(2),rs.getString(3)),task,issuer,subject,key);
        if(rows.size()>1)throw corrupt();return rows.isEmpty()?null:rows.getFirst();
    }
    PrepareRequest requestForPreparation(String task,String id) {
        var rows=db.query("SELECT preparation_id,request_id,request_sha FROM con5_preparation_request WHERE task_id=? AND preparation_id=?",
                (rs,n)->new PrepareRequest(rs.getString(1),rs.getString(2),rs.getString(3)),task,id);
        if(rows.size()>1)throw corrupt();return rows.isEmpty()?null:rows.getFirst();
    }
    void savePrepareRequest(String task,String issuer,String subject,String key,String sha,String id) {
        db.update("INSERT INTO con5_preparation_request VALUES(?,?,?,?,?,?)",task,issuer,subject,key,sha,id);
    }
    Current current(String task) {
        var rows=db.query("SELECT revision_id,revision_number FROM con5_feature_current WHERE task_id=?",
                (rs,row)->new Current(rs.getString(1),rs.getLong(2)),task);
        return rows.isEmpty()?null:rows.getFirst();
    }
    FeatureView replay(String task,String issuer,String subject,String request,String sha) {
        var rows=db.query("SELECT revision_id,request_sha FROM con5_feature_revision WHERE task_id=? AND actor_issuer=? "
                + "AND actor_subject=? AND request_id=?",(rs,row)->{
            if(!sha.equals(rs.getString(2))) throw ProductComparisonPreparationLoader.conflict();return rs.getString(1);},task,issuer,subject,request);
        return rows.isEmpty()?null:revision(task,rows.getFirst());
    }
    FeatureView save(Revision r,Current before) {
        String sha=json.sha(r);
        db.update("INSERT INTO con5_feature_revision(revision_id,task_id,revision_number,previous_revision_id,preparation_id,prepare_sha,"
                + "target_sha,package_sha,feature_count,actor_issuer,actor_subject,request_id,request_sha,statement_json,confirmed_at,expires_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",r.revisionId(),r.taskId(),r.revisionNumber(),r.previousRevisionId(),r.preparationId(),
                r.prepareSha(),r.targetSha(),sha,r.features().size(),r.actorIssuer(),r.actorSubject(),r.requestId(),r.requestSha(),
                json.canonical(r.userStatement()),Timestamp.from(r.confirmedAt()),Timestamp.from(r.expiresAt()));
        List<Feature> pending=new ArrayList<>(r.features()); Set<String> inserted=new HashSet<>();
        while(!pending.isEmpty()) {
            int count=pending.size();
            for(var item:List.copyOf(pending)) if(item.parentFeatureId()==null || inserted.contains(item.parentFeatureId())) {
                var span=item.original();
                db.update("INSERT INTO con5_feature(revision_id,feature_id,ordinal,parent_feature_id,char_start,char_end,original_version,"
                        + "original_ref,json_pointer,original_sha,byte_start,byte_end,span_sha,claim_number,user_text_json) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",r.revisionId(),item.featureId(),item.ordinal(),item.parentFeatureId(),
                        item.characterStart(),item.characterEnd(),span.version(),span.ref(),span.pointer(),span.originalSha(),
                        span.byteStart(),span.byteEnd(),span.spanSha(),item.claimNumber(),json.canonical(item.userText()));
                inserted.add(item.featureId());pending.remove(item);
            }
            if(count==pending.size()) throw corrupt();
        }
        if(before==null) db.update("INSERT INTO con5_feature_current VALUES(?,?,?)",r.taskId(),r.revisionId(),r.revisionNumber());
        else if(db.update("UPDATE con5_feature_current SET revision_id=?,revision_number=? WHERE task_id=? AND revision_id=? "
                + "AND revision_number=?",r.revisionId(),r.revisionNumber(),r.taskId(),before.revisionId(),before.number())!=1)
            throw ProductComparisonPreparationLoader.conflict();
        db.update("INSERT INTO con5_feature_event VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),r.taskId(),r.revisionId(),
                r.actorIssuer(),r.actorSubject(),r.requestSha(),sha,Timestamp.from(r.confirmedAt()));
        return revision(r.taskId(),r.revisionId());
    }
    FeatureView revision(String task,String id) {
        record Stored(Revision revision,String sha,long count) { }
        var rows=db.query("SELECT revision_number,previous_revision_id,preparation_id,prepare_sha,target_sha,actor_issuer,actor_subject,"
                + "request_id,request_sha,statement_json,confirmed_at,expires_at,package_sha,feature_count FROM con5_feature_revision "
                + "WHERE task_id=? AND revision_id=?",(rs,row)->new Stored(new Revision(id,task,rs.getLong(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
                json.stored(rs.getString(10),String.class),rs.getTimestamp(11).toInstant(),rs.getTimestamp(12).toInstant(),features(id)),
                rs.getString(13),rs.getLong(14)),task,id);
        if(rows.size()!=1) throw ProductComparisonPreparationLoader.conflict();
        var stored=rows.getFirst();var r=stored.revision();
        if(stored.count()!=r.features().size() || !stored.sha().equals(json.sha(r))) throw corrupt();
        var events=db.query("SELECT request_sha,package_sha,actor_issuer,actor_subject,occurred_at FROM con5_feature_event "
                + "WHERE task_id=? AND revision_id=?",(rs,row)->rs.getString(1).equals(r.requestSha()) && rs.getString(2).equals(stored.sha())
                && rs.getString(3).equals(r.actorIssuer()) && rs.getString(4).equals(r.actorSubject())
                && rs.getTimestamp(5).toInstant().equals(r.confirmedAt()),task,id);
        if(events.size()!=1 || !events.getFirst()) throw corrupt();
        return new FeatureView(r,stored.sha());
    }
    private List<Feature> features(String id) {
        return db.query("SELECT feature_id,ordinal,char_start,char_end,user_text_json,parent_feature_id,claim_number,original_version,"
                + "original_ref,json_pointer,original_sha,byte_start,byte_end,span_sha FROM con5_feature WHERE revision_id=? ORDER BY ordinal",
                (rs,row)->new Feature(rs.getString(1),rs.getLong(2),rs.getInt(3),rs.getInt(4),json.stored(rs.getString(5),String.class),
                rs.getString(6),rs.getObject(7)==null?null:rs.getInt(7),new Span(rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),
                rs.getInt(12),rs.getInt(13),rs.getString(14))),id);
    }
    private static IllegalStateException corrupt() {return new IllegalStateException("Comparison persisted closure or digest invalid");}
}
