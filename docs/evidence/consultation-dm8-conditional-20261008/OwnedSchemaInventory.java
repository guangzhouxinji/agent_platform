import java.sql.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;
/** Isolated engineering metadata only: no old Goal/Source rows, locks or archive content. */
public class OwnedSchemaInventory {
 public static void main(String[] args) throws Exception {
  StringBuilder rows=new StringBuilder();int count=0;Set<String> schemas=new TreeSet<>();
  try(Connection c=DriverManager.getConnection(System.getenv("DM8_JDBC_URL"),System.getenv("DM8_JDBC_USER"),System.getenv("DM8_JDBC_PASSWORD"));Statement s=c.createStatement()) {
   s.setQueryTimeout(10);try(ResultSet r=s.executeQuery("SELECT OWNER,TABLE_NAME FROM DBA_TABLES WHERE OWNER IN ('CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH') ORDER BY OWNER,TABLE_NAME")) {
    while(r.next()){schemas.add(r.getString(1));rows.append(r.getString(1)).append('.').append(r.getString(2)).append('\n');count++;}
   }
   if(schemas.size()!=6||count<36)throw new IllegalStateException("OWNED_BASELINE_MISSING");
   int temp=0;try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME LIKE 'ID________________' OR USERNAME LIKE 'CT________________' OR USERNAME LIKE 'CMF________________'")){r.next();temp=r.getInt(1);}
   String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.toString().getBytes(StandardCharsets.UTF_8)));
   System.out.println("{\"schemaCount\":"+schemas.size()+",\"tableCount\":"+count+",\"tableInventorySha256\":\""+sha+"\",\"temporarySchemaCount\":"+temp+",\"businessRowsRead\":0,\"writeStatements\":0}");
  }
 }
}
