import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.enforcement.testing.EnforcementDm8IsolationGuard;
/** New independent test DM8 schema only. No product or authority access. */
public class EnforcementIsolatedSchemaPreparation88 {
 public static void main(String[] args)throws Exception {
  String url=System.getenv("JOB_PACK_DM8_URL"),schema=System.getenv("JOB_PACK_DM8_USER"),run=System.getenv("ENF_TEST_RUN_ID");
  EnforcementDm8IsolationGuard.validateEndpoint(url,schema,schema,run);
  String password=System.getenv("JOB_PACK_DM8_PASSWORD");if(password==null||!password.matches("[A-Za-z0-9!]{24,80}"))throw new IllegalArgumentException("Safe isolated secret missing");
  Connection c=null;for(int n=0;n<30;n++){try{c=DriverManager.getConnection(url,"SYSDBA",System.getenv("SYSDBA_PWD"));break;}catch(SQLException e){if(n==29)throw new IllegalStateException("New isolated DM8 not ready, code="+e.getErrorCode());Thread.sleep(1500);}}
  var out=new LinkedHashMap<String,Object>();out.put("checkedAt",Instant.now().toString());out.put("url",url);out.put("schema",schema);out.put("runId",run);
  try(Connection db=c;var s=db.createStatement()){
   try(var q=db.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?")){q.setString(1,schema);try(var r=q.executeQuery()){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Isolated user exists; refuse reuse");}}
   s.execute("CREATE USER "+schema+" IDENTIFIED BY \""+password+"\"");s.execute("GRANT RESOURCE TO "+schema);
   out.put("createdNewIsolatedTechnicalSchema",true);out.put("mainOrAuthorityAccess",false);
  }catch(SQLException e){throw new IllegalStateException("Isolated initialization failed, code="+e.getErrorCode()+", state="+e.getSQLState());}
  Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
}
