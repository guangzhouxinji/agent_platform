import java.nio.file.*;import java.util.*;import java.time.*;import com.fasterxml.jackson.databind.ObjectMapper;
public class ConsultationWindow03Facts {
 public static void main(String[] args)throws Exception {
 var file=Path.of(args[0]);if(Files.exists(file))throw new IllegalStateException("PRESERVE_REPORT");var r=new LinkedHashMap<String,Object>();r.put("observedAt",Instant.now().toString());
 try(var c=ConsultationNativeMigration.open("CON_NATIVE_PRIMARY",5301,"CONPROD")) {
 r.put("organization",ConsultationNativeMigration.rows(c,"SELECT organization_id,status,version FROM con3_organization WHERE organization_id='ORG-CON-PRODUCT'"));
 r.put("createEpoch",ConsultationNativeMigration.rows(c,"SELECT issuer,subject_id,authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer='https://localhost:5443' AND subject_id='7b389c47-9f5b-4004-ad95-084600b351ef' AND organization='ORG-CON-PRODUCT' AND domain_code='CONSULTATION' AND resource_type='ORGANIZATION' AND resource_id='ORG-CON-PRODUCT' AND action_code='CREATE' AND purpose='TASK_WORK'"));
 r.put("createGrant",ConsultationNativeMigration.rows(c,"SELECT g.grant_id,g.grant_version,g.status,g.expires_at FROM id2_grant g JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id WHERE e.issuer='https://localhost:5443' AND e.subject_id='7b389c47-9f5b-4004-ad95-084600b351ef' AND e.organization='ORG-CON-PRODUCT' AND e.domain_code='CONSULTATION' AND e.resource_type='ORGANIZATION' AND e.resource_id='ORG-CON-PRODUCT' AND e.action_code='CREATE' AND e.purpose='TASK_WORK'"));
 r.put("source",ConsultationNativeMigration.rows(c,"SELECT source_id,contract_version,decision_id,source_epoch,row_version,status FROM con4_source_current WHERE source_id='PATENTSVIEW_ZENODO_2024'"));
 r.put("nativeRunCount",ConsultationNativeMigration.rows(c,"SELECT COUNT(*) AS TOTAL FROM agent_run_head"));
 }new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),r);
 }
}
