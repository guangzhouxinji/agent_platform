import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Only exact missing backup SELECT permissions; does not issue product Grants or data writes. */
public class CompleteBackupReadCoverage {
 public static void main(String[]a)throws Exception{
  if(a.length!=1||Files.exists(Path.of(a[0]))||Instant.now().isAfter(Instant.parse("2026-10-07T10:00:00Z")))throw new IllegalArgumentException("EXACT_REPORT_AND_WINDOW_REQUIRED");
  var tables=List.of("ID2_WORKLOAD","ID2_WORKLOAD_EVENT","ID2_WORKLOAD_REQUEST","CON3_MODEL_INVOCATION","CON5_PREPARATION","CON5_PREPARED_DOCUMENT","CON5_FEATURE_REVISION","CON5_FEATURE","CON5_FEATURE_CURRENT","CON5_FEATURE_EVENT","CON5_COMPARISON_INPUT","CON5_COMPARISON_CANDIDATE","CON5_COMPARISON_CURRENT","CON5_COMPARISON_EVENT");
  var actions=new ArrayList<String>();try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5301","CONPROD",System.getenv("PROTECTION_INSPECT_PASSWORD"))){
   for(String t:tables){try(var s=c.prepareStatement("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?")){s.setString(1,t);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("MISSING_EXACT_TABLE "+t);}}
    try(var s=c.prepareStatement("SELECT COUNT(*) FROM USER_TAB_PRIVS WHERE OWNER='CONPROD' AND TABLE_NAME=? AND GRANTEE='CONPROD_BKP' AND PRIVILEGE='SELECT'")){s.setString(1,t);try(var r=s.executeQuery()){r.next();if(r.getInt(1)>0)continue;}}
    try(var s=c.createStatement()){s.execute("GRANT SELECT ON CONPROD."+t+" TO CONPROD_BKP");actions.add(t);}
   }
  }
  var out=new LinkedHashMap<String,Object>();out.put("observedAt",Instant.now().toString());out.put("operation","MINIMUM_EXISTING_BACKUP_ACCOUNT_SELECT_FOR_APPROVED_PROTECTION");out.put("grantedSelectTables",actions);out.put("productGrantOrSourceUseChanged",false);out.put("domainRowsModified",0);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(a[0]).toFile(),out);
 }
}
