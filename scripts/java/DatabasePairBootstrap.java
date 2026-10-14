import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Empty owned two-instance infrastructure only. Never imports or activates product identities/data. */
public final class DatabasePairBootstrap {
 private final Properties p = new Properties();
 private final Map<String,Connection> admins = new LinkedHashMap<>();
 private final List<Scope> scopes = new ArrayList<>();
 private int checks, readsDenied, writesDenied, ddlDenied, roundTrips, wrongInstanceDenied;
 private record Scope(String kind, String domain, String owner, String app, List<String> files, List<String> tables) { }
 public static void main(String[] args) throws Exception {
  if(args.length!=3 || !Set.of("prepare","verify").contains(args[0]))throw new IllegalArgumentException("EXACT_MODE_PROPERTIES_REPORT_REQUIRED");
  var tool=new DatabasePairBootstrap();
  try {tool.run(args[0],Path.of(args[1]),Path.of(args[2]));}
  catch(SQLException e){throw new IllegalStateException("DB_PAIR_SQL_FAILED code="+e.getErrorCode()+" state="+e.getSQLState());}
  finally {for(var c:tool.admins.values())try{c.close();}catch(SQLException ignored){ }}
 }
 private void run(String mode,Path props,Path report) throws Exception {
  try(var r=Files.newBufferedReader(props,StandardCharsets.UTF_8)){p.load(r);}
  require(!Files.exists(report),"REPORT_EXISTS");
  for(String kind:List.of("business","authority")) {
   String url=value(kind+".url");require(url.matches("jdbc:dm://127\.0\.0\.1:[0-9]{4,5}"),"OWNED_LOOPBACK_REQUIRED");
   int port=Integer.parseInt(url.substring(url.lastIndexOf(':')+1));
   require(port>=1024&&port<=65535&&!Set.of(5236,5237,5301,5302,5311,5312,5319,5367,5370,5443,5444,5445).contains(port),"PROTECTED_ENDPOINT");
   String other=kind.equals("business")?"authority":"business";
   require(!url.equals(value(other+".url")),"DISTINCT_INSTANCES_REQUIRED");
   Connection db=null;
   for(int i=0;i<50;i++)try{db=DriverManager.getConnection(url,"SYSDBA",secret(kind.equals("business")?"BUSINESS_SYSDBA":"AUTHORITY_SYSDBA"));break;}catch(SQLException e){if(i==49)throw e;Thread.sleep(1500);}
   admins.put(kind,db);
   for(String domain:List.of("consultation","enforcement","adjudication")) {
    String prefix=kind+"."+domain+".";
    String owner=identifier(value(prefix+"owner")),app=identifier(value(prefix+"app"));
    scopes.add(new Scope(kind,domain,owner,app,List.of(value(prefix+"files").split(",")),List.of(value(prefix+"tables").split(","))));
   }
  }
  require(scopes.stream().flatMap(s->java.util.stream.Stream.of(s.owner,s.app)).distinct().count()==12,"TWELVE_DISTINCT_ACCOUNTS_REQUIRED");
  for(Scope s:scopes)for(String f:s.files) {
   require(f.matches("db/(dm8|identity-authority)/[A-Za-z0-9_]+\.sql"),"PINNED_SQL_PATH_REQUIRED");
   byte[] bytes=Files.readAllBytes(Path.of(value("workspace")).resolve(f));
   require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(value("sha."+f)),"MIGRATION_SHA_CHANGED");
  }
  if(mode.equals("prepare")) {
   for(Scope s:scopes)for(String user:List.of(s.owner,s.app))
    require(number(admins.get(s.kind),"SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?",user)==0,"ACCOUNT_EXISTS_REFUSE_PARTIAL_REINITIALIZATION");
   for(Scope s:scopes)prepare(s);
  }
  for(Scope s:scopes)verify(s);
  String json="{\"scope\":\"EMPTY_BASELINES_NOT_PRODUCT_ACTIVATION\",\"mode\":\""+mode+"\",\"schemas\":6,\"accounts\":12,\"checks\":"+checks+",\"crossReadsRejected\":"+readsDenied+",\"crossWritesRejected\":"+writesDenied+",\"runtimeDdlRejected\":"+ddlDenied+",\"ownedProbeRoundTrips\":"+roundTrips+",\"wrongInstanceAccountsRejected\":"+wrongInstanceDenied+",\"productSubjectsCreated\":0,\"productGrantsCreated\":0,\"passed\":true}";
  Files.writeString(report,json,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
  System.out.println("DB_PAIR_VERIFIED scopes=6 cross_read_denials="+readsDenied+" cross_write_denials="+writesDenied+" ddl_denials="+ddlDenied+" checks="+checks);
 }
 private void prepare(Scope scope) throws Exception {
  Connection admin=admins.get(scope.kind);
  try(var st=admin.createStatement()) {
   for(String user:List.of(scope.owner,scope.app))st.execute("CREATE USER "+user+" IDENTIFIED BY \""+secret(user)+"\"");
   st.execute("GRANT RESOURCE TO "+scope.owner);
  }
  try(var owner=connect(scope.kind,scope.owner);var st=owner.createStatement()) {
   for(String file:scope.files) {
    String sql=Files.readString(Path.of(value("workspace")).resolve(file));
    for(String statement:sql.split(";\\s*(?:\\r?\\n|$)"))if(!statement.isBlank())st.execute(statement);
   }
   for(String table:scope.tables)st.execute("GRANT "+permissions(scope.kind,identifier(table))+" ON "+table+" TO "+scope.app);
  }
 }
 private String permissions(String kind,String table) {
  if(kind.equals("business"))return table.endsWith("_EVENT")||table.equals("AGENT_RUN_REVISION")?"SELECT,INSERT":"SELECT,INSERT,UPDATE,DELETE";
  return switch(table) {
   case "ID2_MAINTAINER" -> "SELECT";
   case "ID2_AUTHORITY_WATERMARK","ADJ2_SOURCE_GENERATION" -> "SELECT,UPDATE";
   case "ID2_MAINTENANCE_AUDIT","ID4_SOURCE_RECOVERY_EVENT","ADJ2_SOURCE_EVENT" -> "SELECT,INSERT";
   default -> "SELECT,INSERT,UPDATE";
  };
 }
 private void verify(Scope scope) throws Exception {
  String probe="DB_PAIR_PROBE_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
  try(var owner=connect(scope.kind,scope.owner);var app=connect(scope.kind,scope.app)) {
   app.setSchema(scope.owner);
   for(String table:scope.tables) { identifier(table);require(number(owner,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?",table)==1,"BASELINE_TABLE_MISSING");number(app,"SELECT COUNT(*) FROM "+scope.owner+"."+table);checks++; }
   require(number(app,"SELECT COUNT(*) FROM "+scope.owner+".ID2_"+(scope.kind.equals("business")?"SUBJECT":"AUTHORITY_WATERMARK"))==0,"PREPARATION_CANNOT_ACTIVATE_PRODUCT");
   for(Scope foreign:scopes)if(foreign.kind.equals(scope.kind)&&!foreign.owner.equals(scope.owner)) {
    String table=foreign.kind.equals("business")?"ID2_SUBJECT":"ID2_AUTHORITY_WATERMARK";
    denied(app,"SELECT COUNT(*) FROM "+foreign.owner+"."+table);readsDenied++;
    denied(app,"UPDATE "+foreign.owner+"."+table+" SET ISSUER=ISSUER WHERE 1=0");writesDenied++;
   }
   boolean made=false;
   try(var own=owner.createStatement()) {
    own.execute("CREATE TABLE "+probe+" (PROBE_ID INTEGER PRIMARY KEY, PROBE_VALUE VARCHAR(40) NOT NULL)");made=true;
    own.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+probe+" TO "+scope.app);
    try(var st=app.createStatement()) {
     require(st.executeUpdate("INSERT INTO "+scope.owner+"."+probe+" VALUES(1,'one')")==1,"OWN_INSERT_FAILED");
     require(st.executeUpdate("UPDATE "+scope.owner+"."+probe+" SET PROBE_VALUE='two' WHERE PROBE_ID=1")==1,"OWN_UPDATE_FAILED");
     require(number(app,"SELECT COUNT(*) FROM "+scope.owner+"."+probe+" WHERE PROBE_VALUE='two'")==1,"OWN_READ_FAILED");
     require(st.executeUpdate("DELETE FROM "+scope.owner+"."+probe+" WHERE PROBE_ID=1")==1,"OWN_DELETE_FAILED");roundTrips++;
     String forbidden="DB_PAIR_FORBIDDEN_"+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase(Locale.ROOT);
     try {st.execute("CREATE TABLE "+scope.owner+"."+forbidden+"(ID INTEGER)");own.execute("DROP TABLE "+forbidden);throw new IllegalStateException("RUNTIME_DDL_WAS_ALLOWED");}
     catch(SQLException expected){require(expected.getErrorCode()==-5514||expected.getSQLState()!=null,"DDL_DENIAL_UNCLASSIFIED");ddlDenied++;checks++;}
    }
   } finally {if(made)try(var st=owner.createStatement()){st.execute("DROP TABLE "+probe);}}
  }
  String other=scope.kind.equals("business")?"authority":"business";
  try(var unexpected=connect(other,scope.app)){throw new IllegalStateException("RUNTIME_ACCOUNT_SHARED_ACROSS_INSTANCES");}
  catch(SQLException expected){wrongInstanceDenied++;checks++;}
 }
 private void denied(Connection c,String sql) throws SQLException {
  try(var st=c.createStatement()){st.execute(sql);throw new IllegalStateException("CROSS_SCHEMA_ACCESS_WAS_ALLOWED");}
  catch(SQLException expected){require(expected.getErrorCode()==-5514||expected.getSQLState()!=null,"CROSS_DENIAL_UNCLASSIFIED");checks++;}
 }
 private Connection connect(String kind,String user) throws SQLException {return DriverManager.getConnection(value(kind+".url"),user,secret(user));}
 private int number(Connection c,String sql,Object...args) throws SQLException {
  try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var r=q.executeQuery()){require(r.next(),"SQL_RESULT_REQUIRED");return r.getInt(1);}}
 }
 private String value(String name){String v=p.getProperty(name);require(v!=null&&!v.isBlank(),"CONFIG_VALUE_REQUIRED:"+name);return v;}
 private String secret(String name){String s=System.getenv("DBPAIR_"+name+"_PASSWORD");require(s!=null&&s.matches("[A-Za-z0-9!]{32,80}"),"PROCESS_SECRET_REQUIRED:"+name);return s;}
 private String identifier(String id){require(id.matches("[A-Z][A-Z0-9_]{2,29}")&&!id.startsWith("SYS"),"SAFE_SQL_IDENTIFIER_REQUIRED");return id;}
 private void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
