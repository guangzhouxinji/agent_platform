import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Native, fail-closed metadata checks for the pinned nine-DDL fixture prefix. */
public final class ConsultationActivationRecoveryFixturePrefix {
  final JsonNode plan;
  private final ConsultationActivationRecoveryFixtureGuard fixture;
  static final String OWNER_SQL="SELECT COUNT(*) FROM SYS.SYSOBJECTS S JOIN SYS.SYSOBJECTS U ON S.PID=U.ID WHERE S.NAME=? AND S.TYPE$='SCH' AND U.NAME='SYSDBA' AND U.TYPE$='UR' AND U.SUBTYPE$='USER'";
  static final String SCHEMA_SQL="SELECT COUNT(*) FROM SYS.SYSOBJECTS WHERE NAME=? AND TYPE$='SCH'";
  public ConsultationActivationRecoveryFixturePrefix(Path root)throws Exception {
    Path p=root.resolve("fixture-plan73.json").toAbsolutePath().normalize();
    for(Path part=p;part!=null;part=part.getParent())require(!Files.isSymbolicLink(part)&&part.equals(part.toRealPath()),"PREFIX_PLAN_REDIRECT");
    byte[] bytes;try(var in=Files.newInputStream(p)){bytes=in.readNBytes(65537);}
    require(bytes.length<=65536&&HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(ConsultationActivationRecoveryFixtureSeed.PLAN_SHA),"PREFIX_PLAN_SHA_CHANGED");
    plan=ConsultationActivationRecoveryJdbcEntry.JSON.readTree(bytes);
    require(plan.path("format").asText().equals("ACTR66_FIXED_PREPARATION_PLAN/v1"),"PREFIX_PLAN_FORMAT");
    fixture=new ConsultationActivationRecoveryFixtureGuard(root.resolve("fixture-contract68.json"));
  }
  static void require(boolean ok,String code)throws Exception {if(!ok)throw new Exception(code);}
  static long count(Connection c,String sql,String value)throws Exception {
    try(PreparedStatement q=c.prepareStatement(sql)) {
      q.setQueryTimeout(15);if(value!=null)q.setString(1,value);
      try(ResultSet rs=q.executeQuery()){require(rs.next(),"PREFIX_COUNT_MISSING");long n=rs.getLong(1);require(!rs.wasNull()&&n>=0&&!rs.next(),"PREFIX_COUNT_INVALID");return n;}
    }
  }
  void connection(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,boolean auto,String schema)throws Exception {
    DatabaseMetaData md=c.getMetaData();
    require("SYSDBA".equals(md.getUserName())&&"8.1.4.170".equals(md.getDatabaseProductVersion())&&md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("dm")&&"jdbc:dm://agent-platform-single-final-restore-20261009:5236".equals(md.getURL())&&schema.equals(c.getSchema())&&(!auto||c.getAutoCommit()),"PREFIX_EXACT_CONNECTION_REQUIRED");
    require(count(c,"SELECT COUNT(*) FROM V$SESSIONS",null)==1,"PREFIX_SOLE_SESSION_REQUIRED");
    try(PreparedStatement q=c.prepareStatement("SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME='COMPATIBLE_MODE'")) {
      q.setQueryTimeout(15);try(ResultSet rs=q.executeQuery()){require(rs.next()&&"0".equals(rs.getString(1))&&!rs.next(),"PREFIX_COMPATIBLE_MODE_REQUIRED");}
    }
  }
  void owner(Connection c,String schema)throws Exception {
    require(count(c,SCHEMA_SQL,"SYSDBA")==1&&count(c,OWNER_SQL,"SYSDBA")==1,"DEFAULT_SCHEMA_OWNER_CALIBRATION_REQUIRED");
    require(count(c,SCHEMA_SQL,schema)==1&&count(c,OWNER_SQL,schema)==1,"EXACT_SYSDBA_FIXTURE_OWNER_REQUIRED");
  }
  static Set<String> tables(Connection c,String schema)throws Exception {
    Set<String> names=new TreeSet<>();
    try(ResultSet rs=c.getMetaData().getTables(null,schema,"%",new String[]{"TABLE"})) {
      while(rs.next()){require(schema.equals(rs.getString("TABLE_SCHEM")),"PREFIX_TABLE_OWNER_DRIFT");require(names.add(rs.getString("TABLE_NAME")),"PREFIX_TABLE_DUPLICATE");}
    }
    Set<String> nativeNames=new TreeSet<>();
    try(PreparedStatement q=c.prepareStatement("SELECT TABLE_NAME FROM DBA_TABLES WHERE OWNER=?")) {
      q.setQueryTimeout(15);q.setString(1,schema);try(ResultSet rs=q.executeQuery()){while(rs.next())require(nativeNames.add(rs.getString(1)),"PREFIX_NATIVE_TABLE_DUPLICATE");}
    }
    require(names.equals(nativeNames),"PREFIX_NATIVE_TABLE_SET_DRIFT");return names;
  }
  private static String normalized(String expression)throws Exception {
    return ConsultationActivationRecoveryFixtureGuard.outer(ConsultationActivationRecoveryFixtureGuard.normalize(expression));
  }
  private void constraints(Connection c,String schema,JsonNode wanted)throws Exception {
    Map<String,Map<String,String>> checks=new HashMap<>();Map<String,Map<String,Integer>> kinds=new HashMap<>();
    try(PreparedStatement q=c.prepareStatement("SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,SEARCH_CONDITION,STATUS,VALIDATED FROM DBA_CONSTRAINTS WHERE OWNER=?")) {
      q.setQueryTimeout(15);q.setString(1,schema);try(ResultSet rs=q.executeQuery()){while(rs.next()) {
        String t=rs.getString(1),name=rs.getString(2),kind=rs.getString(3);
        require(wanted.has(t)&&"ENABLED".equals(rs.getString(5))&&"VALIDATED".equals(rs.getString(6))&&Set.of("C","P","U","R").contains(kind),"PREFIX_CONSTRAINT_SET_DRIFT");
        kinds.computeIfAbsent(t,k->new HashMap<>()).merge(kind,1,Integer::sum);
        if(kind.equals("C")){String text=rs.getString(4);require(text!=null&&checks.computeIfAbsent(t,k->new HashMap<>()).put(name,normalized(text))==null,"PREFIX_CHECK_DUPLICATE_OR_NULL");}
      }}
    }
    var fields=wanted.fields();while(fields.hasNext()) {
      var table=fields.next();String t=table.getKey();JsonNode required=table.getValue().path("checks");
      Map<String,String> got=checks.getOrDefault(t,Map.of());Set<String> names=new HashSet<>();required.fieldNames().forEachRemaining(names::add);
      require(got.keySet().equals(names),"PREFIX_CHECK_NAMES_DRIFT");
      for(String name:names) {
        JsonNode candidates=required.get(name);boolean matched=false;
        if(candidates.isArray()){for(JsonNode candidate:candidates)matched|=Objects.equals(got.get(name),normalized(candidate.asText()));}
        else matched=Objects.equals(got.get(name),normalized(candidates.asText()));
        require(matched,"PREFIX_CHECK_EXPRESSION_DRIFT");
      }
      Map<String,Integer> expected=new HashMap<>(Map.of("P",1,"C",names.size()));expected.put(t.equals("ID2_SUBJECT")?"U":"R",1);
      require(kinds.getOrDefault(t,Map.of()).equals(expected),"PREFIX_CONSTRAINT_COUNTS_DRIFT");
    }
  }
  public void assertPrefix(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,int index)throws Exception {
    require(index>=0&&index<=9,"PREFIX_INDEX_FORBIDDEN");String schema=s.namespace().schema();
    connection(c,s,true,index==0?"SYSDBA":schema);JsonNode wanted=plan.path("prefixes").get(index).path("tables");
    if(index==0) {
      require(count(c,SCHEMA_SQL,"SYSDBA")==1&&count(c,OWNER_SQL,"SYSDBA")==1,"DEFAULT_SCHEMA_OWNER_CALIBRATION_REQUIRED");
      require(count(c,SCHEMA_SQL,schema)==0&&count(c,"SELECT COUNT(*) FROM DBA_OBJECTS WHERE OWNER=?",schema)==0,"FIXTURE_SCHEMA_MUST_NOT_EXIST");
    } else owner(c,schema);
    Set<String> names=new TreeSet<>();wanted.fieldNames().forEachRemaining(names::add);
    require(tables(c,schema).equals(names),"PREFIX_EXACT_TABLES_REQUIRED");
    require(count(c,index<=1?"SELECT COUNT(*) FROM DBA_OBJECTS WHERE OWNER=? AND NOT (OBJECT_TYPE='SCH' AND OBJECT_NAME=OWNER)":"SELECT COUNT(*) FROM DBA_OBJECTS WHERE OWNER=? AND NOT (OBJECT_TYPE='SCH' AND OBJECT_NAME=OWNER) AND OBJECT_TYPE NOT IN ('TABLE','INDEX','CONSTRAINT')",schema)==0,"PREFIX_EXTRA_OBJECTS_FORBIDDEN");
    for(String t:names){fixture.assertTableShape(c,schema,t,wanted.path(t).path("columns"));require(count(c,"SELECT COUNT(*) FROM \""+schema+"\"."+t,null)==0,"PREFIX_ZERO_ROWS_REQUIRED");}
    constraints(c,schema,wanted);connection(c,s,true,index==0?"SYSDBA":schema);
  }
  void assertFinal(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s)throws Exception {
    if(s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY){assertPrefix(c,s,9);return;}
    String schema=s.namespace().schema();connection(c,s,true,schema);owner(c,schema);
    fixture.assertCurrent(c,s,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE);
    require(tables(c,schema).equals(Set.of("ID2_SUBJECT","ID2_SUBJECT_EVENT","ID2_ACTIVATION_RECOVERY_RECEIPT")),"FINAL_EXACT_TABLES_REQUIRED");
    try(PreparedStatement q=c.prepareStatement("SELECT SEARCH_CONDITION FROM DBA_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME='ID2_SUBJECT_EVENT' AND CONSTRAINT_NAME='CK_ID2_SUBJECT_EVENT_OPERATION' AND CONSTRAINT_TYPE='C'")) {
      q.setQueryTimeout(15);q.setString(1,schema);try(ResultSet rs=q.executeQuery()){require(rs.next()&&Objects.equals(normalized(rs.getString(1)),"OPERATIONIN('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE','REISSUE')")&&!rs.next(),"FINAL_NEW6_REQUIRED");}
    }
    String time="TIMESTAMP '2000-01-01 00:00:00'";
    require(count(c,"SELECT COUNT(*) FROM id2_subject WHERE created_at<>"+time,null)==0,"FINAL_SUBJECT_TIME_DRIFT");
    String historical=s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT?"id2_activation_recovery_receipt WHERE activation_expires_at<>"+time+" OR occurred_at<>"+time:"id2_subject_event WHERE occurred_at<>"+time;
    require(count(c,"SELECT COUNT(*) FROM "+historical,null)==0,"FINAL_HISTORY_TIME_DRIFT");connection(c,s,true,schema);
  }
}
