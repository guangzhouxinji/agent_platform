import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.Snapshot;

/** Incident preflight: offline digest analysis and SELECT-only metadata; no product actions. */
public class EnforcementRecoveryPreflight84 {
 static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
 static final List<String> TABLES = List.of("enf8_real_business_task", "enf8_real_business_task_event",
   "enf8_job_pack_revision", "enf8_job_pack_receipt", "enf8_job_pack_event");
 static String sha(byte[] bytes) throws Exception {
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
 }
 static List<Map<String,Object>> rows(ResultSet rs) throws Exception {
  var result=new ArrayList<Map<String,Object>>(); var meta=rs.getMetaData();
  while(rs.next()) {var row=new LinkedHashMap<String,Object>();
   for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getString(i));
   result.add(row);
  }return result;
 }
 static Object select(Connection db,String sql,Object... args) throws Exception {
  if(!sql.startsWith("SELECT ") || sql.contains("FOR UPDATE"))throw new IllegalArgumentException("SELECT only");
  try(var st=db.prepareStatement(sql)){st.setQueryTimeout(10);for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);
   try(var rs=st.executeQuery()){return rows(rs);}
  }catch(SQLException error){return Map.of("unavailable",true,"sqlState",Objects.toString(error.getSQLState(),""),"errorCode",error.getErrorCode());}
 }
 static List<Map<String,Object>> metadata(ResultSet rs) throws Exception {try(rs){return rows(rs);}}
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("historical-export workspace output required");
  var result=new LinkedHashMap<String,Object>();result.put("checkedAt",Instant.now().toString());
  result.put("scope","OFFLINE_DIGEST_AND_SELECT_ONLY_PREFLIGHT_NOT_RECOVERY_OR_WRITE_ISOLATION");
  var bytes=Files.readAllBytes(Path.of(args[0]));var text=new String(bytes,StandardCharsets.UTF_8).replaceFirst("^\uFEFF","");
  var history=JSON.readTree(text);var digest=new LinkedHashMap<String,Object>();
  digest.put("exportFileByteSHA256",sha(bytes));digest.put("exportReplacementCharacters",text.chars().filter(c->c==0xfffd).count());
  var source=Path.of(args[1],"backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement/jobpack/JobPackService.java");
  var contract=Path.of(args[1],"backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement/jobpack/JobPackContract.java");
  digest.put("algorithmSourceSHA256",sha(Files.readAllBytes(source)));digest.put("contractSourceSHA256",sha(Files.readAllBytes(contract)));
  digest.put("algorithm","JobPackService.build: SHA-256 UTF-8(id|revision|contentRevision|organization|title|purpose|facts.toString|findings.toString|publicInput.toString); Java record formatting, JSON map insertion order retained");
  var packages=new ArrayList<Map<String,Object>>();
  for(var row:history.path("enf8_job_pack_revision")){
   String payload=row.path("payload").asText();var p=JSON.readValue(payload,Snapshot.class);
   String input=p.id()+"|"+p.revision()+"|"+p.contentRevision()+"|"+p.organization()+"|"+p.title()+"|"+p.purpose()+"|"+p.facts()+"|"+p.findings()+"|"+p.publicInput();
   String computed=sha(input.getBytes(StandardCharsets.UTF_8));var item=new LinkedHashMap<String,Object>();
   item.put("packageId",p.id());item.put("revision",p.revision());item.put("storedColumnSHA256",row.path("sha256").asText());
   item.put("storedPayloadSHA256",p.sha256());item.put("exportedPayloadUTF8SHA256",sha(payload.getBytes(StandardCharsets.UTF_8)));
   item.put("businessAlgorithmInput",input);item.put("recomputedBusinessSHA256",computed);
   item.put("matchesStored",computed.equals(p.sha256())&&computed.equals(row.path("sha256").asText()));
   item.put("payloadReplacementCharacters",payload.chars().filter(c->c==0xfffd).count());
   item.put("originalCLOBBytesAvailable",false);item.put("jsonReserialized",false);packages.add(item);
  }
  digest.put("packages",packages);digest.put("conclusion","Mismatch or replacement characters reject promotion; exported values are not proven lossless originals");
  result.put("businessDigest",digest);
  try(var db=DriverManager.getConnection(System.getenv("ENF_AUDIT_URL"),System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))){
   db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
   String schema=System.getenv("ENF_AUDIT_USER").toUpperCase(Locale.ROOT);var dm=db.getMetaData();
   result.put("db",Map.of("schema",schema,"url",System.getenv("ENF_AUDIT_URL"),"driverVersion",dm.getDriverVersion(),"readOnlyRequested",true,"lockingClauses",false));
   var tables=new LinkedHashMap<String,Object>();
   for(String name:TABLES){String table=name.toUpperCase(Locale.ROOT);var item=new LinkedHashMap<String,Object>();
    item.put("columns",select(db,"SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,COLUMN_ID FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",schema,table));
    var pk=metadata(dm.getPrimaryKeys(null,schema,table));item.put("primaryKeys",pk);
    var indexes=metadata(dm.getIndexInfo(null,schema,table,true,false));item.put("uniqueIndexes",indexes);
    item.put("foreignKeys",metadata(dm.getImportedKeys(null,schema,table)));
    item.put("constraints",select(db,"SELECT CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,R_OWNER,R_CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME",schema,table));
    item.put("constraintColumns",select(db,"SELECT CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM ALL_CONS_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME,POSITION",schema,table));
    item.put("triggers",select(db,"SELECT TRIGGER_NAME,TRIGGER_TYPE,TRIGGERING_EVENT,STATUS,TABLE_OWNER,TABLE_NAME FROM ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=?",schema,table));
    var keys=new LinkedHashMap<String,SortedMap<Integer,String>>();
    for(var index:indexes){String col=(String)index.get("column_name");if(col!=null)keys.computeIfAbsent((String)index.get("index_name"),k->new TreeMap<>()).put(Integer.parseInt((String)index.get("ordinal_position")),col.toLowerCase(Locale.ROOT));}
    for(var key:pk)keys.computeIfAbsent((String)key.get("pk_name"),k->new TreeMap<>()).put(Integer.parseInt((String)key.get("key_seq")),((String)key.get("column_name")).toLowerCase(Locale.ROOT));
    var checks=new ArrayList<Map<String,Object>>();
    for(var row:history.path(name))for(var key:keys.entrySet()){
     var values=new ArrayList<Object>();var conditions=new ArrayList<String>();boolean nullable=false;
     for(String col:key.getValue().values()){if(!col.matches("[a-z0-9_]+"))throw new IllegalStateException("Unsafe metadata identifier");if(row.path(col).isMissingNode()||row.path(col).isNull()){nullable=true;break;}
      values.add(row.path(col).asText());conditions.add(col+"=?");}
     if(nullable)continue;
     var check=new LinkedHashMap<String,Object>();check.put("key",key.getKey());check.put("columns",key.getValue().values());check.put("candidateValues",values);
     check.put("result",select(db,"SELECT COUNT(*) AS MATCHING_ROWS FROM "+name+" WHERE "+String.join(" AND ",conditions),values.toArray()));checks.add(check);
    }item.put("candidateKeyConflictChecks",checks);tables.put(name,item);
   }
   result.put("actualTables",tables);
   result.put("sessionViewColumns",select(db,"SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME='V$SESSIONS' ORDER BY COLUMN_ID"));
   result.put("businessSessions",select(db,"SELECT SESS_ID,USER_NAME,CLNT_IP,APPNAME,STATE FROM V$SESSIONS WHERE USER_NAME=?",schema));
   db.rollback();
  }
  result.put("businessWrites",0);result.put("serviceRestart",false);result.put("testsRun",0);
  Files.writeString(Path.of(args[2]),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8);
 }
}
