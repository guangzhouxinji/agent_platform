import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public class EnforcementRecoveryCurrentSnapshot86 {
 static final ObjectMapper JSON=new ObjectMapper();
 static Object query(Connection db,String sql,List<String> values)throws Exception {
  if(!sql.startsWith("SELECT ")||sql.contains("FOR UPDATE"))throw new IllegalArgumentException("Non-locking SELECT only");
  try(var s=db.prepareStatement(sql)){s.setQueryTimeout(10);for(int i=0;i<values.size();i++)s.setString(i+1,values.get(i));
   try(var r=s.executeQuery()){var out=new ArrayList<Map<String,Object>>();var m=r.getMetaData();
    while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));out.add(row);}return out;
   }
  }
 }
 public static void main(String[] args)throws Exception {
  String url=System.getenv("ENF_AUDIT_URL");if(!"jdbc:dm://127.0.0.1:5311".equals(url)||!"ENFACC_BIZ".equals(System.getenv("ENF_AUDIT_USER")))throw new IllegalArgumentException("Approved main business read scope only");
  var source=JSON.readTree(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8));var result=new LinkedHashMap<String,Object>();
  result.put("checkedAt",Instant.now().toString());result.put("scope","PRE_REPAIR_SELECT_ONLY_CURRENT_KEYS_ALIASES_DOCUMENT_AND_SELECTED_AUTHORIZATION_PROJECTION");result.put("url",url);
  try(var db=DriverManager.getConnection(url,System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))){
   db.setReadOnly(true);db.setAutoCommit(false);var values=new ArrayList<String>();var terms=new ArrayList<String>();
   for(String col:List.of("task_id","candidate_id","package_id")){var keys=new ArrayList<String>();for(var r:source.path("enf8_real_business_task")){keys.add("?");values.add(r.path(col).asText());}terms.add(col+" IN ("+String.join(",",keys)+")");}
   result.put("enf8_real_business_task",query(db,"SELECT * FROM enf8_real_business_task WHERE "+String.join(" OR ",terms)+" ORDER BY task_id",values));
   String task="ENF-RBC-CNIPA-GUIDANCE-CASE-1",pack="ENF-JOB-CNIPA-GUIDANCE-CASE-1";
   result.put("enf8_real_business_task_event",query(db,"SELECT * FROM enf8_real_business_task_event WHERE task_id=? OR request_key=? ORDER BY request_key",List.of(task,source.path("enf8_real_business_task_event").get(0).path("request_key").asText())));
   for(String table:List.of("enf8_job_pack_revision","enf8_job_pack_receipt","enf8_job_pack_event","enf8_integration_trigger","enf8_job_pack_file"))result.put(table,query(db,"SELECT * FROM "+table+" WHERE package_id=?",List.of(pack)));
   result.put("postIncidentDocument",query(db,"SELECT * FROM enf12_document_draft_revision WHERE draft_id=? ORDER BY revision",List.of("ENF14-FACT-DOCUMENT-20261003-70")));
   result.put("authorizationBoundaryDigest",query(db,"SELECT issuer,subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose,authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? ORDER BY subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose",List.of("https://127.0.0.1:5444")));
   db.rollback();
  }result.put("businessWrites",0);result.put("authorityDatabaseAccess",false);result.put("restoreExecution","NOT_RUN_GATE_PENDING");
  Files.writeString(Path.of(args[1]),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
}
