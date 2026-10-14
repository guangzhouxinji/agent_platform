package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual DM8 storage engineering only. Frozen product store, fixed SQL, no formal Task/API/model. */
public final class StartCommandsDm8Harness {
 static final String TASK="VER-CON-COMPARISON-START-DM8-01",URL="jdbc:dm://127.0.0.1:5492";
 static final List<String> MIGRATIONS=List.of("V095__product_comparison_input.sql","V098__product_comparison_worker_delegation.sql","V099__product_comparison_candidate_lease.sql","V100__product_comparison_matrix.sql","V102__product_comparison_start_command.sql");
 static final List<String> ANCESTORS=List.of("con3_task","id2_subject","con5_feature_revision","con5_preparation","con5_prepared_document","con3_worker_delegation");
 static final String SHA="a".repeat(64), REQUEST="b".repeat(64), CONFIG="configuration-version-17-not-id";
 static final Instant NOW=Instant.parse("2026-10-07T08:00:00Z"),UNTIL=NOW.plusSeconds(600);
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static Path out;static JdbcTemplate main,empty;static TransactionTemplate tx;static ProductComparisonStartCommands store;
 static final List<Map<String,Object>> checks=new ArrayList<>(); static int fixtureNumber=0;
 static String required(String k){String v=System.getenv(k);if(v==null||v.isBlank())throw new IllegalArgumentException("Required owned setting "+k);return v;}
 static String password(String user){String p=required("V102_ENGINEERING_"+user+"_PASSWORD");if(!p.matches("[A-Za-z0-9_]{32,48}"))throw new IllegalArgumentException("Secret shape");return p;}
 static DataSource ds(String user,String schema){return new DriverManagerDataSource(URL,user,password(user)){
  @Override public Connection getConnection()throws SQLException{var c=super.getConnection();try{c.setSchema(schema);return c;}catch(SQLException e){c.close();throw e;}}
 };}
 static Connection readyAdmin()throws Exception{SQLException last=null;for(int i=0;i<30;i++){try{return DriverManager.getConnection(URL,"SYSDBA",password("SYSDBA"));}catch(SQLException ex){last=ex;Thread.sleep(1000);}}throw last;}
 static void write(String file,Object v)throws Exception{Files.write(out.resolve(file),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(v),StandardOpenOption.CREATE_NEW);}
 static void require(boolean b,String name){if(!b)throw new AssertionError(name);check(true,name);}
 static void check(boolean b,String name){checks.add(Map.of("check",name,"passed",b));}
 interface Action{void run()throws Exception;}
 static void refusal(String name,Action a)throws Exception{try{a.run();check(false,name);}catch(IllegalStateException|IllegalArgumentException ex){checks.add(Map.of("check",name,"passed",true,"rejection",ex.getClass().getSimpleName()));}}
 static Timestamp ts(Instant i){return Timestamp.from(i);}
 static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static String hash(String s)throws Exception{return hash(s.getBytes(StandardCharsets.UTF_8));}
 static List<String> tables()throws Exception{var r=new ArrayList<>(ANCESTORS);for(String file:MIGRATIONS){var m=Pattern.compile("(?im)^CREATE TABLE\\s+([a-z0-9_]+)").matcher(Files.readString(Path.of("db/dm8",file)));while(m.find())r.add(m.group(1));}return r;}
 static void script(JdbcTemplate owner,String file)throws Exception{String text=Files.readString(Path.of("db/dm8",file)).replaceAll("(?m)^\\s*--[^\\r\\n]*","");for(String s:text.split(";"))if(!s.isBlank())owner.execute(s);}
 static void initialize()throws Exception{
  try(var connection=readyAdmin()){
   var admin=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
   var names=List.of("STARTMAIN","STARTMAIN_SVC","STARTEMPTY","STARTEMPTY_SVC");
   for(String u:names)require(admin.queryForObject("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?",Integer.class,u)==0,"fresh name unoccupied "+u);
   write("fresh-users-before-ddl.json",Map.of("task",TASK,"namesConfirmedAbsent",names,"recordedAt",Instant.now().toString(),"queryOnly",true));
   for(String owner:List.of("STARTMAIN","STARTEMPTY")){
    admin.execute("CREATE USER "+owner+" IDENTIFIED BY \""+password(owner)+"\"");admin.execute("CREATE USER "+owner+"_SVC IDENTIFIED BY \""+password(owner+"_SVC")+"\"");admin.execute("GRANT RESOURCE TO "+owner);
   }
  }
  for(String schema:List.of("STARTMAIN","STARTEMPTY")){
   var owner=new JdbcTemplate(ds(schema,schema));
   owner.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY,version BIGINT,authorization_version BIGINT,cancel_epoch BIGINT,state VARCHAR(30),expires_at TIMESTAMP)");
   owner.execute("CREATE TABLE id2_subject(issuer VARCHAR(300),subject_id VARCHAR(200),PRIMARY KEY(issuer,subject_id))");
   owner.execute("CREATE TABLE con5_feature_revision(task_id VARCHAR(36),revision_id VARCHAR(36),revision_number BIGINT,PRIMARY KEY(task_id,revision_id,revision_number))");
   owner.execute("CREATE TABLE con5_preparation(task_id VARCHAR(36),preparation_id VARCHAR(36),PRIMARY KEY(task_id,preparation_id))");
   owner.execute("CREATE TABLE con5_prepared_document(preparation_id VARCHAR(36),document_role VARCHAR(16),document_key VARCHAR(64),PRIMARY KEY(preparation_id,document_role,document_key))");
   owner.execute("CREATE TABLE con3_worker_delegation(delegation_id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36),human_issuer VARCHAR(300),human_subject VARCHAR(200),service_subject VARCHAR(200),authorization_version BIGINT,status VARCHAR(20),expires_at TIMESTAMP)");
   for(String m:MIGRATIONS)script(owner,m);
   for(String t:tables())owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE,REFERENCES ON "+t+" TO "+schema+"_SVC");
   check(true,"actual five migrations applied unchanged in "+schema);
  }
 }
 static record Fixture(String task,String input,String feature,String preparation,String candidateA,String candidateB,String delegation,String attempt,String report,String key,ProductComparisonStartCommands.Scope scope,ProductComparisonStartCommands.Actor actor){ }
 static Fixture fixture(String label){
  String n=label+"-"+(++fixtureNumber),task="t-"+n,input="i-"+n,feature="f-"+n,preparation="p-"+n;
  var actor=new ProductComparisonStartCommands.Actor("https://owned-start-engineering.test","human-"+n);
  main.update("INSERT INTO con3_task VALUES(?,5,17,0,'IN_PROGRESS',?)",task,ts(UNTIL.plusSeconds(300)));
  main.update("INSERT INTO id2_subject VALUES(?,?)",actor.issuer(),actor.subject());
  main.update("INSERT INTO con5_feature_revision VALUES(?,?,1)",task,feature);
  main.update("INSERT INTO con5_preparation VALUES(?,?)",task,preparation);
  main.update("INSERT INTO con5_comparison_input VALUES(?,?,?,1,?,5,NULL,'{}',?,2,?,?,?, ?,?,?)",input,task,feature,preparation,SHA,actor.issuer(),actor.subject(),"input-"+n,REQUEST,ts(NOW),ts(UNTIL.plusSeconds(100)));
  for(String candidate:List.of("A","B")){
   main.update("INSERT INTO con5_prepared_document VALUES(?,'CANDIDATE',?)",preparation,candidate);
   main.update("INSERT INTO con5_comparison_candidate VALUES(?,?,'CANDIDATE',?,?,'QUEUED')",input,preparation,candidate,candidate.equals("A")?0:1);
  }
  main.update("INSERT INTO con5_comparison_current VALUES(?,?)",task,input);
  return new Fixture(task,input,feature,preparation,"A","B","d-"+n,"a-"+n,"r-"+n,"key-"+n,new ProductComparisonStartCommands.Scope(task,input,"A",SHA,5,0),actor);
 }
 static ProductComparisonStartCommands.ManagedBinding managed(){return new ProductComparisonStartCommands.ManagedBinding("owned-worker",CONFIG);}
 static ProductComparisonStartCommands.Reservation reserve(Fixture f){return store.reserveExact(f.scope(),f.actor(),f.key(),REQUEST,managed(),UNTIL);}
 static ProductComparisonStartCommands.Command dispatch(Fixture f){var c=reserve(f).command();require(store.dispatchOnce(c.commandId()).isPresent(),"original dispatch "+f.task());return store.readExact(f.scope(),f.actor(),c.commandId());}
 static void delegation(Fixture f,String id,long authorization,String candidate){
  main.update("INSERT INTO con3_worker_delegation VALUES(?,?,?,?,?,?, 'ACTIVE',?)",id,f.task(),f.actor().issuer(),f.actor().subject(),"owned-worker",authorization,ts(UNTIL));
  main.update("INSERT INTO con5_comparison_delegation VALUES(?,?,?,?,?,?,'owned-worker',?,?)",id,f.task(),f.input(),candidate,SHA,REQUEST,ts(NOW),ts(UNTIL));
 }
 static void attempt(Fixture f,String id,String delegation,String candidate,long token){
  main.update("INSERT INTO con5_candidate_attempt VALUES(?,?,?,?,?,5,0,'owned-worker',?,'owned-claim',?,?,?,'ACTIVE',NULL,?,?)",id,f.task(),f.input(),candidate,SHA,delegation,REQUEST,SHA,token,ts(NOW),ts(UNTIL));
  main.update("INSERT INTO con5_candidate_lease VALUES(?,?,?,?,'ACTIVE',?)",f.input(),candidate,id,token,ts(UNTIL));
 }
 static ProductComparisonStartCommands.Command bound(Fixture f){var c=dispatch(f);delegation(f,f.delegation(),17,"A");store.bindDelegation(c.commandId(),f.delegation());attempt(f,f.attempt(),f.delegation(),"A",1);return store.bindAttempt(c.commandId(),new ProductComparisonStartCommands.AttemptBinding(f.attempt(),1,UNTIL));}
 static String status(String id){return main.queryForObject("SELECT status FROM con5_start_command WHERE command_id=?",String.class,id);}
 static String receipt(Fixture f)throws Exception{
  String response="{\"kind\":\"owned-engineering-not-model-output\"}";
  var coords=new LinkedHashMap<String,Object>();coords.put("taskId",f.task());coords.put("comparisonId",f.input());coords.put("candidateId","A");coords.put("inputSha",SHA);coords.put("attemptId",f.attempt());coords.put("token",1);coords.put("reportId",f.report());
  var reportMap=new LinkedHashMap<>(coords);reportMap.put("configurationVersion",CONFIG);reportMap.put("workerSubject","owned-worker");reportMap.put("responseSha",hash(response));reportMap.put("approvalSha",REQUEST);
  String report=JSON.writeValueAsString(reportMap),sha=hash(report);var receiptMap=new LinkedHashMap<>(coords);receiptMap.put("reportSha",sha);receiptMap.put("status","COMMITTED");String receipt=JSON.writeValueAsString(receiptMap);
  main.update("UPDATE con5_candidate_attempt SET status='COMMITTED' WHERE attempt_id=?",f.attempt());main.update("UPDATE con5_candidate_lease SET status='COMMITTED' WHERE attempt_id=?",f.attempt());main.update("UPDATE con5_comparison_candidate SET status='COMMITTED' WHERE comparison_id=? AND candidate_id='A'",f.input());
  main.update("INSERT INTO con5_matrix_invocation VALUES(?,?,?,?,'COMMITTED',?,?,?)",f.attempt(),REQUEST,REQUEST,CONFIG,hash(response),response,ts(NOW));
  main.update("INSERT INTO con5_matrix_report VALUES(?,?,?,'A',?,1,?,?,?,?,?)",f.report(),f.task(),f.input(),f.attempt(),SHA,sha,report,ts(NOW),ts(UNTIL));
  main.update("INSERT INTO con5_matrix_receipt VALUES(?,?,?,?,?)",f.attempt(),f.report(),sha,receipt,hash(receipt));return sha;
 }
 static void rolledBack(String name,boolean expectRefusal,String sql,Object... args){
  tx.executeWithoutResult(s->{boolean denied=false;String detail="accepted";
   try{main.update(sql,args);}catch(org.springframework.dao.DataAccessException ex){denied=true;var cause=ex.getMostSpecificCause();detail=cause instanceof SQLException e?"sqlState="+e.getSQLState()+" code="+e.getErrorCode():cause.getClass().getSimpleName();}
   checks.add(Map.of("check",name,"passed",expectRefusal==denied,"expectedRefusal",expectRefusal,"actualRefusal",denied,"detail",detail,"faultRolledBack",true));s.setRollbackOnly();
  });
 }
 static void physical()throws Exception{
  var columns=new ArrayList<Map<String,Object>>();var imported=new ArrayList<Map<String,Object>>();var primary=new ArrayList<String>();
  try(var c=ds("STARTMAIN_SVC","STARTMAIN").getConnection()){
   var m=c.getMetaData();try(var rs=m.getColumns(null,"STARTMAIN","CON5_START_COMMAND",null)){while(rs.next())columns.add(Map.of("name",rs.getString("COLUMN_NAME"),"type",rs.getInt("DATA_TYPE"),"typeName",rs.getString("TYPE_NAME"),"size",rs.getInt("COLUMN_SIZE"),"nullable",rs.getInt("NULLABLE"),"ordinal",rs.getInt("ORDINAL_POSITION")));}
   try(var rs=m.getPrimaryKeys(null,"STARTMAIN","CON5_START_COMMAND")){while(rs.next())primary.add(rs.getString("COLUMN_NAME"));}
   try(var rs=m.getImportedKeys(null,"STARTMAIN","CON5_START_COMMAND")){while(rs.next())imported.add(Map.of("name",rs.getString("FK_NAME"),"column",rs.getString("FKCOLUMN_NAME"),"parentSchema",rs.getString("PKTABLE_SCHEM"),"parentTable",rs.getString("PKTABLE_NAME"),"parentColumn",rs.getString("PKCOLUMN_NAME")));}
  }
  write("v102-physical-metadata.json",Map.of("columns",columns,"primaryKey",primary,"foreignKeys",imported));
  check(columns.size()==21,"actual physical V102 has 21 columns");
  var expected=List.of("COMMAND_ID","TASK_ID","COMPARISON_ID","CANDIDATE_ID","INPUT_SHA","TASK_VERSION","CANCEL_EPOCH","ACTOR_ISSUER","ACTOR_SUBJECT","REQUEST_ID","REQUEST_SHA","SERVICE_SUBJECT","CONFIGURATION_VERSION","DELEGATION_ID","ATTEMPT_ID","FENCING_TOKEN","LEASE_UNTIL","STATUS","ERROR_CODE","CREATED_AT","EXPIRES_AT");
  check(columns.stream().map(c->c.get("name")).toList().equals(expected),"exact physical columns/order include configuration_version");
  check(primary.equals(List.of("COMMAND_ID")),"actual command_id primary key");
  check(imported.size()==6&&imported.stream().allMatch(k->k.get("parentSchema").equals("STARTMAIN")),"actual four FK constraints six component columns all own schema");
  var cfg=columns.stream().filter(c->c.get("name").equals("CONFIGURATION_VERSION")).findFirst().orElseThrow();check(cfg.get("size").equals(200)&&cfg.get("nullable").equals(DatabaseMetaData.columnNoNulls),"configuration_version VARCHAR200 NOT NULL");
 }
 static void storageProtocols()throws Exception{
  var replay=fixture("replay");var first=reserve(replay);check(first.newlyReserved(),"new reservation ownership");
  var second=store.reserveExact(replay.scope(),replay.actor(),replay.key(),REQUEST,managed(),NOW.plusSeconds(20));
  check(!second.newlyReserved()&&second.command().equals(first.command()),"original-key replay retains immutable coordinates and deadline");
  refusal("original key different digest conflicts",()->store.reserveExact(replay.scope(),replay.actor(),replay.key(),SHA,managed(),UNTIL));
  refusal("original key configuration id instead of version conflicts",()->store.reserveExact(replay.scope(),replay.actor(),replay.key(),REQUEST,new ProductComparisonStartCommands.ManagedBinding("owned-worker","configuration-id-not-version"),UNTIL));
  refusal("original key different service conflicts",()->store.reserveExact(replay.scope(),replay.actor(),replay.key(),REQUEST,new ProductComparisonStartCommands.ManagedBinding("other-worker",CONFIG),UNTIL));
  refusal("wrong actor cannot read exact command",()->store.readExact(replay.scope(),new ProductComparisonStartCommands.Actor(replay.actor().issuer(),"other-human"),first.command().commandId()));
  refusal("another candidate cannot read exact command",()->store.readExact(new ProductComparisonStartCommands.Scope(replay.task(),replay.input(),"B",SHA,5,0),replay.actor(),first.command().commandId()));
  check(store.dispatchOnce(first.command().commandId()).isPresent(),"durable dispatch one winner");check(store.dispatchOnce(first.command().commandId()).isEmpty(),"dispatch replay no send permission");
  store.markReconcile(first.command().commandId(),"ENGINEERING_RESPONSE_LOST");
  check(store.dispatchOnce(first.command().commandId()).isEmpty(),"unknown command cannot dispatch again");
  refusal("new key cannot bypass unknown candidate fence",()->store.reserveExact(replay.scope(),replay.actor(),"another-key",REQUEST,managed(),UNTIL));
  var accepted=fixture("accepted");reserve(accepted);
  refusal("new key cannot bypass interrupted ACCEPTED",()->store.reserveExact(accepted.scope(),accepted.actor(),"new-key",REQUEST,managed(),UNTIL));
  var versions=fixture("versions");main.update("UPDATE con3_task SET version=6 WHERE task_id=?",versions.task());refusal("stale Task business version rejected",()->reserve(versions));
  main.update("UPDATE con3_task SET version=5,cancel_epoch=1 WHERE task_id=?",versions.task());refusal("cancel epoch mismatch rejected",()->reserve(versions));
  main.update("UPDATE con3_task SET cancel_epoch=0 WHERE task_id=?",versions.task());main.update("UPDATE con5_comparison_input SET input_sha=? WHERE comparison_id=?",REQUEST,versions.input());refusal("current Input SHA mismatch rejected",()->reserve(versions));
  main.update("UPDATE con5_comparison_input SET input_sha=? WHERE comparison_id=?",SHA,versions.input());
  refusal("already expired requested window rejected",()->store.reserveExact(versions.scope(),versions.actor(),versions.key(),REQUEST,managed(),NOW));
  refusal("requested window beyond Input rejected",()->store.reserveExact(versions.scope(),versions.actor(),versions.key(),REQUEST,managed(),UNTIL.plusSeconds(101)));
  var expiry=reserve(versions).command();var late=new ProductComparisonStartCommands(main,tx,Clock.fixed(UNTIL.plusSeconds(100),ZoneOffset.UTC));refusal("fresh dispatch rechecks actual Input deadline",()->late.dispatchOnce(expiry.commandId()));check(status(expiry.commandId()).equals("ACCEPTED"),"rejected dispatch leaves original accepted history");
  refusal("ambient transaction cannot hand off uncommitted command",()->tx.execute(s->reserve(fixture("ambient"))));
  var auth=fixture("auth");var ac=dispatch(auth);delegation(auth,"bad5-"+auth.delegation(),5,"A");delegation(auth,"old16-"+auth.delegation(),16,"A");delegation(auth,auth.delegation(),17,"A");
  refusal("delegation TaskVersion5 is not AuthorizationVersion17",()->store.bindDelegation(ac.commandId(),"bad5-"+auth.delegation()));refusal("stale authorization16 rejected",()->store.bindDelegation(ac.commandId(),"old16-"+auth.delegation()));check(store.readExact(auth.scope(),auth.actor(),ac.commandId()).delegationId()==null,"authorization negatives retain null binding");
  check(store.bindDelegation(ac.commandId(),auth.delegation()).delegationId().equals(auth.delegation()),"Task5/auth17 exact delegation binds");
  attempt(auth,auth.attempt(),auth.delegation(),"A",1);
  refusal("wrong fencing token rejected",()->store.bindAttempt(ac.commandId(),new ProductComparisonStartCommands.AttemptBinding(auth.attempt(),2,UNTIL)));
  refusal("wrong lease rejected",()->store.bindAttempt(ac.commandId(),new ProductComparisonStartCommands.AttemptBinding(auth.attempt(),1,UNTIL.minusSeconds(1))));
  check(store.bindAttempt(ac.commandId(),new ProductComparisonStartCommands.AttemptBinding(auth.attempt(),1,UNTIL)).attemptId().equals(auth.attempt()),"native exact attempt/fence/lease binds");
  var unknown=store.markReconcile(ac.commandId(),"NATIVE_RECEIPT_LOST");check(unknown.attemptId().equals(auth.attempt())&&unknown.fencingToken()==1,"unknown retains original exact attempt");check(store.markReconcile(ac.commandId(),"LATER_ERROR").errorCode().equals("NATIVE_RECEIPT_LOST"),"unknown retains first error");
  main.update("UPDATE con3_task SET authorization_version=18 WHERE task_id=?",auth.task());refusal("currently stale bound delegation rejects",()->store.bindDelegation(ac.commandId(),auth.delegation()));
  var committed=fixture("receipt");var cc=bound(committed);refusal("caller result without native receipt cannot commit",()->store.commitFromExactReceipt(cc.commandId(),committed.report(),SHA));String reportSha=receipt(committed);
  refusal("wrong report SHA cannot commit",()->store.commitFromExactReceipt(cc.commandId(),committed.report(),SHA));main.update("UPDATE con5_matrix_invocation SET configuration_version='configuration-id-not-version' WHERE attempt_id=?",committed.attempt());refusal("native configuration id cannot substitute version",()->store.commitFromExactReceipt(cc.commandId(),committed.report(),reportSha));
  main.update("UPDATE con5_matrix_invocation SET configuration_version=? WHERE attempt_id=?",CONFIG,committed.attempt());
  String originalReceipt=main.queryForObject("SELECT receipt_json FROM con5_matrix_receipt WHERE attempt_id=?",String.class,committed.attempt());String wrong=originalReceipt.replace("\"candidateId\":\"A\"","\"candidateId\":\"B\"");main.update("UPDATE con5_matrix_receipt SET receipt_json=?,receipt_sha=? WHERE attempt_id=?",wrong,hash(wrong),committed.attempt());refusal("receipt self-consistent digest but wrong candidate rejected",()->store.commitFromExactReceipt(cc.commandId(),committed.report(),reportSha));
  main.update("UPDATE con5_matrix_receipt SET receipt_json=?,receipt_sha=? WHERE attempt_id=?",originalReceipt,hash(originalReceipt),committed.attempt());check(store.commitFromExactReceipt(cc.commandId(),committed.report(),reportSha).status().equals("COMMITTED"),"exact native report/receipt closure commits");check(store.commitFromExactReceipt(cc.commandId(),committed.report(),reportSha).status().equals("COMMITTED"),"exact commit replay does not duplicate");
  refusal("new key cannot bypass committed fence",()->store.reserveExact(committed.scope(),committed.actor(),"new-key",REQUEST,managed(),UNTIL));
  main.update("UPDATE con3_task SET cancel_epoch=1 WHERE task_id=?",committed.task());check(store.readExact(committed.scope(),committed.actor(),cc.commandId()).status().equals("CANCELLED"),"changed cancel epoch projects cancelled");check(status(cc.commandId()).equals("COMMITTED"),"cancel projection does not erase committed history");
  var bscope=new ProductComparisonStartCommands.Scope(committed.task(),committed.input(),"B",SHA,5,1);var bc=store.reserveExact(bscope,committed.actor(),"b-explicit",REQUEST,managed(),UNTIL).command();store.dispatchOnce(bc.commandId());String bd="b-"+committed.delegation(),ba="b-"+committed.attempt();delegation(committed,bd,17,"B");store.bindDelegation(bc.commandId(),bd);attempt(committed,ba,bd,"B",2);main.update("UPDATE con5_candidate_attempt SET cancel_epoch=1 WHERE attempt_id=?",ba);store.bindAttempt(bc.commandId(),new ProductComparisonStartCommands.AttemptBinding(ba,2,UNTIL));main.update("UPDATE con5_candidate_attempt SET status='FAILED' WHERE attempt_id=?",ba);main.update("UPDATE con5_candidate_lease SET status='FAILED' WHERE attempt_id=?",ba);main.update("UPDATE con5_comparison_candidate SET status='FAILED' WHERE comparison_id=? AND candidate_id='B'",committed.input());
  check(store.lookupOriginalKey(committed.task(),committed.actor(),"b-explicit").orElseThrow().status().equals("FAILED"),"known native B failure projects failed");check(!store.reserveExact(bscope,committed.actor(),"b-explicit",REQUEST,managed(),UNTIL).newlyReserved(),"B original key replay no redispatch");check(store.reserveExact(bscope,committed.actor(),"b-explicit-retry",REQUEST,managed(),UNTIL).newlyReserved(),"explicit new key may reserve B after exact known failure");check(status(cc.commandId()).equals("COMMITTED"),"B failure/retry retains A committed result");
  concurrent();
 }
 static void concurrent()throws Exception{
  var f=fixture("race");try(var pool=Executors.newFixedThreadPool(2)){
   var gate=new CyclicBarrier(2);Callable<ProductComparisonStartCommands.Reservation> action=()->{gate.await(10,TimeUnit.SECONDS);return reserve(f);};var a=pool.submit(action);var b=pool.submit(action);var x=a.get(30,TimeUnit.SECONDS);var y=b.get(30,TimeUnit.SECONDS);
   check(x.command().commandId().equals(y.command().commandId())&&((x.newlyReserved()?1:0)+(y.newlyReserved()?1:0)==1),"DM8 concurrent same-key reservation one durable winner");
   var gate2=new CyclicBarrier(2);Callable<Boolean> send=()->{gate2.await(10,TimeUnit.SECONDS);return store.dispatchOnce(x.command().commandId()).isPresent();};var aa=pool.submit(send);var bb=pool.submit(send);check((aa.get(30,TimeUnit.SECONDS)?1:0)+(bb.get(30,TimeUnit.SECONDS)?1:0)==1,"DM8 concurrent dispatch one durable winner");
  }
 }
 static void constraints()throws Exception{
  var f=fixture("constraints");var c=bound(f);String id=c.commandId();
  rolledBack("NULL configuration version rejected",true,"UPDATE con5_start_command SET configuration_version=NULL WHERE command_id=?",id);
  rolledBack("oversized configuration version rejected",true,"UPDATE con5_start_command SET configuration_version=? WHERE command_id=?","x".repeat(201),id);
  rolledBack("missing Input FK rejected",true,"UPDATE con5_start_command SET comparison_id='missing' WHERE command_id=?",id);
  rolledBack("missing candidate FK rejected",true,"UPDATE con5_start_command SET candidate_id='missing' WHERE command_id=?",id);
  rolledBack("missing delegation FK rejected",true,"UPDATE con5_start_command SET delegation_id='missing' WHERE command_id=?",id);
  rolledBack("missing attempt FK rejected",true,"UPDATE con5_start_command SET attempt_id='missing' WHERE command_id=?",id);
  rolledBack("business version zero rejected",true,"UPDATE con5_start_command SET task_version=0 WHERE command_id=?",id);
  rolledBack("negative cancel epoch rejected",true,"UPDATE con5_start_command SET cancel_epoch=-1 WHERE command_id=?",id);
  rolledBack("unknown status rejected",true,"UPDATE con5_start_command SET status='INVENTED' WHERE command_id=?",id);
  rolledBack("deadline equal creation rejected",true,"UPDATE con5_start_command SET expires_at=created_at WHERE command_id=?",id);
  rolledBack("attempt present but lease NULL rejected",true,"UPDATE con5_start_command SET lease_until=NULL WHERE command_id=?",id);
  rolledBack("attempt present but delegation NULL rejected",true,"UPDATE con5_start_command SET delegation_id=NULL WHERE command_id=?",id);
  rolledBack("attempt present but fence zero rejected",true,"UPDATE con5_start_command SET fencing_token=0 WHERE command_id=?",id);
  rolledBack("attempt present but fence NULL must be rejected",true,"UPDATE con5_start_command SET fencing_token=NULL WHERE command_id=?",id);
  rolledBack("COMMITTED with fence NULL must be rejected",true,"UPDATE con5_start_command SET status='COMMITTED',fencing_token=NULL WHERE command_id=?",id);
  rolledBack("NULL attempt with remaining binding rejected",true,"UPDATE con5_start_command SET attempt_id=NULL WHERE command_id=?",id);
  var noAttempt=fixture("nulls");var plain=reserve(noAttempt).command();
  rolledBack("no attempt COMMITTED rejected",true,"UPDATE con5_start_command SET status='COMMITTED' WHERE command_id=?",plain.commandId());
  rolledBack("fence without attempt rejected",true,"UPDATE con5_start_command SET fencing_token=1 WHERE command_id=?",plain.commandId());
  rolledBack("lease without attempt rejected",true,"UPDATE con5_start_command SET lease_until=? WHERE command_id=?",ts(UNTIL),plain.commandId());
  rolledBack("all nullable binding fields absent allowed",false,"UPDATE con5_start_command SET delegation_id=NULL,attempt_id=NULL,fencing_token=NULL,lease_until=NULL WHERE command_id=?",plain.commandId());
  rolledBack("original Task issuer human request unique",true,"INSERT INTO con5_start_command SELECT ?,task_id,comparison_id,candidate_id,input_sha,task_version,cancel_epoch,actor_issuer,actor_subject,request_id,request_sha,service_subject,configuration_version,delegation_id,attempt_id,fencing_token,lease_until,status,error_code,created_at,expires_at FROM con5_start_command WHERE command_id=?",UUID.randomUUID().toString(),plain.commandId());
 }
 static List<String> reverseStatements()throws Exception{
  String u=Files.readString(Path.of("db/dm8/U102__product_comparison_start_command.sql")).replaceAll("(?m)^\\s*--[^\\r\\n]*","");
  String[] parts=u.split("(?m)^\\s*/\\s*$");require(parts.length==2,"fixed U102 contains guard before DROP");return List.of(parts[0].trim(),parts[1].trim().replaceFirst(";\\s*$",""));
 }
 static void reverse()throws Exception{
  String before=JSON.writeValueAsString(snapshot(main,tables()));var statements=reverseStatements();int executed=0;String code="no error";boolean denied=false;
  try{var owner=new JdbcTemplate(ds("STARTMAIN","STARTMAIN"));for(String s:statements){owner.execute(s);executed++;}}
  catch(org.springframework.dao.DataAccessException refusal){denied=true;var cause=refusal.getMostSpecificCause();code=cause instanceof SQLException e?"sqlState="+e.getSQLState()+" code="+e.getErrorCode()+" message="+e.getMessage():cause.getMessage();}
  check(denied&&executed==0,"nonempty fixed U102 guard refuses before DROP");require(JSON.writeValueAsString(snapshot(main,tables())).equals(before),"nonempty inverse preserves all command histories and table digests");
  write("nonempty-u102-result.json",Map.of("guardRejected",denied,"successfulStatements",executed,"error",code,"allTableDigestsUnchanged",true,"dropExecuted",executed>1));
  require(empty.queryForObject("SELECT COUNT(*) FROM con5_start_command",Integer.class)==0,"STARTEMPTY inverse has genuinely empty command history");
  var eo=new JdbcTemplate(ds("STARTEMPTY","STARTEMPTY"));for(String s:statements)eo.execute(s);
  try(var c=ds("STARTEMPTY_SVC","STARTEMPTY").getConnection();var rs=c.getMetaData().getTables(null,"STARTEMPTY","CON5_START_COMMAND",new String[]{"TABLE"})){check(!rs.next(),"empty U102 drops only own STARTEMPTY command table");}
  write("empty-u102-result.json",Map.of("emptyGuardSucceeded",true,"dropSucceeded",true,"schema","STARTEMPTY","remainingTables",tables().size()-1));
  refusalData("missing V102 fails frozen store constructor closed",()->new ProductComparisonStartCommands(empty,new TransactionTemplate(new DataSourceTransactionManager(empty.getDataSource())),Clock.fixed(NOW,ZoneOffset.UTC)));
 }
 static void refusalData(String name,Action a)throws Exception{
  try{a.run();check(false,name);}catch(org.springframework.dao.DataAccessException ex){var cause=ex.getMostSpecificCause();checks.add(Map.of("check",name,"passed",true,"sqlError",cause instanceof SQLException e?"state="+e.getSQLState()+" code="+e.getErrorCode():cause.getClass().getSimpleName()));}
 }
 static void runtimeIsolation()throws Exception{
  refusalData("STARTMAIN runtime cannot read STARTEMPTY parent table",()->main.queryForObject("SELECT COUNT(*) FROM STARTEMPTY.con3_task",Integer.class));
  refusalData("STARTEMPTY runtime cannot read STARTMAIN command history",()->empty.queryForObject("SELECT COUNT(*) FROM STARTMAIN.con5_start_command",Integer.class));
  refusalData("STARTMAIN runtime cannot write STARTEMPTY metadata",()->main.update("UPDATE STARTEMPTY.con3_task SET version=version"));
  refusalData("runtime CREATE TABLE denied in current owner schema",()->main.execute("CREATE TABLE runtime_must_not_create(id BIGINT)"));
  refusalData("runtime ALTER TABLE denied",()->main.execute("ALTER TABLE con5_start_command ADD runtime_must_not_add BIGINT"));
 }
 static Map<String,Object> snapshot(JdbcTemplate db,List<String> tables)throws Exception{
  var result=new TreeMap<String,Object>();for(String t:tables){
   var rows=db.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>) c->{
    var list=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+t)){
     var meta=r.getMetaData();while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=meta.getColumnCount();i++)row.add(meta.getColumnType(i)==Types.TIMESTAMP?(r.getTimestamp(i)==null?null:r.getTimestamp(i).toLocalDateTime().toString()):r.getString(i));
      try{list.add(JSON.writeValueAsString(row));}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new SQLException("Snapshot serialization",ex);}
     }
    }Collections.sort(list);return list;
   });result.put(t,Map.of("rows",rows.size(),"sortedRowSha256",hash(JSON.writeValueAsBytes(rows))));
  }return result;
 }
 static Map<String,Object> restartReceipt(String command,Fixture f){return Map.of("commandId",command,"task",f.task(),"input",f.input(),"candidate","A","inputSha",SHA,"taskVersion",5,"cancelEpoch",0,"issuer",f.actor().issuer(),"subject",f.actor().subject(),"key",f.key());}
 static void prepareRestart()throws Exception{
  var f=fixture("persistent");var c=bound(f);var u=store.markReconcile(c.commandId(),"PERSISTENT_RECEIPT_LOST");require(u.status().equals("RECONCILE_REQUIRED"),"restart fixture is durable unknown exact attempt");
  write("restart-command-coordinate.json",restartReceipt(c.commandId(),f));write("main-before-restart-baseline.json",snapshot(main,tables()));
 }
 static void afterRestart()throws Exception{
  var before=JSON.readTree(Files.readAllBytes(out.resolve("main-before-restart-baseline.json")));check(before.equals(JSON.readTree(JSON.writeValueAsBytes(snapshot(main,tables())))),"real stop/start fresh JVM preserves all19 main table digests");
  var saved=JSON.readTree(Files.readAllBytes(out.resolve("restart-command-coordinate.json")));String id=saved.path("commandId").asText();var scope=new ProductComparisonStartCommands.Scope(saved.path("task").asText(),saved.path("input").asText(),"A",SHA,5,0);var actor=new ProductComparisonStartCommands.Actor(saved.path("issuer").asText(),saved.path("subject").asText());
  var c=store.readExact(scope,actor,id);check(c.status().equals("RECONCILE_REQUIRED")&&c.attemptId()!=null&&c.fencingToken()==1,"persistent unknown exact attempt retained after process restart");
  check(!store.reserveExact(scope,actor,saved.path("key").asText(),REQUEST,managed(),UNTIL).newlyReserved(),"after-restart original key does not reserve again");check(store.dispatchOnce(id).isEmpty(),"after-restart unknown does not redispatch");
  refusal("after-restart new key cannot bypass unknown fence",()->store.reserveExact(scope,actor,"persistent-new-key",REQUEST,managed(),UNTIL));
  check(before.equals(JSON.readTree(JSON.writeValueAsBytes(snapshot(main,tables())))),"after-restart refusal and reads do not rewrite original history");
 }
 static void diagnostics()throws Exception{
  var owner=new JdbcTemplate(ds("STARTMAIN","STARTMAIN"));
  var facts=new LinkedHashMap<String,Object>();
  try{facts.put("constraintDictionary",owner.queryForList("SELECT OWNER,TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,R_OWNER,R_CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE OWNER='STARTMAIN' AND TABLE_NAME='CON5_START_COMMAND'"));}
  catch(org.springframework.dao.DataAccessException ex){facts.put("dictionaryUnavailable",ex.getMostSpecificCause().getClass().getSimpleName());}
  write("v102-constraint-dictionary.json",facts);
  String id=main.queryForObject("SELECT command_id FROM con5_start_command WHERE request_id LIKE 'key-constraints-%'",String.class);
  require(id!=null,"existing own constraint fixture only");
  var before=JSON.writeValueAsString(snapshot(main,tables()));
  rolledBack("actual composite Task Input FK refuses foreign task with existing comparison",true,"UPDATE con5_start_command SET task_id='nonexistent-engineering-task' WHERE command_id=?",id);
  rolledBack("actual Input FK refuses mismatched Task of another existing Input",true,"UPDATE con5_start_command SET task_id=(SELECT task_id FROM con5_comparison_input WHERE comparison_id LIKE 'i-replay-%') WHERE command_id=?",id);
  check(before.equals(JSON.writeValueAsString(snapshot(main,tables()))),"dictionary/foreign Task probes leave all histories unchanged");
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("Phase required");String phase=args[0];out=Path.of(required("V102_ENGINEERING_OUTPUT")).toAbsolutePath().normalize();
  require(required("V102_ENGINEERING_OWNER").equals(TASK),"explicit engineering task owner");require(required("V102_ENGINEERING_URL").equals(URL),"exclusive owned loopback5492");require(out.equals(Path.of("docs/evidence/single-database-persistent-20261007/v102-engineering-01").toAbsolutePath()),"exclusive evidence path");
  try{
   if(phase.equals("initialize"))initialize();else if(!phase.equals("after-restart")&&!phase.equals("diagnostic"))throw new IllegalArgumentException("Phase unknown");
   try(var readiness=readyAdmin()){ check(true,"actual DM8 JDBC ready before service phase"); }
   var ds=ds("STARTMAIN_SVC","STARTMAIN");main=new JdbcTemplate(ds);empty=new JdbcTemplate(ds("STARTEMPTY_SVC","STARTEMPTY"));tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new ProductComparisonStartCommands(main,tx,Clock.fixed(NOW,ZoneOffset.UTC));
   if(phase.equals("initialize")){physical();storageProtocols();constraints();runtimeIsolation();reverse();prepareRestart();}else if(phase.equals("diagnostic"))diagnostics();else afterRestart();
   long failed=checks.stream().filter(c->!Boolean.TRUE.equals(c.get("passed"))).count();write(phase+"-result.json",Map.of("task",TASK,"phase",phase,"actualDm8",true,"recordedAt",Instant.now().toString(),"checks",checks,"passedChecks",checks.size()-failed,"failedChecks",failed,"scope","FIXED_MIGRATION_AND_FROZEN_STORE_ENGINEERING_ONLY_NOT_PRODUCTION_MIGRATION_OR_API_MODEL"));
   System.out.println("PHASE="+phase+" PASSED="+(checks.size()-failed)+" FAILED="+failed+" ACTUAL_DM8=true");if(failed>0)System.exit(2);
  }catch(Throwable ex){
   // Managed passwords never appear in published errors or SQL DDL logs.
   String message=String.valueOf(ex.getMessage()).replaceAll("(?i)IDENTIFIED BY.+","[secret redacted]");
   write(phase+"-fatal-result.json",Map.of("task",TASK,"phase",phase,"completedChecks",checks,"fatalType",ex.getClass().getName(),"message",message,"claimComplete",false));System.err.println("FATAL phase="+phase+" type="+ex.getClass().getSimpleName());System.exit(1);
  }
 }
}
