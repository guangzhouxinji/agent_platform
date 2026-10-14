import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
/** Exact admitted migration. No domain, identity, authority or source UPDATE/INSERT. */
public class AdjudicationRuntimeMigration {
 static List<String> rows(Connection c,String sql)throws Exception {var out=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery(sql)){while(r.next()){var v=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)v.add(String.valueOf(r.getObject(i)));out.add(String.join("|",v));}}return out;}
 static Map<String,List<String>> original(Connection c)throws Exception {
  var out=new LinkedHashMap<String,List<String>>();
  out.put("caseHeads",rows(c,"SELECT case_id,head_version,head_sha256 FROM adj2_case_work ORDER BY case_id"));
  out.put("revisions",rows(c,"SELECT case_id,work_version,payload_sha256 FROM adj2_case_revision ORDER BY case_id,work_version"));
  out.put("grants",rows(c,"SELECT grant_id,status,expires_at FROM id2_grant ORDER BY grant_id"));
  out.put("budget",rows(c,"SELECT case_id,calls,input_tokens,output_tokens,cost_micros,frozen,expires_at FROM adj3_model_budget ORDER BY case_id"));
  out.put("sessions",rows(c,"SELECT COUNT(*) FROM id2_session"));return out;
 }
 public static void main(String[] args)throws Exception {
  String url=System.getenv("ADJ_MIGRATION_URL"),user=System.getenv("ADJ_MIGRATION_USER");
  if(!"jdbc:dm://127.0.0.1:5301".equals(url)||!"ADJLLM20261003".equals(user))throw new IllegalArgumentException("EXACT_PRIMARY_SCHEMA_REQUIRED");
  var file=Path.of(args[0]).toRealPath();if(!file.toString().replace('\\','/').endsWith("/db/dm8/V096__agent_runtime_checkpoint.sql"))throw new IllegalArgumentException("EXACT_MIGRATION_REQUIRED");
  try(var c=DriverManager.getConnection(url,user,System.getenv("ADJ_MIGRATION_PASSWORD"))) {
   if(!rows(c,"SELECT USER FROM DUAL").equals(List.of(user)))throw new IllegalStateException("SCHEMA_MISMATCH");
   if(!rows(c,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('AGENT_RUN_HEAD','AGENT_RUN_REVISION')").equals(List.of("0")))throw new IllegalStateException("REFUSE_EXISTING_OR_PARTIAL_RUNTIME");
   var before=original(c);System.out.println("BEFORE="+before);
   byte[] bytes=Files.readAllBytes(file);System.out.println("MIGRATION_SHA="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
   String sql=Files.readString(file).replaceAll("(?m)^--[^\\r\\n]*","");int statements=0;
   for(String part:sql.split(";")){if(part.isBlank())continue;if(!part.stripLeading().matches("(?s)CREATE (TABLE agent_run_(head|revision)|INDEX idx_agent_run_(parent|expiry)).*"))throw new IllegalArgumentException("UNEXPECTED_DDL");try(var s=c.createStatement()){s.execute(part);statements++;}}
   if(statements!=4)throw new IllegalStateException("EXACT_FOUR_DDL_REQUIRED");
   var after=original(c);System.out.println("AFTER="+after);if(!before.equals(after))throw new IllegalStateException("ORIGINAL_BUSINESS_CHANGED");
   System.out.println("RUNTIME_TABLES="+rows(c,"SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN ('AGENT_RUN_HEAD','AGENT_RUN_REVISION') ORDER BY TABLE_NAME"));
   System.out.println("ORIGINAL_BUSINESS_UNCHANGED=true");System.out.println("AUTHORITY_WRITES=0 MODEL_CALLS=0 AUTHORIZATION_WRITES=0");System.out.println("FINISHED="+Instant.now());
  }
 }
}
