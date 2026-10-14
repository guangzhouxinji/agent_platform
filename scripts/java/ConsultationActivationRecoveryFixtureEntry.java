import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** One closed case per START/connection/lease; all host authority remains in the original RPC. */
public final class ConsultationActivationRecoveryFixtureEntry {
  static final Path ROOT=Path.of("/jdbc/activation-validation97");
  static JsonNode selected(ConsultationActivationRecoveryFixturePrefix prefix,ConsultationActivationRecoveryJdbcStep.Scope scope,String operation)throws Exception {
    for(JsonNode c:prefix.plan.path("cases"))if(c.path("schema").asText().equals(scope.namespace().schema())&&c.path("operation").asText().equals(operation))return c;
    throw new Exception("FIXTURE_CASE_SCHEMA_BINDING");
  }
  static final class Live implements ConsultationActivationRecoveryJdbcStep.LiveGuard {
    final ConsultationActivationRecoveryJdbcEntry.Rpc rpc;final ConsultationActivationRecoveryFixturePrefix prefix;final ConsultationActivationRecoveryFixtureGuard fixture;
    Connection connection;int index=0;boolean preparing=true,seeding=false;
    ConsultationActivationRecoveryJdbcStep.Operation operation=ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD;
    Live(ConsultationActivationRecoveryJdbcEntry.Rpc rpc,ConsultationActivationRecoveryFixturePrefix prefix,ConsultationActivationRecoveryFixtureGuard fixture){this.rpc=rpc;this.prefix=prefix;this.fixture=fixture;}
    public void assertCurrent(ConsultationActivationRecoveryJdbcStep.Scope s)throws Exception {
      if(!s.runId().equals(rpc.run)||!s.namespace().schema().equals(rpc.schema))throw new Exception("FIXTURE_RPC_SCOPE_DRIFT");
      rpc.exchange("GUARD",Map.of("phase",connection==null?"BEFORE_CONNECTION":"CURRENT_STEP"));
      if(connection==null)return;
      if(preparing)prefix.assertPrefix(connection,s,index);
      else {prefix.connection(connection,s,!seeding,s.namespace().schema());prefix.owner(connection,s.namespace().schema());fixture.assertCurrent(connection,s,operation);}
    }
  }
  private static void prepare(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,Live live,ConsultationActivationRecoveryJdbcStep.Journal journal,JsonNode action)throws Exception {
    String step=action.path("step").asText(),sha=action.path("sqlSha256").asText();
    journal.appendAndSync(s,step,"PREPARED",sha);
    try{live.assertCurrent(s);}catch(Exception refused){journal.appendAndSync(s,step,"REFUSED",sha);throw refused;}
    try(Statement q=c.createStatement()) {
      q.setQueryTimeout(30);q.execute(action.path("sql").asText());
      if(live.index==0)c.setSchema(s.namespace().schema());
      journal.appendAndSync(s,step,"COMPLETED",sha);
    }catch(Exception error){try{journal.appendAndSync(s,step,"UNKNOWN",sha);}catch(Exception ignored){}throw new ConsultationActivationRecoveryJdbcStep.Failure("FIXTURE_DDL_OUTCOME_UNKNOWN_STOP_REQUIRED","UNKNOWN",error);}
    live.index++;live.assertCurrent(s);
  }
  static Map<String,Object> executeCase(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,String caseOperation,Live live,ConsultationActivationRecoveryJdbcStep.FrozenSql sql,ConsultationActivationRecoveryFixtureSeed seed)throws Exception {
    return executeCase(c,s,caseOperation,live,sql,seed,false);
  }
  static Map<String,Object> executeCase(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,String caseOperation,Live live,ConsultationActivationRecoveryJdbcStep.FrozenSql sql,ConsultationActivationRecoveryFixtureSeed seed,boolean resumed)throws Exception {
    return executeCase(c,s,caseOperation,live,sql,seed,resumed?1:0);
  }
  static Map<String,Object> executeCase(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,String caseOperation,Live live,ConsultationActivationRecoveryJdbcStep.FrozenSql sql,ConsultationActivationRecoveryFixtureSeed seed,int completedBefore)throws Exception {
    ConsultationActivationRecoveryFixturePrefix.require(completedBefore==0||completedBefore==1||completedBefore==10,"EXACT_RECOVERY_PREFIX_REQUIRED");
    boolean resumed=completedBefore>0,partial=completedBefore==10;
    JsonNode selected=selected(live.prefix,s,caseOperation);
    ConsultationActivationRecoveryFixturePrefix.require(!resumed||s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY&&caseOperation.equals("CASE_EMPTY"),"SCHEMA_ONLY_RESUME_BINDING_REQUIRED");
    if(resumed){live.index=partial?8:1;c.setSchema(s.namespace().schema());}
    var journal=ConsultationActivationRecoveryJdbcEntry.journal(live.rpc);live.connection=c;live.assertCurrent(s);
    if(!partial){if(!resumed)prepare(c,s,live,journal,selected.path("create"));for(JsonNode action:live.prefix.plan.path("prepare"))prepare(c,s,live,journal,action);}
    live.preparing=false;int ddl=partial?0:resumed?8:9,transactions=0;boolean empty=s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY;
    if(!empty){live.seeding=true;seed.execute(c,s,ConsultationActivationRecoveryFixtureSeed.Kind.SUBJECT,live,journal);live.seeding=false;transactions++;}
    for(var operation:List.of(ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD,ConsultationActivationRecoveryJdbcStep.Operation.V_ADD_NEW,ConsultationActivationRecoveryJdbcStep.Operation.V_CREATE_RECEIPT)) {
      if(partial&&operation==ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD)continue;
      live.operation=operation;var result=ConsultationActivationRecoveryJdbcStep.executeStep(c,s,sql,operation,ConsultationActivationRecoveryJdbcStep.PreviousOutcome.CLEAR,live,journal);
      ConsultationActivationRecoveryFixturePrefix.require(result.outcome().equals("FORWARD_STEP_COMPLETED_REOBSERVE_REQUIRED")&&result.completedDdlStatements()==1,"FIXTURE_FORWARD_RESULT_REQUIRED");ddl++;
    }
    if(!empty){live.seeding=true;seed.execute(c,s,s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT?ConsultationActivationRecoveryFixtureSeed.Kind.RECEIPT:ConsultationActivationRecoveryFixtureSeed.Kind.EVENT,live,journal);live.seeding=false;transactions++;}
    live.operation=ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE;
    var result=ConsultationActivationRecoveryJdbcStep.executeStep(c,s,sql,live.operation,ConsultationActivationRecoveryJdbcStep.PreviousOutcome.CLEAR,live,journal);
    ConsultationActivationRecoveryFixturePrefix.require(empty?result.outcome().equals("REVERSE_COMPLETED_REOBSERVE_REQUIRED")&&result.vendorCode()==0&&result.completedDdlStatements()==3:result.outcome().equals("GUARD_REFUSED_HISTORY")&&result.vendorCode()==-20001&&result.completedDdlStatements()==0,"FIXTURE_INVERSE_RESULT_REQUIRED");
    ddl+=result.completedDdlStatements();live.assertCurrent(s);live.prefix.assertFinal(c,s);live.assertCurrent(s);
    ConsultationActivationRecoveryFixturePrefix.require(ddl==selected.path("completedDdlStatements").asInt()-completedBefore&&transactions==selected.path("committedSeedTransactions").asInt(),"FIXTURE_CASE_COUNTS_REQUIRED");
    Map<String,Object> output=new LinkedHashMap<>();output.put("type","RESULT");output.put("runId",s.runId().toString());output.put("schema",s.namespace().schema());output.put("outcome",selected.path("outcome").asText());output.put("completedDdlStatements",ddl);output.put("committedSeedTransactions",transactions);output.put("vendorCode",result.vendorCode());output.put("sqlState",result.sqlState());output.put("guardSqlSha256",live.prefix.plan.path("migration").get(3).path("sqlSha256").asText());return output;
  }
  public static void main(String[] args) {
    try {
      if(args.length!=0)throw new Exception("RPC_ARGUMENTS_REFUSED");JsonNode start=ConsultationActivationRecoveryJdbcEntry.Rpc.line(System.in);var scope=ConsultationActivationRecoveryJdbcEntry.scope(start);
      String previous=ConsultationActivationRecoveryJdbcEntry.Rpc.text(start,"previousOutcome");int completedBefore=previous.equals("RECONCILED_V_DROP_OLD_6F13")?10:previous.equals("RECONCILED_SCHEMA_ONLY_3976")?1:0;boolean resumed=completedBefore>0;
      if(!previous.equals("CLEAR")&&!resumed)throw new Exception("UNKNOWN_REQUIRES_RECONCILIATION");
      var prefix=new ConsultationActivationRecoveryFixturePrefix(ROOT);var selected=selected(prefix,scope,ConsultationActivationRecoveryJdbcEntry.Rpc.text(start,"operation"));
      var rpc=new ConsultationActivationRecoveryJdbcEntry.Rpc(System.in,System.out,scope.runId(),scope.namespace().schema());var live=new Live(rpc,prefix,new ConsultationActivationRecoveryFixtureGuard(ROOT.resolve("fixture-contract68.json")));
      var sql=ConsultationActivationRecoveryJdbcStep.FrozenSql.load(ROOT);var seed=new ConsultationActivationRecoveryFixtureSeed(ROOT.resolve("fixture-plan73.json"));live.assertCurrent(scope);
      String url=System.getenv("LIFECYCLE_JDBC_URL"),user=System.getenv("LIFECYCLE_JDBC_USER"),password=System.getenv("LIFECYCLE_JDBC_PASSWORD");
      if(!"jdbc:dm://agent-platform-single-final-restore-20261009:5236".equals(url)||!"SYSDBA".equals(user)||password==null||password.isEmpty())throw new Exception("EXACT_MANAGED_CONNECTION_REQUIRED");
      try(Connection c=DriverManager.getConnection(url,user,password)){System.out.println(ConsultationActivationRecoveryJdbcEntry.JSON.writeValueAsString(executeCase(c,scope,selected.path("operation").asText(),live,sql,seed,completedBefore)));}
    }catch(Exception error) {
      String code=!(error instanceof SQLException)&&error.getMessage()!=null&&error.getMessage().matches("[A-Z][A-Z0-9_]{0,100}")?error.getMessage():"FIXTURE_ENTRY_REFUSED_OR_UNKNOWN";
      String outcome=error instanceof ConsultationActivationRecoveryJdbcStep.Failure f?f.outcome:"REFUSED_OR_UNKNOWN";
      String state=error instanceof ConsultationActivationRecoveryJdbcStep.Failure f?f.sqlState:error instanceof SQLException q&&q.getSQLState()!=null&&q.getSQLState().matches("[A-Z0-9]{5}")?q.getSQLState():"UNKNOWN";
      int vendor=error instanceof ConsultationActivationRecoveryJdbcStep.Failure f?f.vendorCode:error instanceof SQLException q?q.getErrorCode():0;
      try{System.out.println(ConsultationActivationRecoveryJdbcEntry.JSON.writeValueAsString(Map.of("type","ERROR","code",code,"outcome",outcome,"sqlState",state,"vendorCode",vendor,"automaticRetryAllowed",false)));}catch(Exception ignored){}
      System.exit(1);
    }
  }
}
