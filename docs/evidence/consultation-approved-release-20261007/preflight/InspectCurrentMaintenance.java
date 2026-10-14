import java.sql.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Actual registered operations principal only: read-only, no authority issuance or business rows. */
public class InspectCurrentMaintenance {
 public static void main(String[] args)throws Exception{
  if(args.length!=1 || Files.exists(Path.of(args[0])))throw new IllegalArgumentException("NEW_REPORT_REQUIRED");
  var result=new LinkedHashMap<String,Object>(); result.put("observedAt",Instant.now().toString());
  try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5302","CONAUTH_SVC",System.getenv("MAINTENANCE_INSPECT_PASSWORD"))){
   c.setReadOnly(true);c.setAutoCommit(false);
   try(var s=c.prepareStatement("SELECT subject_ref,organization,certificate_sha,allowed_roles,status,version FROM CONAUTH.id2_maintainer WHERE singleton_key=1")){
    s.setQueryTimeout(15);try(var r=s.executeQuery()){
     if(!r.next())throw new IllegalStateException("REGISTERED_PRINCIPAL_MISSING");
     for(String field:List.of("subject_ref","organization","certificate_sha","allowed_roles","status","version"))result.put(field,r.getString(field));
     if(r.next())throw new IllegalStateException("AMBIGUOUS_PRINCIPAL");
    }
   } c.rollback();
  }catch(SQLException e){System.err.println("MAINTENANCE_SQL_FAILURE code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(2);return;}
  result.put("writes",0);result.put("sourceUseOrTaskPermissionGranted",false);
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),result);
 }
}