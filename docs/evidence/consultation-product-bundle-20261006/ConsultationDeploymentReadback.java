import java.sql.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** SELECT-only metadata from exact managed product endpoints. No migration, issuance or renewal. */
public class ConsultationDeploymentReadback {
 static List<Map<String,Object>> rows(Connection c,String sql) throws Exception {
  if(!sql.startsWith("SELECT "))throw new IllegalArgumentException("READBACK_SELECT_ONLY");
  List<Map<String,Object>> out=new ArrayList<>();
  try(var s=c.createStatement()){s.setMaxRows(30);try(var r=s.executeQuery(sql)){var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++){Object value=r.getObject(i);row.put(m.getColumnLabel(i),value==null?null:value.toString());}out.add(row);}}}return out;
 }
 static Connection open(String role,int port) throws Exception {
  String url=System.getenv(role+"_URL");if(!("jdbc:dm://127.0.0.1:"+port).equals(url))throw new IllegalArgumentException("EXACT_MANAGED_ENDPOINT_REQUIRED");
  return DriverManager.getConnection(url,System.getenv(role+"_USER"),System.getenv(role+"_PASSWORD"));
 }
 public static void main(String[] args)throws Exception {
  var report=new LinkedHashMap<String,Object>();report.put("observedAt",Instant.now().toString());report.put("scope","EXACT_ORIGINAL_PRODUCT_SELECT_ONLY_NOT_RESTORE_OR_ACCEPTANCE");
  try(var c=open("READBACK_PRIMARY",5301);var a=open("READBACK_AUTHORITY",5302)) {
   report.put("databaseTime",rows(c,"SELECT CURRENT_TIMESTAMP AS OBSERVED_DATABASE_TIME FROM DUAL"));
   report.put("newTables",rows(c,"SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN ('AGENT_RUN_HEAD','AGENT_RUN_REVISION','CON3_MODEL_INVOCATION') ORDER BY TABLE_NAME"));
   report.put("taskCounts",rows(c,"SELECT COUNT(*) AS TOTAL_TASKS,SUM(CASE WHEN expires_at>CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS UNEXPIRED_TASKS,MAX(expires_at) AS LATEST_TASK_EXPIRY FROM con3_task"));
   report.put("recentTasks",rows(c,"SELECT task_id,state,version,expires_at FROM con3_task ORDER BY created_at DESC"));
   report.put("grantCounts",rows(c,"SELECT action_code,purpose,status,COUNT(*) AS ROW_COUNT,SUM(CASE WHEN expires_at>CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS UNEXPIRED_ROWS,MAX(expires_at) AS LATEST_EXPIRY FROM id2_grant GROUP BY action_code,purpose,status ORDER BY action_code,purpose,status"));
   report.put("sourceModelAvailability",rows(c,"SELECT c.source_id,c.contract_version,c.status,a.valid_until AS ADMISSION_VALID_UNTIL,MAX(f.valid_until) AS MODEL_VALID_UNTIL,a.responsible_party FROM con4_source_current c JOIN con4_source_admission a ON a.decision_id=c.decision_id LEFT JOIN con4_source_field_use f ON f.decision_id=a.decision_id AND f.use_code='MODEL' GROUP BY c.source_id,c.contract_version,c.status,a.valid_until,a.responsible_party"));
   report.put("usableModelFieldCount",rows(c,"SELECT COUNT(*) AS CURRENT_USABLE_MODEL_FIELDS FROM con4_source_current c JOIN con4_source_admission a ON a.decision_id=c.decision_id JOIN con4_source_field_use f ON f.decision_id=a.decision_id WHERE c.status='ACTIVE' AND a.valid_until>CURRENT_TIMESTAMP AND f.use_code='MODEL' AND f.valid_until>CURRENT_TIMESTAMP"));
   report.put("workers",rows(c,"SELECT subject_id,status,workload_kind,allowed_purpose,expires_at FROM id2_workload ORDER BY created_at DESC"));
   report.put("grantWatermark",rows(a,"SELECT issuer,generation,state,intent_id,history_sha FROM id2_authority_watermark"));
   report.put("sourceWatermark",rows(a,"SELECT source_id,source_epoch,state,intent_id,history_sha FROM id4_source_watermark"));
   report.put("businessWritesExecuted",false);
  }
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
 }
}
