import java.nio.file.*;import java.util.*;import java.time.*;import com.fasterxml.jackson.databind.ObjectMapper;
public class ConsultationRunAudit {
 public static void main(String[] args)throws Exception {
 var file=Path.of(args[0]);if(Files.exists(file))throw new IllegalStateException("PRESERVE_REPORT");var r=new LinkedHashMap<String,Object>();r.put("observedAt",Instant.now().toString());var task="7e4f0611-2095-40a0-bd10-4445e7baffd3";
 try(var c=ConsultationNativeMigration.open("CON_NATIVE_PRIMARY",5301,"CONPROD")) {
 r.put("task",ConsultationNativeMigration.rows(c,"SELECT task_id,state,version,authorization_version,expires_at FROM con3_task WHERE task_id='"+task+"'"));
 r.put("runs",ConsultationNativeMigration.rows(c,"SELECT run_id,agent_id,task_id,goal_id,goal_version,scope_ref,head_version,expires_at FROM agent_run_head WHERE task_id='"+task+"'"));
 r.put("revisions",ConsultationNativeMigration.rows(c,"SELECT r.run_id,r.revision,r.payload_sha FROM agent_run_revision r JOIN agent_run_head h ON h.run_id=r.run_id WHERE h.task_id='"+task+"' ORDER BY r.revision"));
 r.put("attempts",ConsultationNativeMigration.rows(c,"SELECT attempt_id,input_revision FROM con4_search_attempt WHERE task_id='"+task+"'"));
 r.put("modelInvocationFences",ConsultationNativeMigration.rows(c,"SELECT invocation_id,task_version,kind,request_id,request_sha,result_set_id,result_sha,created_at,expires_at FROM con3_model_invocation WHERE task_id='"+task+"'"));
 r.put("modelTurns",ConsultationNativeMigration.rows(c,"SELECT turn_id,task_version,kind,result_set_id,result_sha,model_id,prompt_version,created_at,expires_at FROM con3_model_turn WHERE task_id='"+task+"'"));
 var payloads=ConsultationNativeMigration.rows(c,"SELECT request_json FROM con3_model_turn WHERE task_id='"+task+"'");var summaries=new ArrayList<Map<String,Object>>();var mapper=new ObjectMapper();
 for(var payload:payloads){var node=mapper.readTree((String)payload.get("REQUEST_JSON"));var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);if(names.contains("claims")||names.contains("claimsText")||names.contains("fullText")||names.contains("messageText"))throw new IllegalStateException("UNAPPROVED_MODEL_BODY");summaries.add(Map.of("keys",names,"memberCount",node.path("members").size(),"goal",node.path("goal").asText(),"sha256",ConsultationNativeMigration.hash(mapper.writeValueAsBytes(node))));}
 r.put("modelPayloadSummaries",summaries);
 r.put("sourceUses",ConsultationNativeMigration.rows(c,"SELECT f.field_code,f.use_code,f.valid_until FROM con4_source_field_use f JOIN con4_source_current s ON s.decision_id=f.decision_id WHERE s.source_id='PATENTSVIEW_ZENODO_2024' ORDER BY f.field_code,f.use_code"));
 r.put("worker",ConsultationNativeMigration.rows(c,"SELECT subject_id,workload_kind,status,expires_at FROM id2_workload WHERE subject_id='42df271e-d4de-444f-aad4-847e59b39855'"));
 }new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),r);
 }
}

