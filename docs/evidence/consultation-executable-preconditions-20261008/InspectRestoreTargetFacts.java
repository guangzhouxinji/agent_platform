import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Only allowed target dictionary metadata: no old protected business data, writes or import. */
public class InspectRestoreTargetFacts {
 static List<Map<String,Object>> query(Connection c,String sql)throws SQLException {var out=new ArrayList<Map<String,Object>>();try(var s=c.prepareStatement(sql)){s.setQueryTimeout(15);try(var r=s.executeQuery()){var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getObject(i));out.add(row);}}}return out;}
 public static void main(String[]a)throws Exception {if(a.length!=1||Files.exists(Path.of(a[0])))throw new IllegalArgumentException("FRESH_OUTPUT_REQUIRED");var out=new LinkedHashMap<String,Object>();out.put("observedAt",Instant.now().toString());out.put("scope","EXACT_5494_DICTIONARY_ONLY_NOT_RESTORE_USE_AUTHORIZATION");
 String secret=System.getenv("TARGET_METADATA_PASSWORD");if(secret==null||secret.isBlank())throw new IllegalStateException("MANAGED_TARGET_CREDENTIAL_REQUIRED");
 try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5494","SYSDBA",secret)){c.setReadOnly(true);c.setAutoCommit(false);
 out.put("databaseProduct",c.getMetaData().getDatabaseProductName());out.put("databaseVersion",c.getMetaData().getDatabaseProductVersion());
 out.put("users",query(c,"SELECT USERNAME,ACCOUNT_STATUS FROM DBA_USERS WHERE USERNAME IN ('CONREL2','CONREL_20261009A') ORDER BY USERNAME"));
 out.put("tablesBySchema",query(c,"SELECT OWNER,COUNT(*) AS TABLE_COUNT FROM DBA_TABLES WHERE OWNER IN ('CONREL2','CONREL_20261009A') GROUP BY OWNER"));
 out.put("existingTableNames",query(c,"SELECT TABLE_NAME FROM DBA_TABLES WHERE OWNER='CONREL2' ORDER BY TABLE_NAME"));c.rollback();
 }catch(SQLException e){System.err.println("TARGET_METADATA_SQL_FAILURE code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(2);return;}
 out.put("businessRowQueries",0);out.put("schemaCreated",false);out.put("writes",0);out.put("oldPackageOpened",false);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(a[0]).toFile(),out); }
}
