import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public class EnforcementRecoveryTargetTriggers86 {
 public static void main(String[] args)throws Exception {
  if(!"jdbc:dm://127.0.0.1:5311".equals(System.getenv("ENF_AUDIT_URL")))throw new IllegalArgumentException("Approved target only");
  var tables=List.of("ENF8_REAL_BUSINESS_TASK","ENF8_REAL_BUSINESS_TASK_EVENT","ENF8_JOB_PACK_REVISION","ENF8_JOB_PACK_RECEIPT","ENF8_JOB_PACK_EVENT");
  var result=new LinkedHashMap<String,Object>();result.put("checkedAt",Instant.now().toString());result.put("scope","SELECT_ONLY_ALL_VISIBLE_TRIGGER_OWNERS_ON_FIVE_TARGET_TABLES");
  try(var db=DriverManager.getConnection(System.getenv("ENF_AUDIT_URL"),System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))){
   db.setReadOnly(true);db.setAutoCommit(false);var sql="SELECT OWNER,TRIGGER_NAME,TABLE_OWNER,TABLE_NAME,TRIGGERING_TYPE,TRIGGERING_EVENT,STATUS FROM ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME IN (?,?,?,?,?)";
   try(var s=db.prepareStatement(sql)){s.setQueryTimeout(10);s.setString(1,"ENFACC_BIZ");for(int i=0;i<tables.size();i++)s.setString(i+2,tables.get(i));
    try(var r=s.executeQuery()){var rows=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));rows.add(row);}result.put("triggers",rows);}
   }db.rollback();
  }result.put("writes",0);Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
}
