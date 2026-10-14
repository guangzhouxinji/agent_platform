import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.io.StringReader;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.Snapshot;

/** Approved exact sixteen-row incident repair. Never invoked by a product or test cleanup. */
public class EnforcementRecoveryApply87 {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final List<String> TABLES=EnforcementRecoveryPreflight84.TABLES;
 static final String FILE_SHA="7c7739b07835709484c4a125f0f4c2e6ef00eab741c2ced458113da498aa88cf";
 static void require(boolean ok,String label){if(!ok)throw new IllegalStateException(label);}
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static void save(Path file,Object data)throws Exception{Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
 static JsonNode query(Connection c,String sql,Object...params)throws Exception{return JSON.valueToTree(EnforcementRecoveryPreflight84.select(c,sql,params));}
 static JsonNode metadata(ResultSet r)throws Exception{return JSON.valueToTree(EnforcementRecoveryPreflight84.metadata(r));}
 static Map<String,SortedMap<Integer,String>> uniqueKeys(Connection c,String table)throws Exception{
  var keys=new LinkedHashMap<String,SortedMap<Integer,String>>();
  try(var rs=c.getMetaData().getIndexInfo(null,"ENFACC_BIZ",table.toUpperCase(Locale.ROOT),true,false)){
   while(rs.next()){String col=rs.getString("COLUMN_NAME");if(col!=null)keys.computeIfAbsent(rs.getString("INDEX_NAME"),x->new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"),col.toLowerCase(Locale.ROOT));}
  }return keys;
 }
 static JsonNode protectedState(Connection c)throws Exception{
  var out=new LinkedHashMap<String,Object>();String pack="ENF-JOB-CNIPA-GUIDANCE-CASE-1";
  out.put("document",EnforcementRecoveryCurrentSnapshot86.query(c,"SELECT * FROM enf12_document_draft_revision WHERE draft_id=? ORDER BY revision",List.of("ENF14-FACT-DOCUMENT-20261003-70")));
  out.put("authorization",EnforcementRecoveryCurrentSnapshot86.query(c,"SELECT issuer,subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose,authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? ORDER BY subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose",List.of("https://127.0.0.1:5444")));
  for(String t:List.of("enf8_integration_trigger","enf8_job_pack_file"))out.put(t,EnforcementRecoveryCurrentSnapshot86.query(c,"SELECT * FROM "+t+" WHERE package_id=?",List.of(pack)));
  return JSON.valueToTree(out);
 }
 static JsonNode fileState(JsonNode originals)throws Exception{
  var out=new ArrayList<Map<String,Object>>();for(JsonNode node:originals.path("originals")){String digest=sha(Files.readAllBytes(Path.of(node.path("path").asText())));require(digest.equals(node.path("expectedSHA256").asText()),"Original SHA mismatch");out.add(Map.of("path",node.path("path").asText(),"sha256",digest));}
  require(out.size()==12,"Exactly twelve originals");var d=originals.path("document");String digest=sha(Files.readAllBytes(Path.of(d.path("path").asText())));require(digest.equals(d.path("expectedSHA256").asText()),"Document original mismatch");out.add(Map.of("path",d.path("path").asText(),"sha256",digest));return JSON.valueToTree(out);
 }
 static JsonNode originalsInDb(Connection c,JsonNode source)throws Exception{
  var out=new LinkedHashMap<String,Object>();int fields=0,rows=0,timestamps=0;
  for(String t:TABLES){var values=new ArrayList<Object>();var keys=EnforcementRecoveryUtf8Reexport85.KEYS.get(t);
   for(JsonNode row:source.path(t)){var terms=new ArrayList<String>();var params=new ArrayList<Object>();for(String k:keys){terms.add(k+"=?");params.add(row.path(k).asText());}
    try(var s=c.prepareStatement("SELECT * FROM "+t+" WHERE "+String.join(" AND ",terms))){s.setQueryTimeout(10);for(int i=0;i<params.size();i++)s.setObject(i+1,params.get(i));try(var r=s.executeQuery()){
     require(r.next(),"Restored row missing");var m=r.getMetaData();var actual=new LinkedHashMap<String,Object>();require(m.getColumnCount()==row.size(),"Column count mismatch");
     for(int i=1;i<=m.getColumnCount();i++){String col=m.getColumnLabel(i).toLowerCase(Locale.ROOT);String val=r.getString(i);JsonNode expected=row.get(col);require(expected!=null,"Unknown column");require(expected.isNull()?val==null:expected.asText().equals(val),"Original field mismatch:"+t+"."+col);fields++;actual.put(col,val);
      if(val!=null&&m.getColumnType(i)==Types.TIMESTAMP){require(r.getTimestamp(i).toLocalDateTime().equals(LocalDateTime.parse(val.replace(' ','T'))),"Timestamp typed mismatch");timestamps++;}
     }require(!r.next(),"Duplicate primary key");values.add(actual);rows++;
    }}
   }out.put(t,values);
  }require(rows==16&&fields==199&&timestamps==29,"Original counts mismatch");out.put("rowCount",rows);out.put("fieldCount",fields);out.put("typedTimestamps",timestamps);return JSON.valueToTree(out);
 }
 public static void main(String[] args)throws Exception{
  require(args.length==3&&List.of("PRECHECK_ONLY","APPLY_APPROVED").contains(args[2]),"incident root/output directory/mode required");Path root=Path.of(args[0]),out=Path.of(args[1]);
  require("ENFACC_BIZ".equals(System.getenv("ENF_AUDIT_USER")),"Ordinary business account only");
  Path file=root.resolve("recovery-reexport-20261004-85/retained-history-utf8.json");require(sha(Files.readAllBytes(file)).equals(FILE_SHA),"Trusted file digest mismatch");
  JsonNode source=JSON.readTree(Files.readString(file,StandardCharsets.UTF_8));require(source.path("rowCount").asInt()==16&&source.path("allFieldLosslessChecksPassed").asBoolean(),"Source not trusted");
  for(JsonNode f:source.path("fieldChecks")){if(!f.path("isNull").asBoolean()){var bytes=Files.readAllBytes(file.getParent().resolve(f.path("fileRelativePath").asText()));require(sha(bytes).equals(f.path("fileByteSHA256").asText()),"Field sidecar digest mismatch");require(new String(bytes,StandardCharsets.UTF_8).equals(f.path("originalValueFromJdbc").asText()),"Field sidecar value mismatch");}}
  for(JsonNode row:source.path("enf8_job_pack_revision")){Snapshot p=JSON.readValue(row.path("payload").asText(),Snapshot.class);String input=p.id()+"|"+p.revision()+"|"+p.contentRevision()+"|"+p.organization()+"|"+p.title()+"|"+p.purpose()+"|"+p.facts()+"|"+p.findings()+"|"+p.publicInput();require(sha(input.getBytes(StandardCharsets.UTF_8)).equals(p.sha256())&&p.sha256().equals(row.path("sha256").asText()),"Business digest mismatch");}
  var schema=JSON.readTree(Files.readString(root.resolve("recovery-approved-20261004-86/current-key-schema-session-preflight.json"),StandardCharsets.UTF_8));
  var catalog=JSON.readTree(Files.readString(root.resolve("recovery-approved-20261004-86/current-constraints-triggers.json"),StandardCharsets.UTF_8));
  var originals=JSON.readTree(Files.readString(root.resolve("recovery-approved-20261004-86/current-originals-and-preservation.json"),StandardCharsets.UTF_8));
  var result=new LinkedHashMap<String,Object>();result.put("startedAt",Instant.now().toString());result.put("scope","APPROVED_16_ORIGINAL_ROWS_SINGLE_BUSINESS_TRANSACTION_NO_AUTHORITY_OR_PRODUCT_ACTION");boolean committed=false,commitAttempted=false;int inserted=0;
  try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5311","ENFACC_BIZ",System.getenv("ENF_AUDIT_PASSWORD"))){
   c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
   try{
    var locked=new ArrayList<String>();for(String table:TABLES){try(var s=c.createStatement()){s.setQueryTimeout(3);s.execute("LOCK TABLE ENFACC_BIZ."+table+" IN EXCLUSIVE MODE NOWAIT");locked.add(table);}}result.put("transactionExclusiveLocks",locked);
    for(String table:TABLES){String upper=table.toUpperCase(Locale.ROOT);var expected=schema.path("actualTables").path(table);
     require(query(c,"SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,COLUMN_ID FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID","ENFACC_BIZ",upper).equals(expected.path("columns")),"Schema drift");
     require(metadata(c.getMetaData().getPrimaryKeys(null,"ENFACC_BIZ",upper)).equals(expected.path("primaryKeys")),"PK drift");require(metadata(c.getMetaData().getImportedKeys(null,"ENFACC_BIZ",upper)).equals(expected.path("foreignKeys")),"FK drift");
     require(query(c,"SELECT CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,R_OWNER,R_CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME","ENFACC_BIZ",upper).equals(expected.path("constraints")),"Constraint drift");
     require(query(c,"SELECT CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM ALL_CONS_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME,POSITION","ENFACC_BIZ",upper).equals(expected.path("constraintColumns")),"Constraint columns drift");
     require(query(c,"SELECT CONSTRAINT_NAME,SEARCH_CONDITION,STATUS FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_TYPE='C'","ENFACC_BIZ",upper).equals(catalog.path("tables").path(table).path("checks")),"Check definition drift");
     JsonNode triggers=query(c,"SELECT OWNER,TRIGGER_NAME FROM ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=?","ENFACC_BIZ",upper);require(triggers.isArray()&&triggers.isEmpty(),"Target trigger exists/unknown");
     var unique=uniqueKeys(c,table);require(!unique.isEmpty(),"Unique key metadata absent");for(JsonNode row:source.path(table))for(var key:unique.values()){var terms=new ArrayList<String>();var params=new ArrayList<Object>();for(String col:key.values()){require(row.has(col)&&col.matches("[a-z0-9_]+"),"Unknown unique column");require(!row.path(col).isNull(),"Nullable key unexpected");terms.add(col+"=?");params.add(row.path(col).asText());}JsonNode count=query(c,"SELECT COUNT(*) AS MATCHING_ROWS FROM "+table+" WHERE "+String.join(" AND ",terms),params.toArray());require(count.isArray()&&count.get(0).path("matching_rows").asInt(-1)==0,"Key/alias conflict");}
    }
    for(JsonNode task:source.path("enf8_real_business_task")){
     for(String t:List.of("enf8_job_pack_revision","enf8_job_pack_receipt","enf8_job_pack_event","enf8_integration_trigger","enf8_job_pack_file")){JsonNode n=query(c,"SELECT COUNT(*) AS N FROM "+t+" WHERE package_id=?",task.path("package_id").asText());require(n.isArray()&&n.get(0).path("n").asInt(-1)==0,"Existing newer/partial package history rejects");}
     JsonNode n=query(c,"SELECT COUNT(*) AS N FROM enf8_real_business_task_event WHERE task_id=?",task.path("task_id").asText());require(n.isArray()&&n.get(0).path("n").asInt(-1)==0,"Existing task history rejects");
    }
    JsonNode protectedBefore=protectedState(c);require(protectedBefore.path("document").size()==1,"Protected document missing");require(protectedBefore.path("enf8_integration_trigger").isEmpty()&&protectedBefore.path("enf8_job_pack_file").isEmpty(),"New package relationships reject");JsonNode filesBefore=fileState(originals);save(out.resolve("application-protected-before.json"),protectedBefore);save(out.resolve("application-files-before.json"),filesBefore);
    var totals=new LinkedHashMap<String,Integer>();for(String t:TABLES){JsonNode n=query(c,"SELECT COUNT(*) AS N FROM "+t);require(n.isArray(),"Count unavailable");totals.put(t,n.get(0).path("n").asInt());}
    if(args[2].equals("PRECHECK_ONLY")){c.rollback();result.put("status","ALL_PRECHECK_GATES_PASSED_LOCKS_RELEASED_APPLICATION_MUST_REACQUIRE_AND_RECHECK");return;}
    for(String table:TABLES)for(JsonNode row:source.path(table)){var cols=new ArrayList<String>();row.fieldNames().forEachRemaining(cols::add);require(cols.stream().allMatch(s->s.matches("[a-z0-9_]+")),"Unsafe column");String sql="INSERT INTO "+table+" ("+String.join(",",cols)+") VALUES ("+String.join(",",Collections.nCopies(cols.size(),"?"))+")";
     try(var s=c.prepareStatement(sql)){s.setQueryTimeout(10);int i=0;for(String col:cols){JsonNode val=row.get(col);JsonNode f=null;for(JsonNode candidate:source.path("fieldChecks"))if(candidate.path("table").asText().equals(table)&&candidate.path("column").asText().equals(col)){f=candidate;break;}require(f!=null,"Missing field type");int type=f.path("jdbcType").asInt();i++;if(val.isNull())s.setNull(i,type);else if(type==Types.TIMESTAMP)s.setTimestamp(i,Timestamp.valueOf(val.asText()));else if(type==Types.BIGINT||type==Types.INTEGER)s.setLong(i,Long.parseLong(val.asText()));else if(type==Types.CLOB)s.setCharacterStream(i,new StringReader(val.asText()),val.asText().length());else s.setString(i,val.asText());}require(s.executeUpdate()==1,"Unexpected insert count");inserted++;}
    }
    require(inserted==16,"Insert count mismatch");JsonNode restored=originalsInDb(c,source);save(out.resolve("restored-in-transaction.json"),restored);
    require(protectedBefore.equals(protectedState(c)),"Protected current state changed");require(filesBefore.equals(fileState(originals)),"Files changed");for(String t:TABLES)require(query(c,"SELECT COUNT(*) AS N FROM "+t).get(0).path("n").asInt()==totals.get(t)+source.path(t).size(),"Table total mismatch");
    save(out.resolve("before-commit-decision.json"),Map.of("allGatesPassed",true,"exclusiveLocksHeld",true,"rows",inserted,"fields",199,"typedTimestamps",29,"currentAuthorizationPreserved",true,"documentPreserved",true,"filesPreserved",true));
    commitAttempted=true;c.commit();committed=true;result.put("committedAt",Instant.now().toString());
    require(originalsInDb(c,source).equals(restored),"Post-commit originals mismatch");require(protectedBefore.equals(protectedState(c)),"Post-commit protected state mismatch");require(filesBefore.equals(fileState(originals)),"Post-commit originals changed");c.rollback();result.put("postCommitReconciliation",true);result.put("status","RESTORED_16_TRUSTED_HISTORICAL_ORIGINAL_ROWS_LATEST_COMPLETENESS_NOT_PROVED");
   }catch(Exception e){if(!commitAttempted)c.rollback();result.put("status",committed?"POST_COMMIT_VERIFICATION_FAILED_NO_AUTOMATIC_COMPENSATION":commitAttempted?"COMMIT_OUTCOME_UNKNOWN_NO_AUTOMATIC_COMPENSATION":"REJECTED_AND_ROLLED_BACK");result.put("errorClass",e.getClass().getSimpleName());if(e instanceof SQLException s){result.put("errorCode",s.getErrorCode());result.put("sqlState",Objects.toString(s.getSQLState(),""));}else result.put("gate",e.getMessage());throw e;}
   finally{result.put("insertStatementsSucceeded",inserted);result.put("committed",committed);result.put("updates",0);result.put("deletes",0);result.put("serviceRestart",false);result.put("fullIncidentRecovery",false);save(out.resolve("restore-execution-result.json"),result);}
  }
 }
}
