package cn.xinji.agentplatform.security.product;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;

/** Owned DM8 successor only; no API, formal Task, models or old evidence writes. */
public final class FencePresenceDm8Harness {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final List<Map<String,Object>> checks=new ArrayList<>();
 static final String V="V103__comparison_start_fence_presence.sql",U="U103__comparison_start_fence_presence.sql";
 static Path out;
 interface Action {void run() throws Exception;}
 static void check(boolean ok,String label){checks.add(Map.of("check",label,"passed",ok));if(!ok)throw new AssertionError(label);}
 static void write(String name,Object data)throws Exception{Files.write(out.resolve(name),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data),StandardOpenOption.CREATE_NEW);}
 static JdbcTemplate db(String user,String schema){return new JdbcTemplate(new DriverManagerDataSource("jdbc:dm://127.0.0.1:5492",user,System.getenv("V103_"+user+"_PASSWORD")){
  @Override public Connection getConnection()throws SQLException{var c=super.getConnection();try{c.setSchema(schema);return c;}catch(SQLException e){c.close();throw e;}}
 });}
 static String script(String name)throws Exception{return Files.readString(Path.of("db/dm8",name)).replaceAll("(?m)^\\s*--[^\\r\\n]*","").trim();}
 static String forward()throws Exception{return script(V).replaceFirst(";\\s*$","");}
 static List<String> reverse()throws Exception{var p=script(U).split("(?m)^\\s*/\\s*$");check(p.length==2,"U103 guard precedes DROP");return List.of(p[0].trim(),p[1].trim().replaceFirst(";\\s*$",""));}
 static Map<String,Object> refusal(String label,Action a)throws Exception{
  try{a.run();check(false,label);return Map.of();}catch(DataAccessException e){var cause=e.getMostSpecificCause();var detail=new LinkedHashMap<String,Object>();detail.put("check",label);detail.put("passed",true);if(cause instanceof SQLException s){detail.put("sqlState",String.valueOf(s.getSQLState()));detail.put("code",s.getErrorCode());detail.put("message",s.getMessage());}else detail.put("error",cause.getClass().getSimpleName());checks.add(detail);return detail;}
 }
 static void rolledBack(JdbcTemplate db,String label,String sql,String id,boolean deny)throws Exception{
  db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c->{c.setAutoCommit(false);try{try(var p=c.prepareStatement(sql)){p.setString(1,id);try{int n=p.executeUpdate();check(!deny && n==1,label);}catch(SQLException e){check(deny,label);checks.add(Map.of("check",label+" native rejection","passed",true,"sqlState",String.valueOf(e.getSQLState()),"code",e.getErrorCode()));}}}finally{c.rollback();c.setAutoCommit(true);}return null;});
 }
 static List<Map<String,Object>> facts(JdbcTemplate db,String owner){return db.queryForList("SELECT OWNER,TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME='CON5_START_COMMAND' AND CONSTRAINT_NAME IN ('CK_CON5_START_COMMAND','CK_CON5_START_FENCE_PRESENT') ORDER BY CONSTRAINT_NAME",owner);}
 static void fixtureSchemas(JdbcTemplate admin)throws Exception{
  for(String u:List.of("V103NEG","V103NEG_SVC","V103EMPTY","V103EMPTY_SVC"))check(admin.queryForObject("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?",Integer.class,u)==0,"fresh username absent "+u);
  write("fresh-schema-admission.json",Map.of("task","FIX-CON-START-FENCE-PRESENCE-01","schemas",List.of("V103NEG","V103EMPTY"),"confirmedAbsent",true));
  for(String s:List.of("V103NEG","V103EMPTY")){
   for(String u:List.of(s,s+"_SVC")){String pw=System.getenv("V103_"+u+"_PASSWORD");if(pw==null||!pw.matches("[A-Za-z0-9_]{32,48}"))throw new IllegalArgumentException("Private generated secret required");admin.execute("CREATE USER "+u+" IDENTIFIED BY \""+pw+"\"");}admin.execute("GRANT RESOURCE TO "+s);
   // Explicit engineering table: actual V102 column/old CHECK bytes, only four foreign keys removed.
   // No ancestor business or formal identities are created in these negative/empty schemas.
   String original=Files.readString(Path.of("db/dm8/V102__product_comparison_start_command.sql"));
   String table=original.substring(original.indexOf("CREATE TABLE"),original.indexOf("CREATE INDEX"));
   table=table.replaceAll("(?m)^\\s*CONSTRAINT fk_con5_start_[^\\r\\n]*\\r?\\n","").trim().replaceFirst(";\\s*$","");
   var owner=db(s,s);owner.execute(table);owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON con5_start_command TO "+s+"_SVC");
   write(s.toLowerCase(Locale.ROOT)+"-engineering-table.sql",Map.of("source","fixed V102","transformation","remove only four FK declarations for isolated CHECK engineering table; retain 21 columns, unique, PK and original CHECK","ddl",table));
  }
 }
 static void copyBoundRow(JdbcTemplate runtime,JdbcTemplate target)throws Exception{
  var rows=runtime.queryForList("SELECT * FROM con5_start_command WHERE attempt_id IS NOT NULL ORDER BY command_id");check(!rows.isEmpty(),"existing own bound fixture available");var row=rows.getFirst();String columns=String.join(",",row.keySet());String placeholders=String.join(",",Collections.nCopies(row.size(),"?"));target.update("INSERT INTO con5_start_command("+columns+") VALUES("+placeholders+")",row.values().toArray());
 }
 static void run()throws Exception{
  var owner=db("STARTMAIN","STARTMAIN");var runtime=db("STARTMAIN_SVC","STARTMAIN");
  var tables=StartCommandsDm8Harness.tables();var before=StartCommandsDm8Harness.snapshot(runtime,tables);write("main-before-v103.json",before);
  var old=JSON.readTree(Files.readAllBytes(Path.of("docs/evidence/single-database-persistent-20261007/v102-engineering-01/main-before-restart-baseline.json")));
  check(old.equals(JSON.valueToTree(before)),"all19 histories match frozen V102 persistent baseline before migration");
  check(runtime.queryForObject("SELECT COUNT(*) FROM con5_start_command",Integer.class)>0,"STARTMAIN legal history nonempty");
  check(runtime.queryForObject("SELECT COUNT(*) FROM con5_start_command WHERE attempt_id IS NOT NULL AND fencing_token IS NULL",Integer.class)==0,"existing bound histories have nonnull fence; no repair needed");
  write("runtime-dictionary-before.json",facts(runtime,"STARTMAIN"));
  check(runtime.queryForObject("SELECT COUNT(*) FROM ALL_CONSTRAINTS WHERE OWNER='STARTMAIN' AND CONSTRAINT_NAME='CK_CON5_START_FENCE_PRESENT'",Integer.class)==0,"V103 not previously applied");
  try{ProductComparisonStartSchema.verify(runtime,"STARTMAIN");check(false,"actual runtime startup gate refuses absent V103");}catch(IllegalStateException e){check(true,"actual runtime startup gate refuses absent V103");}
  owner.execute(forward());check(true,"actual frozen V103 applied to legal nonempty STARTMAIN");
  write("runtime-dictionary-after.json",facts(runtime,"STARTMAIN"));
  // The unmodified product gate tests actual DM8 SEARCH_CONDITION/type/STATUS with runtime privileges.
  ProductComparisonStartSchema.verify(runtime,"STARTMAIN");check(true,"actual unmodified ProductComparisonStartSchema passes runtime dictionary");
  var bound=runtime.queryForList("SELECT command_id FROM con5_start_command WHERE attempt_id IS NOT NULL ORDER BY command_id",String.class).getFirst();
  rolledBack(runtime,"NULL fence with bound attempt refused","UPDATE con5_start_command SET fencing_token=NULL WHERE command_id=?",bound,true);
  rolledBack(runtime,"COMMITTED NULL fence refused","UPDATE con5_start_command SET status='COMMITTED',fencing_token=NULL WHERE command_id=?",bound,true);
  rolledBack(runtime,"zero fence still refused by original CHECK","UPDATE con5_start_command SET fencing_token=0 WHERE command_id=?",bound,true);
  rolledBack(runtime,"negative fence still refused by original CHECK","UPDATE con5_start_command SET fencing_token=-1 WHERE command_id=?",bound,true);
  String reserved=runtime.queryForList("SELECT command_id FROM con5_start_command WHERE attempt_id IS NULL ORDER BY command_id",String.class).getFirst();
  rolledBack(runtime,"reserved NULL attempt/fence still allowed","UPDATE con5_start_command SET attempt_id=NULL,fencing_token=NULL WHERE command_id=?",reserved,false);
  var rev=reverse();int[] executed={0};var rejection=refusal("actual U103 nonempty guard refuses before DROP",()->{for(String s:rev){owner.execute(s);executed[0]++;}});
  check(executed[0]==0,"nonempty U103 did not reach DROP");check(((Number)rejection.get("code")).intValue()==-20001,"actual U103 nonempty native code -20001");
  ProductComparisonStartSchema.verify(runtime,"STARTMAIN");
  var after=StartCommandsDm8Harness.snapshot(runtime,tables);write("main-after-v103-and-probes.json",after);check(before.equals(after),"all19 legal row digests unchanged after migration/probes/U103 refusal");
  var admin=db("SYSDBA","SYSDBA");fixtureSchemas(admin);
  var neg=db("V103NEG","V103NEG");copyBoundRow(runtime,neg);neg.update("UPDATE con5_start_command SET fencing_token=NULL");
  var negBefore=StartCommandsDm8Harness.snapshot(neg,List.of("con5_start_command"));write("illegal-history-before.json",negBefore);
  write("illegal-history-v103-rejection.json",refusal("actual V103 refuses pre-existing illegal row",()->neg.execute(forward())));
  var negAfter=StartCommandsDm8Harness.snapshot(neg,List.of("con5_start_command"));write("illegal-history-after.json",negAfter);check(negBefore.equals(negAfter),"failed forward migration leaves illegal row exactly unchanged");
  check(neg.queryForObject("SELECT COUNT(*) FROM ALL_CONSTRAINTS WHERE OWNER='V103NEG' AND CONSTRAINT_NAME='CK_CON5_START_FENCE_PRESENT'",Integer.class)==0,"failed V103 does not publish new constraint");
  var empty=db("V103EMPTY","V103EMPTY");var er=db("V103EMPTY_SVC","V103EMPTY");check(empty.queryForObject("SELECT COUNT(*) FROM con5_start_command",Integer.class)==0,"fresh empty schema has no history");
  empty.execute(forward());ProductComparisonStartSchema.verify(er,"V103EMPTY");check(true,"actual gate passes new empty runtime with V103");
  for(String s:rev)empty.execute(s);check(true,"actual U103 empty dedicated schema succeeds");
  check(empty.queryForObject("SELECT COUNT(*) FROM ALL_CONSTRAINTS WHERE OWNER='V103EMPTY' AND CONSTRAINT_NAME='CK_CON5_START_COMMAND'",Integer.class)==1,"U103 preserves original CHECK");
  try{ProductComparisonStartSchema.verify(er,"V103EMPTY");check(false,"actual runtime startup gate rejects after empty U103");}catch(IllegalStateException e){check(true,"actual runtime startup gate rejects after empty U103");}
  write("empty-u103-dictionary.json",facts(er,"V103EMPTY"));
  check(before.equals(StartCommandsDm8Harness.snapshot(runtime,tables)),"new-schema negative/empty engineering never changes main history");
 }
 public static void main(String[] args)throws Exception{
  out=Path.of(System.getenv("V103_OUTPUT"));int code=0;
  try{run();}catch(Throwable e){code=2;checks.add(Map.of("check","fatal phase exception","passed",false,"type",e.getClass().getName(),"message",String.valueOf(e.getMessage())));}
  long failed=checks.stream().filter(m->Boolean.FALSE.equals(m.get("passed"))).count();
  write("execution-result.json",Map.of("task","FIX-CON-START-FENCE-PRESENCE-01","actualDm8",true,"checks",checks,"passed",checks.size()-failed,"failed",failed,"exitCode",code,"oldV102EvidenceUntouched",true));
  System.out.println("V103 DM8 assertions passed="+(checks.size()-failed)+" failed="+failed+" exit="+code);System.exit(code);
 }
}
