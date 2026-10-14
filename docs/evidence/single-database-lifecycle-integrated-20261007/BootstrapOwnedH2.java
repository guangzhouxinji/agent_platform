import java.nio.file.*;
import java.sql.*;
public class BootstrapOwnedH2 {
 public static void main(String[] args)throws Exception {
  Path base=Path.of(args[0]).toAbsolutePath().normalize(),allowed=Path.of("docs/evidence/single-database-lifecycle-integrated-20261007/engineering-db").toAbsolutePath().normalize();
  if(!base.startsWith(allowed)||Files.exists(Path.of(base+".mv.db")))throw new IllegalStateException("new dedicated engineering DB required");
  Files.createDirectories(base.getParent());
  try(Connection c=DriverManager.getConnection("jdbc:h2:file:"+base.toString().replace('\\','/')+";MODE=Oracle","SA",System.getenv("LIFECYCLE_H2_SA_PASSWORD"));Statement s=c.createStatement()) {
   for(String schema:new String[]{"CONPROD","CONAUTH","ENFPROD","ENFAUTH","ADJPROD","ADJAUTH"}) {
    for(String user:new String[]{schema,schema+"_SVC"}) {
     String password=System.getenv("LIFECYCLE_H2_"+user+"_PASSWORD");if(password==null||!password.matches("[a-f0-9]{48}"))throw new IllegalArgumentException();
     s.execute("CREATE USER "+user+" PASSWORD '"+password+"'");
    }
    s.execute("CREATE SCHEMA "+schema+" AUTHORIZATION "+schema);
   }
  }
  System.out.print("{\"actualFileH2Bootstrap\":true,\"schemas\":6,\"accounts\":12,\"businessRecordsCreated\":false}");
 }
}
