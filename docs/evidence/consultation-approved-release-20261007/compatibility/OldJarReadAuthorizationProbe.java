import java.nio.file.*;
import java.net.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import java.lang.reflect.*;
import java.security.MessageDigest;

/** Actual old Jar read/authorization code, two fresh H2 databases, no service/network or real identity. */
public class OldJarReadAuthorizationProbe {
 static Path out; static List<String> events=new ArrayList<>();static int failures=0;static final String ISSUER="https://offline.invalid";
 static String q(String s){return MigrationCompatibilityProbe.q(s);}
 static void result(String id,boolean pass,String detail){if(!pass)failures++;events.add("{\"id\":"+q(id)+",\"passed\":"+pass+",\"detail\":"+q(detail)+"}");}
 static class FixedOldLoader extends URLClassLoader {
  final ZipFile jar;final Map<String,String>loaded=new TreeMap<>();
  FixedOldLoader(Path path,URL[]libs)throws Exception{super(libs,OldJarReadAuthorizationProbe.class.getClassLoader());jar=new ZipFile(path.toFile());}
  protected Class<?>findClass(String name)throws ClassNotFoundException{
   var entry=jar.getEntry("BOOT-INF/classes/"+name.replace('.','/')+".class");if(entry!=null)try{byte[]b=jar.getInputStream(entry).readAllBytes();loaded.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)));return defineClass(name,b,0,b.length);}catch(Exception e){throw new ClassNotFoundException(name,e);}return super.findClass(name);
  }
  public void close()throws java.io.IOException{super.close();jar.close();}
 }
 static void update(Connection c,String sql,Object...args)throws Exception{try(var ps=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);ps.executeUpdate();}}
 static class Fixture implements AutoCloseable {
  final Connection c,a;final FixedOldLoader loader;final Object jdbc,authority,watermark,access,directory;final Class<?>factsClass,actionClass,jwtClass;final Method permits,digest;final Instant now=Instant.now();long generation=1;
  Fixture(FixedOldLoader loader)throws Exception{
   this.loader=loader;c=MigrationCompatibilityProbe.db();a=MigrationCompatibilityProbe.db();for(Path p:MigrationCompatibilityProbe.paths("baseline-paths.txt"))MigrationCompatibilityProbe.apply(c,p);MigrationCompatibilityProbe.supplement(c);MigrationCompatibilityProbe.supplement(a,"authority-columns.tsv");
   Timestamp start=Timestamp.from(now.minusSeconds(14400)),end=Timestamp.from(now.plusSeconds(3600));
   update(c,"INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,'actor','offline-actor','not-an-authentication-hash','ACTIVE',1,?)",ISSUER,start);
   update(c,"INSERT INTO id2_key_control VALUES(?,NULL,0)",ISSUER);
   update(c,"INSERT INTO id2_signing_key VALUES('k',?,?,?,?,'ACTIVE',?,NULL)",ISSUER,new byte[]{1},new byte[]{1},new byte[12],start);
   update(c,"INSERT INTO id2_session VALUES('sid','jti',?,'actor','offline-client','k',?,?,'ACTIVE',NULL)",ISSUER,start,end);
   update(c,"INSERT INTO con3_organization VALUES('org','offline','ACTIVE',1,?)",start);
   update(c,"INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,expires_at,retention_ref,retention_version,retention_evidence_sha,created_at,authorization_version) VALUES('t','org',?,'actor','AWAITING_GOAL',1,?,'offline',1,?,?,1)",ISSUER,end,"a".repeat(64),start);
   update(c,"INSERT INTO con3_data_scope VALUES('scope','t','org',1,'TASK_METADATA')");
   update(c,"INSERT INTO id2_grant VALUES('g',?,'actor','org','CONSULTATION','t','scope','TASK','t',1,'READ','TASK_WORK',1,?,?,'ACTIVE',1)",ISSUER,start,end);
   update(c,"INSERT INTO id2_current_epoch VALUES(?,'actor','org','CONSULTATION','TASK','t','READ','TASK_WORK',1,0,'g')",ISSUER);
   Class<?> jdbcClass=loader.loadClass("org.springframework.jdbc.core.JdbcTemplate");jdbc=jdbcClass.getConstructor(javax.sql.DataSource.class).newInstance(new MigrationCompatibilityProbe.MemoryDataSource(c));authority=jdbcClass.getConstructor(javax.sql.DataSource.class).newInstance(new MigrationCompatibilityProbe.MemoryDataSource(a));
   Class<?> digestClass=loader.loadClass("cn.xinji.agentplatform.security.product.ProductGrantHistoryBatch");digest=digestClass.getDeclaredMethod("digest",jdbcClass,String.class,boolean.class,boolean.class);digest.setAccessible(true);
   update(a,"INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) VALUES(?,1,'READY',NULL,?)",ISSUER,digest.invoke(null,jdbc,ISSUER,true,true));
   Class<?> wm=loader.loadClass("cn.xinji.agentplatform.security.product.ProductGrantWatermark");watermark=wm.getConstructor(jdbcClass,jdbcClass,String.class).newInstance(jdbc,authority,ISSUER);
   Class<?> ac=loader.loadClass("cn.xinji.agentplatform.security.product.ProductConsultationAccess");access=ac.getConstructor(jdbcClass,wm,String.class,Clock.class).newInstance(jdbc,watermark,ISSUER,Clock.systemUTC());
   factsClass=loader.loadClass("cn.xinji.agentplatform.security.product.ProductConsultationAccess$ResourceFacts");actionClass=loader.loadClass("cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy$Action");jwtClass=loader.loadClass("org.springframework.security.oauth2.jwt.Jwt");permits=ac.getMethod("permits",jwtClass,factsClass,actionClass);
   Class<?> dir=loader.loadClass("cn.xinji.agentplatform.security.product.JdbcProductTaskDirectory");directory=dir.getConstructor(jdbcClass,Clock.class).newInstance(jdbc,Clock.systemUTC());
  }
  Object jwt(String issuer,String subject,String sid)throws Exception{Map<String,Object>claims=new HashMap<>();claims.put("iss",issuer);claims.put("sub",subject);claims.put("jti","jti");if(sid!=null)claims.put("sid",sid);return jwtClass.getConstructor(String.class,Instant.class,Instant.class,Map.class,Map.class).newInstance("offline-not-signed",now.minusSeconds(60),now.plusSeconds(3600),Map.of("alg","none"),claims);}
  Object facts(String org,String task,String scope,long version)throws Exception{return factsClass.getConstructor(String.class,String.class,String.class,String.class,String.class,long.class).newInstance(org,task,scope,"TASK","t",version);}
  @SuppressWarnings({"unchecked","rawtypes"}) boolean read(Object jwt,Object facts)throws Exception{return (boolean)permits.invoke(access,jwt,facts,Enum.valueOf((Class)actionClass,"READ"));}
  boolean read()throws Exception{return read(jwt(ISSUER,"actor","sid"),facts("org","t","scope",1));}
  void refresh()throws Exception{update(a,"UPDATE id2_authority_watermark SET history_sha=?,generation=?,state='READY',intent_id=NULL WHERE issuer=?",digest.invoke(null,jdbc,ISSUER,true,true),++generation,ISSUER);}
  void assertDenied(String id)throws Exception{result(id,!read(),"Actual fixed old permits() returned false; no token signature validation claimed");}
  void assertClosed(String id)throws Exception{try{read();result(id,false,"unexpected return");}catch(InvocationTargetException e){result(id,e.getCause() instanceof IllegalStateException,"Actual fixed old watermark closed: "+e.getCause().getMessage());}}
  void cases(String phase)throws Exception{
   result(phase+"_active_current_read",read(),"Actual old access/policy/watermark; synthetic preverified Jwt supplied, not a real login");
   Object before=directory.getClass().getMethod("find",String.class).invoke(directory,"t");result(phase+"_actual_task_read",((Optional<?>)before).isPresent(),"Actual old JdbcProductTaskDirectory.find read mapped resource");
   result(phase+"_null_jwt_denied",!read(null,facts("org","t","scope",1)),"Invalid caller denied before grant query");
   result(phase+"_wrong_issuer_denied",!read(jwt("https://other.invalid","actor","sid"),facts("org","t","scope",1)),"Current issuer mismatch");
   result(phase+"_wrong_subject_denied",!read(jwt(ISSUER,"other","sid"),facts("org","t","scope",1)),"Current subject mismatch");
   result(phase+"_missing_sid_denied",!read(jwt(ISSUER,"actor",null),facts("org","t","scope",1)),"Missing current session binding");
   for(String field:List.of("organization","task","scope","version")){Object facts=switch(field){case "organization"->facts("other","t","scope",1);case "task"->facts("org","other","scope",1);case "scope"->facts("org","t","other",1);default->facts("org","t","scope",2);};result(phase+"_wrong_"+field+"_denied",!read(jwt(ISSUER,"actor","sid"),facts),"Persisted resource facts do not match grant");}
   update(c,"UPDATE id2_session SET status='REVOKED'");assertDenied(phase+"_revoked_session_denied");update(c,"UPDATE id2_session SET status='ACTIVE'");
   update(c,"UPDATE id2_session SET expires_at=?",Timestamp.from(now.minusSeconds(3600)));assertDenied(phase+"_expired_session_denied");update(c,"UPDATE id2_session SET expires_at=?",Timestamp.from(now.plusSeconds(3600)));
   update(c,"UPDATE id2_subject SET status='DISABLED'");assertDenied(phase+"_disabled_subject_denied");update(c,"UPDATE id2_subject SET status='ACTIVE'");
   update(c,"UPDATE id2_grant SET status='REVOKED'");assertClosed(phase+"_unsynchronized_revocation_closed");refresh();assertDenied(phase+"_synchronized_revocation_denied");update(c,"UPDATE id2_grant SET status='ACTIVE'");refresh();
   update(c,"UPDATE id2_grant SET expires_at=?",Timestamp.from(now.minusSeconds(3600)));refresh();assertDenied(phase+"_expired_grant_denied");update(c,"UPDATE id2_grant SET expires_at=?",Timestamp.from(now.plusSeconds(3600)));refresh();
   update(c,"UPDATE id2_current_epoch SET current_grant_id=NULL");refresh();assertDenied(phase+"_withdrawn_epoch_denied");update(c,"UPDATE id2_current_epoch SET current_grant_id='g'");refresh();
   update(a,"UPDATE id2_authority_watermark SET state='PENDING',intent_id='offline-intent'");assertClosed(phase+"_pending_authority_closed");refresh();
   update(a,"UPDATE id2_authority_watermark SET history_sha=?","0".repeat(64));assertClosed(phase+"_history_mismatch_closed");refresh();
   result(phase+"_restored_current_fixture_read",read(),"Offline authority fixture synchronized with actual old history digest; no current authorization changed");
  }
  public void close()throws Exception{c.close();a.close();}
 }
 public static void main(String[]args)throws Exception{
  out=Path.of(args[0]).toAbsolutePath();MigrationCompatibilityProbe.out=out;List<URL>urls=new ArrayList<>();try(var files=Files.list(out.resolve("old-auth/lib"))){for(Path p:files.toList())urls.add(p.toUri().toURL());}
  Path root=out.getParent().getParent().getParent().getParent();Path jar=root.resolve("backend/apps/platform-api/target/agent-platform-consultation-browser-20261006.jar");
  try(var loader=new FixedOldLoader(jar,urls.toArray(URL[]::new))){
   try(var f=new Fixture(loader)){f.cases("before");Object taskBefore=f.directory.getClass().getMethod("find",String.class).invoke(f.directory,"t");for(Path p:MigrationCompatibilityProbe.paths("forward-paths.txt"))MigrationCompatibilityProbe.apply(f.c,p);f.cases("after");Object taskAfter=f.directory.getClass().getMethod("find",String.class).invoke(f.directory,"t");result("task_record_preserved_after_five_migrations",taskBefore.equals(taskAfter),"Actual old task record equality, including version/scope/expiry");}
   Files.writeString(out.resolve("old-auth-loaded-class-binding.json"),MigrationCompatibilityProbe.stringMap(loader.loaded));
  }catch(Exception e){Throwable cause=e instanceof InvocationTargetException?e.getCause():e;result("old_probe_exception",false,cause.getClass().getName()+": "+cause.getMessage());}
  Files.writeString(out.resolve("old-jar-authorization-results.json"),"{\"scope\":\"Actual old fixed-Jar access/watermark/domain policy/task-directory bytecode, two isolated H2 schemas and synthetic preverified Jwt; NOT real JWT validation/login/DM8/retained-result HTTP/old writes/live rollback\",\"assertions\":"+events.size()+",\"failures\":"+failures+",\"results\":["+String.join(",\n",events)+"]}\n");System.out.println("Old-Jar read authorization assertions="+events.size()+" failures="+failures);if(failures>0)System.exit(1);
 }
}
