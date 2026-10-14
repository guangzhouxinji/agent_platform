import java.nio.file.*;import java.util.*;import java.time.*;import com.fasterxml.jackson.databind.ObjectMapper;
public class ConsultationWindow03ScopeFacts {
 public static void main(String[] args)throws Exception {
 var file=Path.of(args[0]);if(Files.exists(file))throw new IllegalStateException("PRESERVE_REPORT");var r=new LinkedHashMap<String,Object>();r.put("observedAt",Instant.now().toString());
 try(var c=ConsultationNativeMigration.open("CON_NATIVE_PRIMARY",5301,"CONPROD")) {
 r.put("workload",ConsultationNativeMigration.rows(c,"SELECT subject_id,status,expires_at FROM id2_workload WHERE subject_id='97636a93-8002-48e1-9a62-e193b9f05490'")); r.put("task",ConsultationNativeMigration.rows(c,"SELECT task_id,state,authorization_version,expires_at FROM con3_task WHERE task_id='c91aa8d3-0716-4e33-a6b5-2a89461cb1c8'")); r.put("workerEpoch",ConsultationNativeMigration.rows(c,"SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE subject_id='97636a93-8002-48e1-9a62-e193b9f05490'"));
r.put("sourceScope",ConsultationNativeMigration.rows(c,"SELECT scope_id,scope_version,task_version,source_row_version,status,expires_at FROM con4_task_source_scope WHERE task_id='c91aa8d3-0716-4e33-a6b5-2a89461cb1c8'")); r.put("scopeGrants",ConsultationNativeMigration.rows(c,"SELECT g.grant_id,g.status,g.expires_at,e.authorization_epoch,e.current_grant_id FROM id2_grant g JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id WHERE g.task_scope='c91aa8d3-0716-4e33-a6b5-2a89461cb1c8'"));
r.put("organization",ConsultationNativeMigration.rows(c,"SELECT organization_id,status,version FROM con3_organization WHERE organization_id='ORG-CON-PRODUCT'"));
 r.put("createEpoch",ConsultationNativeMigration.rows(c,"SELECT issuer,subject_id,authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer='https://localhost:5443' AND subject_id='7b389c47-9f5b-4004-ad95-084600b351ef' AND organization='ORG-CON-PRODUCT' AND domain_code='CONSULTATION' AND resource_type='ORGANIZATION' AND resource_id='ORG-CON-PRODUCT' AND action_code='CREATE' AND purpose='TASK_WORK'"));
 r.put("createGrant",ConsultationNativeMigration.rows(c,"SELECT g.grant_id,g.grant_version,g.status,g.expires_at FROM id2_grant g JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id WHERE e.issuer='https://localhost:5443' AND e.subject_id='7b389c47-9f5b-4004-ad95-084600b351ef' AND e.organization='ORG-CON-PRODUCT' AND e.domain_code='CONSULTATION' AND e.resource_type='ORGANIZATION' AND e.resource_id='ORG-CON-PRODUCT' AND e.action_code='CREATE' AND e.purpose='TASK_WORK'"));
 r.put("source",ConsultationNativeMigration.rows(c,"SELECT source_id,contract_version,decision_id,source_epoch,row_version,status FROM con4_source_current WHERE source_id='PATENTSVIEW_ZENODO_2024'"));
 r.put("nativeRunCount",ConsultationNativeMigration.rows(c,"SELECT COUNT(*) AS TOTAL FROM agent_run_head"));
 }new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),r);
 }
}


