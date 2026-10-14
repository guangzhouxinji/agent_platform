import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** No row writes. Probe bounded five-table exclusive transaction window and always roll back. */
public class EnforcementRecoveryLockWindow87 {
 public static void main(String[] args)throws Exception {
  if(!"ENFACC_BIZ".equals(System.getenv("ENF_AUDIT_USER")))throw new IllegalArgumentException("Business identity required");
  var out=new LinkedHashMap<String,Object>();var acquired=new ArrayList<String>();out.put("checkedAt",Instant.now().toString());out.put("rowWrites",0);out.put("adminUsedForLocks",false);
  try(var db=DriverManager.getConnection("jdbc:dm://127.0.0.1:5311","ENFACC_BIZ",System.getenv("ENF_AUDIT_PASSWORD"))){
   db.setAutoCommit(false);try {
    for(String table:List.of("enf8_real_business_task","enf8_real_business_task_event","enf8_job_pack_revision","enf8_job_pack_receipt","enf8_job_pack_event")){
     try(var s=db.createStatement()){s.setQueryTimeout(3);s.execute("LOCK TABLE ENFACC_BIZ."+table+" IN EXCLUSIVE MODE NOWAIT");acquired.add(table);}
    }out.put("allFiveExclusiveLocksAcquired",true);
   }catch(SQLException e){out.put("allFiveExclusiveLocksAcquired",false);out.put("errorCode",e.getErrorCode());out.put("sqlState",Objects.toString(e.getSQLState(),""));}
   finally {db.rollback();out.put("rolledBackAndReleased",true);}
  }out.put("acquired",acquired);out.put("notAnApplicationTimeIsolationProof",true);
  Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
}
