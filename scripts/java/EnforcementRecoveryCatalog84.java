import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public class EnforcementRecoveryCatalog84 {
 static final ObjectMapper JSON=new ObjectMapper();
 static final List<String> TABLES=List.of("ENF8_REAL_BUSINESS_TASK","ENF8_REAL_BUSINESS_TASK_EVENT","ENF8_JOB_PACK_REVISION","ENF8_JOB_PACK_RECEIPT","ENF8_JOB_PACK_EVENT");
 static List<String> columns(Connection db,String view)throws Exception {
  try(var s=db.createStatement()){s.setQueryTimeout(10);try(var r=s.executeQuery("SELECT * FROM "+view+" WHERE 1=0")){
   var out=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)out.add(r.getMetaData().getColumnLabel(i));return out;
  }}
 }
 static Object query(Connection db,String sql,Object...params)throws Exception {
  try(var s=db.prepareStatement(sql)){s.setQueryTimeout(10);for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);
   try(var r=s.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();
    for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));out.add(row);
   }return out;}
  }catch(SQLException e){return Map.of("unavailable",true,"errorCode",e.getErrorCode(),"sqlState",Objects.toString(e.getSQLState(),""));}
 }
 public static void main(String[] args)throws Exception {
  var result=new LinkedHashMap<String,Object>();result.put("checkedAt",Instant.now().toString());result.put("scope","SELECT_ONLY_TRIGGER_AND_CHECK_CONSTRAINT_METADATA");
  try(var db=DriverManager.getConnection(System.getenv("ENF_AUDIT_URL"),System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))){
   db.setReadOnly(true);db.setAutoCommit(false);String owner="ENFACC_BIZ";
   for(String view:List.of("ALL_TRIGGERS","USER_TRIGGERS","ALL_CONSTRAINTS")){
    try{result.put(view+"Columns",columns(db,view));}catch(SQLException e){result.put(view+"Columns",Map.of("unavailable",true,"errorCode",e.getErrorCode()));}
   }
   var perTable=new LinkedHashMap<String,Object>();for(String table:TABLES){var entry=new LinkedHashMap<String,Object>();
    entry.put("allTriggers",query(db,"SELECT OWNER,TRIGGER_NAME,TRIGGERING_TYPE,TRIGGERING_EVENT,STATUS,TABLE_NAME FROM ALL_TRIGGERS WHERE OWNER=? AND TABLE_NAME=?",owner,table));
    entry.put("userTriggers",query(db,"SELECT TRIGGER_NAME,TRIGGERING_TYPE,TRIGGERING_EVENT,STATUS,TABLE_NAME FROM USER_TRIGGERS WHERE TABLE_NAME=?",table));
    entry.put("checks",query(db,"SELECT CONSTRAINT_NAME,SEARCH_CONDITION,STATUS FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_TYPE='C'",owner,table));
    try(var rs=db.getMetaData().getExportedKeys(null,owner,table)){var keys=new ArrayList<Map<String,Object>>();while(rs.next()){var row=new LinkedHashMap<String,Object>();
     for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getString(i));keys.add(row);}entry.put("referencingForeignKeys",keys);
    }perTable.put(table.toLowerCase(Locale.ROOT),entry);
   }result.put("tables",perTable);db.rollback();
  }result.put("writes",0);Files.writeString(Path.of(args[0]),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8);
 }
}
