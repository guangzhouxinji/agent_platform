import java.sql.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Evidence-only metadata SELECT probe. No caller SQL, no DDL/DML/DCL, no business rows. */
public final class ReadCatalog49 {
 static final String SCOPE="('CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH')";
 static Object read(Connection c,String sql){var rows=new ArrayList<Map<String,Object>>();try(var s=c.prepareStatement(sql)){s.setQueryTimeout(15);s.setMaxRows(10000);try(var r=s.executeQuery()){var m=r.getMetaData();while(r.next()){var row=new TreeMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof Clob cl)v=cl.getSubString(1,(int)cl.length());else if(v!=null&&!(v instanceof Number)&&!(v instanceof Boolean))v=v.toString();row.put(m.getColumnLabel(i),v);}rows.add(row);}}return Map.of("ok",true,"rows",rows);}catch(SQLException e){return Map.of("ok",false,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode());}}
 public static void main(String[] args)throws Exception{
  var out=new LinkedHashMap<String,Object>();out.put("capturedAt",java.time.Instant.now().toString());out.put("executedWrites",0);out.put("scope","six-schema catalog only; no normalization or recovery approval");
  try(var c=DriverManager.getConnection(System.getenv("LIFECYCLE_JDBC_URL"),System.getenv("LIFECYCLE_JDBC_USER"),System.getenv("LIFECYCLE_JDBC_PASSWORD"))){c.setReadOnly(true);
   out.put("databaseVersion",c.getMetaData().getDatabaseProductVersion());
   out.put("viewDefinitions",read(c,"SELECT OWNER,VIEW_NAME,TEXT FROM DBA_VIEWS WHERE OWNER='SYS' AND VIEW_NAME IN ('DBA_CONSTRAINTS','DBA_CONS_COLUMNS','DBA_INDEXES','DBA_IND_COLUMNS','DBA_IND_EXPRESSIONS','DBA_OBJECTS','DBA_TAB_COLUMNS')"));
   out.put("indexExpressions",read(c,"SELECT * FROM DBA_IND_EXPRESSIONS WHERE INDEX_OWNER IN "+SCOPE));
   out.put("nativeIndexes",read(c,"SELECT S.NAME AS SCHEMA_NAME,O.NAME AS INDEX_NAME,O.ID AS INDEX_ID,O.PID AS TABLE_ID,T.NAME AS TABLE_NAME,I.XTYPE,I.FLAG,I.KEYNUM,I.ISUNIQUE FROM SYS.SYSOBJECTS O JOIN SYS.SYSINDEXES I ON I.ID=O.ID JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID JOIN SYS.SYSOBJECTS T ON T.ID=O.PID WHERE S.NAME IN "+SCOPE));
   out.put("nativeObjects",read(c,"SELECT S.NAME AS SCHEMA_NAME,O.NAME,O.ID,O.PID,O.TYPE$,O.SUBTYPE$,O.INFO1,O.INFO2,O.INFO3,O.INFO4,O.CRTDATE FROM SYS.SYSOBJECTS O JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE S.NAME IN "+SCOPE));
   out.put("nativeConstraints",read(c,"SELECT S.NAME AS SCHEMA_NAME,O.NAME AS CONSTRAINT_NAME,C.ID,C.TABLEID,C.TYPE$,C.VALID,C.INDEXID,C.FINDEXID,C.FACTION,C.TRIGID FROM SYS.SYSCONS C JOIN SYS.SYSOBJECTS O ON O.ID=C.ID JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE S.NAME IN "+SCOPE));
  }catch(SQLException e){out.put("connectionFailure",Map.of("sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode()));}
  System.out.println(new ObjectMapper().writeValueAsString(out));
 }
}
