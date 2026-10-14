import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import com.fasterxml.jackson.databind.ObjectMapper;
public class ConsultationComparisonTables {
 static Set<String> expected=Set.of("CON5_PREPARATION","CON5_PREPARED_DOCUMENT","CON5_FEATURE_REVISION","CON5_FEATURE","CON5_FEATURE_CURRENT","CON5_FEATURE_EVENT","CON5_COMPARISON_INPUT","CON5_COMPARISON_CANDIDATE","CON5_COMPARISON_CURRENT","CON5_COMPARISON_EVENT");
 public static void main(String[] args)throws Exception {
  var report=new LinkedHashMap<String,Object>();var output=Path.of(args[1]);if(Files.exists(output))throw new IllegalStateException("PRESERVE_REPORT");
  report.put("startedAt",Instant.now().toString());report.put("taskId","DEP-CON-COMPARISON-TABLES-01");report.put("mode",args[0]);
  try(var c=ConsultationNativeMigration.open("CON_NATIVE_PRIMARY",5301,"CONPROD")) {
   var before=ConsultationNativeMigration.fingerprints(c);report.put("originalBefore",before);
   var present=new TreeSet<String>();for(var t:ConsultationNativeMigration.rows(c,"SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"))present.add((String)t.get("TABLE_NAME"));
   if(expected.stream().anyMatch(present::contains))throw new IllegalStateException("REFUSE_EXISTING_OR_PARTIAL_TABLES");
   var statements=new ArrayList<String>();var migrations=new ArrayList<Map<String,Object>>();
   for(String name:List.of("V094__product_comparison_feature_confirmation.sql","V095__product_comparison_input.sql")) {
    var file=Path.of(args[2],"db","dm8",name).toRealPath();var sql=Files.readString(file);int count=0;
    for(var part:sql.split(";")){if(part.isBlank())continue;var stmt=part.strip();var m=Pattern.compile("^CREATE TABLE (con5_[a-z_]+) \\(").matcher(stmt);if(!m.find()||!expected.contains(m.group(1).toUpperCase(Locale.ROOT)))throw new IllegalStateException("UNAPPROVED_DDL");statements.add(stmt);count++;}
    if(count!=(name.startsWith("V094")?6:4))throw new IllegalStateException("STATEMENT_COUNT");migrations.add(Map.of("file",name,"sha256",ConsultationNativeMigration.hash(Files.readAllBytes(file)),"statements",count));
   }
   var checked=new TreeSet<String>();
   for(var stmt:statements){var ref=Pattern.compile("REFERENCES (\\w+)\\(([^)]+)\\)").matcher(stmt);while(ref.find()){
    String parent=ref.group(1).toUpperCase(Locale.ROOT);if(expected.contains(parent))continue;
    if(!present.contains(parent))throw new IllegalStateException("MISSING_PARENT "+parent);
    String columns=ref.group(2).replace(" ","").toUpperCase(Locale.ROOT);String key=parent+"("+columns+")";if(checked.contains(key))continue;
    var unique=new LinkedHashMap<String,List<String>>();
    try(var r=c.getMetaData().getIndexInfo(null,"CONPROD",parent,true,false)){while(r.next()){String index=r.getString("INDEX_NAME"),column=r.getString("COLUMN_NAME");if(index!=null&&column!=null)unique.computeIfAbsent(index,k->new ArrayList<>()).add(column.toUpperCase(Locale.ROOT));}}
    if(unique.values().stream().noneMatch(v->String.join(",",v).equals(columns)))throw new IllegalStateException("MISSING_UNIQUE_PARENT_KEY "+key);
    checked.add(key);
   }}
   report.put("parentKeysVerified",checked);report.put("approvedMigrations",migrations);
   if(args[0].equals("apply")){var done=new ArrayList<String>();report.put("createdTables",done);for(var stmt:statements){try(var s=c.createStatement()){s.execute(stmt);}done.add(stmt.substring(13,stmt.indexOf(" (")));}}
   var after=ConsultationNativeMigration.fingerprints(c);var originalAfter=new TreeMap<>(after);for(var name:expected)originalAfter.remove(name);
   report.put("originalAfter",originalAfter);report.put("originalUnchanged",before.equals(originalAfter));if(!before.equals(originalAfter))throw new IllegalStateException("ORIGINAL_CHANGED");
   var empty=new TreeMap<String,Object>();if(args[0].equals("apply")){for(var name:expected){var item=(Map<?,?>)after.get(name);if(item==null||!Integer.valueOf(0).equals(item.get("rows")))throw new IllegalStateException("NEW_TABLE_NOT_EMPTY");empty.put(name,item);}}
   report.put("newEmptyTables",empty);report.put("success",true);
  }catch(Exception e){report.put("success",false);report.put("failureClass",e.getClass().getName());report.put("failure",e.getMessage());throw e;}finally{report.put("finishedAt",Instant.now().toString());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);}
 }
}
