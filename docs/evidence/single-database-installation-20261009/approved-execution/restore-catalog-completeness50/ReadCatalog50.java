import java.sql.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Evidence-only bounded dictionary SELECTs; no external SQL or business table reads. */
public final class ReadCatalog50 {
 static final String SCOPE="('CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH')";
 static final String OBJECT_SCOPE="SELECT O.ID FROM SYS.SYSOBJECTS O JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE S.NAME IN "+SCOPE;
 static final String HIDDEN="SELECT O.ID FROM SYS.SYSOBJECTS O JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE S.NAME IN "+SCOPE+" AND O.TYPE$='SCHOBJ' AND ((O.SUBTYPE$='UTAB' AND (O.INFO3 & 0x0100000000)!=0) OR (O.SUBTYPE$='TRIG' AND (O.INFO1 & 0x20)!=0))";
 static Object read(Connection c,String sql){var rows=new ArrayList<Map<String,Object>>();try(var s=c.prepareStatement(sql)){s.setQueryTimeout(15);s.setMaxRows(10001);try(var r=s.executeQuery()){var m=r.getMetaData();while(r.next()){if(rows.size()>=10000)return Map.of("ok",false,"code","QUERY_ROW_LIMIT");var row=new TreeMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof Clob cl){if(cl.length()>2097152)return Map.of("ok",false,"code","CLOB_LIMIT");v=cl.getSubString(1,(int)cl.length());}else if(v instanceof byte[] b){if(b.length>2097152)return Map.of("ok",false,"code","BINARY_LIMIT");v=Map.of("binaryHex",HexFormat.of().formatHex(b));}else if(v!=null&&!(v instanceof Number)&&!(v instanceof Boolean))v=v.toString();row.put(m.getColumnLabel(i),v);}rows.add(row);}}return Map.of("ok",true,"rows",rows);}catch(SQLException e){return Map.of("ok",false,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode());}}
 public static void main(String[] args)throws Exception{
  var out=new LinkedHashMap<String,Object>();out.put("capturedAt",java.time.Instant.now().toString());out.put("executedWrites",0);out.put("scope","six-schema dictionary only; no normalization or recovery approval");
  try(var c=DriverManager.getConnection(System.getenv("LIFECYCLE_JDBC_URL"),System.getenv("LIFECYCLE_JDBC_USER"),System.getenv("LIFECYCLE_JDBC_PASSWORD"))){c.setReadOnly(true);out.put("databaseVersion",c.getMetaData().getDatabaseProductVersion());
   out.put("versionRows",read(c,"SELECT * FROM V$VERSION"));
   out.put("nativeObjects",read(c,"SELECT S.NAME AS SCHEMA_NAME,O.* FROM SYS.SYSOBJECTS O JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE S.NAME IN "+SCOPE));
   out.put("nativeConstraints",read(c,"SELECT S.NAME AS SCHEMA_NAME,O.NAME AS CONSTRAINT_NAME,C.* FROM SYS.SYSCONS C JOIN SYS.SYSOBJECTS O ON O.ID=C.ID JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE S.NAME IN "+SCOPE));
   out.put("hiddenColumns",read(c,"SELECT S.NAME AS SCHEMA_NAME,O.NAME AS TABLE_NAME,C.* FROM SYS.SYSCOLUMNS C JOIN SYS.SYSOBJECTS O ON O.ID=C.ID JOIN SYS.SYSOBJECTS S ON S.ID=O.SCHID WHERE O.ID IN ("+HIDDEN+")"));
   out.put("hiddenTexts",read(c,"SELECT T.* FROM SYS.SYSTEXTS T WHERE T.ID IN ("+HIDDEN+") ORDER BY T.ID,T.SEQNO"));
   out.put("hiddenDependencies",read(c,"SELECT D.* FROM SYS.SYSDEPENDENCIES D WHERE D.ID IN ("+HIDDEN+")"));
   out.put("hiddenObjectInfos",read(c,"SELECT I.* FROM SYS.SYSOBJINFOS I WHERE I.ID IN ("+HIDDEN+")"));
   out.put("viewDefinitions",read(c,"SELECT OWNER,VIEW_NAME,TEXT FROM DBA_VIEWS WHERE OWNER='SYS' AND VIEW_NAME IN ('DBA_TRIGGERS','DBA_DEPENDENCIES','DBA_TAB_COLUMNS','DBA_CONSTRAINTS')"));
  }catch(SQLException e){out.put("connectionFailure",Map.of("sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode()));}
  System.out.println(new ObjectMapper().writeValueAsString(out));
 }
}
