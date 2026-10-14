import java.sql.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
public final class ReadTableCatalog50 {
 public static void main(String[] args)throws Exception{var out=new TreeMap<String,Object>();out.put("capturedAt",java.time.Instant.now().toString());out.put("executedWrites",0);try(var c=DriverManager.getConnection(System.getenv("LIFECYCLE_JDBC_URL"),System.getenv("LIFECYCLE_JDBC_USER"),System.getenv("LIFECYCLE_JDBC_PASSWORD"))){c.setReadOnly(true);out.put("tables",ReadCatalog50.read(c,"SELECT * FROM DBA_TABLES WHERE OWNER IN "+ReadCatalog50.SCOPE));out.put("viewDefinition",ReadCatalog50.read(c,"SELECT OWNER,VIEW_NAME,TEXT FROM DBA_VIEWS WHERE OWNER='SYS' AND VIEW_NAME='DBA_TABLES'"));}System.out.println(new ObjectMapper().writeValueAsString(out));}
}
