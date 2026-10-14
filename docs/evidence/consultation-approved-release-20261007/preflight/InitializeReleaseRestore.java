import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
public class InitializeReleaseRestore {
 public static void main(String[]a)throws Exception{if(a.length!=1||Files.exists(Path.of(a[0])))throw new IllegalArgumentException("NEW_REPORT_REQUIRED");
 try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5494","SYSDBA",System.getenv("RELEASE_RESTORE_ADMIN"))){
 try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='CONRELEASE'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("NEVER_OVERWRITE_SCHEMA");}
 String pw=System.getenv("RELEASE_RESTORE_OWNER");if(pw==null||!pw.matches("[A-Za-z0-9]{20,80}"))throw new IllegalArgumentException("MANAGED_NEW_SECRET_REQUIRED");
 try(var s=c.createStatement()){s.execute("CREATE USER CONRELEASE IDENTIFIED BY \""+pw+"\"");s.execute("GRANT RESOURCE TO CONRELEASE");}
 }
 new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(a[0]).toFile(),Map.of("observedAt",Instant.now().toString(),"schema","CONRELEASE","target","127.0.0.1:5494","existingTargetOverwritten",false,"liveDataWrites",0)); }
}
