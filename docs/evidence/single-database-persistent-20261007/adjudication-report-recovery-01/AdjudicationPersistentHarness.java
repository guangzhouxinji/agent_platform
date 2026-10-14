package cn.xinji.agentplatform.adjudication.casework;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.io.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
public final class AdjudicationPersistentHarness {
 static final String TASK="VER-ADJ-SINGLE-DB-REPORT-RECOVERY-01",CONTAINER="agent-platform-single-persistent-primary-dm8-02-20261007";
 static final List<String> TABLES=List.of("adj2_case_work","adj2_case_original","adj2_case_revision","adj2_case_audit","adj2_case_receipt","adj2_case_command_lock");
 static final List<String> AUTH_TABLES=List.of("adj2_source_generation","adj2_source_use","adj2_source_event");
 static final List<String> PROTECTED=List.of("CONPROD","CONAUTH","ENFPROD","ENFAUTH","ADJPROD","ADJAUTH","SRCMAIN","SRCAUTH","STARTMAIN","STARTEMPTY");
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final CaseWorkCodec CODEC=new CaseWorkCodec(JSON);static final Clock CLOCK=Clock.systemUTC();
 static final List<Map<String,Object>> checks=new ArrayList<>();static Path out;static JsonNode pack;static CaseInput input;static Scope scope;
 static String env(String k){String v=System.getenv(k);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing private setting "+k);return v;}
 static JdbcTemplate db(String user,String schema){return new JdbcTemplate(new DriverManagerDataSource("jdbc:dm://127.0.0.1:5492",user,env("ADJ_"+user+"_PASSWORD")){
  @Override public Connection getConnection()throws SQLException{var c=super.getConnection();try{c.setSchema(schema);return c;}catch(SQLException e){c.close();throw e;}}
 });}
 static TransactionTemplate tx(JdbcTemplate db){return new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));}
 static JdbcCaseWorkRepository repo(JdbcTemplate db){return new JdbcCaseWorkRepository(db,tx(db),CODEC,CLOCK);}
 static CaseSourceAuthority authority(){var db=db("ADJENGAUTH_SVC","ADJENGAUTH");return new CaseSourceAuthority(db,tx(db),CODEC,CLOCK);}
 static CaseWorkCommands commands(JdbcCaseWorkRepository repo)throws Exception{return new CaseWorkCommands(repo,CODEC,CaseReportPolicy.load(CODEC,"backend/apps/platform-api/src/main/resources/adjudication/report-policy-v2.json",500000),CLOCK);}
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static void write(String name,Object value)throws Exception{Files.write(out.resolve(name),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}
 static void check(boolean yes,String label){checks.add(Map.of("check",label,"passed",yes));if(!yes)throw new AssertionError(label);}
 interface Action{void run()throws Exception;}
 static void deny(String name,Action action)throws Exception{try{action.run();}catch(SecurityException|IllegalArgumentException|IllegalStateException e){checks.add(Map.of("check",name,"passed",true,"exceptionType",e.getClass().getName(),"message",String.valueOf(e.getMessage())));return;}throw new AssertionError("Expected refusal "+name);}
 static void ddl(JdbcTemplate db,String file)throws Exception{String s=Files.readString(Path.of(file)).replaceAll("(?m)^\\s*--[^\\r\\n]*","");for(String q:s.split(";"))if(!q.isBlank())db.execute(q);}
 static void grant(JdbcTemplate owner,String who,List<String> tables){for(String t:tables)owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE,REFERENCES ON "+t+" TO "+who);}
 static final class ScopedAccess implements Access {
  final CaseSourceAuthority auth=authority();final Set<String> allowed=Set.of("CREATE","READ","WRITE");
  public String actor(){return "owned-engineering-author-not-jwt";}
  public String identity(){return actor();}
  public void require(Scope s,String action){if(!scope.equals(s)||!allowed.contains(action))throw new SecurityException("EXACT_ENGINEERING_SCOPE_ACTION_REQUIRED");auth.generation();}
  public long generation(){return auth.generation();}public void assertUnchanged(long g){auth.assertUnchanged(g);}
  public void sources(Scope s,List<Source> src,String use){require(s,"READ");if(!Set.of("READ","SAVE","ANALYZE").contains(use))throw new SecurityException("MODEL_OR_OTHER_USE_FORBIDDEN");auth.require(s,src,use);}
 }
 static Map<String,Object> snapshot(JdbcTemplate db,List<String> tables)throws Exception{
  var result=new TreeMap<String,Object>();for(String t:tables){var rows=db.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>)c->{var list=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+t)){var m=r.getMetaData();while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=m.getColumnCount();i++)row.add(m.getColumnType(i)==Types.TIMESTAMP?(r.getTimestamp(i)==null?null:r.getTimestamp(i).toLocalDateTime().toString()):r.getString(i));try{list.add(JSON.writeValueAsString(row));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new SQLException("Snapshot JSON encoding",e);}}}Collections.sort(list);return list;});result.put(t,Map.of("rows",rows.size(),"sortedRowSha256",sha(JSON.writeValueAsBytes(rows))));}return result;
 }
 static Map<String,Object> protectedSnapshot()throws Exception{
  var admin=db("SYSDBA","SYSDBA");var all=new TreeMap<String,Object>();for(String s:PROTECTED){var names=admin.queryForList("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME",String.class,s);var qualified=names.stream().map(t->s+"."+t).toList();all.putAll(snapshot(admin,qualified));}return all;
 }
 static void load()throws Exception{
  pack=JSON.readTree(Files.readAllBytes(out.resolve("owned-input/records.json")));
  if("r3".equals(System.getenv("ADJ_RUN_COORDINATE"))){pack=pack.deepCopy();String id=pack.path("caseInput").path("id").asText()+"-r3";((ObjectNode)pack.path("caseInput")).put("id",id);for(var m:pack.path("materials"))((ObjectNode)m).put("caseId",id);((ObjectNode)pack.path("claim")).put("caseId",id);if(!Files.exists(out.resolve("engineering-coordinate-mapping.json")))write("engineering-coordinate-mapping.json",Map.of("originalCaseId","engineering-case-001","runCaseId",id,"reason","Preserve failed engineering Access identity/actor history; new coordinate only, no input content or source SHA change","originalRecordsSha256",sha(Files.readAllBytes(out.resolve("owned-input/records.json")))));}
  input=CODEC.request(pack.path("caseInput"),CaseInput.class);scope=Scope.of(input);
  var manifest=JSON.readTree(Files.readAllBytes(out.resolve("owned-input/manifest.json")));check(manifest.path("licenseAndPurpose").path("allowed").toString().equals("[\"MECHANICAL_DOMAIN_TEST\"]"),"exact approved original mechanical-only purpose");check(sha(Files.readAllBytes(out.resolve("owned-input/records.json"))).equals(manifest.path("files").get(0).path("sha256").asText()),"records bytes match original licensed manifest");
 }
 static void initialize(boolean resume)throws Exception{
 if(!resume){
  write("protected-before.json",protectedSnapshot());var admin=db("SYSDBA","SYSDBA");var users=List.of("ADJENGMAIN","ADJENGMAIN_SVC","ADJENGREST","ADJENGREST_SVC","ADJENGAUTH","ADJENGAUTH_SVC");for(String u:users)check(admin.queryForObject("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?",Integer.class,u)==0,"fresh owned user absent "+u);write("fresh-users-before-ddl.json",Map.of("task",TASK,"namesAbsent",users));
  for(String u:users){String p=env("ADJ_"+u+"_PASSWORD");if(!p.matches("[A-Za-z0-9_]{32,48}"))throw new IllegalArgumentException("Generated private password required");admin.execute("CREATE USER "+u+" IDENTIFIED BY \""+p+"\"");}for(String s:List.of("ADJENGMAIN","ADJENGREST","ADJENGAUTH"))admin.execute("GRANT RESOURCE TO "+s);
  var main=db("ADJENGMAIN","ADJENGMAIN");ddl(main,"db/dm8/V086__adjudication_case_work.sql");grant(main,"ADJENGMAIN_SVC",TABLES);
  // Restore schema deliberately empty before native import: no seeded business results or lock rows.
  var auth=db("ADJENGAUTH","ADJENGAUTH");ddl(auth,"db/dm8/adjudication_source_authority.sql");grant(auth,"ADJENGAUTH_SVC",AUTH_TABLES);
  }else{
   var admin=db("SYSDBA","SYSDBA");for(String u:List.of("ADJENGMAIN","ADJENGMAIN_SVC","ADJENGREST","ADJENGREST_SVC","ADJENGAUTH","ADJENGAUTH_SVC"))check(admin.queryForObject("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?",Integer.class,u)==1,"prior owned user retained "+u);
   check(db("ADJENGMAIN","ADJENGMAIN").queryForObject("SELECT COUNT(*) FROM adj2_case_work WHERE case_id=?",Integer.class,input.id())==0,"new run coordinate has no business case, prior failed history retained");
   check(authority().generation()==4,"prior four engineering source permissions retained, no rollback or restoration");
   grant(db("ADJENGMAIN","ADJENGMAIN"),"ADJENGMAIN_SVC",TABLES);grant(db("ADJENGAUTH","ADJENGAUTH"),"ADJENGAUTH_SVC",AUTH_TABLES);
   write("runtime-reference-grant-round3.json",Map.of("scope","Only own new MAIN/AUTH tables REFERENCES plus original DML; no DDL/global/cross-domain grants","cause","DM8 runtime FK DML requires REFERENCES, actual error -5506 in preserved initial result"));
  }
  var authority=authority();var sources=new LinkedHashSet<Source>();sources.add(input.source());for(var m:pack.path("materials"))sources.add(CODEC.request(m,Material.class).source());sources.add(CODEC.request(pack.path("claim"),Claim.class).source());
  String evidence=sha(Files.readAllBytes(out.resolve("owned-input/manifest.json")));var receipts=new ArrayList<Object>();for(Source source:sources)receipts.add(authority.register(new CaseSourceAuthority.Use(scope,source,Set.of("READ","SAVE","ANALYZE"),CLOCK.instant().plusSeconds(86400),"user-20261007-registered-MECHANICAL_DOMAIN_TEST-original-manifest",evidence),"owned-engineering-maintainer-not-production"));write("source-register-receipts-round3.json",receipts);
  var access=new ScopedAccess();deny("wrong case scope denied",()->access.require(new Scope("different-case",scope.organization(),scope.dataScope()),"READ"));deny("MODEL use denied",()->access.sources(scope,List.of(input.source()),"MODEL"));
  var runtime=db("ADJENGMAIN_SVC","ADJENGMAIN");var repo=repo(runtime);var commands=commands(repo);var commandReceipts=new ArrayList<Receipt>();var create=JSON.createObjectNode();create.set("input",pack.path("caseInput"));create.put("originalText",pack.path("caseOriginalText").asText());var first=commands.create(create,"adj-r3-create-01",access);commandReceipts.add(first);check(commands.create(create,"adj-r3-create-01",access).equals(first),"exact create replay original receipt");
  long version=first.workVersion();for(var material:pack.path("materials")){var body=JSON.createObjectNode().put("expectedVersion",version);body.set("material",material);var receipt=commands.execute(input.id(),"MATERIAL",null,body,"adj-r3-material-"+version,access);commandReceipts.add(receipt);version=receipt.workVersion();}
  var claim=JSON.createObjectNode().put("expectedVersion",version);claim.set("claim",pack.path("claim"));var cr=commands.execute(input.id(),"CLAIM",null,claim,"adj-r3-claim-01",access);commandReceipts.add(cr);
  var report=commands.execute(input.id(),"GENERATE_REPORT",null,JSON.createObjectNode().put("expectedVersion",cr.workVersion()),"adj-r3-report-01",access);commandReceipts.add(report);write("product-command-receipts.json",commandReceipts);
  var state=repo.read(input.id(),null,access);check(state.materials().size()==2&&state.claims().size()==1,"actual product persisted two materials and claim");check(!state.reports().isEmpty()&&state.reports().getLast().sections().stream().allMatch(s->!s.content().isBlank()),"actual deterministic product nonempty report generated");check(!state.reports().getLast().complete(),"missing legal/context/professional evidence stays blocking");check(state.citations().isEmpty()&&state.findings().isEmpty(),"no legal source/professional findings invented");
  write("product-active-state.json",state);write("product-active-originals.json",repo.originals(input.id(),access));write("product-active-versions.json",repo.versions(input.id(),access));write("main-active-baseline.json",snapshot(runtime,TABLES));write("authority-active-baseline.json",snapshot(db("ADJENGAUTH_SVC","ADJENGAUTH"),AUTH_TABLES));
  deny("runtime cannot read authority schema",()->{try{runtime.queryForObject("SELECT COUNT(*) FROM ADJENGAUTH.adj2_source_use",Integer.class);}catch(org.springframework.dao.DataAccessException e){throw new SecurityException("runtime cross-schema refused",e);}});
  check(JSON.valueToTree(protectedSnapshot()).equals(JSON.readTree(Files.readAllBytes(out.resolve("protected-before.json")))),"SRC START original36 engineering histories unchanged after initialization");
 }
 static void afterRestart()throws Exception{
  var runtime=db("ADJENGMAIN_SVC","ADJENGMAIN");check(JSON.valueToTree(snapshot(runtime,TABLES)).equals(JSON.readTree(Files.readAllBytes(out.resolve("main-active-baseline.json")))),"actual DM8 stop/start all six business digests retained");check(JSON.valueToTree(snapshot(db("ADJENGAUTH_SVC","ADJENGAUTH"),AUTH_TABLES)).equals(JSON.readTree(Files.readAllBytes(out.resolve("authority-active-baseline.json")))),"actual DM8 stop/start current authority retained");var repo=repo(runtime);var access=new ScopedAccess();check(repo.read(input.id(),null,access).equals(CODEC.decode(Files.readString(out.resolve("product-active-state.json")),State.class)),"fresh JVM product report exact after persistent restart");check(repo.versions(input.id(),access).equals(Arrays.asList(CODEC.decode(Files.readString(out.resolve("product-active-versions.json")),Version[].class))),"fresh JVM product immutable versions after restart");
 }
 static void revoke()throws Exception{
  var auth=authority();long prior=auth.generation();var receipt=auth.revoke(auth.key(scope,input.source()),1,"owned-engineering-current-withdrawal");write("current-withdrawal-receipt.json",receipt);check(auth.generation()==prior+1,"real current generation advances on withdrawal");var repo=repo(db("ADJENGMAIN_SVC","ADJENGMAIN"));deny("withdrawn source denies main product read",()->repo.read(input.id(),null,new ScopedAccess()));write("authority-withdrawn-baseline.json",snapshot(db("ADJENGAUTH_SVC","ADJENGAUTH"),AUTH_TABLES));
 }
 static void afterRestore()throws Exception{
  var owner=db("ADJENGREST","ADJENGREST");grant(owner,"ADJENGREST_SVC",TABLES);var restored=db("ADJENGREST_SVC","ADJENGREST");var rowSnapshot=snapshot(restored,TABLES);write("restored-business-baseline.json",rowSnapshot);check(JSON.valueToTree(rowSnapshot).equals(JSON.readTree(Files.readAllBytes(out.resolve("main-active-baseline.json")))),"native restored all six business row digests identical");
  var repo=repo(restored);var access=new ScopedAccess();deny("restored old business cannot revive withdrawn current read",()->repo.read(input.id(),null,access));deny("restored old business cannot revive original read",()->repo.originals(input.id(),access));deny("restored old business cannot revive history read",()->repo.versions(input.id(),access));
  var payload=restored.queryForObject("SELECT payload FROM adj2_case_revision WHERE case_id=? AND work_version=(SELECT head_version FROM adj2_case_work WHERE case_id=?)",String.class,input.id(),input.id());var decoded=CODEC.decode(payload,State.class);CODEC.validate(decoded,scope);check(decoded.equals(CODEC.decode(Files.readString(out.resolve("product-active-state.json")),State.class)),"product codec validates exact restored historical report without granting read");
  check(JSON.valueToTree(snapshot(db("ADJENGAUTH_SVC","ADJENGAUTH"),AUTH_TABLES)).equals(JSON.readTree(Files.readAllBytes(out.resolve("authority-withdrawn-baseline.json")))),"AUTH current withdrawn generation/event never restored or regressed");
  for(String file:List.of("manifest.json","records.json"))check(sha(Files.readAllBytes(out.resolve("authenticated-restored-input/"+file))).equals(sha(Files.readAllBytes(out.resolve("owned-input/"+file)))),"authenticated original bytes equal "+file);
  check(JSON.valueToTree(protectedSnapshot()).equals(JSON.readTree(Files.readAllBytes(out.resolve("protected-before.json")))),"all protected SRC START and original36 histories unchanged after native restore");write("protected-after.json",protectedSnapshot());
 }
 public static void main(String[] args)throws Exception{
  out=Path.of(env("ADJ_OUTPUT"));String phase=args[0];int code=0;
  try{load();switch(phase){case "initialize"->initialize(false);case "initialize-resume"->initialize(true);case "initialize-run3"->initialize(true);case "after-restart"->afterRestart();case "after-restart-typed"->afterRestart();case "revoke"->revoke();case "after-restore"->afterRestore();default->throw new IllegalArgumentException("Unknown phase");}}
  catch(Throwable e){code=2;var trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));String sanitized=trace.toString();for(var entry:System.getenv().entrySet())if(entry.getKey().startsWith("ADJ_")&&(entry.getKey().endsWith("_PASSWORD")||entry.getKey().endsWith("_KEY")))sanitized=sanitized.replace(entry.getValue(),"[REDACTED]");checks.add(Map.of("check","phase actual exception","passed",false,"type",e.getClass().getName(),"sanitizedStack",sanitized));}
  long failed=checks.stream().filter(c->Boolean.FALSE.equals(c.get("passed"))).count();write(phase+"-result.json",Map.of("task",TASK,"phase",phase,"passed",checks.size()-failed,"failed",failed,"exitCode",code,"actualDm8",true,"checks",checks));System.out.println(phase+" passed="+(checks.size()-failed)+" failed="+failed+" exit="+code);System.exit(code);
 }
}





