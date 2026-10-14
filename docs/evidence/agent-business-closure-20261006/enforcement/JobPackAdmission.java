import java.sql.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public class JobPackAdmission {
 static final ObjectMapper JSON=new ObjectMapper();
 static List<String> currentFindings(JsonNode p,String databaseDate){
  Map<String,List<String>> required=Map.of(
   "TASK",List.of("externalTaskRef","externalTaskVersion","originalTaskSha256","currentProcess","expectedOutput"),
   "SOURCE_RIGHTS",List.of("sourceRef","sourceVersion","rightsHolder","allowedUses","scope","validFrom","validTo"),
   "ACTOR_POSITION",List.of("personRef","organizationRef","positionTitle","appointmentRef","validFrom","validTo"),
   "APPROVAL",List.of("decision","method","reason","targetContentRevision"),
   "HUMAN_RECEIPT",List.of("personRef","positionTitle","text","targetContentRevision"),
   "BUSINESS_EVALUATION",List.of("completeness","traceability","operability","accuracy","humanEffort","basis","conclusion","targetContentRevision"));
  boolean functional="FUNCTIONAL_VALIDATION".equals(p.path("purpose").asText());
  List<String> types=functional?List.of("TASK","SOURCE_RIGHTS","APPROVAL","HUMAN_RECEIPT","BUSINESS_EVALUATION"):
   p.path("publicInput").isObject()?List.of("ACTOR_POSITION","APPROVAL","HUMAN_RECEIPT","BUSINESS_EVALUATION"):List.of("TASK","SOURCE_RIGHTS","ACTOR_POSITION","APPROVAL","HUMAN_RECEIPT","BUSINESS_EVALUATION");
  Map<String,JsonNode> facts=new HashMap<>();for(JsonNode f:p.path("facts"))facts.put(f.path("type").asText(),f);
  List<String> findings=new ArrayList<>();for(String type:types){var f=facts.get(type);if(f==null){findings.add("MISSING:"+type);continue;}
   if(!"VERIFIED".equals(f.path("verification").asText()))findings.add("UNVERIFIED:"+type);
   var fields=f.path("fields");List<String> keys=functional&&type.equals("HUMAN_RECEIPT")?List.of("text","targetContentRevision"):required.get(type);
   for(String key:keys)if(fields.path(key).asText().isBlank())findings.add("MISSING_FIELD:"+type+":"+key);
   if(type.equals("SOURCE_RIGHTS")||type.equals("ACTOR_POSITION"))try{if(java.time.LocalDate.parse(fields.path("validTo").asText()).isBefore(java.time.LocalDate.parse(databaseDate)))findings.add("EXPIRED:"+type);}catch(RuntimeException e){findings.add("INVALID_DATE:"+type);}
   if(type.equals("SOURCE_RIGHTS")&&!fields.path("allowedUses").asText().contains(functional?"FUNCTIONAL_VALIDATION":"REAL_INTEGRATION"))findings.add("USE_NOT_ALLOWED:SOURCE_RIGHTS");
   if(List.of("APPROVAL","HUMAN_RECEIPT","BUSINESS_EVALUATION").contains(type)&&!Long.toString(p.path("contentRevision").asLong()).equals(fields.path("targetContentRevision").asText()))findings.add("STALE_TARGET:"+type);
   if(type.equals("APPROVAL")&&!"APPROVED".equals(fields.path("decision").asText()))findings.add("NOT_APPROVED");
   if(type.equals("BUSINESS_EVALUATION"))for(String key:List.of("completeness","traceability","operability","accuracy","humanEffort"))try{int score=Integer.parseInt(fields.path(key).asText());if(score<1||score>5)findings.add("INVALID_SCORE:"+key);}catch(RuntimeException e){findings.add("INVALID_SCORE:"+key);}
  }return findings;
 }
 static void query(Connection c,String label,String sql)throws Exception{
  if(!sql.startsWith("SELECT "))throw new IllegalArgumentException("SELECT_ONLY");
  System.out.println("QUERY "+label+" SQL="+sql);
  try(Statement s=c.createStatement()){s.setQueryTimeout(15);try(ResultSet r=s.executeQuery(sql)){var m=r.getMetaData();while(r.next()){for(int i=1;i<=m.getColumnCount();i++)System.out.print((i==1?"":" | ")+m.getColumnLabel(i)+"="+r.getString(i));System.out.println();}}}
 }
 static void watermark()throws Exception{
  String[] queries={
   "SELECT e.event_id,e.request_id,e.grant_id,e.operation,e.before_version,e.after_version,e.after_epoch,e.maintainer_ref,e.request_sha FROM id2_grant_event e JOIN id2_grant g ON g.grant_id=e.grant_id WHERE g.issuer=? ORDER BY e.event_id",
   "SELECT grant_id,subject_id,organization,domain_code,task_scope,data_scope,resource_type,resource_id,resource_version,action_code,purpose,grant_generation,expires_at,status,grant_version FROM id2_grant WHERE issuer=? ORDER BY grant_id",
   "SELECT subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose,authorization_epoch,source_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? ORDER BY subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose",
   "SELECT e.event_id,e.subject_id,e.before_version,e.after_version,e.actor_ref FROM id2_subject_event e WHERE e.issuer=? AND e.operation='DISABLE' ORDER BY e.event_id",
   "SELECT s.subject_id,s.status,s.credential_version,s.disabled_at FROM id2_subject s WHERE s.issuer=? AND EXISTS (SELECT 1 FROM id2_subject_event e WHERE e.issuer=s.issuer AND e.subject_id=s.subject_id AND e.operation='DISABLE') ORDER BY s.subject_id"};
  try(Connection b=DriverManager.getConnection(System.getenv("ENF_RO_BUSINESS_URL"),System.getenv("ENF_RO_BUSINESS_USER"),System.getenv("ENF_RO_BUSINESS_PASSWORD"));Connection a=DriverManager.getConnection(System.getenv("ENF_RO_AUTHORITY_URL"),System.getenv("ENF_RO_AUTHORITY_USER"),System.getenv("ENF_RO_AUTHORITY_PASSWORD"))){
   b.setAutoCommit(false);a.setAutoCommit(false);b.setReadOnly(true);a.setReadOnly(true);
   try(Statement check=b.createStatement();ResultSet present=check.executeQuery("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='ENFACC_BIZ' AND TABLE_NAME='ID2_WORKLOAD'")){present.next();if(present.getInt(1)!=0)throw new IllegalStateException("WORKLOAD_FRAMING_REQUIRES_SEPARATE_VERIFICATION");}
   List<String[]> before=new ArrayList<>();try(Statement st=a.createStatement();ResultSet r=st.executeQuery("SELECT ISSUER,GENERATION,STATE,INTENT_ID,HISTORY_SHA FROM ENFACC_AUTH.ID2_AUTHORITY_WATERMARK")){while(r.next())before.add(new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)});}
   for(String[] row:before){
    MessageDigest hash=MessageDigest.getInstance("SHA-256");
    for(String sql:queries){try(PreparedStatement st=b.prepareStatement(sql)){st.setString(1,row[0]);try(ResultSet r=st.executeQuery()){while(r.next())for(int i=1;i<=r.getMetaData().getColumnCount();i++){String value=r.getString(i);hash.update((value==null?"-1:":value.length()+":"+value).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}}}
    String actual=HexFormat.of().formatHex(hash.digest());boolean stable=false;
    try(PreparedStatement st=a.prepareStatement("SELECT GENERATION,STATE,INTENT_ID,HISTORY_SHA FROM ENFACC_AUTH.ID2_AUTHORITY_WATERMARK WHERE ISSUER=?")){st.setString(1,row[0]);try(ResultSet r=st.executeQuery()){if(r.next())stable=Objects.equals(row[1],r.getString(1))&&Objects.equals(row[2],r.getString(2))&&Objects.equals(row[3],r.getString(3))&&Objects.equals(row[4],r.getString(4));}}
    System.out.println("WATERMARK_CHECK generation="+row[1]+" ready="+"READY".equals(row[2])+" noPendingIntent="+(row[3]==null)+" stableBeforeAfter="+stable+" canonicalHistoryDigestMatch="+actual.equals(row[4])+" framing=ProductGrantHistoryBatch_sections0to4_UTF16_length_UTF8_bytes");
   }
   b.rollback();a.rollback();
  }
 }
 static boolean original(String key,String sha)throws Exception{
  if(key==null||!key.matches("[0-9a-f-]{36}")||sha==null||!sha.matches("[0-9a-f]{64}"))return false;
  Path root=Path.of(System.getenv("ENF_RO_VAULT"),"files").toRealPath();Path file=root.resolve(key+".blob");
  return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&file.toRealPath().startsWith(root)&&sha.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
 }
 public static void main(String[] args)throws Exception{
  Class.forName("dm.jdbc.driver.DmDriver");
  watermark();
  try(Connection c=DriverManager.getConnection(System.getenv("ENF_RO_BUSINESS_URL"),System.getenv("ENF_RO_BUSINESS_USER"),System.getenv("ENF_RO_BUSINESS_PASSWORD"))){
   c.setAutoCommit(false);c.setReadOnly(true);System.out.println("JDBC_READ_ONLY="+c.isReadOnly());query(c,"clock","SELECT CURRENT_TIMESTAMP AS DB_TIME FROM DUAL");
   String databaseDate;try(Statement d=c.createStatement();ResultSet r=d.executeQuery("SELECT CURRENT_TIMESTAMP FROM DUAL")){r.next();databaseDate=r.getString(1).substring(0,10);}
   query(c,"jobpack_grants","SELECT g.RESOURCE_ID,g.RESOURCE_VERSION,g.TASK_SCOPE,g.DATA_SCOPE,g.ACTION_CODE,COUNT(*) AS LIVE_GRANTS,MIN(g.EXPIRES_AT) AS EARLIEST_EXPIRY,MAX(g.EXPIRES_AT) AS LATEST_EXPIRY FROM ENFACC_BIZ.ID2_GRANT g WHERE g.DOMAIN_CODE='ENFORCEMENT' AND g.PURPOSE='JOB_PACK' AND g.STATUS='ACTIVE' AND g.EXPIRES_AT>CURRENT_TIMESTAMP GROUP BY g.RESOURCE_ID,g.RESOURCE_VERSION,g.TASK_SCOPE,g.DATA_SCOPE,g.ACTION_CODE");
   query(c,"jobpack_current_grants","SELECT g.RESOURCE_ID,g.ACTION_CODE,COUNT(*) AS CURRENT_ACTIVE_SUBJECT_GRANTS FROM ENFACC_BIZ.ID2_GRANT g JOIN ENFACC_BIZ.ID2_CURRENT_EPOCH e ON e.CURRENT_GRANT_ID=g.GRANT_ID JOIN ENFACC_BIZ.ID2_SUBJECT u ON u.ISSUER=g.ISSUER AND u.SUBJECT_ID=g.SUBJECT_ID WHERE g.DOMAIN_CODE='ENFORCEMENT' AND g.PURPOSE='JOB_PACK' AND g.STATUS='ACTIVE' AND u.STATUS='ACTIVE' AND g.EXPIRES_AT>CURRENT_TIMESTAMP GROUP BY g.RESOURCE_ID,g.ACTION_CODE");
   query(c,"jobpack_live_authenticated_grants","SELECT COUNT(*) AS WITH_LIVE_SESSION FROM ENFACC_BIZ.ID2_GRANT g JOIN ENFACC_BIZ.ID2_CURRENT_EPOCH e ON e.CURRENT_GRANT_ID=g.GRANT_ID JOIN ENFACC_BIZ.ID2_SUBJECT u ON u.ISSUER=g.ISSUER AND u.SUBJECT_ID=g.SUBJECT_ID JOIN ENFACC_BIZ.ID2_SESSION s ON s.ISSUER=g.ISSUER AND s.SUBJECT_ID=g.SUBJECT_ID WHERE g.DOMAIN_CODE='ENFORCEMENT' AND g.PURPOSE='JOB_PACK' AND g.STATUS='ACTIVE' AND u.STATUS='ACTIVE' AND g.EXPIRES_AT>CURRENT_TIMESTAMP AND s.STATUS='ACTIVE' AND s.EXPIRES_AT>CURRENT_TIMESTAMP");
   query(c,"business_generation","SELECT MAX(AFTER_EPOCH) AS LATEST_GRANT_EVENT_EPOCH,COUNT(*) AS GRANT_EVENT_ROWS FROM ENFACC_BIZ.ID2_GRANT_EVENT");
   query(c,"collection_tasks","SELECT TASK_ID,PACKAGE_ID,VERSION,ASSIGNMENT_STATE,WORKFLOW_STATE FROM ENFACC_BIZ.ENF8_REAL_BUSINESS_TASK ORDER BY TASK_ID");
   query(c,"triggers","SELECT PACKAGE_ID,PACKAGE_REVISION,TASK_ID,STATE FROM ENFACC_BIZ.ENF8_INTEGRATION_TRIGGER ORDER BY PACKAGE_ID,PACKAGE_REVISION");
   String sql="SELECT p.PACKAGE_ID,p.REVISION,p.STATE,p.PAYLOAD FROM ENFACC_BIZ.ENF8_JOB_PACK_REVISION p WHERE p.REVISION=(SELECT MAX(q.REVISION) FROM ENFACC_BIZ.ENF8_JOB_PACK_REVISION q WHERE q.PACKAGE_ID=p.PACKAGE_ID) ORDER BY p.PACKAGE_ID";
   System.out.println("QUERY latest_package_redacted SQL="+sql);
   try(Statement st=c.createStatement();ResultSet r=st.executeQuery(sql)){while(r.next()){
    JsonNode p=JSON.readTree(r.getString(4));System.out.println("PACKAGE id="+r.getString(1)+" revision="+r.getLong(2)+" persistedState="+r.getString(3)+" purpose="+p.path("purpose").asText()+" contentRevision="+p.path("contentRevision").asLong()+" findings="+p.path("findings"));
    System.out.println("DERIVED_INPUT_FINDINGS databaseDate="+databaseDate+" findings="+currentFindings(p,databaseDate)+" source=JobPackService.findings_only_not_runtime_or_legal_acceptance");
    JsonNode pub=p.path("publicInput");if(pub.isObject())System.out.println("PUBLIC_INPUT candidate="+pub.path("candidateId").asText()+" collectionDecision="+pub.path("collectionDecision").asText()+" originalShaMatchAtRecordedFilesRoot="+original(pub.path("rawFileKey").asText(),pub.path("rawSha256").asText())+" sourceUse=COLLECTION_NOT_DOCUMENT_DRAFT_PERMISSION");
    for(JsonNode fact:p.path("facts")){
     var fields=fact.path("fields");String type=fact.path("type").asText();
     System.out.println("FACT type="+type+" verification="+fact.path("verification").asText()+" verifiedByDifferentSubmitter="+(!fact.path("submittedBy").asText().equals(fact.path("verifiedBy").asText()))+" validFrom="+fields.path("validFrom").asText()+" validTo="+fields.path("validTo").asText()+" allowedUses="+fields.path("allowedUses").asText()+" targetContentRevision="+fields.path("targetContentRevision").asText());
     for(JsonNode e:fact.path("evidence")){
      boolean registered;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM ENFACC_BIZ.ENF8_JOB_PACK_FILE WHERE PACKAGE_ID=? AND EVIDENCE_REF=? AND FILE_KEY=? AND SHA256=?")){s.setString(1,r.getString(1));s.setString(2,e.path("ref").asText());s.setString(3,e.path("fileKey").asText());s.setString(4,e.path("sha256").asText());try(ResultSet x=s.executeQuery()){x.next();registered=x.getInt(1)==1;}}
      System.out.println("EVIDENCE registered="+registered+" originalShaMatchAtRecordedFilesRoot="+original(e.path("fileKey").asText(),e.path("sha256").asText()));
     }
    }
   }}
   c.rollback();
  }
  try(Connection c=DriverManager.getConnection(System.getenv("ENF_RO_AUTHORITY_URL"),System.getenv("ENF_RO_AUTHORITY_USER"),System.getenv("ENF_RO_AUTHORITY_PASSWORD"))){c.setAutoCommit(false);c.setReadOnly(true);query(c,"authority_clock","SELECT CURRENT_TIMESTAMP AS DB_TIME FROM DUAL");query(c,"independent_watermark","SELECT GENERATION,STATE FROM ENFACC_AUTH.ID2_AUTHORITY_WATERMARK");c.rollback();}
 }
}
