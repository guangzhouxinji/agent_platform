import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Specific isolated V107/U107 JDBC steps. No main, secrets, Docker, bootstrap, or arbitrary SQL API.
 * Deployment must supply the real current admission/fence guard; source availability is not admission. */
public final class ConsultationActivationRecoveryJdbcStep {
  public static final String TARGET_ID="78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481";
  public enum Namespace { EMPTY("ACTR66_EMPTY"), RECEIPT("ACTR66_RECEIPT"), EVENT("ACTR66_EVENT");
    private final String schema; Namespace(String s){schema=s;} public String schema(){return schema;} }
  public enum Operation { V_DROP_OLD, V_ADD_NEW, V_CREATE_RECEIPT, U_REVERSE_COMPLETE }
  public enum PreviousOutcome { CLEAR, UNKNOWN }
  public record Scope(String targetId, Namespace namespace, UUID runId) {
    public Scope { if(!TARGET_ID.equals(targetId)||namespace==null||runId==null)throw new IllegalArgumentException("EXACT_ISOLATED_SCOPE_REQUIRED"); }
  }
  public interface LiveGuard { void assertCurrent(Scope scope) throws Exception; }
  public interface Journal { void appendAndSync(Scope scope,String step,String phase,String sqlSha256) throws Exception; }
  public record Result(String outcome,int completedDdlStatements,int vendorCode,String sqlState) {
    public Result(String outcome,int completedDdlStatements){this(outcome,completedDdlStatements,0,"NONE");}
  }
  public static final class Failure extends Exception {
    public final String outcome,sqlState; public final int vendorCode;
    Failure(String code,String outcome,Exception cause){super(code);this.outcome=outcome;
      String state=cause instanceof SQLException s?s.getSQLState():null;
      sqlState=cause instanceof Failure f?f.sqlState:state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN";
      vendorCode=cause instanceof Failure f?f.vendorCode:cause instanceof SQLException s?s.getErrorCode():0;
    }
  }
  private static Failure refuse(String code){return new Failure(code,"NOT_EXECUTED",null);}
  private static String hash(byte[] b)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
  private record Sql(String text,String sha) {}
  public static final class FrozenSql {
    private final List<Sql> forward,inverse;
    private FrozenSql(List<Sql> v,List<Sql> u){forward=List.copyOf(v);inverse=List.copyOf(u);}
    /** Raw whole-file pins are checked before interpreting separators; no SQL text supplied by callers. */
    public static FrozenSql load(Path workspace)throws Exception {
      Path root=workspace.toAbsolutePath().normalize(); if(!root.equals(root.toRealPath()))throw refuse("SQL_ROOT_REDIRECT");
      String v=read(root,"V107__product_activation_recovery.sql","1b36de0c01ad6b5fd1bba76a0a4e43f3050974d1f66101f41d2cb90cbf042cde");
      String u=read(root,"U107__product_activation_recovery.sql","858ce5557dfbe449485abc85f91a1e47b89aff2d3083aec691e9118cb0e72fb6");
      var matcher=Pattern.compile("\\r?\\n/\\r?\\n").matcher(u);
      if(!matcher.find())throw refuse("REVERSE_SEPARATOR_REQUIRED");int start=matcher.start(),end=matcher.end();
      if(matcher.find())throw refuse("ONE_REVERSE_SEPARATOR_REQUIRED");
      String block=u.substring(u.indexOf("DECLARE"),start).strip();
      if(!block.startsWith("DECLARE")||!block.endsWith("END;"))throw refuse("FULL_GUARD_REQUIRED");
      List<Sql> inverse=new ArrayList<>();inverse.add(sql(block));inverse.addAll(split(u.substring(end)));
      List<Sql> forward=split(v);if(forward.size()!=3||inverse.size()!=4)throw refuse("EXACT_SQL_LAYOUT_REQUIRED");
      return new FrozenSql(forward,inverse);
    }
    private static String read(Path root,String name,String pin)throws Exception {
      for(String rel:List.of("db","db/dm8","db/dm8/"+name)){Path p=root.resolve(rel);if(Files.isSymbolicLink(p)||!p.equals(p.toRealPath()))throw refuse("SQL_REDIRECT_REJECTED");}
      Path file=root.resolve("db/dm8/"+name);byte[] bytes;
      try(var stream=Files.newInputStream(file)){bytes=stream.readNBytes(65537);}
      if(bytes.length>65536||!hash(bytes).equals(pin))throw refuse("SQL_SHA_MISMATCH");return new String(bytes,StandardCharsets.UTF_8);
    }
    private static List<Sql> split(String source)throws Exception {List<Sql> out=new ArrayList<>();for(String s:source.split(";"))if(!s.isBlank())out.add(sql(s.strip()+";"));return out;}
    private static Sql sql(String s)throws Exception{return new Sql(s,hash(s.getBytes(StandardCharsets.UTF_8)));}
  }
  /** Durable attempt journal, not a task ledger. Never opens/truncates a pre-existing journal. */
  public static final class ExclusiveFileJournal implements Journal,AutoCloseable {
    private final FileChannel channel;private final FileLock lock;private final UUID runId;
    public ExclusiveFileJournal(Path file,UUID runId)throws Exception {
      this.runId=Objects.requireNonNull(runId);
      Path p=file.toAbsolutePath().normalize();
      if(!file.isAbsolute()||!p.getFileName().toString().equals("activation-validation-"+runId+".jsonl"))throw refuse("EXACT_NEW_JOURNAL_REQUIRED");
      for(Path a=p.getParent();a!=null;a=a.getParent())if(Files.isSymbolicLink(a)||!a.equals(a.toRealPath()))throw refuse("JOURNAL_REDIRECT_REJECTED");
      channel=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
      FileLock acquired=null;try{acquired=channel.tryLock();if(acquired==null)throw refuse("JOURNAL_LOCK_REQUIRED");}catch(Exception e){channel.close();throw e;}lock=acquired;
    }
    public synchronized void appendAndSync(Scope s,String step,String phase,String digest)throws Exception {
      if(!s.runId.equals(runId)||!step.matches("[A-Z_]{1,64}")||!Set.of("PREPARED","COMPLETED","REFUSED","UNKNOWN").contains(phase)||!digest.matches("[a-f0-9]{64}")||!lock.isValid())throw refuse("JOURNAL_BINDING_LOST");
      String row="{\"runId\":\""+runId+"\",\"schema\":\""+s.namespace.schema+"\",\"step\":\""+step+"\",\"phase\":\""+phase+"\",\"sqlSha256\":\""+digest+"\"}\n";
      ByteBuffer bytes=StandardCharsets.UTF_8.encode(row);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
    }
    public synchronized void close()throws IOException {try{lock.release();}finally{channel.close();}}
  }
  private record State(String constraint,boolean receipt,long receipts,long events,long unexpected) {}
  private static void current(Connection c,Scope scope,LiveGuard guard)throws Exception {
    guard.assertCurrent(scope);
    if(!scope.namespace.schema.equals(c.getSchema()))throw refuse("SESSION_SCHEMA_DRIFT");
  }
  private static long count(Connection c,Scope s,LiveGuard guard,String sql)throws Exception {
    current(c,s,guard);try(Statement q=c.createStatement()){q.setQueryTimeout(30);try(ResultSet rs=q.executeQuery(sql)){if(!rs.next())throw refuse("COUNT_REQUIRED");long n=rs.getLong(1);if(rs.wasNull()||n<0||rs.next())throw refuse("EXACT_COUNT_REQUIRED");return n;}}
  }
  private static String recognize(String condition)throws Exception {
    String normalized=condition.replaceAll("\\s+","").replace("\"","").toUpperCase(Locale.ROOT);
    if(normalized.startsWith("CHECK"))normalized=normalized.substring(5);
    while(normalized.startsWith("(")&&normalized.endsWith(")"))normalized=normalized.substring(1,normalized.length()-1);
    String old="OPERATIONIN('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE')";
    if(normalized.equals(old))return "OLD5";
    if(normalized.equals(old.substring(0,old.length()-1)+",'REISSUE')"))return "NEW6";
    throw refuse("OPERATION_CONSTRAINT_UNRECOGNIZED");
  }
  private static State state(Connection c,Scope s,LiveGuard guard)throws Exception {
    current(c,s,guard);Set<String> tables=new HashSet<>();
    try(PreparedStatement q=c.prepareStatement("SELECT TABLE_NAME FROM DBA_TABLES WHERE OWNER=? AND TABLE_NAME IN ('ID2_SUBJECT','ID2_SUBJECT_EVENT','ID2_ACTIVATION_RECOVERY_RECEIPT')")){
      q.setQueryTimeout(30);q.setString(1,s.namespace.schema);try(ResultSet rs=q.executeQuery()){while(rs.next()){String n=rs.getString(1);if(n==null||!tables.add(n))throw refuse("TABLE_INVENTORY_INVALID");}}
    }
    if(!tables.containsAll(Set.of("ID2_SUBJECT","ID2_SUBJECT_EVENT")))throw refuse("ISOLATED_FIXTURE_REQUIRED");
    current(c,s,guard);String constraint="ABSENT";
    try(PreparedStatement q=c.prepareStatement("SELECT SEARCH_CONDITION FROM DBA_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME='ID2_SUBJECT_EVENT' AND CONSTRAINT_NAME='CK_ID2_SUBJECT_EVENT_OPERATION' AND CONSTRAINT_TYPE='C'")){
      q.setQueryTimeout(30);q.setString(1,s.namespace.schema);try(ResultSet rs=q.executeQuery()){if(rs.next()){String text=rs.getString(1);if(text==null||rs.next())throw refuse("ONE_OPERATION_CONSTRAINT_REQUIRED");constraint=recognize(text);}}
    }
    boolean receipt=tables.contains("ID2_ACTIVATION_RECOVERY_RECEIPT");
    long events=count(c,s,guard,"SELECT COUNT(*) FROM id2_subject_event WHERE operation='REISSUE'");
    long unexpected=count(c,s,guard,"SELECT COUNT(*) FROM id2_subject_event WHERE operation NOT IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE','REISSUE')");
    long receipts=receipt?count(c,s,guard,"SELECT COUNT(*) FROM id2_activation_recovery_receipt"):-1;
    return new State(constraint,receipt,receipts,events,unexpected);
  }
  private static boolean empty(State s){return s.events==0&&(!s.receipt||s.receipts==0);}
  private static void admissible(State s,Operation op)throws Exception {
    if(s.unexpected!=0)throw refuse("UNSUPPORTED_EVENT_OPERATION");
    boolean ok=switch(op){
      case V_DROP_OLD -> s.constraint.equals("OLD5")&&!s.receipt&&empty(s);
      case V_ADD_NEW -> s.constraint.equals("ABSENT")&&(s.receipt||s.events==0);
      case V_CREATE_RECEIPT -> s.constraint.equals("NEW6")&&!s.receipt&&s.events==0;
      case U_REVERSE_COMPLETE -> s.constraint.equals("NEW6")&&s.receipt;
    };
    if(!ok)throw refuse("CURRENT_STEP_PRECONDITION_REFUSED");
  }
  private static void prepare(Connection c,Scope s,LiveGuard guard,Journal journal,String step,Sql sql)throws Exception {
    journal.appendAndSync(s,step,"PREPARED",sql.sha);
    try{current(c,s,guard);}catch(Exception refused){
      try{journal.appendAndSync(s,step,"REFUSED",sql.sha);}catch(Exception journalError){throw new Failure("PRE_ATTEMPT_JOURNAL_UNRESOLVED","UNKNOWN",journalError);}
      throw new Failure("CURRENT_GUARD_REFUSED","NOT_EXECUTED",refused);
    }
  }
  private static void execute(Connection c,Scope s,LiveGuard guard,Journal journal,String step,Sql sql)throws Exception {
    // A failed pre-attempt fsync performs no SQL. No success after a post-attempt journal failure.
    prepare(c,s,guard,journal,step,sql);
    try(Statement q=c.createStatement()){
      q.setQueryTimeout(30);q.execute(sql.text);journal.appendAndSync(s,step,"COMPLETED",sql.sha);
    }catch(Exception e){
      try{journal.appendAndSync(s,step,"UNKNOWN",sql.sha);}catch(Exception ignored){/* Original failure still stops all successor writes. */}
      throw new Failure("SQL_ATTEMPT_UNKNOWN_STOP_REQUIRED","UNKNOWN",e);
    }
  }
  public static Result executeStep(Connection c,Scope scope,FrozenSql sql,Operation operation,PreviousOutcome previous,LiveGuard guard,Journal journal)throws Failure {
    try{return executeBounded(c,scope,sql,operation,previous,guard,journal);}
    catch(Failure f){throw f;}
    catch(Exception error){throw new Failure("EXECUTION_GUARD_OR_METADATA_FAILED_STOP_REQUIRED","NOT_EXECUTED_OR_RECONCILIATION_REQUIRED",error);}
  }
  private static Result executeBounded(Connection c,Scope scope,FrozenSql sql,Operation operation,PreviousOutcome previous,LiveGuard guard,Journal journal)throws Exception {
    Objects.requireNonNull(c);Objects.requireNonNull(scope);Objects.requireNonNull(sql);Objects.requireNonNull(operation);Objects.requireNonNull(previous);Objects.requireNonNull(guard);Objects.requireNonNull(journal);
    if(previous==PreviousOutcome.UNKNOWN)throw refuse("UNKNOWN_REQUIRES_RECONCILIATION");
    synchronized(c){
      guard.assertCurrent(scope);DatabaseMetaData md=c.getMetaData();
      if(!"SYSDBA".equalsIgnoreCase(md.getUserName())||!"8.1.4.170".equals(md.getDatabaseProductVersion())||!md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("dm")||!"jdbc:dm://agent-platform-single-final-restore-20261009:5236".equals(md.getURL())||!c.getAutoCommit())throw refuse("EXACT_ISOLATED_CONNECTION_REQUIRED");
      c.setSchema(scope.namespace.schema);State before=state(c,scope,guard);admissible(before,operation);
      if(operation!=Operation.U_REVERSE_COMPLETE){int index=switch(operation){case V_DROP_OLD->0;case V_ADD_NEW->1;case V_CREATE_RECEIPT->2;default->throw refuse("OPERATION_REQUIRED");};execute(c,scope,guard,journal,operation.name(),sql.forward.get(index));return new Result("FORWARD_STEP_COMPLETED_REOBSERVE_REQUIRED",1);}
      Sql block=sql.inverse.get(0);prepare(c,scope,guard,journal,"U_GUARD",block);
      try(Statement q=c.createStatement()){
        q.setQueryTimeout(30);q.execute(block.text);
      }catch(Exception error){
        if(error instanceof SQLException vendor && vendor.getErrorCode()==-20001){
          try{journal.appendAndSync(scope,"U_GUARD","REFUSED",block.sha);}catch(Exception journalError){throw new Failure("GUARD_REFUSAL_JOURNAL_UNKNOWN","UNKNOWN",journalError);}
          String state=vendor.getSQLState();
          return new Result(empty(before)?"GUARD_REFUSED_UNEXPECTED":"GUARD_REFUSED_HISTORY",0,vendor.getErrorCode(),state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");
        }
        try{journal.appendAndSync(scope,"U_GUARD","UNKNOWN",block.sha);}catch(Exception ignored){}
        throw new Failure("GUARD_RESULT_UNKNOWN_STOP_REQUIRED","UNKNOWN",error);
      }
      try{journal.appendAndSync(scope,"U_GUARD","COMPLETED",block.sha);}catch(Exception journalError){
        try{journal.appendAndSync(scope,"U_GUARD","UNKNOWN",block.sha);}catch(Exception ignored){}
        throw new Failure("GUARD_COMPLETION_JOURNAL_UNKNOWN","UNKNOWN",journalError);
      }
      if(!empty(before))throw refuse("GUARD_DID_NOT_REFUSE_HISTORY");
      String[] steps={"U_DROP_RECEIPT","U_DROP_OPERATION","U_ADD_OLD_OPERATION"};
      for(int i=1;i<4;i++){
        try {
          State now=state(c,scope,guard);
          boolean shape=i==1?now.constraint.equals("NEW6")&&now.receipt:i==2?now.constraint.equals("NEW6")&&!now.receipt:now.constraint.equals("ABSENT")&&!now.receipt;
          if(!shape||now.unexpected!=0||!empty(now))throw refuse("INVERSE_PARTIAL_OR_HISTORY_STOP_REQUIRED");
          execute(c,scope,guard,journal,steps[i-1],sql.inverse.get(i));
        }catch(Exception stopped){
          if(stopped instanceof Failure f&&f.outcome.equals("UNKNOWN"))throw f;
          throw new Failure("INVERSE_STOP_REQUIRED",i>1?"PARTIAL_COMMITTED_STOP_REQUIRED":"NOT_EXECUTED",stopped);
        }
      }
      return new Result("REVERSE_COMPLETED_REOBSERVE_REQUIRED",3);
    }
  }
  private ConsultationActivationRecoveryJdbcStep() {}
}
