import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Component JDBC/catalog/RPC model only. Neither metadata nor vendor errors are native DM8 evidence. */
public final class ConsultationActivationRecoveryFixtureIntegrationTest {
  static int passed;static Path root;static JsonNode plan,contract;
  static ConsultationActivationRecoveryFixturePrefix prefix;
  interface Checked {void run()throws Exception;}
  static void check(boolean yes,String code){if(!yes)throw new AssertionError(code);}
  static void test(String name,Checked body)throws Exception{body.run();passed++;System.out.println("PASS "+name);}
  static Exception fails(Checked body)throws Exception{try{body.run();throw new AssertionError("REFUSAL_REQUIRED");}catch(AssertionError e){throw e;}catch(Exception e){return e;}}
  static Object zero(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;if(t==short.class)return (short)0;return null;}
  @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},h);}
  static Map<String,Object> row(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
  static ResultSet rows(List<Map<String,Object>> rows){int[] index={-1};return proxy(ResultSet.class,(p,m,a)->{
    if(m.getName().equals("next"))return ++index[0]<rows.size();
    if(Set.of("getString","getLong","getShort").contains(m.getName())){Object v=a[0] instanceof Integer i?new ArrayList<>(rows.get(index[0]).values()).get(i-1):rows.get(index[0]).get(a[0]);if(m.getName().equals("getString"))return v==null?null:v.toString();if(m.getName().equals("getShort"))return Short.parseShort(v.toString());return Long.parseLong(v.toString());}
    if(m.getName().equals("wasNull"))return false;return zero(m.getReturnType());
  });}
  static ResultSet count(long n){return rows(List.of(row("COUNT",n)));}
  static final class Model {
    final ConsultationActivationRecoveryJdbcStep.Scope scope;
    int index=0,commits=0,ddl=0;long subjects=0,events=0,receipts=0,sessions=1;
    String schema="SYSDBA",operation="OLD5",mode="0",version="8.1.4.170",fault=null,failStep=null,pendingStep=null;boolean receipt=false,auto=true;
    Model(ConsultationActivationRecoveryJdbcStep.Namespace ns){scope=new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ns,UUID.randomUUID());}
    Set<String> tables(){Set<String> n=new TreeSet<>();plan.path("prefixes").get(index).path("tables").fieldNames().forEachRemaining(n::add);if(receipt)n.add("ID2_ACTIVATION_RECOVERY_RECEIPT");if("extraTable".equals(fault))n.add("UNEXPECTED");return n;}
    JsonNode metadata(String t){return t.equals("ID2_ACTIVATION_RECOVERY_RECEIPT")?contract.path("tables").path(t):plan.path("prefixes").get(index).path("tables").path(t);}
    Map<String,String> checks(String t){Map<String,String> m=new LinkedHashMap<>();metadata(t).path("checks").fields().forEachRemaining(e->m.put(e.getKey(),e.getValue().isArray()?e.getValue().get(0).asText():e.getValue().asText()));
      if(t.equals("ID2_SUBJECT_EVENT")&&index==9){m.remove("CK_ID2_SUBJECT_EVENT_OPERATION");if(!operation.equals("ABSENT"))m.put("CK_ID2_SUBJECT_EVENT_OPERATION","operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE'"+(operation.equals("NEW6")?",'REISSUE'":"")+")");}
      if(t.equals("ID2_ACTIVATION_RECOVERY_RECEIPT"))m.put("CK_ID2_ACTIVATION_VERSIONS","EXPECTED_VERSION>=1 AND RESULT_VERSION=(EXPECTED_VERSION+1)");
      if("check".equals(fault)&&!m.isEmpty())m.put(m.keySet().iterator().next(),"1=1");return m;
    }
    DatabaseMetaData metadata(){return proxy(DatabaseMetaData.class,(p,m,a)->switch(m.getName()) {
      case "getUserName"->"SYSDBA";case "getDatabaseProductVersion"->version;case "getDatabaseProductName"->"DM COMPONENT PROXY";case "getURL"->"jdbc:dm://agent-platform-single-final-restore-20261009:5236";
      case "getTables"->rows(tables().stream().map(t->row("TABLE_SCHEM",scope.namespace().schema(),"TABLE_NAME",t)).toList());
      case "getColumns"->{String t=(String)a[2];List<Map<String,Object>> r=new ArrayList<>();for(JsonNode column:metadata(t).path("columns")){Map<String,Object> v=row("TABLE_SCHEM",scope.namespace().schema(),"TABLE_NAME",t);column.fields().forEachRemaining(e->v.put(e.getKey(),e.getValue().isNull()?null:e.getValue().asText()));r.add(v);}if("column".equals(fault)&&!r.isEmpty())r.get(0).put("COLUMN_SIZE","999");yield rows(r);}
      case "getPrimaryKeys"->{String t=(String)a[2];List<Map<String,Object>> r=new ArrayList<>();int i=0;for(JsonNode col:contract.path("primaryKeys").path(t))r.add(row("TABLE_SCHEM",scope.namespace().schema(),"TABLE_NAME",t,"KEY_SEQ",++i,"COLUMN_NAME",col.asText()));if("pk".equals(fault)&&!r.isEmpty())r.remove(0);yield rows(r);}
      case "getImportedKeys"->{String t=(String)a[2];List<Map<String,Object>> r=new ArrayList<>();if(!t.equals("ID2_SUBJECT")){int i=0;for(String col:List.of("ISSUER","SUBJECT_ID"))r.add(row("FKTABLE_SCHEM",scope.namespace().schema(),"PKTABLE_SCHEM","fkOwner".equals(fault)?"CONPROD":scope.namespace().schema(),"FKTABLE_NAME",t,"PKTABLE_NAME","ID2_SUBJECT","FK_NAME","FK_FIXED","DELETE_RULE",3,"UPDATE_RULE",3,"FKCOLUMN_NAME",col,"PKCOLUMN_NAME",col,"KEY_SEQ",++i));}yield rows(r);}
      default->zero(m.getReturnType());
    });}
    Connection connection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
      case "getMetaData"->metadata();case "getSchema"->schema;case "setSchema"->{schema=(String)a[0];yield null;}case "getAutoCommit"->auto;case "setAutoCommit"->{auto=(Boolean)a[0];yield null;}
      case "commit"->{commits++;yield null;}case "rollback"->null;
      case "prepareStatement"->statement((String)a[0],true);case "createStatement"->statement(null,false);
      default->zero(m.getReturnType());
    });}
    Object statement(String prepared,boolean typed){Map<Integer,String> params=new HashMap<>();Class<? extends Statement> type=typed?PreparedStatement.class:Statement.class;return proxy(type,(p,m,a)->{
      if(m.getName().equals("setString")){params.put((Integer)a[0],(String)a[1]);return null;}
      if(m.getName().equals("executeQuery"))return query(prepared==null?(String)a[0]:prepared,params);
      if(m.getName().equals("execute")){execute((String)a[0]);return false;}
      if(m.getName().equals("executeUpdate")){String sql=(String)a[0];int n=0;for(JsonNode seed:plan.path("seeds")){if(seed.path("sql").asText().equals(sql)){if(n==0)subjects=1;else if(n==1)receipts=1;else events=1;return 1;}n++;}throw new AssertionError("UNCONTROLLED_DML");}
      return zero(m.getReturnType());
    });}
    ResultSet query(String sql,Map<Integer,String> params)throws Exception {
      if(sql.equals(ConsultationActivationRecoveryFixturePrefix.SCHEMA_SQL)){boolean defaultSchema="SYSDBA".equals(params.get(1));return count(defaultSchema?1:index==0?0:1);}
      if(sql.equals(ConsultationActivationRecoveryFixturePrefix.OWNER_SQL))return count("owner".equals(fault)?0:"ownerDuplicate".equals(fault)?2:1);
      if(sql.contains("V$DM_INI"))return rows(List.of(row("PARA_VALUE",mode)));
      if(sql.contains("V$SESSIONS"))return count(sessions);
      if(sql.contains("FROM DBA_OBJECTS"))return count("extraObject".equals(fault)?1:index>0&&!sql.contains("NOT (OBJECT_TYPE='SCH' AND OBJECT_NAME=OWNER)")?1:0);
      if(sql.contains("FROM DBA_TABLES"))return rows(tables().stream().map(t->row("TABLE_NAME",t)).toList());
      if(sql.startsWith("SELECT SEARCH_CONDITION"))return operation.equals("ABSENT")?rows(List.of()):rows(List.of(row("SEARCH_CONDITION",checks("ID2_SUBJECT_EVENT").get("CK_ID2_SUBJECT_EVENT_OPERATION"))));
      if(sql.contains("FROM DBA_CONSTRAINTS")){
        List<Map<String,Object>> r=new ArrayList<>();for(String t:tables()){
          r.add(row("TABLE_NAME",t,"CONSTRAINT_NAME","PK_FIXED","CONSTRAINT_TYPE","P","SEARCH_CONDITION",null,"STATUS","ENABLED","VALIDATED","VALIDATED"));
          r.add(row("TABLE_NAME",t,"CONSTRAINT_NAME","SECOND_FIXED","CONSTRAINT_TYPE",t.equals("ID2_SUBJECT")?"U":"R","SEARCH_CONDITION",null,"STATUS","ENABLED","VALIDATED","VALIDATED"));
          for(var check:checks(t).entrySet())r.add(row("TABLE_NAME",t,"CONSTRAINT_NAME",check.getKey(),"CONSTRAINT_TYPE","C","SEARCH_CONDITION",check.getValue(),"STATUS","disabled".equals(fault)?"DISABLED":"ENABLED","VALIDATED","VALIDATED"));
        }return rows(r);
      }
      if(sql.contains("FROM DBA_INDEXES")){
        String t=params.get(2);List<Map<String,Object>> r=new ArrayList<>();int i=0;for(JsonNode ix:contract.path("indexShapes").path(t))r.add(row("INDEX_NAME",t+"_"+(i++),"INDEX_TYPE",ix.path("type").asText(),"UNIQUENESS",ix.path("uniqueness").asText()));if("index".equals(fault))r.add(row("INDEX_NAME",t+"_0","INDEX_TYPE","NORMAL","UNIQUENESS","UNIQUE"));return rows(r);
      }
      if(sql.contains("FROM DBA_IND_COLUMNS")){String name=params.get(2),t=name.substring(0,name.lastIndexOf('_'));int i=Integer.parseInt(name.substring(name.lastIndexOf('_')+1));List<Map<String,Object>> r=new ArrayList<>();for(JsonNode col:contract.path("indexShapes").path(t).get(i).path("columns"))r.add(row("COLUMN_NAME",col.asText()));return rows(r);}
      if(sql.startsWith("SELECT COUNT(*) FROM ")){
        if(sql.contains(" WHERE ")){if(sql.contains("WHERE operation='REISSUE'"))return count(events);if(sql.contains("WHERE operation NOT IN"))return count(0);return count("time".equals(fault)&&sql.contains("created_at")?1:0);}
        if(sql.toUpperCase(Locale.ROOT).contains("ID2_ACTIVATION_RECOVERY_RECEIPT"))return count(receipts);
        if(sql.toUpperCase(Locale.ROOT).contains("ID2_SUBJECT_EVENT"))return count(events);
        if(sql.toUpperCase(Locale.ROOT).contains("ID2_SUBJECT"))return count(subjects);
      }
      throw new AssertionError("UNREVIEWED_QUERY "+sql);
    }
    void execute(String sql)throws Exception {
      JsonNode selected=ConsultationActivationRecoveryFixtureEntry.selected(prefix,scope,"CASE_"+scope.namespace().name());
      JsonNode a=index==0?selected.path("create"):index<=8?plan.path("prepare").get(index-1):null;
      if(a!=null&&sql.equals(a.path("sql").asText())){if(a.path("step").asText().equals(failStep))throw new SQLException("PRIVATE_MARKER_NEVER_PRINT","08006",74);index++;ddl++;return;}
      for(JsonNode migration:plan.path("migration"))if(sql.equals(migration.path("sql").asText())&&migration.path("step").asText().equals(pendingStep)){
        String step=migration.path("step").asText();if(step.equals(failStep))throw new SQLException("PRIVATE_MARKER_NEVER_PRINT","08006",74);
        switch(step){case "V_DROP_OLD","U_DROP_OPERATION"->operation="ABSENT";case "V_ADD_NEW"->operation="NEW6";case "V_CREATE_RECEIPT"->receipt=true;case "U_DROP_RECEIPT"->receipt=false;case "U_ADD_OLD_OPERATION"->operation="OLD5";case "U_GUARD"->{if(subjects>0)throw new SQLException("PRIVATE_GUARD_MARKER","guardState".equals(fault)?null:"45000","wrongVendor".equals(fault)?-5517:-20001);return;}default->throw new AssertionError("UNCONTROLLED_STEP");}ddl++;return;
      }
      throw new AssertionError("UNCONTROLLED_DDL");
    }
  }
  static final class RpcPort {
    final ArrayDeque<Integer> input=new ArrayDeque<>();final ByteArrayOutputStream line=new ByteArrayOutputStream();final List<JsonNode> messages=new ArrayList<>();String refusal=null;Model model;
    final InputStream in=new InputStream(){public int read(){return input.isEmpty()?-1:input.removeFirst();}};
    final OutputStream out=new OutputStream(){public void write(int b)throws IOException {if(b!='\n'){line.write(b);return;}try{JsonNode m=ConsultationActivationRecoveryJdbcEntry.JSON.readTree(line.toByteArray());line.reset();messages.add(m);if(model!=null&&m.path("type").asText().equals("JOURNAL")&&m.path("phase").asText().equals("PREPARED"))model.pendingStep=m.path("step").asText();Map<String,Object> ack=new LinkedHashMap<>();for(String field:List.of("runId","schema","sequence","nonce"))ack.put(field,field.equals("sequence")?m.path(field).asLong():m.path(field).asText());ack.put("type",m.path("type").asText()+"_ACK");ack.put("proofSha256","a".repeat(64));if(refusal!=null&&refusal.equals(m.path("step").asText()+":"+m.path("phase").asText()))ack.put("nonce",UUID.randomUUID().toString());for(byte v:(ConsultationActivationRecoveryJdbcEntry.JSON.writeValueAsString(ack)+"\n").getBytes(StandardCharsets.UTF_8))input.add(v&255);}catch(Exception e){throw new IOException(e);}}};
  }
  static Map<String,Object> run(Model model,RpcPort port)throws Exception {
    return run(model,port,false);
  }
  static Map<String,Object> run(Model model,RpcPort port,boolean resumed)throws Exception {return run(model,port,resumed?1:0);}
  static Map<String,Object> run(Model model,RpcPort port,int completedBefore)throws Exception {
    port.model=model;
    var rpc=new ConsultationActivationRecoveryJdbcEntry.Rpc(port.in,port.out,model.scope.runId(),model.scope.namespace().schema());
    var live=new ConsultationActivationRecoveryFixtureEntry.Live(rpc,prefix,new ConsultationActivationRecoveryFixtureGuard(root.resolve("fixture-contract68.json")));
    JsonNode selected=ConsultationActivationRecoveryFixtureEntry.selected(prefix,model.scope,"CASE_"+model.scope.namespace().name());
    return ConsultationActivationRecoveryFixtureEntry.executeCase(model.connection(),model.scope,selected.path("operation").asText(),live,ConsultationActivationRecoveryJdbcStep.FrozenSql.load(root),new ConsultationActivationRecoveryFixtureSeed(root.resolve("fixture-plan73.json")),completedBefore);
  }
  public static void main(String[] args)throws Exception {
    root=Path.of(args[0]);prefix=new ConsultationActivationRecoveryFixturePrefix(root);plan=prefix.plan;contract=ConsultationActivationRecoveryJdbcEntry.JSON.readTree(Files.readAllBytes(root.resolve("fixture-contract68.json")));
    test("97 partial resumes only five remaining DDL",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=9;m.operation="ABSENT";RpcPort p=new RpcPort();var r=run(m,p,10);check(m.ddl==5&&r.get("completedDdlStatements").equals(5),"NEW_FIVE_REQUIRED");check(p.messages.stream().filter(x->x.path("type").asText().equals("JOURNAL")).findFirst().orElseThrow().path("step").asText().equals("V_ADD_NEW"),"NO_PREFIX_REPLAY");});
    for(String fault:List.of("extraObject","column","check","disabled"))test("97 prefix refuses "+fault,()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=9;m.operation="ABSENT";m.fault=fault;fails(()->run(m,new RpcPort(),10));check(m.ddl==0,"NO_DDL_BEFORE_PROOF");});
    test("97 changed old constraint refuses before DDL",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=9;fails(()->run(m,new RpcPort(),10));check(m.ddl==0,"NO_REPLAY");});
    test("97 other namespace cannot resume",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EVENT);m.index=9;m.operation="ABSENT";fails(()->run(m,new RpcPort(),10));check(m.ddl==0,"NAMESPACE_BOUND");});
    test("96 exact schema-only resume starts at first unexecuted DDL",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=1;RpcPort p=new RpcPort();Map<String,Object> result=run(m,p,true);check(m.ddl==14&&result.get("completedDdlStatements").equals(14),"NEW_DDL_COUNT_REQUIRED");check(p.messages.stream().noneMatch(x->x.path("step").asText().equals("P_CREATE_SCHEMA")),"NO_REPEATED_CREATE");});
    test("96 schema-only resume refuses extra object before any DDL",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=1;m.fault="extraObject";fails(()->run(m,new RpcPort(),true));check(m.ddl==0,"NO_DDL_BEFORE_PREFIX_PROVEN");});
    test("96 missing schema prefix is not inferred from old history",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);fails(()->run(m,new RpcPort(),true));check(m.ddl==0,"NO_MISSING_SCHEMA_RESUME");});
    test("96 other namespaces cannot borrow schema-only resume",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EVENT);m.index=1;fails(()->run(m,new RpcPort(),true));check(m.ddl==0,"NO_OTHER_NAMESPACE_RESUME");});
    for(int i=0;i<=9;i++){final int n=i;test("component exact native prefix "+i,()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=n;m.schema=n==0?"SYSDBA":m.scope.namespace().schema();prefix.assertPrefix(m.connection(),m.scope,n);check(m.ddl==0,"READ_ONLY_PREFIX");});}
    for(String fault:List.of("owner","ownerDuplicate","extraObject","extraTable","column","pk","fkOwner","index","check","disabled"))test("component prefix refuses "+fault,()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=9;m.schema=m.scope.namespace().schema();m.fault=fault;fails(()->prefix.assertPrefix(m.connection(),m.scope,9));check(m.ddl==0,"NO_REFUSED_PREFIX_WRITE");});
    for(String fault:List.of("mode","version","session","schema","rows"))test("component current prefix refuses "+fault,()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.index=9;m.schema=m.scope.namespace().schema();switch(fault){case "mode"->m.mode="1";case "version"->m.version="unknown";case "session"->m.sessions=2;case "schema"->m.schema="CONPROD";default->m.subjects=1;}fails(()->prefix.assertPrefix(m.connection(),m.scope,9));});
    for(var ns:ConsultationActivationRecoveryJdbcStep.Namespace.values())test("component complete case invokes real prefix/Step/seed/RPC classes "+ns,()->{
      Model m=new Model(ns);RpcPort p=new RpcPort();Map<String,Object> result=run(m,p);boolean empty=ns==ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY;
      check(m.ddl==(empty?15:12)&&m.commits==(empty?0:2),"ACTUAL_COMPONENT_CALL_COUNTS");check(result.get("vendorCode").equals(empty?0:-20001)&&result.get("sqlState").equals(empty?"NONE":"45000"),"RAW_VENDOR_RESULT");
      List<String> expected=new ArrayList<>();JsonNode selected=ConsultationActivationRecoveryFixtureEntry.selected(prefix,m.scope,"CASE_"+ns.name());for(JsonNode step:selected.path("journal")){expected.add(step.path("step").asText()+":PREPARED");expected.add(step.path("step").asText()+":"+(!empty&&step.path("step").asText().equals("U_GUARD")?"REFUSED":"COMPLETED"));}
      List<String> actual=p.messages.stream().filter(x->x.path("type").asText().equals("JOURNAL")).map(x->x.path("step").asText()+":"+x.path("phase").asText()).toList();check(actual.equals(expected),"CLOSED_CASE_JOURNAL_ORDER");check(p.messages.size()<256,"RPC_LIMIT_NOT_EXCEEDED");System.out.println("CASE_"+ns+"_RPC="+p.messages.size());
    });
    for(String step:List.of("P_CREATE_SCHEMA","P_ADD_KIND","V_ADD_NEW","U_DROP_OPERATION"))test("component SQL attempt error keeps UNKNOWN "+step,()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);m.failStep=step;RpcPort p=new RpcPort();Exception e=fails(()->run(m,p));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.outcome.equals("UNKNOWN")&&f.vendorCode==74&&f.sqlState.equals("08006"),"UNKNOWN_RAW_SQL_ERROR");check(p.messages.stream().anyMatch(x->x.path("step").asText().equals(step)&&x.path("phase").asText().equals("UNKNOWN")),"UNKNOWN_JOURNAL_REQUIRED");});
    test("component wrong vendor is not accepted as history guard refusal",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT);m.fault="wrongVendor";Exception e=fails(()->run(m,new RpcPort()));check(e instanceof ConsultationActivationRecoveryJdbcStep.Failure f&&f.vendorCode==-5517,"NO_PERMISSION_ERROR_AS_GUARD_SUCCESS");});
    test("component null SQLSTATE is retained as UNKNOWN, never invented",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EVENT);m.fault="guardState";check(run(m,new RpcPort()).get("sqlState").equals("UNKNOWN"),"NO_INVENTED_SQLSTATE");});
    test("component PREPARED ACK mismatch performs no DDL",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);RpcPort p=new RpcPort();p.refusal="P_CREATE_SCHEMA:PREPARED";fails(()->run(m,p));check(m.ddl==0,"NO_DDL_BEFORE_DURABLE_ACK");});
    test("component COMPLETED ACK mismatch retains committed DDL and stops",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);RpcPort p=new RpcPort();p.refusal="P_CREATE_SCHEMA:COMPLETED";fails(()->run(m,p));check(m.ddl==1&&m.index==1,"NO_BLIND_DDL_RETRY");});
    test("component schema binding is closed",()->{Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY);fails(()->ConsultationActivationRecoveryFixtureEntry.selected(prefix,m.scope,"CASE_EVENT"));});
    for(String fault:List.of("time","oldOperation","missingReceipt"))test("component final case check refuses "+fault,()->{
      Model m=new Model(ConsultationActivationRecoveryJdbcStep.Namespace.EVENT);m.index=9;m.schema=m.scope.namespace().schema();m.subjects=1;m.events=1;m.receipt=true;m.operation="NEW6";
      if(fault.equals("time"))m.fault="time";if(fault.equals("oldOperation"))m.operation="OLD5";if(fault.equals("missingReceipt"))m.receipt=false;
      fails(()->prefix.assertFinal(m.connection(),m.scope));
    });
    System.out.println("FIXTURE74_COMPONENT_TESTS="+passed+"; DM8_CONNECTIONS=0; HOST_AUTHORITY=RPC_COMPONENT_PORT; NATIVE_METADATA=COMPONENT_MODEL");
  }
}
