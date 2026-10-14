import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Real H2 transactions; explicit component proxies for DM metadata, sessions and host guard. */
public final class ConsultationActivationRecoveryFixtureSeedTest {
  static int passed;
  static JsonNode plan;
  static ConsultationActivationRecoveryFixtureSeed seed;
  static void require(boolean yes,String code){if(!yes)throw new AssertionError(code);}
  interface Checked {void run()throws Exception;}
  static void test(String name,Checked run)throws Exception {run.run();passed++;System.out.println("PASS "+name);}
  static Exception fails(Checked run)throws Exception {try{run.run();throw new AssertionError("REFUSAL_REQUIRED");}catch(AssertionError e){throw e;}catch(Exception e){return e;}}
  @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
  static Object invoke(Object receiver,Method method,Object[] args)throws Throwable {
    try{return method.invoke(receiver,args);}catch(InvocationTargetException e){throw e.getCause();}
  }
  static final class Database implements AutoCloseable {
    final String url="jdbc:h2:mem:actr73_"+UUID.randomUUID().toString().replace("-","")+";DB_CLOSE_DELAY=-1";
    final Connection raw;
    final ConsultationActivationRecoveryJdbcStep.Scope scope;
    final List<String> records=new ArrayList<>();
    int commits,rollbacks,guards;String journalFailure=null,commitFailure=null,guardFailure=null,mode="0",version="8.1.4.170";long sessions=1;
    Database(ConsultationActivationRecoveryJdbcStep.Namespace namespace)throws Exception {
      scope=new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,namespace,UUID.randomUUID());
      raw=DriverManager.getConnection(url);try(Statement q=raw.createStatement()){q.execute("CREATE SCHEMA "+namespace.schema());}
      raw.setSchema(namespace.schema());
      for(JsonNode item:plan.path("prepare"))try(Statement q=raw.createStatement()){q.execute(item.path("sql").asText());}
      for(JsonNode item:plan.path("migration")) {
        if(item.path("step").asText().startsWith("U_"))break;
        try(Statement q=raw.createStatement()){q.execute(item.path("sql").asText());}
      }
    }
    Connection connection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()) {
      case "getMetaData"->proxy(DatabaseMetaData.class,(q,n,b)->switch(n.getName()) {
        case "getUserName"->"SYSDBA";case "getDatabaseProductVersion"->version;
        case "getDatabaseProductName"->"DM component metadata proxy, not DM8";
        case "getURL"->"jdbc:dm://agent-platform-single-final-restore-20261009:5236";
        default->invoke(raw.getMetaData(),n,b);
      });
      case "createStatement"->{Statement real=(Statement)invoke(raw,m,a);yield proxy(Statement.class,(q,n,b)->{
        if(n.getName().equals("executeQuery")&&b[0].equals("SELECT COUNT(*) FROM V$SESSIONS"))return real.executeQuery("SELECT CAST("+sessions+" AS BIGINT)");
        return invoke(real,n,b);
      });}
      case "prepareStatement"->{
        if(a[0].equals("SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME='COMPATIBLE_MODE'"))yield raw.prepareStatement("SELECT '"+mode+"'");
        yield invoke(raw,m,a);
      }
      case "commit"->{commits++;if("before".equals(commitFailure))throw new SQLException("COMPONENT_COMMIT_BEFORE","08006",73);raw.commit();if("after".equals(commitFailure))throw new SQLException("COMPONENT_COMMIT_AFTER","08006",73);yield null;}
      case "rollback"->{rollbacks++;raw.rollback();yield null;}
      default->invoke(raw,m,a);
    });}
    ConsultationActivationRecoveryJdbcStep.Journal journal(){return (s,step,phase,hash)->{
      require(s.equals(scope),"JOURNAL_SCOPE");
      JsonNode selected=null;for(JsonNode item:plan.path("seeds"))if(item.path("step").asText().equals(step)||item.path("commitStep").asText().equals(step))selected=item;
      require(selected!=null,"JOURNAL_FIXED_STEP");
      require(hash.equals(selected.path(step.endsWith("_COMMIT")?"commitSha256":"sqlSha256").asText()),"JOURNAL_HASH");
      String record=step+":"+phase;
      if(record.equals(journalFailure))throw new Exception("COMPONENT_FSYNC_FAILURE");
      records.add(record);
    };}
    void execute(ConsultationActivationRecoveryFixtureSeed.Kind kind)throws Exception {
      seed.execute(connection(),scope,kind,s->{require(s.equals(scope),"GUARD_SCOPE");guards++;if(guardFailure!=null&&records.contains(guardFailure))throw new Exception("COMPONENT_CURRENT_GUARD_LOST");},journal());
    }
    long visibleSubjects()throws Exception {
      try(Connection c=DriverManager.getConnection(url)){c.setSchema(scope.namespace().schema());try(Statement q=c.createStatement();ResultSet rs=q.executeQuery("SELECT COUNT(*) FROM id2_subject")){rs.next();return rs.getLong(1);}}
    }
    public void close()throws Exception {raw.close();try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){q.execute("DROP ALL OBJECTS");}}
  }
  public static void main(String[] args)throws Exception {
    Path file=Path.of(args[0]);seed=new ConsultationActivationRecoveryFixtureSeed(file);plan=ConsultationActivationRecoveryJdbcEntry.JSON.readTree(Files.readAllBytes(file));
    test("H2 real fixed subject and receipt commits with separate journal boundaries",()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)) {
        db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT);db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.RECEIPT);
        require(db.commits==2&&db.rollbacks==0&&db.visibleSubjects()==1,"TWO_REAL_COMMITS");
        require(db.records.equals(List.of("SEED_SUBJECT:PREPARED","SEED_SUBJECT:COMPLETED","SEED_SUBJECT_COMMIT:PREPARED","SEED_SUBJECT_COMMIT:COMPLETED","SEED_RECEIPT:PREPARED","SEED_RECEIPT:COMPLETED","SEED_RECEIPT_COMMIT:PREPARED","SEED_RECEIPT_COMMIT:COMPLETED")),"EXACT_DURABLE_ORDER");
      }
    });
    test("H2 real fixed subject and REISSUE commits",()->{try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.EVENT)){db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT);db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.EVENT);require(db.commits==2&&db.rollbacks==0,"TWO_EVENT_COMMITS");}});
    for(String phase:List.of("SEED_SUBJECT:PREPARED","SEED_SUBJECT:COMPLETED","SEED_SUBJECT_COMMIT:PREPARED"))test("H2 precommit fsync failure "+phase,()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){db.journalFailure=phase;fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(db.commits==0&&db.visibleSubjects()==0,"NO_COMMIT_ON_FSYNC");require(db.rollbacks==(phase.equals("SEED_SUBJECT:PREPARED")?0:1),"ONLY_PRECOMMIT_ROLLBACK");}
    });
    for(String failure:List.of("before","after"))test("H2 commit invocation error remains UNKNOWN without rollback "+failure,()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){db.commitFailure=failure;Exception e=fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN")&&f.vendorCode==73&&f.sqlState.equals("08006"),"RAW_COMMIT_ERROR");require(db.commits==1&&db.rollbacks==0&&db.records.contains("SEED_SUBJECT_COMMIT:UNKNOWN"),"NO_SPECULATIVE_ROLLBACK");require(db.visibleSubjects()==(failure.equals("after")?1:0),"ACTUAL_H2_COMMIT_VISIBILITY");}
    });
    test("H2 postcommit fsync error preserves committed row and UNKNOWN",()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){db.journalFailure="SEED_SUBJECT_COMMIT:COMPLETED";Exception e=fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN"),"POSTCOMMIT_UNKNOWN");require(db.commits==1&&db.rollbacks==0&&db.visibleSubjects()==1,"COMMIT_RETAINED");}
    });
    test("seed for EMPTY is rejected before host guard or INSERT",()->{try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY)){fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(db.guards==0&&db.commits==0&&db.records.isEmpty(),"EMPTY_NO_ATTEMPT");}});
    test("event cannot enter receipt case",()->{try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.EVENT));require(db.guards==0&&db.records.isEmpty(),"WRONG_CASE_NO_ATTEMPT");}});
    for(String field:List.of("mode","version","sessions"))test("component exact native connection refuses "+field,()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){switch(field){case "mode"->db.mode="1";case "version"->db.version="wrong";default->db.sessions=2;}
        fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(db.records.isEmpty()&&db.commits==0,"NO_WRONG_CONNECTION_INSERT");}
    });
    test("duplicate seed does not replay insert or commit",()->{try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT);fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(db.commits==1&&db.records.size()==4,"NO_SEED_REPLAY");}});
    test("lost host guard before commit rolls back actual insert without invoking commit",()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){db.guardFailure="SEED_SUBJECT_COMMIT:PREPARED";fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT));require(db.commits==0&&db.rollbacks==1&&db.visibleSubjects()==0,"GUARD_LOSS_PRECOMMIT_ROLLBACK");}
    });
    test("wrong existing subject timestamp cannot be accepted as receipt seed input",()->{
      try(var db=new Database(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)){db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT);try(Statement q=db.raw.createStatement()){q.executeUpdate("UPDATE id2_subject SET created_at=TIMESTAMP '2001-01-01 00:00:00'");}
        fails(()->db.execute(ConsultationActivationRecoveryFixtureSeed.Kind.RECEIPT));require(db.commits==1&&db.records.size()==4,"WRONG_SUBJECT_NOT_CONSUMED");}
    });
    test("plan source mutation refuses before a connection is supplied",()->{
      Path scratch=Files.createTempFile("actr73-plan-component-",".json");
      try{Files.write(scratch,Files.readAllBytes(file));Files.writeString(scratch," ",StandardOpenOption.APPEND);Exception e=fails(()->new ConsultationActivationRecoveryFixtureSeed(scratch));require(e.getMessage().equals("SEED_PLAN_SHA_CHANGED"),"NO_MUTABLE_SEED_PLAN");}finally{Files.delete(scratch);}
    });
    System.out.println("FIXTURE73_SEED_TESTS="+passed+"; H2_REAL_TRANSACTIONS=true; NATIVE_METADATA=COMPONENT_PROXY; DM8_CONNECTIONS=0; HOST_ADMISSION=false");
  }
}
