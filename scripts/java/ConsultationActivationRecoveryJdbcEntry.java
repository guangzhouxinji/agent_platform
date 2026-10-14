import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Closed RPC bridge. Authority is revalidated by the host, not a default-allow guard. */
public final class ConsultationActivationRecoveryJdbcEntry {
  static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  static final Path ROOT=Path.of("/jdbc/activation-validation68");
  static final class Rpc {
    final InputStream in;final OutputStream out;final UUID run;final String schema;long sequence=0;
    Rpc(InputStream in,OutputStream out,UUID run,String schema){this.in=in;this.out=out;this.run=run;this.schema=schema;}
    static JsonNode line(InputStream in)throws Exception {
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();int n;
      while((n=in.read())!=-1){if(n=='\n')break;if(bytes.size()>=4096)throw new Exception("RPC_LINE_LIMIT");bytes.write(n);}
      if(n==-1||bytes.size()==0)throw new Exception("RPC_EOF_OR_EMPTY");
      return JSON.readTree(bytes.toByteArray());
    }
    static void keys(JsonNode node,String... expected)throws Exception {Set<String> fields=new HashSet<>();node.fieldNames().forEachRemaining(fields::add);if(!node.isObject()||!fields.equals(Set.of(expected)))throw new Exception("RPC_EXACT_FIELDS_REQUIRED");}
    static String text(JsonNode node,String key)throws Exception {if(!node.path(key).isTextual())throw new Exception("RPC_TEXT_FIELD_REQUIRED");return node.get(key).textValue();}
    static UUID uuid(String value)throws Exception {UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new Exception("RPC_CANONICAL_UUID_REQUIRED");return id;}
    void exchange(String kind,Map<String,Object> payload)throws Exception {
      if(++sequence>256)throw new Exception("RPC_SEQUENCE_LIMIT");String nonce=UUID.randomUUID().toString();
      Map<String,Object> message=new LinkedHashMap<>(payload);message.put("type",kind);message.put("runId",run.toString());message.put("schema",schema);message.put("sequence",sequence);message.put("nonce",nonce);message.put("javaPid",ProcessHandle.current().pid());
      out.write((JSON.writeValueAsString(message)+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
      JsonNode ack=line(in);keys(ack,"type","runId","schema","sequence","nonce","proofSha256");
      if(!text(ack,"type").equals(kind+"_ACK")||!text(ack,"runId").equals(run.toString())||!text(ack,"schema").equals(schema)||!text(ack,"nonce").equals(nonce)||!ack.path("sequence").isIntegralNumber()||ack.path("sequence").longValue()!=sequence||!text(ack,"proofSha256").matches("[a-f0-9]{64}"))throw new Exception("RPC_ACK_BINDING_REFUSED");
    }
  }
  static ConsultationActivationRecoveryJdbcStep.Scope scope(JsonNode start)throws Exception {
    Rpc.keys(start,"type","runId","schema","operation","previousOutcome");
    if(!Rpc.text(start,"type").equals("START"))throw new Exception("RPC_START_REQUIRED");
    String schema=Rpc.text(start,"schema");var ns=Arrays.stream(ConsultationActivationRecoveryJdbcStep.Namespace.values()).filter(n->n.schema().equals(schema)).findFirst().orElseThrow(()->new Exception("RPC_SCHEMA_REFUSED"));
    return new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ns,Rpc.uuid(Rpc.text(start,"runId")));
  }
  static final class CurrentGuard implements ConsultationActivationRecoveryJdbcStep.LiveGuard {
    final Rpc rpc;final ConsultationActivationRecoveryFixtureGuard fixture;final ConsultationActivationRecoveryJdbcStep.Operation operation;Connection connection;
    CurrentGuard(Rpc rpc,ConsultationActivationRecoveryFixtureGuard fixture,ConsultationActivationRecoveryJdbcStep.Operation op){this.rpc=rpc;this.fixture=fixture;this.operation=op;}
    public void assertCurrent(ConsultationActivationRecoveryJdbcStep.Scope s)throws Exception {
      if(!s.runId().equals(rpc.run)||!s.namespace().schema().equals(rpc.schema))throw new Exception("RPC_SCOPE_DRIFT");
      rpc.exchange("GUARD",Map.of("phase",connection==null?"BEFORE_CONNECTION":"CURRENT_STEP"));
      if(connection!=null)fixture.assertCurrent(connection,s,operation);
    }
  }
  static ConsultationActivationRecoveryJdbcStep.Journal journal(Rpc rpc){return (s,step,phase,sha)->{
    if(!s.runId().equals(rpc.run)||!s.namespace().schema().equals(rpc.schema))throw new Exception("RPC_JOURNAL_SCOPE_DRIFT");
    rpc.exchange("JOURNAL",Map.of("step",step,"phase",phase,"sqlSha256",sha));
  };}
  public static void main(String[] args){
    try{
      if(args.length!=0)throw new Exception("RPC_ARGUMENTS_REFUSED");JsonNode start=Rpc.line(System.in);var s=scope(start);
      var op=ConsultationActivationRecoveryJdbcStep.Operation.valueOf(Rpc.text(start,"operation"));var previous=ConsultationActivationRecoveryJdbcStep.PreviousOutcome.valueOf(Rpc.text(start,"previousOutcome"));
      if(previous!=ConsultationActivationRecoveryJdbcStep.PreviousOutcome.CLEAR)throw new Exception("UNKNOWN_REQUIRES_RECONCILIATION");
      Rpc rpc=new Rpc(System.in,System.out,s.runId(),s.namespace().schema());var sql=ConsultationActivationRecoveryJdbcStep.FrozenSql.load(ROOT);var fixture=new ConsultationActivationRecoveryFixtureGuard(ROOT.resolve("fixture-contract68.json"));CurrentGuard guard=new CurrentGuard(rpc,fixture,op);
      guard.assertCurrent(s); // Host current approval/physical gates are checked before reading any credential.
      String url=System.getenv("LIFECYCLE_JDBC_URL"),user=System.getenv("LIFECYCLE_JDBC_USER"),password=System.getenv("LIFECYCLE_JDBC_PASSWORD");
      if(!"jdbc:dm://agent-platform-single-final-restore-20261009:5236".equals(url)||!"SYSDBA".equals(user)||password==null||password.isEmpty())throw new Exception("EXACT_MANAGED_CONNECTION_REQUIRED");
      try(Connection c=DriverManager.getConnection(url,user,password)){
        guard.connection=c;var result=ConsultationActivationRecoveryJdbcStep.executeStep(c,s,sql,op,previous,guard,journal(rpc));
        System.out.println(JSON.writeValueAsString(Map.of("type","RESULT","runId",s.runId().toString(),"schema",s.namespace().schema(),"outcome",result.outcome(),"completedDdlStatements",result.completedDdlStatements())));
      }
    }catch(Exception error){
      String code=error.getMessage()!=null&&error.getMessage().matches("[A-Z][A-Z0-9_]{0,100}")?error.getMessage():"ENTRY_REFUSED_OR_UNKNOWN_KEEP_STOPPED";
      String outcome=error instanceof ConsultationActivationRecoveryJdbcStep.Failure f?f.outcome:"REFUSED_OR_UNKNOWN";
      String state=error instanceof ConsultationActivationRecoveryJdbcStep.Failure f?f.sqlState:error instanceof SQLException q&&q.getSQLState()!=null&&q.getSQLState().matches("[A-Z0-9]{5}")?q.getSQLState():"UNKNOWN";
      int vendor=error instanceof ConsultationActivationRecoveryJdbcStep.Failure f?f.vendorCode:error instanceof SQLException q?q.getErrorCode():0;
      try{System.out.println(JSON.writeValueAsString(Map.of("type","ERROR","code",code,"outcome",outcome,"sqlState",state,"vendorCode",vendor,"automaticRetryAllowed",false)));}catch(Exception ignored){}
      System.exit(1);
    }
  }
}
