import java.sql.*;
/** Creates only the fresh isolated native-import target, never a production schema or grant. */
class CreateAdjudicationRecoverySchema {
 public static void main(String[] a)throws Exception {
  Class.forName("dm.jdbc.driver.DmDriver");String u=System.getenv("ADJ_BACKUP_RESTORE_URL");if(!"jdbc:dm://127.0.0.1:5369".equals(u))throw new SecurityException("Independent recovery database only");
  String p=System.getenv("ADJ_BACKUP_PASSWORD");if(!p.matches("[A-Za-z0-9]{32}"))throw new SecurityException("Exact isolated schema credential required");
  try(var c=DriverManager.getConnection(u,"SYSDBA",System.getenv("SYSDBA_PWD"));var s=c.createStatement()){s.execute("CREATE USER ADJLLM20261003 IDENTIFIED BY \""+p+"\"");s.execute("GRANT RESOURCE TO ADJLLM20261003");}
 }
}
