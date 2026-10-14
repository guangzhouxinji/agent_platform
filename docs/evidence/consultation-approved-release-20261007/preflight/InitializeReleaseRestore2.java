import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
public class InitializeReleaseRestore2 {
 public static void main(String[]a)throws Exception{if(a.length!=1||Files.exists(Path.of(a[0])))throw new IllegalArgumentException("NEW_REPORT_REQUIRED");
 try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5494","SYSDBA",System.getenv("RELEASE_RESTORE_ADMIN"))){
 try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='CONREL2'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("NEVER_OVERWRITE_SCHEMA");}
 String pw=System.getenv("RELEASE_RESTORE_OWNER");if(pw==null||!pw.matches("[A-Za-z0-9]{20,80}"))throw new IllegalArgumentException("MANAGED_NEW_SECRET_REQUIRED");
 try(var s=c.createStatement()){s.execute("CREATE USER CONREL2 IDENTIFIED BY \""+pw+"\"");s.execute("GRANT RESOURCE TO CONREL2");s.execute("CREATE USER CONPROD_BKP IDENTIFIED BY \""+System.getenv("RELEASE_RESTORE_BACKUP")+"\"");s.execute("ALTER USER CONPROD_BKP ACCOUNT LOCK");}
 }
 new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(a[0]).toFile(),Map.of("observedAt",Instant.now().toString(),"schema","CONREL2","target","127.0.0.1:5494","existingTargetOverwritten",false,"liveDataWrites",0)); }
}
