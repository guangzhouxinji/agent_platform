package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonMatrixResults.*;
import cn.xinji.agentplatform.security.product.ProductComparisonMatrixResults.Package;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Every result, native row, citation, receipt and fencing transition commits together. */
final class ProductComparisonMatrixStore {
    record Response(String status,String requestSha,String approvalSha,String configurationVersion,String raw) { }
    private final JdbcTemplate db;private final ProductComparisonJson json;private final TransactionTemplate tx;private final Clock clock;
    private final ProductComparisonLineageStore lineage;
    ProductComparisonMatrixStore(JdbcTemplate db,ProductComparisonJson json,TransactionTemplate tx,Clock clock) {
        this(db,json,tx,clock,new ProductComparisonLineageStore(db,json));
    }
    ProductComparisonMatrixStore(JdbcTemplate db,ProductComparisonJson json,TransactionTemplate tx,Clock clock,ProductComparisonLineageStore lineage) {
        this.db=db;this.json=json;this.tx=tx;this.clock=clock;
        this.lineage=Objects.requireNonNull(lineage);
        for(String table:List.of("con5_matrix_invocation","con5_matrix_report","con5_matrix_row","con5_matrix_citation","con5_matrix_receipt"))
            db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);
    }
    /** Advisory current check before claim. reserve still enforces budget atomically before an external call. */
    void requireAvailableBudget(ProductComparisonModelAdmission.Permit permit) {
        Long used=db.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation WHERE approval_sha=?",Long.class,permit.evidenceSha());
        if(used==null||used>=permit.approval().maxCalls())throw new IllegalStateException("COMPARISON_MODEL_BUDGET_EXHAUSTED");
    }
    void requireLineageSchema(){ProductComparisonSchemaGuard.lineage(db);}
    ProductComparisonLineageFacts.Captured capture(ProductComparisonWorkerMaterials.Loaded loaded,cn.xinji.agentplatform.runtime.ModelGateway.Configuration config,String requestSha) {
        return new ProductComparisonLineageFacts(json,new ProductComparisonMatrixCalculation(json)).capture(loaded,config,requestSha);
    }
    Response reserve(String task,String attempt,String candidate,String requestSha,ProductComparisonModelAdmission.Permit permit,
                     ProductComparisonLineageFacts.Captured captured,Runnable current) {
        if(captured==null||!task.equals(captured.snapshot().taskId())||!requestSha.equals(captured.requestSha()))throw corrupt();
        return tx.execute(status->{lock(task);current.run();var rows=response(attempt);
            if(rows!=null){if(!rows.requestSha().equals(requestSha)||!rows.approvalSha().equals(permit.evidenceSha())
                    || !rows.configurationVersion().equals(permit.approval().configuration().version()))throw corrupt();
                if(!"RECEIVED".equals(rows.status()))throw new IllegalStateException("MODEL_INVOCATION_UNRESOLVED");
                if(!captured.equals(lineage.capture(lineage.material(task,attempt))))throw corrupt();
                current.run();return rows;}
            long calls=db.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation WHERE approval_sha=?",Long.class,permit.evidenceSha());
            if(calls>=permit.approval().maxCalls())throw new IllegalStateException("COMPARISON_MODEL_BUDGET_EXHAUSTED");
            if(db.update("INSERT INTO con5_matrix_invocation VALUES(?,?,?,?,'RESERVED',NULL,NULL,?)",attempt,permit.evidenceSha(),requestSha,
                    permit.approval().configuration().version(),Timestamp.from(clock.instant()))!=1)throw corrupt();
            lineage.reserve(task,attempt,candidate,permit.evidenceSha(),captured,permit.approval().configuration());
            current.run();return null;});
    }
    void receive(String task,String attempt,String raw,String configurationVersion,Runnable current) {
        tx.executeWithoutResult(status->{lock(task);current.run();
            if(db.update("UPDATE con5_matrix_invocation SET response_json=?,response_sha=?,status='RECEIVED' "
                    +"WHERE attempt_id=? AND status='RESERVED' AND configuration_version=?",raw,ProductComparisonJson.hash(raw),attempt,configurationVersion)!=1)throw corrupt();current.run();});
    }
    void reject(String task,String attempt,Runnable current,Runnable failCandidate) {
        tx.executeWithoutResult(status->{lock(task);current.run();if(db.update("UPDATE con5_matrix_invocation SET status='REJECTED' WHERE attempt_id=? AND status='RECEIVED'",attempt)!=1)throw corrupt();failCandidate.run();});
    }
    /** Only the owner of a newly reserved, still-unsent call may close a known pre-dispatch failure. */
    void rejectBeforeDispatch(String task,String attempt,Runnable current,Runnable failCandidate) {
        tx.executeWithoutResult(status->{lock(task);current.run();
            if(db.update("UPDATE con5_matrix_invocation SET status='REJECTED' WHERE attempt_id=? AND status='RESERVED' "
                    + "AND response_json IS NULL AND response_sha IS NULL",attempt)!=1)throw corrupt();
            failCandidate.run(); // joins this transaction; its exact lease/current-source checks remain mandatory
        });
    }
    Receipt commit(Package value,Runnable current,Runnable committed) {
        return tx.execute(status->{lock(value.taskId());current.run();var response=response(value.attemptId());
            if(response==null||!"RECEIVED".equals(response.status())||!value.responseSha().equals(ProductComparisonJson.hash(response.raw()))
                    ||!value.approvalSha().equals(response.approvalSha())||!value.configurationVersion().equals(response.configurationVersion())||value.rows().isEmpty())throw corrupt();
            String encoded=json.canonical(value),sha=ProductComparisonJson.hash(encoded);
            db.update("INSERT INTO con5_matrix_report VALUES(?,?,?,?,?,?,?,?,?,?,?)",value.reportId(),value.taskId(),value.comparisonId(),value.candidateId(),value.attemptId(),
                    value.token(),value.inputSha(),sha,encoded,Timestamp.from(value.createdAt()),Timestamp.from(value.expiresAt()));
            for(int i=0;i<value.rows().size();i++){var row=value.rows().get(i);String raw=json.canonical(row);
                db.update("INSERT INTO con5_matrix_row VALUES(?,?,?,?,?,?)",value.reportId(),i,row.featureId(),json.sha(row.claimBranch()),raw,ProductComparisonJson.hash(raw));
                for(int c=0;c<row.citations().size();c++){String citation=json.canonical(row.citations().get(c));
                    db.update("INSERT INTO con5_matrix_citation VALUES(?,?,?,?,?)",value.reportId(),i,c,citation,ProductComparisonJson.hash(citation));}}
            var receipt=new Receipt(value.taskId(),value.comparisonId(),value.candidateId(),value.inputSha(),value.attemptId(),value.token(),value.reportId(),sha,value.resultVersion(),"COMMITTED");
            String rec=json.canonical(receipt);db.update("INSERT INTO con5_matrix_receipt VALUES(?,?,?,?,?)",value.attemptId(),value.reportId(),sha,rec,ProductComparisonJson.hash(rec));
            current.run();
            if(db.update("UPDATE con5_candidate_attempt SET status='COMMITTED' WHERE attempt_id=? AND fencing_token=? AND status='ACTIVE'",value.attemptId(),value.token())!=1
                ||db.update("UPDATE con5_candidate_lease SET status='COMMITTED' WHERE comparison_id=? AND candidate_id=? AND attempt_id=? AND fencing_token=? AND status='ACTIVE'",
                    value.comparisonId(),value.candidateId(),value.attemptId(),value.token())!=1
                ||db.update("UPDATE con5_comparison_candidate SET status='COMMITTED' WHERE comparison_id=? AND candidate_id=? AND status='RUNNING'",value.comparisonId(),value.candidateId())!=1
                ||db.update("UPDATE con5_matrix_invocation SET status='COMMITTED' WHERE attempt_id=? AND status='RECEIVED'",value.attemptId())!=1)throw corrupt();
            lineage.commit(encoded,sha);
            committed.run();read(value.taskId(),value.comparisonId(),value.reportId());return receipt;});
    }
    Report byAttempt(String task,String comparison,String attempt) {
        var ids=db.queryForList("SELECT report_id FROM con5_matrix_report WHERE task_id=? AND comparison_id=? AND attempt_id=?",String.class,task,comparison,attempt);
        return ids.isEmpty()?null:read(task,comparison,ids.getFirst());
    }
    Report read(String task,String comparison,String report) {
        var rows=db.query("SELECT package_json,report_sha,candidate_id,attempt_id,fencing_token,input_sha,created_at,expires_at FROM con5_matrix_report WHERE task_id=? AND comparison_id=? AND report_id=?",(rs,n)->{
            String raw=rs.getString(1),sha=rs.getString(2);if(!ProductComparisonJson.hash(raw).equals(sha))throw corrupt();
            var value=json.stored(raw,Package.class);
            if(!value.candidateId().equals(rs.getString(3))||!value.attemptId().equals(rs.getString(4))||value.token()!=rs.getLong(5)
                    ||!value.inputSha().equals(rs.getString(6))||!value.createdAt().equals(rs.getTimestamp(7).toInstant())||!value.expiresAt().equals(rs.getTimestamp(8).toInstant()))throw corrupt();
            return new Report(value,sha,null);},task,comparison,report);
        if(rows.size()!=1)throw corrupt();var result=rows.getFirst();var value=result.result();
        if(!task.equals(value.taskId())||!comparison.equals(value.comparisonId())||!report.equals(value.reportId())||!value.expiresAt().isAfter(clock.instant()))throw corrupt();
        var nativeRows=db.query("SELECT ordinal,row_json,row_sha,feature_id,branch_sha FROM con5_matrix_row WHERE report_id=? ORDER BY ordinal",(rs,n)->{
            if(rs.getLong(1)!=n||!ProductComparisonJson.hash(rs.getString(2)).equals(rs.getString(3)))throw corrupt();
            var row=json.stored(rs.getString(2),cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.Row.class);
            if(!row.featureId().equals(rs.getString(4))||!json.sha(row.claimBranch()).equals(rs.getString(5)))throw corrupt();
            var citations=db.query("SELECT citation_ordinal,citation_json,citation_sha FROM con5_matrix_citation WHERE report_id=? AND row_ordinal=? ORDER BY citation_ordinal",(cr,k)->{
                if(cr.getLong(1)!=k||!ProductComparisonJson.hash(cr.getString(2)).equals(cr.getString(3)))throw corrupt();
                return json.stored(cr.getString(2),cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText.Evidence.class);},report,n);
            if(!citations.equals(row.citations()))throw corrupt();return row;},report);
        if(!nativeRows.equals(value.rows()))throw corrupt();
        var receipts=db.query("SELECT receipt_json,receipt_sha,report_sha FROM con5_matrix_receipt WHERE attempt_id=? AND report_id=?",(rs,n)->{
            if(!ProductComparisonJson.hash(rs.getString(1)).equals(rs.getString(2))||!result.reportSha().equals(rs.getString(3)))throw corrupt();return json.stored(rs.getString(1),Receipt.class);},value.attemptId(),report);
        var expected=new Receipt(task,comparison,value.candidateId(),value.inputSha(),value.attemptId(),value.token(),report,result.reportSha(),value.resultVersion(),"COMMITTED");
        if(receipts.size()!=1||!receipts.getFirst().equals(expected))throw corrupt();
        Long closure=db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt a JOIN con5_candidate_lease l ON l.attempt_id=a.attempt_id AND l.fencing_token=a.fencing_token "
                +"JOIN con5_matrix_invocation i ON i.attempt_id=a.attempt_id JOIN con5_comparison_candidate c ON c.comparison_id=a.comparison_id AND c.candidate_id=a.candidate_id "
                +"WHERE a.attempt_id=? AND a.task_id=? AND a.comparison_id=? AND a.candidate_id=? AND a.input_sha=? AND a.fencing_token=? "
                +"AND a.status='COMMITTED' AND l.status='COMMITTED' AND i.status='COMMITTED' AND c.status='COMMITTED' AND i.response_sha=? AND i.approval_sha=? AND i.configuration_version=?",
                Long.class,value.attemptId(),task,comparison,value.candidateId(),value.inputSha(),value.token(),value.responseSha(),value.approvalSha(),value.configurationVersion());
        if(closure!=1)throw corrupt();var response=response(value.attemptId());
        if(response==null||response.raw()==null||!ProductComparisonJson.hash(response.raw()).equals(value.responseSha()))throw corrupt();
        return new Report(value,result.reportSha(),expected);
    }
    String rawResponse(String attempt){var value=response(attempt);if(value==null||value.raw()==null)throw corrupt();return value.raw();}
    List<String> ids(String task,String comparison) {return db.queryForList("SELECT report_id FROM con5_matrix_report WHERE task_id=? AND comparison_id=? ORDER BY created_at,report_id",String.class,task,comparison);}
    private Response response(String attempt) {
        var rows=db.query("SELECT status,request_sha,approval_sha,configuration_version,response_json,response_sha FROM con5_matrix_invocation WHERE attempt_id=?",(rs,n)->{
            String raw=rs.getString(5);if(raw!=null&&!ProductComparisonJson.hash(raw).equals(rs.getString(6)))throw corrupt();return new Response(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),raw);},attempt);
        if(rows.size()>1)throw corrupt();return rows.isEmpty()?null:rows.getFirst();
    }
    private void lock(String task){if(db.queryForList("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",String.class,task).size()!=1)throw corrupt();}
    static IllegalStateException corrupt(){return new IllegalStateException("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");}
}

