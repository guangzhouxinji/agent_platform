import java.lang.reflect.*;
import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static java.lang.Boolean.TRUE;

/** Component-only JDBC proxies. Does not load a JDBC driver or connect to any database. */
public final class ConsultationActivationRecoveryJdbcStepTest {
  static int passed=0;
  static Path workspace;
  static ConsultationActivationRecoveryJdbcStep.FrozenSql bundle;
  static final UUID RUN=UUID.fromString("00000000-0000-4000-8000-000000000067");
  static final ConsultationActivationRecoveryJdbcStep.Scope SCOPE=new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY,RUN);
  interface Checked {void run()throws Exception;}
  static void check(boolean condition,String code){if(!condition)throw new AssertionError(code);}
  static void test(String name,Checked body)throws Exception {body.run();passed++;System.out.println("PASS "+name);}
  static Exception fails(Checked body)throws Exception {try{body.run();throw new AssertionError("EXPECTED_REFUSAL");}catch(AssertionError e){throw e;}catch(Exception e){return e;}}
  static Object value(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
  @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
    if(m.getName().equals("toString"))return "COMPONENT_PROXY";
    if(m.getName().equals("hashCode"))return System.identityHashCode(p);
    if(m.getName().equals("equals"))return p==a[0];return h.invoke(p,m,a);
  });}
  static ResultSet rows(List<Object> rows){int[] cursor={-1};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
    case "next" -> ++cursor[0]<rows.size();case "getString" -> (String)rows.get(cursor[0]);case "getLong" -> (Long)rows.get(cursor[0]);case "wasNull" -> false;default -> value(m.getReturnType());
  });}
  static class Model {
    String constraint="OLD5",schema="SYSDBA",user="SYSDBA",version="8.1.4.170",url="jdbc:dm://agent-platform-single-final-restore-20261009:5236";
    boolean receipt=false,auto=true,missingBase=false,guardLie=false,insertAfterGuard=false;
    long receipts=0,events=0,unexpected=0;int metadataCalls=0;String failSqlPrefix=null;SQLException guardFailure=null;
    final List<String> executed=new ArrayList<>();
    Connection connection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
      case "getMetaData" -> {metadataCalls++;yield proxy(DatabaseMetaData.class,(q,n,b)->switch(n.getName()){
        case "getUserName" -> user;case "getDatabaseProductVersion" -> version;case "getDatabaseProductName" -> "DM DBMS";case "getURL" -> url;default -> value(n.getReturnType());});}
      case "getAutoCommit" -> auto;case "setSchema" -> {schema=(String)a[0];yield null;}case "getSchema" -> schema;
      case "prepareStatement" -> statement((String)a[0],true);case "createStatement" -> statement(null,false);default -> value(m.getReturnType());
    });}
    Object statement(String prepared,boolean typed){return proxy(typed?PreparedStatement.class:Statement.class,(p,m,a)->{
      String name=m.getName();if(name.equals("setString")){check(((String)a[1]).equals(SCOPE.namespace().schema()),"EXACT_QUERY_OWNER");return null;}
      if(name.equals("executeQuery")){
        String sql=prepared==null?(String)a[0]:prepared;
        if(sql.contains("FROM DBA_TABLES")){List<Object> tables=new ArrayList<>();if(!missingBase){tables.add("ID2_SUBJECT");tables.add("ID2_SUBJECT_EVENT");}if(receipt)tables.add("ID2_ACTIVATION_RECOVERY_RECEIPT");return rows(tables);}
        if(sql.contains("FROM DBA_CONSTRAINTS")){
          if(constraint.equals("ABSENT"))return rows(List.of());
          String old="operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE')";
          return rows(List.of(constraint.equals("OLD5")?old:constraint.equals("NEW6")?old.substring(0,old.length()-1)+",'REISSUE')":"operation IN ('REGISTER') OR 1=1"));
        }
        if(sql.contains("WHERE operation='REISSUE'"))return rows(List.of(events));
        if(sql.contains("WHERE operation NOT IN"))return rows(List.of(unexpected));
        if(sql.contains("FROM id2_activation_recovery_receipt"))return rows(List.of(receipts));
        throw new AssertionError("UNREVIEWED_QUERY");
      }
      if(name.equals("execute")){
        String sql=(String)a[0];executed.add(sql);
        if(failSqlPrefix!=null&&sql.startsWith(failSqlPrefix))throw new SQLException("SENTINEL_PRIVATE_NEVER_PRINT","08006",123);
        if(sql.startsWith("DECLARE")){
          if(guardFailure!=null)throw guardFailure;
          if(!guardLie&&(receipts>0||events>0))throw new SQLException("Activation recovery history prohibits reverse migration","45000",-20001);
          if(insertAfterGuard)events=1;
        }else if(sql.startsWith("CREATE TABLE id2_activation_recovery_receipt")){check(!receipt,"NO_BLIND_CREATE");receipt=true;}
        else if(sql.startsWith("DROP TABLE id2_activation_recovery_receipt")){check(receipts==0&&events==0,"NO_HISTORY_DROP");receipt=false;}
        else if(sql.startsWith("ALTER TABLE id2_subject_event DROP"))constraint="ABSENT";
        else if(sql.startsWith("ALTER TABLE id2_subject_event ADD"))constraint=sql.contains("'REISSUE'")?"NEW6":"OLD5";
        else throw new AssertionError("UNREVIEWED_SQL_WRITE");
        return true;
      }
      return value(m.getReturnType());
    });}
  }
  static class Log implements ConsultationActivationRecoveryJdbcStep.Journal {
    final List<String> records=new ArrayList<>();String failStep=null,failPhase=null;
    public void appendAndSync(ConsultationActivationRecoveryJdbcStep.Scope scope,String step,String phase,String hash)throws Exception{
      check(scope.equals(SCOPE)&&hash.matches("[a-f0-9]{64}"),"EXACT_JOURNAL_BINDING");records.add(step+":"+phase);
      if(step.equals(failStep)&&phase.equals(failPhase))throw new IOException("SENTINEL_PRIVATE_NEVER_PRINT");
    }
  }
  static final ConsultationActivationRecoveryJdbcStep.LiveGuard ACCEPT=s->{check(s.equals(SCOPE),"EXACT_COMPONENT_SCOPE");};
  static ConsultationActivationRecoveryJdbcStep.Result run(Model m,ConsultationActivationRecoveryJdbcStep.Operation op,Log log)throws Exception{return ConsultationActivationRecoveryJdbcStep.executeStep(m.connection(),SCOPE,bundle,op,ConsultationActivationRecoveryJdbcStep.PreviousOutcome.CLEAR,ACCEPT,log);}
  static Model installed(){Model m=new Model();m.constraint="NEW6";m.receipt=true;return m;}
  public static void main(String[] args)throws Exception {
    check(args.length==1,"EXACT_WORKSPACE_ARGUMENT");workspace=Path.of(args[0]).toAbsolutePath().normalize();bundle=ConsultationActivationRecoveryJdbcStep.FrozenSql.load(workspace);
    test("forward is three actual pinned JDBC steps with fresh preconditions",()->{
      Model m=new Model();Log log=new Log();for(var op:List.of(ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,ConsultationActivationRecoveryJdbcStep.Operation.V_ADD_NEW,ConsultationActivationRecoveryJdbcStep.Operation.V_CREATE_RECEIPT))check(run(m,op,log).completedDdlStatements()==1,"ONE_DDL_ONLY");
      check(m.executed.size()==3&&m.constraint.equals("NEW6")&&m.receipt,"FORWARD_NEW_STATE");check(log.records.size()==6,"PREPARED_AND_COMPLETED_EACH");
    });
    test("committed DROP cannot be blindly replayed",()->{Model m=new Model();Log log=new Log();run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,log);fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,log));check(m.executed.size()==1,"NO_SECOND_DROP");});
    test("partial missing constraint permits ADD only",()->{Model m=new Model();m.constraint="ABSENT";fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_CREATE_RECEIPT,new Log()));check(m.executed.isEmpty(),"NO_WRONG_PARTIAL_WRITE");run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_ADD_NEW,new Log());});
    test("missing receipt with REISSUE never invents empty history",()->{Model m=new Model();m.constraint="NEW6";m.events=1;fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_CREATE_RECEIPT,new Log()));check(m.executed.isEmpty(),"PRESERVE_HISTORY");});
    test("missing constraint with intact history only forward ADD",()->{Model m=installed();m.constraint="ABSENT";m.receipts=1;m.events=1;run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_ADD_NEW,new Log());check(m.receipts==1&&m.events==1&&m.receipt,"HISTORY_UNCHANGED");});
    test("empty full U preserves complete DECLARE and ordered original DDL",()->{Model m=installed();Log log=new Log();var out=run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,log);check(out.completedDdlStatements()==3&&m.executed.size()==4,"FOUR_ACTUAL_CALLS");String source=Files.readString(workspace.resolve("db/dm8/U107__product_activation_recovery.sql"));int end=source.indexOf("\r\n/\r\n");if(end<0)end=source.indexOf("\n/\n");String block=source.substring(source.indexOf("DECLARE"),end).strip();check(m.executed.get(0).equals(block),"GUARD_RAW_BYTES_NOT_SPLIT");check(m.constraint.equals("OLD5")&&!m.receipt,"ORIGINAL_EMPTY_SCHEMA");});
    for(boolean receipts:List.of(true,false))test(receipts?"receipt history calls real guard but no destructive JDBC":"REISSUE history calls real guard but no destructive JDBC",()->{Model m=installed();if(receipts)m.receipts=1;else m.events=1;Log log=new Log();var out=run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,log);check(out.outcome().equals("GUARD_REFUSED_HISTORY")&&m.executed.size()==1&&m.receipt,"GUARD_HISTORY_REFUSAL_ONLY");check(log.records.equals(List.of("U_GUARD:PREPARED","U_GUARD:REFUSED")),"REFUSAL_JOURNAL");});
    test("guard returning success on history cannot unlock DROP",()->{Model m=installed();m.receipts=1;m.guardLie=true;fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,new Log()));check(m.executed.size()==1&&m.receipt,"NO_DESTRUCTIVE_AFTER_BROKEN_GUARD");});
    test("guard syntax/connection error is UNKNOWN not business refusal",()->{Model m=installed();m.guardFailure=new SQLException("SENTINEL_PRIVATE_NEVER_PRINT","42000",-30000);Exception e=fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,new Log()));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN")&&f.vendorCode==-30000,"PRESERVE_MACHINE_ERROR");check(!e.toString().contains("SENTINEL"),"NO_DATABASE_TEXT");check(m.executed.size()==1,"NO_DDL_AFTER_UNKNOWN");});
    test("UNKNOWN refuses before any JDBC metadata or SQL",()->{Model m=new Model();fails(()->ConsultationActivationRecoveryJdbcStep.executeStep(m.connection(),SCOPE,bundle,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,ConsultationActivationRecoveryJdbcStep.PreviousOutcome.UNKNOWN,ACCEPT,new Log()));check(m.executed.isEmpty()&&m.metadataCalls==0,"NO_UNKNOWN_REPLAY");});
    test("pre-attempt journal failure performs no SQL",()->{Model m=new Model();Log l=new Log();l.failStep="V_DROP_OLD";l.failPhase="PREPARED";fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,l));check(m.executed.isEmpty()&&m.constraint.equals("OLD5"),"NO_SQL_BEFORE_FSYNC");});
    test("committed DDL plus journal failure is UNKNOWN never success",()->{Model m=new Model();Log l=new Log();l.failStep="V_DROP_OLD";l.failPhase="COMPLETED";Exception e=fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,l));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN"),"COMMITTED_RESPONSE_UNKNOWN");check(m.constraint.equals("ABSENT")&&m.executed.size()==1&&l.records.contains("V_DROP_OLD:UNKNOWN"),"KEEP_COMMITTED_EVIDENCE");check(!e.toString().contains("SENTINEL"),"NO_PRIVATE_TEXT");});
    test("guard post-completion journal failure does not proceed to DROP",()->{Model m=installed();Log l=new Log();l.failStep="U_GUARD";l.failPhase="COMPLETED";Exception e=fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,l));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN"),"GUARD_JOURNAL_UNKNOWN");check(m.executed.size()==1&&m.receipt,"NO_DDL_AFTER_FSYNC_FAILURE");});
    test("DDL SQL exception remains UNKNOWN without cleaning",()->{Model m=installed();m.failSqlPrefix="DROP TABLE";Log l=new Log();Exception e=fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,l));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN")&&f.sqlState.equals("08006"),"MACHINE_UNKNOWN");check(m.executed.size()==2&&m.receipt&&m.constraint.equals("NEW6"),"NO_SUCCESSOR_AFTER_FAILED_DROP");});
    test("history appearing after guard stops inverse before DROP",()->{Model m=installed();m.insertAfterGuard=true;fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,new Log()));check(m.executed.size()==1&&m.receipt,"RECHECK_HISTORY");});
    test("live fence loss after receipt DROP reports partial committed stop",()->{Model m=installed();Log l=new Log();var guard=(ConsultationActivationRecoveryJdbcStep.LiveGuard)(s)->{if(l.records.contains("U_DROP_RECEIPT:COMPLETED"))throw new IOException("CURRENT_FENCE_LOST");};Exception e=fails(()->ConsultationActivationRecoveryJdbcStep.executeStep(m.connection(),SCOPE,bundle,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,ConsultationActivationRecoveryJdbcStep.PreviousOutcome.CLEAR,guard,l));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("PARTIAL_COMMITTED_STOP_REQUIRED"),"DO_NOT_CLAIM_ROLLBACK");check(m.executed.size()==2&&!m.receipt&&m.constraint.equals("NEW6"),"NO_DROP_OPERATION_AFTER_FENCE_LOSS");});
    test("partial inverse refuses complete U and performs no SQL",()->{Model m=installed();m.receipt=false;fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE,new Log()));check(m.executed.isEmpty(),"NO_PARTIAL_U_CLEANUP");});
    test("non-original operation constraint and malformed rows refuse",()->{Model m=new Model();m.constraint="OTHER";fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,new Log()));check(m.executed.isEmpty(),"NO_UNRECOGNIZED_WRITE");m.constraint="OLD5";m.unexpected=1;fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,new Log()));check(m.executed.isEmpty(),"NO_INVALID_OPERATION_WRITE");});
    for(String kind:List.of("USER","VERSION","URL","AUTOCOMMIT","BASE_TABLES"))test("refuse changed "+kind,()->{Model m=new Model();switch(kind){case "USER"->m.user="CONPROD";case "VERSION"->m.version="8.1.4.171";case "URL"->m.url="jdbc:dm://agent-platform-single-final-primary-20261009:5236";case "AUTOCOMMIT"->m.auto=false;default->m.missingBase=true;}fails(()->run(m,ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,new Log()));check(m.executed.isEmpty(),"NO_WRONG_CONNECTION_SQL");});
    test("scope cannot name original schemas or a different target",()->{fails(()->new ConsultationActivationRecoveryJdbcStep.Scope("f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f",ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY,RUN));check(Arrays.stream(ConsultationActivationRecoveryJdbcStep.Namespace.values()).map(x->x.schema()).toList().equals(List.of("ACTR66_EMPTY","ACTR66_RECEIPT","ACTR66_EVENT")),"CLOSED_NAMESPACE_SET");check(Arrays.stream(ConsultationActivationRecoveryJdbcStep.class.getDeclaredMethods()).noneMatch(x->x.getName().equals("main")),"NO_UNGATED_MAIN");});
    test("durable file journal is exclusive and never overwrites original bytes",()->{Path parent=Files.createTempDirectory(workspace.resolve("docs/evidence/activation-recovery-design-20261010"),"journal-component67-");Path file=parent.resolve("activation-validation-"+RUN+".jsonl");try(var j=new ConsultationActivationRecoveryJdbcStep.ExclusiveFileJournal(file,RUN)){j.appendAndSync(SCOPE,"V_DROP_OLD","PREPARED","a".repeat(64));j.appendAndSync(SCOPE,"V_DROP_OLD","COMPLETED","a".repeat(64));}byte[] before=Files.readAllBytes(file);fails(()->new ConsultationActivationRecoveryJdbcStep.ExclusiveFileJournal(file,RUN));check(Arrays.equals(before,Files.readAllBytes(file)),"ORIGINAL_JOURNAL_UNCHANGED");check(Files.readAllLines(file).size()==2,"TWO_DURABLE_RECORDS");});
    System.out.println("COMPONENT_TESTS_PASSED="+passed+"; REAL_DM8_EXECUTIONS=0; DEPLOYMENTS=0; SECRETS=0");
  }
}
