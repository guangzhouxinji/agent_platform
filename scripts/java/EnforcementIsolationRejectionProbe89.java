import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import cn.xinji.agentplatform.enforcement.testing.EnforcementDm8IsolationGuard;
/** Existing dedicated89 test DB read-only; no product connection or cleanup. */
public class EnforcementIsolationRejectionProbe89 {
 static final ObjectMapper J=new ObjectMapper();
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static List<String> files(Path root)throws Exception{try(var stream=Files.walk(root)){var out=new ArrayList<String>();for(Path p:stream.filter(Files::isRegularFile).sorted().toList())out.add(root.relativize(p)+":"+sha(Files.readAllBytes(p)));return out;}}
 static Map<String,Object> state(Connection c,String schema)throws Exception{
  var out=new LinkedHashMap<String,Object>();for(String t:List.of("enf8_real_business_task","enf8_real_business_task_event","enf8_job_pack_revision","enf8_job_pack_receipt","enf8_job_pack_event","enf8_job_pack_file","enf8_integration_trigger")){
   try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+schema+"."+t)){var rows=new ArrayList<String>();while(r.next()){var fields=new ArrayList<String>();for(int n=1;n<=r.getMetaData().getColumnCount();n++)fields.add(r.getString(n));rows.add(J.writeValueAsString(fields));}Collections.sort(rows);out.put(t,Map.of("count",rows.size(),"sha256",sha(J.writeValueAsBytes(rows))));}
  }return out;
 }
 public static void main(String[] args)throws Exception {
  JsonNode m=J.readTree(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8));String run=m.path("runId").asText(),schema=m.path("schema").asText();
  if(!run.equals("ENF14-TEST-ISOLATION-20261004-89")||m.path("port").asInt()!=5382||!schema.matches("ENFISO_[A-F0-9]{12}"))throw new IllegalArgumentException("Only dedicated89 read scope");
  String url="jdbc:dm://127.0.0.1:5382";Path root=Path.of(m.path("fileRoot").asText());var out=new LinkedHashMap<String,Object>();out.put("checkedAt",Instant.now().toString());out.put("scope","EXISTING_DEDICATED89_READ_ONLY_SAFETY_REJECTION_AND_PRESERVATION_NOT_PRODUCT_I");
  try(var c=DriverManager.getConnection(url,"SYSDBA",System.getenv("ENF_ISOLATED_ADMIN_PASSWORD"))){c.setReadOnly(true);c.setAutoCommit(false);var before=state(c,schema);var originalFiles=files(root);
   var rejected=new LinkedHashMap<String,String>();for(var candidate:Map.of("mainPort","jdbc:dm://127.0.0.1:5311","sameOwnedRun",url).entrySet()){
    try{EnforcementDm8IsolationGuard.beforeConnect(candidate.getValue(),schema);throw new AssertionError("Unsafe entry accepted");}catch(Exception expected){rejected.put(candidate.getKey(),expected.getClass().getSimpleName());}
   }
   if(!rejected.get("mainPort").equals("IllegalStateException")||!rejected.get("sameOwnedRun").equals("FileAlreadyExistsException"))throw new AssertionError("Wrong safety rejection");
   var after=state(c,schema);if(!before.equals(after)||!originalFiles.equals(files(root)))throw new AssertionError("Rejection caused side effects");c.rollback();out.put("rejections",rejected);out.put("isolatedDatabaseBeforeAfterEqual",true);out.put("allOwnedFileSHABeforeAfterEqual",true);out.put("databaseTableFingerprints",after);out.put("ownedFileFingerprints",originalFiles);out.put("mainDatabaseConnections",0);out.put("businessWrites",0);out.put("cleanupDeletes",0);out.put("status","PASSED_ISOLATED_SAFETY_PROBE_ONLY");
  }Files.writeString(Path.of(args[1]),J.writerWithDefaultPrettyPrinter().writeValueAsString(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
}
