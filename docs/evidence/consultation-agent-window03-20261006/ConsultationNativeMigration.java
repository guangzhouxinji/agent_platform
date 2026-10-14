import java.sql.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Exact primary-only migration; old domain/identity/source rows are only hashed, never rewritten or printed. */
public class ConsultationNativeMigration {
 static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static List<Map<String,Object>> rows(Connection c,String sql)throws Exception{
  if(!sql.startsWith("SELECT "))throw new IllegalArgumentException("SELECT_ONLY");
  var out=new ArrayList<Map<String,Object>>();try(var s=c.createStatement();var r=s.executeQuery(sql)){var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getString(i));out.add(row);}}return out;
 }
 static SortedMap<String,Object> fingerprints(Connection c)throws Exception{
  var out=new TreeMap<String,Object>();for(var t:rows(c,"SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME")){
   String table=(String)t.get("TABLE_NAME");if(Set.of("AGENT_RUN_HEAD","AGENT_RUN_REVISION","CON3_MODEL_INVOCATION").contains(table))continue;
   if(!table.matches("[A-Z][A-Z0-9_]+"))throw new IllegalStateException("UNEXPECTED_TABLE_NAME");
   var hashes=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table)){
    while(r.next()){var b=new StringBuilder();for(int i=1;i<=r.getMetaData().getColumnCount();i++){String v=r.getString(i);b.append(v==null?"N;":v.length()+":"+v+";");}hashes.add(hash(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}}
   Collections.sort(hashes);out.put(table,Map.of("rows",hashes.size(),"sha256",hash(String.join("\n",hashes).getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
  }return out;
 }
 static Connection open(String role,int port,String expectedUser)throws Exception{
  if(!("jdbc:dm://127.0.0.1:"+port).equals(System.getenv(role+"_URL"))||!expectedUser.equals(System.getenv(role+"_USER")))throw new IllegalArgumentException("EXACT_CONNECTION_REQUIRED");
  var c=DriverManager.getConnection(System.getenv(role+"_URL"),expectedUser,System.getenv(role+"_PASSWORD"));
  if(!rows(c,"SELECT USER AS DB_USER FROM DUAL").getFirst().get("DB_USER").equals(expectedUser))throw new IllegalStateException("SCHEMA_MISMATCH");return c;
 }
 public static void main(String[] args)throws Exception{
  var report=new LinkedHashMap<String,Object>();report.put("observedAt",Instant.now().toString());report.put("scope","EXACT_CONPROD_MIGRATION_NO_OLD_DOMAIN_OR_AUTHORITY_WRITES");
  try(var c=open("CON_NATIVE_PRIMARY",5301,"CONPROD");var a=open("CON_NATIVE_AUTHORITY",5302,"CONAUTH_SVC")){
   report.put("databaseTime",rows(c,"SELECT CURRENT_TIMESTAMP AS OBSERVED_AT FROM DUAL"));
   report.put("subjects",rows(c,"SELECT subject_id,login_name,subject_kind,status,credential_version FROM id2_subject ORDER BY subject_id"));
   report.put("identityTables",rows(c,"SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME LIKE 'ID2%' ORDER BY TABLE_NAME"));
   report.put("grantCounts",rows(c,"SELECT action_code,purpose,COUNT(*) AS TOTAL,SUM(CASE WHEN expires_at>CURRENT_TIMESTAMP AND status='ACTIVE' THEN 1 ELSE 0 END) AS CURRENT_ROWS FROM id2_grant GROUP BY action_code,purpose ORDER BY action_code,purpose"));
   report.put("taskCounts",rows(c,"SELECT COUNT(*) AS TOTAL,SUM(CASE WHEN expires_at>CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS CURRENT_ROWS FROM con3_task"));
   report.put("sources",rows(c,"SELECT c.source_id,c.contract_version,c.status,a.valid_until,a.responsible_party FROM con4_source_current c JOIN con4_source_admission a ON a.decision_id=c.decision_id"));
   var before=fingerprints(c);report.put("originalPrimaryBefore",before);
   report.put("authorityWatermark",rows(a,"SELECT issuer,generation,state,intent_id,history_sha FROM CONAUTH.id2_authority_watermark"));
   report.put("sourceWatermark",rows(a,"SELECT source_id,source_epoch,state,intent_id,history_sha FROM CONAUTH.id4_source_watermark"));
   if(args[0].equals("migrate")){
    if(!rows(c,"SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN ('AGENT_RUN_HEAD','AGENT_RUN_REVISION','CON3_MODEL_INVOCATION')").isEmpty())throw new IllegalStateException("REFUSE_EXISTING_OR_PARTIAL_MIGRATION");
    var executed=new ArrayList<Map<String,Object>>();for(String name:List.of("V096__agent_runtime_checkpoint.sql","V097__consultation_model_invocation.sql")){
     var file=Path.of(args[2],"db","dm8",name).toRealPath();String sql=String.join("\n",Files.readAllLines(file).stream().filter(line->!line.startsWith("--")).toList());int count=0;
     for(String part:sql.split(";")){if(part.isBlank())continue;String stmt=part.strip();if(!(stmt.startsWith("CREATE TABLE agent_run_head (")||stmt.startsWith("CREATE TABLE agent_run_revision (")||stmt.startsWith("CREATE TABLE con3_model_invocation (")||stmt.startsWith("CREATE INDEX idx_agent_run_parent ON ")||stmt.startsWith("CREATE INDEX idx_agent_run_expiry ON ")))throw new IllegalArgumentException("UNAPPROVED_DDL");try(var s=c.createStatement()){s.execute(stmt);}count++;}
     if(count!=(name.startsWith("V096")?4:1))throw new IllegalStateException("DDL_COUNT_MISMATCH");executed.add(Map.of("migration",name,"sha256",hash(Files.readAllBytes(file)),"statements",count));
    }report.put("executedMigrations",executed);
   }
   var after=fingerprints(c);report.put("originalPrimaryAfter",after);report.put("originalPrimaryUnchanged",before.equals(after));
   report.put("newTables",rows(c,"SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN ('AGENT_RUN_HEAD','AGENT_RUN_REVISION','CON3_MODEL_INVOCATION') ORDER BY TABLE_NAME"));
   if(!before.equals(after))throw new IllegalStateException("ORIGINAL_DOMAIN_CHANGED");
  }new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);System.out.println("EXACT_CONPROD_READBACK_OR_MIGRATION_OK_NO_ORIGINAL_ROWS_CHANGED");
 }
}
