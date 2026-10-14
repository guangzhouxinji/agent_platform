import java.sql.*;
import java.nio.file.*;
import java.util.*;
/** New owned isolation instance only. Not product initialization or business authorization. */
class PrepareAdjudicationExitRegression {
 public static void main(String[] args)throws Exception {
  String url=System.getenv("JOB_PACK_DM8_URL"),schema=System.getenv("ADJ_EXIT_ADDITIONAL_SCHEMA");
  if(!"jdbc:dm://127.0.0.1:5411".equals(url)||!schema.matches("ADJREG_[A-F0-9]{12}"))throw new SecurityException("Owned exit regression endpoint only");
  String password=System.getenv("JOB_PACK_DM8_PASSWORD");
  if(password==null||!password.matches("[A-Za-z0-9!]{24,80}"))throw new SecurityException("Owned credential required");
  try(var c=DriverManager.getConnection(url,"SYSDBA",System.getenv("SYSDBA_PWD"));var s=c.createStatement()){
   try(var q=c.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?")){q.setString(1,schema);try(var r=q.executeQuery()){r.next();if(r.getInt(1)!=0)throw new SecurityException("Existing schema forbidden");}}
   s.execute("CREATE USER "+schema+" IDENTIFIED BY \""+password+"\"");s.execute("GRANT RESOURCE TO "+schema);
  }
  if(args.length==1){
   Path migration=Path.of(args[0]).toAbsolutePath().normalize();
   if(!migration.getFileName().toString().equals("V080__enforcement_v2_document_rule.sql"))throw new SecurityException("Exact parent migration required");
   try(var c=DriverManager.getConnection(url,schema,password);var s=c.createStatement()){
    try(var r=s.executeQuery("SELECT COUNT(*) FROM USER_TABLES")){r.next();if(r.getInt(1)!=0)throw new SecurityException("Fresh empty schema required");}
    for(String sql:Files.readString(migration).split(";"))if(!sql.isBlank())s.execute(sql.trim());
   }
  }
  System.out.println("OWNED_NEW_SCHEMA_READY "+schema);
 }
}
