import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.*;

/** Fixed SELECT-only native admission probe. No migrations, commands or provider SDKs. */
class NativeAdmission {
 static Connection db; static String schema; static Set<String> tables=new HashSet<>();
 static final ObjectMapper json=new ObjectMapper();
 static void query(String label,String sql) throws Exception {
  if(!sql.startsWith("SELECT ")||sql.contains(";")||sql.contains("FOR UPDATE"))throw new IllegalArgumentException("SELECT_ONLY");
  try(var s=db.createStatement()){s.setQueryTimeout(20);s.setMaxRows(100);try(var r=s.executeQuery(sql)){
   var m=r.getMetaData();System.out.print(label+"_COLUMNS");for(int i=1;i<=m.getColumnCount();i++)System.out.print("\t"+m.getColumnLabel(i));System.out.println();
   int count=0;while(r.next()){System.out.print(label);for(int i=1;i<=m.getColumnCount();i++)System.out.print("\t"+String.valueOf(r.getObject(i)).replaceAll("[\\r\\n\\t]"," "));System.out.println();count++;}System.out.println(label+"_ROWS\t"+count);
  }}
 }
 static boolean has(String name){return tables.contains(name.toUpperCase(Locale.ROOT));}
 static void nativeInput() throws Exception {
  if(!has("ADJ2_CASE_WORK")||!has("ADJ2_CASE_REVISION"))throw new IllegalStateException("NATIVE_INPUT_TABLES_MISSING");
  String sql="SELECT w.head_version,w.head_sha256,r.input_version,r.payload,r.payload_sha256 FROM "+schema+".adj2_case_work w JOIN "+schema+".adj2_case_revision r ON r.case_id=w.case_id AND r.work_version=w.head_version WHERE w.case_id='adjudication-materials-case-01' AND w.organization='project-adjudication' AND w.data_scope='adjudication-materials-case-01-materials'";
  try(var st=db.createStatement()){st.setQueryTimeout(20);st.setMaxRows(2);try(var rs=st.executeQuery(sql)){
   if(!rs.next())throw new IllegalStateException("EXACT_NATIVE_INPUT_MISSING");
   String payload=rs.getString(4),sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
   boolean payloadSha=sha.equals(rs.getString(5)),headSha=sha.equals(rs.getString(2));
   if(!payloadSha||!headSha)throw new IllegalStateException("NATIVE_INPUT_SHA_DRIFT");
   JsonNode n=json.readTree(payload),ci=n.path("caseInput");
   boolean coordinates=n.path("version").asLong()==rs.getLong(1)&&n.path("inputVersion").asLong()==rs.getLong(3)&&ci.path("id").asText().equals("adjudication-materials-case-01")&&ci.path("organization").asText().equals("project-adjudication")&&ci.path("dataScope").asText().equals("adjudication-materials-case-01-materials");
   if(!coordinates)throw new IllegalStateException("NATIVE_INPUT_COORDINATE_DRIFT");
   var superseded=new HashSet<String>();for(JsonNode m:n.path("materials"))if(!m.path("supersedesMaterialId").isNull()&&!m.path("supersedesMaterialId").isMissingNode())superseded.add(m.path("supersedesMaterialId").asText());
   int active=0,readable=0;for(JsonNode m:n.path("materials"))if(!superseded.contains(m.path("id").asText())){active++;if(m.path("readable").asBoolean())readable++;}
   int elements=0;for(JsonNode c:n.path("claims"))elements+=c.path("elements").size();
   int completeReports=0;boolean latestComplete=false;for(JsonNode r:n.path("reports")){boolean complete=true;for(JsonNode d:r.path("diagnostics"))if(d.path("blocking").asBoolean())complete=false;if(complete)completeReports++;latestComplete=complete;}
   var out=json.createObjectNode().put("workVersion",rs.getLong(1)).put("inputVersion",rs.getLong(3)).put("payloadShaMatches",payloadSha).put("headShaMatches",headSha).put("coordinatesMatch",coordinates).put("payloadSha256",sha).put("materialVersions",n.path("materials").size()).put("activeMaterials",active).put("activeReadableFlagMaterials",readable).put("issues",ci.path("issues").size()).put("claimVersions",n.path("claims").size()).put("elementsAcrossClaimVersions",elements).put("citations",n.path("citations").size()).put("elementFindings",n.path("findings").size()).put("reports",n.path("reports").size()).put("completeReportsByNoBlockingDiagnostics",completeReports);
   if(n.path("reports").size()==0)out.putNull("latestReportComplete");else out.put("latestReportComplete",latestComplete);
   // readable is the persisted flag only, not a substitute for current Source-use access.
   System.out.println("NATIVE_INPUT_METADATA\t"+out);
   if(rs.next())throw new IllegalStateException("EXACT_NATIVE_INPUT_DUPLICATED");
  }}
 }
 static void primary() throws Exception {
  nativeInput();
  if(has("ADJ2_CASE_WORK"))query("CASES","SELECT case_id,organization,data_scope,head_version,head_sha256 FROM "+schema+".adj2_case_work");
  if(has("ID2_GRANT"))query("GRANT_EXPIRY","SELECT resource_id,action_code,status,expires_at,task_scope,data_scope,resource_version,purpose FROM "+schema+".id2_grant WHERE domain_code='ADJUDICATION' AND issuer='https://localhost:5445'");
  if(has("ID2_GRANT")&&has("ID2_CURRENT_EPOCH")&&has("ID2_SUBJECT"))query("CURRENT_GRANTS_WITH_ACTIVE_HUMAN","SELECT g.resource_id,g.action_code,g.expires_at FROM "+schema+".id2_grant g JOIN "+schema+".id2_current_epoch c ON c.current_grant_id=g.grant_id AND c.issuer=g.issuer AND c.subject_id=g.subject_id AND c.organization=g.organization AND c.domain_code=g.domain_code AND c.resource_type=g.resource_type AND c.resource_id=g.resource_id AND c.action_code=g.action_code AND c.purpose=g.purpose JOIN "+schema+".id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id WHERE g.issuer='https://localhost:5445' AND g.domain_code='ADJUDICATION' AND g.resource_type='ADJ_CASE' AND g.resource_version=1 AND g.purpose='CASE_AUXILIARY' AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE' AND u.subject_kind='HUMAN'");
  if(has("ID2_WORKLOAD"))query("DOMAIN_WORKLOADS","SELECT workload_kind,allowed_purpose,status,expires_at FROM "+schema+".id2_workload WHERE issuer='https://localhost:5445'");
  if(has("ID2_SESSION"))query("LIVE_SESSION_COUNT","SELECT COUNT(*) AS live_sessions FROM "+schema+".id2_session WHERE issuer='https://localhost:5445' AND status='ACTIVE' AND expires_at>CURRENT_TIMESTAMP");
  if(has("ADJ3_MODEL_BUDGET"))query("MODEL_BUDGET","SELECT case_id,calls,input_tokens,output_tokens,cost_micros,frozen,expires_at FROM "+schema+".adj3_model_budget");
  if(has("ADJ3_MATERIAL_RUN"))query("MODEL_RUNS","SELECT state,expires_at,COUNT(*) AS retained_runs FROM "+schema+".adj3_material_run GROUP BY state,expires_at");
  if(has("AGENT_RUN_HEAD"))query("AGENT_HEADS","SELECT agent_id,task_id,goal_version,head_version,expires_at FROM "+schema+".agent_run_head WHERE agent_id LIKE '%adjudication%'");
 }
 static void authority() throws Exception {
  if(has("ID2_AUTHORITY_WATERMARK"))query("AUTHORITY_WATERMARK","SELECT issuer,generation,state,history_sha FROM "+schema+".id2_authority_watermark WHERE issuer='https://localhost:5445'");
  if(has("ADJ2_SOURCE_GENERATION"))query("SOURCE_GENERATION","SELECT generation,state FROM "+schema+".adj2_source_generation");
  if(has("ADJ2_SOURCE_USE")){
   String sql="SELECT case_id,state,payload,payload_sha256 FROM "+schema+".adj2_source_use";
   try(var s=db.createStatement()){s.setQueryTimeout(20);s.setMaxRows(100);try(var r=s.executeQuery(sql)){while(r.next()){
    String payload=r.getString(3);String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    if(!sha.equals(r.getString(4)))throw new IllegalStateException("SOURCE_PAYLOAD_SHA_DRIFT");
    JsonNode n=json.readTree(payload);String until=n.path("validUntil").asText();
    System.out.println("SOURCE_USE\t"+r.getString(1)+"\t"+r.getString(2)+"\t"+n.path("source").path("id").asText()+"\t"+n.path("source").path("version").asText()+"\t"+n.path("source").path("sha256").asText()+"\t"+n.path("allowedUses")+"\t"+until+"\tclientUtcUnexpired="+Instant.parse(until).isAfter(Instant.now()));
   }}}
  }
 }
 public static void main(String[] args) throws Exception {
  boolean primary=args[0].equals("PRIMARY");schema=primary?"ADJLLM20261003":"ADJAUTH20261004";
  String user=primary?schema:"ADJAUTH_SVC",url=primary?"jdbc:dm://127.0.0.1:5301":"jdbc:dm://127.0.0.1:5370";
  String password=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
  Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(10);
  try(Connection conn=DriverManager.getConnection(url,user,password)){db=conn;password=null;
   // Hint is supplemental only: all executed statements above are fixed reviewed SELECT.
   try{db.setReadOnly(true);System.out.println("READONLY_HINT\tSUPPORTED");}catch(SQLException ignored){System.out.println("READONLY_HINT\tUNSUPPORTED_SELECT_ONLY_SQL_STILL_REQUIRED");}
   System.out.println("SCOPE\t"+schema+"\tSELECT_ONLY_NO_PROVIDER_NO_AUTHORIZATION_WRITE");
   query("DB_CLOCK","SELECT CURRENT_TIMESTAMP AS db_now FROM dual");
   try(var s=db.createStatement();var r=s.executeQuery("SELECT table_name FROM all_tables WHERE owner='"+schema+"' ORDER BY table_name")){while(r.next()){String name=r.getString(1);tables.add(name);if(name.startsWith("ADJ")||name.startsWith("ID2")||name.startsWith("AGENT")||name.contains("WORKER")||name.contains("GOAL")||name.contains("TASK"))System.out.println("TABLE\t"+name);}}
   System.out.println("RUNTIME_TABLES\tAGENT_RUN_HEAD="+has("AGENT_RUN_HEAD")+"\tAGENT_RUN_REVISION="+has("AGENT_RUN_REVISION"));
   if(primary)primary();else authority();
  }
 }
}


