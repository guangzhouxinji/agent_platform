import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.io.Reader;
import com.fasterxml.jackson.databind.*;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.Snapshot;

/** Re-read only the 16 retained historical keys from the existing isolated 5367 replica. */
public class EnforcementRecoveryUtf8Reexport85 {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final Map<String,List<String>> KEYS=new LinkedHashMap<>();
 static {KEYS.put("enf8_real_business_task",List.of("task_id"));
  KEYS.put("enf8_real_business_task_event",List.of("request_key"));
  KEYS.put("enf8_job_pack_revision",List.of("package_id","revision"));
  KEYS.put("enf8_job_pack_receipt",List.of("request_key"));KEYS.put("enf8_job_pack_event",List.of("event_id"));}
 static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 static long replacements(String value){return value.chars().filter(c->c==0xfffd).count();}
 static void save(Path path,String value)throws Exception {Files.writeString(path,value,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
 static Connection connect(String url,String user,String password)throws Exception {
  SQLException last=null;for(int n=0;n<15;n++){try{return DriverManager.getConnection(url,user,password);}catch(SQLException e){last=e;Thread.sleep(1500);}}
  throw new SQLException("Isolated replica not SQL-ready after bounded attempts",last.getSQLState(),last.getErrorCode());
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("old-export workspace new-evidence-dir required");
  String url=System.getenv("ENF_AUDIT_URL");if(!"jdbc:dm://127.0.0.1:5367".equals(url))throw new IllegalArgumentException("Only existing isolated replica 5367 allowed");
  Path out=Path.of(args[2]).toAbsolutePath().normalize();Files.createDirectories(out.resolve("fields"));
  var old=JSON.readTree(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8).replaceFirst("^\uFEFF",""));
  var result=new LinkedHashMap<String,Object>();var metrics=new ArrayList<Map<String,Object>>();
  result.put("checkedAt",Instant.now().toString());result.put("scope","EXISTING_ISOLATED_REPLICA_EXACT_16_KEYS_SELECT_ONLY_UTF8_NO_PRIMARY_ACCESS");
  result.put("url",url);result.put("schema",System.getenv("ENF_AUDIT_USER"));result.put("timestampTimezone","NOT_ASSERTED_DB_LOCAL_TYPED_VALUE_PRESERVED");
  var rawRows=new LinkedHashMap<String,List<Map<String,Object>>>();boolean fieldsOK=true;int rowsRead=0;
  try(var db=connect(url,System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))){
   db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
   result.put("driverVersion",db.getMetaData().getDriverVersion());
   for(var entry:KEYS.entrySet()){String table=entry.getKey();var rows=new ArrayList<Map<String,Object>>();
    for(var candidate:old.path(table)){var pk=new LinkedHashMap<String,String>();var terms=new ArrayList<String>();
     for(String column:entry.getValue()){String val=candidate.path(column).asText();if(val.isBlank()||replacements(val)>0)throw new IllegalArgumentException("Untrusted candidate key");pk.put(column,val);terms.add(column+"=?");}
     try(var s=db.prepareStatement("SELECT * FROM "+table+" WHERE "+String.join(" AND ",terms))){s.setQueryTimeout(10);int bind=1;for(String val:pk.values())s.setString(bind++,val);
      try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing retained key: "+table+pk);
       var row=new LinkedHashMap<String,Object>();var meta=r.getMetaData();String rowTag=sha(JSON.writeValueAsBytes(pk)).substring(0,16);
       for(int i=1;i<=meta.getColumnCount();i++){String column=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);String value=r.getString(i);row.put(column,value);
        var field=new LinkedHashMap<String,Object>();field.put("table",table);field.put("key",pk);field.put("column",column);field.put("sqlType",meta.getColumnTypeName(i));
        field.put("jdbcType",meta.getColumnType(i));field.put("isNull",value==null);field.put("originalValueFromJdbc",value);
        if(value!=null){byte[] utf8=value.getBytes(StandardCharsets.UTF_8);Path file=out.resolve("fields").resolve(table+"-"+rowTag+"-"+column+".utf8");save(file,value);
         String reread=Files.readString(file,StandardCharsets.UTF_8);field.put("utf16Units",value.length());field.put("unicodeCodePoints",value.codePointCount(0,value.length()));
         field.put("utf8Bytes",utf8.length);field.put("replacementCharacters",replacements(value));field.put("jdbcTextUtf8SHA256",sha(utf8));
         field.put("fileRelativePath",out.relativize(file).toString().replace(java.io.File.separatorChar,'/'));field.put("fileByteSHA256",sha(Files.readAllBytes(file)));
         field.put("utf8RoundTripEqual",value.equals(reread));field.put("byteMeaning","Canonical UTF8 of JDBC decoded text, not DM8 physical storage bytes");
         boolean fieldOK=value.equals(reread)&&replacements(value)==0;
         if(meta.getColumnType(i)==Types.CLOB||meta.getColumnType(i)==Types.VARCHAR||meta.getColumnType(i)==Types.LONGVARCHAR){
          try(Reader reader=r.getCharacterStream(i)){var sb=new StringBuilder();char[] chars=new char[4096];int n;while((n=reader.read(chars))!=-1)sb.append(chars,0,n);
           field.put("jdbcCharacterStreamEqual",value.contentEquals(sb));field.put("jdbcCharacterStreamUnits",sb.length());fieldOK&=value.contentEquals(sb);}
          if(meta.getColumnType(i)==Types.CLOB){Clob clob=r.getClob(i);long length=clob.length();field.put("jdbcClobLength",length);
           field.put("jdbcClobLengthMatches",length==value.length());fieldOK&=length==value.length();clob.free();}
         }
         if(meta.getColumnType(i)==Types.TIMESTAMP){Timestamp stamp=r.getTimestamp(i);field.put("jdbcTimestampLocal",stamp.toLocalDateTime().toString());field.put("jdbcTimestampNanos",stamp.getNanos());
          boolean equal=LocalDateTime.parse(value.replace(' ','T')).equals(stamp.toLocalDateTime());field.put("timestampTypedValueEqual",equal);fieldOK&=equal;}
         field.put("losslessChecksPassed",fieldOK);fieldsOK&=fieldOK;
        }else field.put("losslessChecksPassed",true);metrics.add(field);
       }if(r.next())throw new IllegalStateException("Duplicate primary key in isolated replica");rows.add(row);rowsRead++;
      }
     }
    }rawRows.put(table,rows);result.put(table,rows);
   }
   for(String table:List.of("enf8_integration_trigger","enf8_job_pack_file")){
    try(var s=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE package_id=?")){s.setQueryTimeout(10);s.setString(1,"ENF-JOB-CNIPA-GUIDANCE-CASE-1");try(var r=s.executeQuery()){r.next();result.put(table+"Count",r.getLong(1));}}
   }db.rollback();
  }
  result.put("rowCount",rowsRead);result.put("fieldChecks",metrics);result.put("allFieldLosslessChecksPassed",fieldsOK);
  var business=new ArrayList<Map<String,Object>>();
  for(var row:rawRows.get("enf8_job_pack_revision")){String payload=(String)row.get("payload");var p=JSON.readValue(payload,Snapshot.class);
   String input=p.id()+"|"+p.revision()+"|"+p.contentRevision()+"|"+p.organization()+"|"+p.title()+"|"+p.purpose()+"|"+p.facts()+"|"+p.findings()+"|"+p.publicInput();
   String computed=sha(input.getBytes(StandardCharsets.UTF_8));var digest=new LinkedHashMap<String,Object>();digest.put("packageId",p.id());digest.put("revision",p.revision());
   digest.put("storedColumnSHA256",row.get("sha256"));digest.put("storedPayloadSHA256",p.sha256());digest.put("recomputedBusinessSHA256",computed);
   digest.put("matchesStored",computed.equals(p.sha256())&&computed.equals(row.get("sha256")));digest.put("algorithmInputUtf8SHA256",sha(input.getBytes(StandardCharsets.UTF_8)));
   digest.put("payloadUtf8SHA256",sha(payload.getBytes(StandardCharsets.UTF_8)));digest.put("payloadJsonReserialized",false);
   save(out.resolve("business-sha-input.utf8"),input);business.add(digest);
  }result.put("businessDigest",business);
  Path workspace=Path.of(args[1]);var algorithm=new LinkedHashMap<String,Object>();
  for(String source:List.of("backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement/jobpack/JobPackService.java","backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement/jobpack/JobPackContract.java"))algorithm.put(source,sha(Files.readAllBytes(workspace.resolve(source))));
  result.put("algorithmSourceSHA256",algorithm);result.put("businessWrites",0);result.put("primaryEndpointsAccessed",List.of());result.put("authorityRead",false);result.put("productStarted",false);
  Path output=out.resolve("retained-history-utf8.json");save(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
  var reread=JSON.readTree(Files.readString(output,StandardCharsets.UTF_8));boolean jsonOK=true;
  for(var e:rawRows.entrySet())for(int j=0;j<e.getValue().size();j++)for(var col:e.getValue().get(j).entrySet()){
   var node=reread.path(e.getKey()).get(j).path(col.getKey());jsonOK&=col.getValue()==null?node.isNull():col.getValue().equals(node.asText());}
  var summary=new LinkedHashMap<String,Object>();summary.put("checkedAt",Instant.now().toString());summary.put("jsonFileByteSHA256",sha(Files.readAllBytes(output)));
  summary.put("jsonAllOriginalFieldValuesRoundTripEqual",jsonOK);summary.put("allFieldLosslessChecksPassed",fieldsOK);summary.put("rowCount",rowsRead);
  summary.put("businessDigest",business);summary.put("replacementCharactersInNewExport",replacements(Files.readString(output,StandardCharsets.UTF_8)));
  summary.put("latestHistoryComplete","NOT_PROVED");summary.put("mainRecovery","NOT_RUN");summary.put("serviceRestartAuthorized",false);
  save(out.resolve("roundtrip-and-business-sha.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
  if(!jsonOK||!fieldsOK||rowsRead!=16||business.stream().anyMatch(d->!Boolean.TRUE.equals(d.get("matchesStored"))))throw new IllegalStateException("Candidate not promoted: lossless/digest/count mismatch; preserved new evidence");
 }
}
