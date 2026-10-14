import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public class EnforcementCollectionRecoveryAudit {
 static final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 static List<Map<String,Object>> query(Connection db,String sql)throws Exception {
  var rows=new ArrayList<Map<String,Object>>();try(var s=db.createStatement();var r=s.executeQuery(sql)){
   var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));rows.add(row);}
  }return rows;
 }
 public static void main(String[] args)throws Exception {
  var result=new LinkedHashMap<String,Object>();result.put("checkedAt",Instant.now().toString());result.put("scope","READ_ONLY_INCIDENT_HISTORY_INVENTORY_NOT_RECOVERY");result.put("endpoint",System.getenv("ENF_AUDIT_URL"));
  try(var db=DriverManager.getConnection(System.getenv("ENF_AUDIT_URL"),System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))) {
   db.setReadOnly(true);db.setAutoCommit(false);result.put("schema",db.getSchema());
   for(String table:List.of("enf8_real_business_task","enf8_real_business_task_event","enf8_job_pack_revision","enf8_job_pack_event","enf8_job_pack_receipt","enf8_integration_trigger","enf8_job_pack_file")) {
    String filter=table.startsWith("enf8_real")?"":" WHERE package_id='ENF-JOB-CNIPA-GUIDANCE-CASE-1'";
    try{result.put(table,query(db,"SELECT * FROM "+table+filter));}catch(SQLException error){result.put(table,Map.of("unavailable",true,"sqlState",Objects.toString(error.getSQLState(),""),"errorCode",error.getErrorCode()));}
   }
   result.put("authorizationBoundaryDigest",query(db,"SELECT issuer,subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose,authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer='https://127.0.0.1:5444' ORDER BY subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose"));
   result.put("historicalActorCurrentIdentity",query(db,"SELECT issuer,subject_id,status FROM id2_subject WHERE issuer='https://127.0.0.1:5444' AND subject_id='3d2c34cf-4beb-42e1-9df8-5ff7158a4e83'"));
   try{result.put("postIncidentDocument",query(db,"SELECT draft_id,revision,content_sha256,artifact_key,artifact_sha256,input_manifest_sha256,review_state FROM enf12_document_draft_revision WHERE draft_id='ENF14-FACT-DOCUMENT-20261003-70' ORDER BY revision"));}catch(SQLException error){result.put("postIncidentDocument",Map.of("unavailable",true));}
   db.rollback();
  }
  System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
 }
}
