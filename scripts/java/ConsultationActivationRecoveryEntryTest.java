import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Offline components only. Proxies never load a DM8 driver or connect. */
public final class ConsultationActivationRecoveryEntryTest {
  interface Checked {void run() throws Exception;}
  static int passed;
  static final ObjectMapper JSON=ConsultationActivationRecoveryJdbcEntry.JSON;
  static final UUID RUN=UUID.fromString("00000000-0000-0000-0000-000000000068");
  static void good(String name,Checked fn)throws Exception{fn.run();passed++;System.out.println("PASS "+name);}
  static void bad(String name,Checked fn)throws Exception{try{fn.run();}catch(Exception e){passed++;System.out.println("PASS refused "+name);return;}throw new AssertionError("expected refusal: "+name);}
  @SuppressWarnings("unchecked") static <T>T proxy(Class<T> c,InvocationHandler h){return (T)Proxy.newProxyInstance(c.getClassLoader(),new Class[]{c},h);}
  static ResultSet rows(List<Map<String,Object>> data){int[] index={-1};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
    case "next" -> ++index[0]<data.size();case "close" -> null;case "wasNull" -> false;
    case "getString" -> {Object v=data.get(index[0]).get(a[0].toString());yield v==null?null:v.toString();}
    case "getLong" -> Long.parseLong(data.get(index[0]).get(a[0].toString()).toString());
    case "getShort" -> Short.parseShort(data.get(index[0]).get(a[0].toString()).toString());
    default -> throw new AssertionError("unexpected ResultSet "+m.getName());});}
  static Map<String,Object> row(Object... args){Map<String,Object> r=new HashMap<>();for(int i=0;i<args.length;i+=2)r.put(args[i].toString(),args[i+1]);return r;}
  static class Fixture {
    JsonNode contract;boolean receipt=false;String drift="";int sessions=1,subjects=0,events=0,receipts=0;String secretField="";String schema="ACTR66_EMPTY";
    Fixture(JsonNode c){contract=c.deepCopy();((ObjectNode)contract.path("tables").path("ID2_ACTIVATION_RECOVERY_RECEIPT").path("checks")).put("CK_ID2_ACTIVATION_VERSIONS","EXPECTED_VERSION>=1 AND RESULT_VERSION=EXPECTED_VERSION+1");}
    List<String> tables(){return receipt?List.of("ID2_SUBJECT","ID2_SUBJECT_EVENT","ID2_ACTIVATION_RECOVERY_RECEIPT"):List.of("ID2_SUBJECT","ID2_SUBJECT_EVENT");}
    Connection connection(){DatabaseMetaData md=proxy(DatabaseMetaData.class,(p,m,a)->{
      List<Map<String,Object>> result=new ArrayList<>();String t=a.length>2?a[2].toString():"";
      switch(m.getName()){
      case "getTables":for(String table:tables())result.add(row("TABLE_SCHEM",schema,"TABLE_NAME",table));if(drift.equals("extraTable"))result.add(row("TABLE_SCHEM",schema,"TABLE_NAME","EXTRA"));break;
      case "getColumns":for(JsonNode col:contract.path("tables").path(t).path("columns")){Map<String,Object> r=row("TABLE_SCHEM",schema,"TABLE_NAME",t);col.fields().forEachRemaining(e->r.put(e.getKey(),e.getValue().isNull()?null:e.getValue().asText()));result.add(r);}if(drift.equals("columnOwner"))result.get(0).put("TABLE_SCHEM","OTHER");break;
      case "getPrimaryKeys":int n=0;for(JsonNode col:contract.path("primaryKeys").path(t))result.add(row("TABLE_SCHEM",schema,"TABLE_NAME",t,"KEY_SEQ",++n,"COLUMN_NAME",col.asText()));if(drift.equals("pkDuplicate"))result.add(result.get(0));break;
      case "getImportedKeys":if(!t.equals("ID2_SUBJECT"))for(int i=0;i<2;i++)result.add(row("FKTABLE_SCHEM",schema,"PKTABLE_SCHEM",drift.equals("fkOwner")?"OTHER":schema,"FKTABLE_NAME",t,"PKTABLE_NAME","ID2_SUBJECT","FK_NAME","FIXTURE_FK","KEY_SEQ",i+1,"FKCOLUMN_NAME",i==0?"ISSUER":"SUBJECT_ID","PKCOLUMN_NAME",i==0?"ISSUER":"SUBJECT_ID","DELETE_RULE",drift.equals("fkAction")?0:3,"UPDATE_RULE",3));break;
      default:throw new AssertionError("unexpected metadata "+m.getName());
      }return rows(result);
    });
    return proxy(Connection.class,(p,m,a)->switch(m.getName()){
      case "getMetaData" -> md;
      case "prepareStatement" -> statement((String)a[0]);
      default -> throw new AssertionError("unexpected connection "+m.getName());});}
    PreparedStatement statement(String sql){Map<Integer,String> params=new HashMap<>();return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
      case "close","setQueryTimeout" -> null;
      case "setString" -> {params.put((Integer)a[0],a[1].toString());yield null;}
      case "executeQuery" -> rows(query(sql,params));
      default -> throw new AssertionError("unexpected statement "+m.getName());});}
    List<Map<String,Object>> query(String sql,Map<Integer,String> params){List<Map<String,Object>> r=new ArrayList<>();
      if(sql.contains("DBA_IND_COLUMNS")){String[] id=params.get(2).split(":");for(JsonNode c:contract.path("indexShapes").path(id[0]).get(Integer.parseInt(id[1])).path("columns"))r.add(row("1",c.asText()));}
      else if(sql.contains("DBA_INDEXES")){int i=0;for(JsonNode ix:contract.path("indexShapes").path(params.get(2)))r.add(row("1",params.get(2)+":"+i++,"2",ix.path("type").asText(),"3",ix.path("uniqueness").asText()));if(drift.equals("extraIndex"))r.add(row("1",params.get(2)+":0","2","NORMAL","3","NONUNIQUE"));}
      else if(sql.contains("DBA_CONSTRAINTS")){for(String t:tables()){
        contract.path("tables").path(t).path("checks").fields().forEachRemaining(e->r.add(row("1",t,"2",e.getKey(),"3","C","4",e.getValue().asText(),"5",drift.equals("disabled")?"DISABLED":"ENABLED","6","VALIDATED")));
        r.add(row("1",t,"2","PK","3","P","4",null,"5","ENABLED","6","VALIDATED"));r.add(row("1",t,"2","UK_OR_FK","3",t.equals("ID2_SUBJECT")?"U":"R","4",null,"5","ENABLED","6","VALIDATED"));}}
      else if(sql.contains("V$SESSIONS"))r.add(row("1",sessions));
      else if(sql.contains("DBA_OBJECTS"))r.add(row("1",drift.equals("extraObject")?1:0));
      else if(sql.contains(" WHERE "))r.add(row("1",!secretField.isEmpty()&&sql.toLowerCase().contains(secretField)?1:0));
      else r.add(row("1",drift.equals("badRows")?2:sql.contains("ID2_ACTIVATION_RECOVERY_RECEIPT")?receipts:sql.contains("ID2_SUBJECT_EVENT")?events:sql.contains("ID2_SUBJECT")?subjects:0));
      return r;
    }
  }
  static void check(Fixture f,Path contract)throws Exception{new ConsultationActivationRecoveryFixtureGuard(contract).assertCurrent(f.connection(),new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY,RUN),ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD);}
  static void rpc(String mutation)throws Exception {
    PipedInputStream input=new PipedInputStream();PipedOutputStream reply=new PipedOutputStream(input);ByteArrayOutputStream output=new ByteArrayOutputStream(){public void flush()throws IOException{
      try{JsonNode sent=JSON.readTree(toByteArray());ObjectNode ack=JSON.createObjectNode();for(String k:List.of("runId","schema","sequence","nonce"))ack.set(k,sent.get(k));ack.put("type","GUARD_ACK");ack.put("proofSha256","a".repeat(64));
        switch(mutation){case "run":ack.put("runId",UUID.randomUUID().toString());break;case "nonce":ack.put("nonce",UUID.randomUUID().toString());break;case "sequence":ack.put("sequence",0);break;case "type":ack.put("type","JOURNAL_ACK");break;case "extra":ack.put("extra",true);break;case "proof":ack.put("proofSha256","bad");break;case "fraction":ack.put("sequence",1.0);break;case "schema":ack.put("schema","ACTR66_EVENT");break;}
        reply.write((JSON.writeValueAsString(ack)+"\n").getBytes(StandardCharsets.UTF_8));reply.flush();
      }catch(Exception e){throw new IOException(e);}}};
    try{new ConsultationActivationRecoveryJdbcEntry.Rpc(input,output,RUN,"ACTR66_EMPTY").exchange("GUARD",Map.of("phase","BEFORE_CONNECTION"));}finally{input.close();reply.close();}
  }
  static void exit68(Path file,JsonNode contract)throws Exception{
    for(var namespace:List.of(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT,ConsultationActivationRecoveryJdbcStep.Namespace.EVENT)){
      good("exit68 nonempty "+namespace,()->{Fixture f=new Fixture(contract);f.schema=namespace.schema();f.subjects=1;if(namespace==ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT){f.receipt=true;f.receipts=1;}else f.events=1;new ConsultationActivationRecoveryFixtureGuard(file).assertCurrent(f.connection(),new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,namespace,RUN),ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE);});
    }
    for(String field:List.of("password_hash","activation_sha","activation_expires_at","disabled_at","credential_version"))bad("exit68 subject rejects "+field,()->{Fixture f=new Fixture(contract);f.subjects=1;f.secretField=field;new ConsultationActivationRecoveryFixtureGuard(file).assertCurrent(f.connection(),new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT,RUN),ConsultationActivationRecoveryJdbcStep.Operation.V_DROP_OLD);});
    bad("exit68 wrong receipt case",()->{Fixture f=new Fixture(contract);f.receipt=true;f.receipts=1;check(f,file);});
    bad("exit68 history required",()->{Fixture f=new Fixture(contract);f.schema="ACTR66_RECEIPT";f.subjects=1;f.receipt=true;new ConsultationActivationRecoveryFixtureGuard(file).assertCurrent(f.connection(),new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT,RUN),ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE);});
    for(String field:List.of("event_id","request_id"))bad("exit68 fixed engineering "+field,()->{Fixture f=new Fixture(contract);f.subjects=1;f.secretField=field;f.receipt=field.equals("request_id");f.receipts=f.receipt?1:0;f.events=f.receipt?0:1;var ns=f.receipt?ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT:ConsultationActivationRecoveryJdbcStep.Namespace.EVENT;f.schema=ns.schema();new ConsultationActivationRecoveryFixtureGuard(file).assertCurrent(f.connection(),new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ns,RUN),ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE);});
    System.out.println("EXIT68_NEW_JAVA_COMPONENTS="+passed+"; INHERITED38_NOT_RERUN; DRIVER_CONNECTIONS=0");
  }
  public static void main(String[] args)throws Exception {
    Path file=Path.of(args[0]);JsonNode contract=JSON.readTree(Files.readAllBytes(file));if(args.length==2&&args[1].equals("exit68")){exit68(file,contract);return;}
    good("base metadata",()->check(new Fixture(contract),file));good("derived receipt metadata",()->{Fixture f=new Fixture(contract);f.receipt=true;check(f,file);});
    for(String drift:List.of("extraTable","columnOwner","pkDuplicate","fkOwner","fkAction","extraIndex","disabled","extraObject","badRows"))bad(drift,()->{Fixture f=new Fixture(contract);f.drift=drift;check(f,file);});
    for(String field:List.of("COLUMN_NAME","DATA_TYPE","COLUMN_SIZE","NULLABLE","COLUMN_DEF","ORDINAL_POSITION"))bad("column "+field,()->{Fixture f=new Fixture(contract);((ObjectNode)f.contract.path("tables").path("ID2_SUBJECT").path("columns").get(0)).put(field,field.equals("ORDINAL_POSITION")?"999":"drift");check(f,file);});
    bad("second session",()->{Fixture f=new Fixture(contract);f.sessions=2;check(f,file);});
    bad("receipt weakened check",()->{Fixture f=new Fixture(contract);f.receipt=true;((ObjectNode)f.contract.path("tables").path("ID2_ACTIVATION_RECOVERY_RECEIPT").path("checks")).put("CK_ID2_ACTIVATION_VERSIONS","EXPECTED_VERSION>=1 OR RESULT_VERSION=EXPECTED_VERSION+1");check(f,file);});
    good("RPC current binding",()->rpc(""));for(String mutation:List.of("run","nonce","sequence","type","extra","proof","fraction","schema"))bad("RPC "+mutation,()->rpc(mutation));
    for(String raw:List.of("{\"a\":1,\"a\":2}\n","{\"a\":1,\"\\u0061\":2}\n","{} {}\n","[]\n","{\"x\":1}\n"))bad("strict line/key "+raw,()->{var n=ConsultationActivationRecoveryJdbcEntry.Rpc.line(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));ConsultationActivationRecoveryJdbcEntry.Rpc.keys(n,"a");});
    bad("bounded line",()->ConsultationActivationRecoveryJdbcEntry.Rpc.line(new ByteArrayInputStream((" ".repeat(4097)+"\n").getBytes())));
    bad("eof",()->ConsultationActivationRecoveryJdbcEntry.Rpc.line(new ByteArrayInputStream("{}".getBytes())));
    good("literal case and spaces retained",()->{if(!ConsultationActivationRecoveryFixtureGuard.normalize(" status = 'Human  A' ").equals("STATUS='Human  A'"))throw new Exception();});
    bad("unterminated literal",()->ConsultationActivationRecoveryFixtureGuard.normalize("'abc"));bad("mixed-case quoted identifier",()->ConsultationActivationRecoveryFixtureGuard.normalize("\"Status\"='HUMAN'"));
    System.out.println("ENTRY68_COMPONENTS="+passed+"; DRIVER_CONNECTIONS=0; PHYSICAL_ACCEPTANCE=false");
  }
}
