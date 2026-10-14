import java.sql.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Original target metadata only. No business rows, DDL/DML, grants or lifecycle operations. */
public final class ConsultationTargetMetadata {
 static List<Map<String,Object>> query(Connection c,String sql,String owner)throws SQLException {
  if(!sql.startsWith("SELECT "))throw new IllegalArgumentException("SELECT_ONLY");
  var rows=new ArrayList<Map<String,Object>>();
  try(var s=c.prepareStatement(sql)){s.setQueryTimeout(15);s.setMaxRows(10000);if(owner!=null)s.setString(1,owner);
   try(var r=s.executeQuery()){var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++){var v=r.getObject(i);row.put(m.getColumnLabel(i),v==null?null:v.toString());}rows.add(row);}}
  }if(rows.size()>=10000)throw new IllegalArgumentException("METADATA_LIMIT_NOT_COMPLETE");return rows;
 }
 static Map<String,Object> inspect(String role,int port,String user,String owner)throws Exception {
  String url=System.getenv(role+"_URL");if(!("jdbc:dm://127.0.0.1:"+port).equals(url)||!user.equals(System.getenv(role+"_USER")))throw new IllegalArgumentException("EXACT_MANAGED_ENDPOINT_AND_USER_REQUIRED");
  var out=new LinkedHashMap<String,Object>();out.put("role",role);out.put("port",port);out.put("schema",owner);out.put("expectedUser",user);
  try(var c=DriverManager.getConnection(url,user,System.getenv(role+"_PASSWORD"))){
   c.setReadOnly(true);c.setAutoCommit(false);out.put("driverReadOnly",c.isReadOnly());
   out.put("connection",query(c,"SELECT USER AS CONNECTION_USER,CURRENT_TIMESTAMP AS OBSERVED_DATABASE_TIME FROM DUAL",null));
   out.put("tables",query(c,"SELECT OWNER,TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME",owner));
   out.put("columns",query(c,"SELECT OWNER,TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,COLUMN_ID FROM ALL_TAB_COLUMNS WHERE OWNER=? ORDER BY TABLE_NAME,COLUMN_ID",owner));
   out.put("constraints",query(c,"SELECT OWNER,TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,R_OWNER,R_CONSTRAINT_NAME,SEARCH_CONDITION FROM ALL_CONSTRAINTS WHERE OWNER=? ORDER BY TABLE_NAME,CONSTRAINT_NAME",owner));
   out.put("constraintColumns",query(c,"SELECT OWNER,TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM ALL_CONS_COLUMNS WHERE OWNER=? ORDER BY TABLE_NAME,CONSTRAINT_NAME,POSITION",owner));
   out.put("indexes",query(c,"SELECT OWNER,TABLE_NAME,INDEX_NAME,UNIQUENESS FROM ALL_INDEXES WHERE OWNER=? ORDER BY TABLE_NAME,INDEX_NAME",owner));
   out.put("indexColumns",query(c,"SELECT INDEX_OWNER,TABLE_NAME,INDEX_NAME,COLUMN_NAME,COLUMN_POSITION FROM ALL_IND_COLUMNS WHERE INDEX_OWNER=? ORDER BY TABLE_NAME,INDEX_NAME,COLUMN_POSITION",owner));
   var logs=new LinkedHashMap<String,Object>();
   for(var row:(List<Map<String,Object>>)out.get("tables")){String table=(String)row.get("TABLE_NAME");
    if("FLYWAY_SCHEMA_HISTORY".equalsIgnoreCase(table))logs.put(table,query(c,"SELECT installed_rank,version,description,script,checksum,success FROM "+owner+".\""+table+"\" ORDER BY installed_rank",null));
    if("DATABASECHANGELOG".equalsIgnoreCase(table))logs.put(table,query(c,"SELECT ID,AUTHOR,FILENAME,DATEEXECUTED,ORDEREXECUTED,EXECTYPE,MD5SUM FROM "+owner+".\""+table+"\" ORDER BY ORDEREXECUTED",null));
   }
   out.put("knownMigrationLogs",logs);out.put("migrationHistoryProved",false);c.rollback();
  }return out;
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=1||Files.exists(Path.of(args[0])))throw new IllegalArgumentException("NEW_REPORT_PATH_REQUIRED");
  var report=new LinkedHashMap<String,Object>();report.put("observedAt",Instant.now().toString());report.put("scope","ORIGINAL_TWO_INSTANCES_METADATA_ONLY_NO_BUSINESS_ROWS_NO_DDL_NO_LIFECYCLE");
  try {report.put("business",inspect("METADATA_PRIMARY",5301,"CONPROD","CONPROD"));report.put("authority",inspect("METADATA_AUTHORITY",5302,"CONAUTH_SVC","CONAUTH"));}
  catch(SQLException e){System.err.println("METADATA_SQL_FAILED code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(2);return;}
  report.put("businessRowsRead",0);report.put("writesExecuted",0);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
 }
}
