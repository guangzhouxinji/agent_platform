package cn.xinji.agentplatform.security.product;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only dispatch history verification; a pending claim remains unknown, never an inferred answer. */
final class ProductModelInvocationRecoveryVerifier {
    private final JdbcTemplate db;
    private final ProductSourceEvidenceFiles files;
    ProductModelInvocationRecoveryVerifier(JdbcTemplate db, Path root, long maxBytes) {
        this.db=db; this.files=new ProductSourceEvidenceFiles(root,maxBytes);
    }
    long verify() {
        if (!ProductModelTurnRetentionReferences.present(db,"CON3_MODEL_INVOCATION")) return 0;
        if (!ProductModelTurnRetentionReferences.present(db)) throw new IllegalStateException("Invocation recovery: MISSING_TURN_SCHEMA");
        var ids=db.queryForList("SELECT invocation_id FROM con3_model_invocation ORDER BY invocation_id",String.class);
        for(String id:ids) check(id);
        return ids.size();
    }
    private void check(String id) {
        var rows=db.queryForList("SELECT * FROM con3_model_invocation WHERE invocation_id=?",id);
        if(rows.size()!=1 || !uuid(id)) invalid(id,"INVOCATION_ID");
        var r=rows.getFirst();String task=(String)r.get("TASK_ID"),kind=(String)r.get("KIND");
        var tasks=db.queryForList("SELECT version,expires_at FROM con3_task WHERE task_id=?",task);
        long version=((Number)r.get("TASK_VERSION")).longValue();
        var created=(java.sql.Timestamp)r.get("CREATED_AT"); var expiry=(java.sql.Timestamp)r.get("EXPIRES_AT");
        if(!uuid(task) || tasks.size()!=1 || version<1 || version>((Number)tasks.getFirst().get("VERSION")).longValue()
                || created==null || expiry==null || !expiry.after(created)
                || expiry.after((java.sql.Timestamp)tasks.getFirst().get("EXPIRES_AT"))) invalid(id,"TASK_VERSION_OR_EXPIRY");
        if(!Set.of("PROPOSAL","EXPLANATION").contains(kind) || blank(r.get("ACTOR_SUBJECT"))
                || !String.valueOf(r.get("REQUEST_ID")).matches("[A-Za-z0-9_-]{1,128}")
                || !sha(r.get("REQUEST_SHA")) || !sha(r.get("RETENTION_EVIDENCE_SHA"))
                || ((Number)r.get("RETENTION_VERSION")).longValue()<1) invalid(id,"METADATA");
        try { files.verified((String)r.get("RETENTION_REF"),(String)r.get("RETENTION_EVIDENCE_SHA")); }
        catch(RuntimeException bad) { invalid(id,"RETENTION_ORIGINAL"); }
        if("EXPLANATION".equals(kind)) {
            if(!uuid((String)r.get("RESULT_SET_ID")) || !sha(r.get("RESULT_SHA"))) invalid(id,"RESULT_BINDING");
            var results=db.queryForList("SELECT a.task_id,a.task_version,r.result_sha FROM con4_result_set r JOIN con4_search_attempt a "
                    + "ON a.attempt_id=r.attempt_id WHERE r.result_set_id=?",r.get("RESULT_SET_ID"));
            if(results.size()!=1 || !task.equals(results.getFirst().get("TASK_ID"))
                    || version!=((Number)results.getFirst().get("TASK_VERSION")).longValue()
                    || !r.get("RESULT_SHA").equals(results.getFirst().get("RESULT_SHA"))) invalid(id,"RESULT_BINDING");
        } else if(r.get("RESULT_SET_ID")!=null || r.get("RESULT_SHA")!=null) invalid(id,"RESULT_BINDING");
        var turns=db.queryForList("SELECT * FROM con3_model_turn WHERE turn_id=? OR (task_id=? AND actor_subject=? AND request_id=?)",
                id,task,r.get("ACTOR_SUBJECT"),r.get("REQUEST_ID"));
        if(turns.size()>1) invalid(id,"TURN_BINDING");
        if(!turns.isEmpty()) {
            var t=turns.getFirst();
            if(!id.equals(t.get("TURN_ID")) || ((java.sql.Timestamp)t.get("CREATED_AT")).before(created)) invalid(id,"TURN_BINDING");
            for(String key:List.of("TASK_ID","TASK_VERSION","ACTOR_SUBJECT","KIND","REQUEST_ID","REQUEST_SHA",
                    "RESULT_SET_ID","RESULT_SHA","EXPIRES_AT","RETENTION_REF","RETENTION_VERSION","RETENTION_EVIDENCE_SHA")) {
                Object a=r.get(key),b=t.get(key);
                if(a instanceof Number && b instanceof Number) { if(((Number)a).longValue()!=((Number)b).longValue()) invalid(id,"TURN_BINDING"); }
                else if(!Objects.equals(a,b)) invalid(id,"TURN_BINDING");
            }
        }
    }
    private static boolean blank(Object value) { return value==null || value.toString().isBlank(); }
    private static boolean sha(Object value) { return value!=null && value.toString().matches("[0-9a-f]{64}"); }
    private static boolean uuid(String value) { try { return UUID.fromString(value).toString().equals(value); } catch(RuntimeException bad) { return false; } }
    private static void invalid(String id,String reason) { throw new IllegalStateException("Recovered model invocation: " + id + ":" + reason); }
}
