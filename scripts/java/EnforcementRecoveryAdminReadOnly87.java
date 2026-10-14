import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Approved administrator SELECT diagnostics. No SQL text, grants or session mutations. */
public class EnforcementRecoveryAdminReadOnly87 {
 static final ObjectMapper JSON=new ObjectMapper();
 static Object read(Connection db,String sql)throws Exception {
  if(!sql.startsWith("SELECT ")||sql.contains("FOR UPDATE"))throw new IllegalArgumentException("Read only");
  try(var s=db.prepareStatement(sql)){s.setQueryTimeout(10);try(var r=s.executeQuery()){
   var m=r.getMetaData();var out=new ArrayList<Map<String,Object>>();
   while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getString(i));out.add(row);}return out;
  }}catch(SQLException e){return Map.of("unavailable",true,"code",e.getErrorCode(),"state",Objects.toString(e.getSQLState(),""));}
 }
 public static void main(String[] args)throws Exception {
  var out=new LinkedHashMap<String,Object>();out.put("checkedAt",Instant.now().toString());out.put("scope","5311_ADMIN_SELECT_ONLY_NO_SQL_TEXT_NO_GRANT_NO_SESSION_TERMINATION");
  try(var db=DriverManager.getConnection("jdbc:dm://127.0.0.1:5311","SYSDBA",System.getenv("ENF_ADMIN_READONLY_PASSWORD"))){
   db.setReadOnly(true);db.setAutoCommit(false);
   for(String view:List.of("V$SESSIONS","V$LOCK","V$TRX","V$TRXWAIT")){
    try(var s=db.prepareStatement("SELECT * FROM "+view+" WHERE 1=0");var r=s.executeQuery()){
     var cols=new ArrayList<Map<String,Object>>();var m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)cols.add(Map.of("name",m.getColumnName(i),"type",m.getColumnTypeName(i)));out.put(view+"Metadata",cols);
    }catch(SQLException e){out.put(view+"Metadata",Map.of("unavailable",true,"code",e.getErrorCode(),"state",Objects.toString(e.getSQLState(),"")));}
   }
   if(args.length>1){for(String entry:Files.readAllLines(Path.of(args[1]),StandardCharsets.UTF_8)){if(entry.isBlank())continue;int split=entry.indexOf('|');out.put(entry.substring(0,split),read(db,entry.substring(split+1)));}}
   db.rollback();
  }
  out.put("writes",0);Files.writeString(Path.of(args[0]),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
}
