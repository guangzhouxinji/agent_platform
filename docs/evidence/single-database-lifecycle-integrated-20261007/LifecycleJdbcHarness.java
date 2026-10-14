import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** Dedicated evidence adapter. No application classes, Maven, shared target or connection switching. */
public final class LifecycleJdbcHarness {
  static final ObjectMapper JSON=new ObjectMapper();
  static String id(String s){if(s==null||!s.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))throw new IllegalArgumentException();return s;}
  static List<Map<String,Object>> query(Connection c,String sql,Object...args)throws SQLException {
    List<Map<String,Object>> out=new ArrayList<>();
    try(PreparedStatement p=c.prepareStatement(sql)){for(int n=0;n<args.length;n++)p.setObject(n+1,args[n]);p.setQueryTimeout(30);
      try(ResultSet r=p.executeQuery()){ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new TreeMap<>();for(int n=1;n<=m.getColumnCount();n++){Object v=r.getObject(n);if(v instanceof Clob)v=r.getString(n);if(v instanceof Long && Math.abs((Long)v)>9007199254740991L)v=v.toString();if(v!=null && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String) && !(v instanceof byte[]))v=v.toString();if(v instanceof byte[])v=Base64.getEncoder().encodeToString((byte[])v);row.put(m.getColumnLabel(n),v);}out.add(row);}}}
    out.sort(Comparator.comparing(x->{try{return JSON.writeValueAsString(x);}catch(Exception e){throw new RuntimeException(e);}}));return out;
  }
  static List<Map<String,Object>> metadata(ResultSet r)throws SQLException {List<Map<String,Object>> out=new ArrayList<>();try(r){ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new TreeMap<>();for(int n=1;n<=m.getColumnCount();n++)row.put(m.getColumnLabel(n),r.getString(n));out.add(row);}}out.sort(Comparator.comparing(Object::toString));return out;}
  static Map<String,Object> rowSummary(Connection c,String schema,String table)throws Exception {
    List<String> hashes=new ArrayList<>();long total=0;
    try(Statement p=c.createStatement();ResultSet r=p.executeQuery("SELECT * FROM \""+schema+"\".\""+table+"\"")) {
      ResultSetMetaData m=r.getMetaData();while(r.next()) {
        Map<String,Object> row=new TreeMap<>();
        for(int n=1;n<=m.getColumnCount();n++){Object v=r.getObject(n);
          if(v instanceof Blob){Blob blob=(Blob)v;if(blob.length()>8*1024*1024)throw new SQLException("capacity","LIMIT",-1);v=blob.getBytes(1,(int)blob.length());}
          if(v instanceof Clob){Clob clob=(Clob)v;if(clob.length()>8*1024*1024)throw new SQLException("capacity","LIMIT",-1);v=clob.getSubString(1,(int)clob.length());}
          if(v instanceof byte[])v=Base64.getEncoder().encodeToString((byte[])v);
          if(v!=null && !(v instanceof Boolean) && !(v instanceof String))v=v.toString();
          row.put(m.getColumnName(n),v);
        }
        byte[] bytes=JSON.writeValueAsBytes(row);total+=bytes.length;if(total>128*1024*1024)throw new SQLException("capacity","LIMIT",-1);
        hashes.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
    }
    Collections.sort(hashes);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(hashes)));
    return Map.of("table",table,"count",hashes.size(),"contentSha256",digest,"digestMethod","SORTED_TYPED_COLUMN_ROW_SHA256/v1");
  }
  static Map<String,Object> inventory(Connection c,JsonNode request)throws Exception {
    boolean dm=c.getMetaData().getDatabaseProductName().toLowerCase().contains("dm");
    List<Object> schemas=new ArrayList<>(),accounts=new ArrayList<>(),errors=new ArrayList<>();
    for(JsonNode s:request.get("schemas")) {String schema=id(s.asText());Map<String,Object> v=new LinkedHashMap<>();v.put("schema",schema);schemas.add(v);
      for(String category:List.of("objects","columns","constraints","foreignKeys","indexes","sequences","views","triggers","dependencies","rows"))v.put(category,new ArrayList<>());
      try {
        if((dm?query(c,"SELECT USERNAME FROM DBA_USERS WHERE USERNAME=?",schema):query(c,"SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME=?",schema)).size()!=1)throw new SQLException("schema absent","SCHEMA",-1);
        List<Map<String,Object>> objects=new ArrayList<>();
        if(dm) for(Map<String,Object> row:query(c,"SELECT OBJECT_NAME,OBJECT_TYPE FROM DBA_OBJECTS WHERE OWNER=? ORDER BY OBJECT_TYPE,OBJECT_NAME",schema)) objects.add(Map.of("name",row.get("OBJECT_NAME"),"type",row.get("OBJECT_TYPE")));
        else {
          for(Map<String,Object> row:query(c,"SELECT TABLE_NAME,TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=?",schema))objects.add(Map.of("name",row.get("TABLE_NAME"),"type",row.get("TABLE_TYPE")));
          for(String[] spec:new String[][]{{"SEQUENCES","SEQUENCE_NAME","SEQUENCE_SCHEMA","SEQUENCE"},{"TRIGGERS","TRIGGER_NAME","TRIGGER_SCHEMA","TRIGGER"},{"INDEXES","INDEX_NAME","INDEX_SCHEMA","INDEX"},{"DOMAINS","DOMAIN_NAME","DOMAIN_SCHEMA","DOMAIN"},{"ROUTINES","SPECIFIC_NAME","ROUTINE_SCHEMA","ROUTINE"},{"SYNONYMS","SYNONYM_NAME","SYNONYM_SCHEMA","SYNONYM"},{"CONSTANTS","CONSTANT_NAME","CONSTANT_SCHEMA","CONSTANT"}})
            for(Map<String,Object> row:query(c,"SELECT "+spec[1]+" FROM INFORMATION_SCHEMA."+spec[0]+" WHERE "+spec[2]+"=?",schema))objects.add(Map.of("name",row.get(spec[1]),"type",spec[3]));
        }
        objects.sort(Comparator.comparing(row->row.get("name").toString()+":"+row.get("type")));v.put("objects",objects);
        v.put("columns",dm?query(c,"SELECT * FROM DBA_TAB_COLUMNS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=?",schema));
        v.put("constraints",dm?query(c,"SELECT * FROM DBA_CONSTRAINTS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=?",schema));
        v.put("indexes",dm?query(c,"SELECT * FROM DBA_INDEXES WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_SCHEMA=?",schema));
        v.put("indexColumns",dm?query(c,"SELECT * FROM DBA_IND_COLUMNS WHERE INDEX_OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE INDEX_SCHEMA=?",schema));
        v.put("checkConstraints",dm?query(c,"SELECT CONSTRAINT_NAME,SEARCH_CONDITION FROM DBA_CONSTRAINTS WHERE OWNER=? AND CONSTRAINT_TYPE='C'",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=?",schema));
        v.put("constraintColumns",dm?query(c,"SELECT * FROM DBA_CONS_COLUMNS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=?",schema));
        v.put("sequences",dm?query(c,"SELECT * FROM DBA_SEQUENCES WHERE SEQUENCE_OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA=?",schema));
        v.put("views",dm?query(c,"SELECT * FROM DBA_VIEWS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=?",schema));
        v.put("triggers",dm?query(c,"SELECT * FROM DBA_TRIGGERS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=?",schema));
        // DM dependency catalog must succeed; missing catalogs are PARTIAL, never guessed empty.
        v.put("dependencies",dm?query(c,"SELECT * FROM DBA_DEPENDENCIES WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=?",schema));
        List<Object> fks=new ArrayList<>(),rows=new ArrayList<>();
        for(Map<String,Object> t:objects)if(List.of("TABLE","BASE TABLE").contains(t.get("type"))) {
          String table=id(t.get("name").toString());fks.addAll(metadata(c.getMetaData().getImportedKeys(null,schema,table)));
          rows.add(rowSummary(c,schema,table));
        }
        v.put("foreignKeys",fks);v.put("rows",rows);
      }catch(SQLException e){errors.add(Map.of("schema",schema,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode()));}
    }
    for(JsonNode a:request.get("accounts")){String name=id(a.asText());Map<String,Object> v=new LinkedHashMap<>();v.put("name",name);accounts.add(v);
      try {
        List<Map<String,Object>> users=dm?query(c,"SELECT USERNAME,ACCOUNT_STATUS FROM DBA_USERS WHERE USERNAME=?",name):query(c,"SELECT USER_NAME,IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME=?",name);
        v.put("exists",users.size()==1);v.put("account",users);
        v.put("systemPrivileges",dm?query(c,"SELECT * FROM DBA_SYS_PRIVS WHERE GRANTEE=?",name):List.of());
        v.put("roleGrants",dm?query(c,"SELECT * FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",name):query(c,"SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=? AND GRANTEDROLE IS NOT NULL",name));
        v.put("tableGrants",dm?query(c,"SELECT * FROM DBA_TAB_PRIVS WHERE GRANTEE=?",name):query(c,"SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=? AND GRANTEDROLE IS NULL",name));
        v.put("columnGrants",dm?query(c,"SELECT * FROM DBA_COL_PRIVS WHERE GRANTEE=?",name):query(c,"SELECT * FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE GRANTEE=?",name));
        Set<String> subjects=new TreeSet<>(List.of(name,"PUBLIC"));
        if(dm){boolean changed=true;while(changed){changed=false;for(String subject:new ArrayList<>(subjects))for(Map<String,Object> grant:query(c,"SELECT GRANTED_ROLE FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",subject))changed|=subjects.add(grant.get("GRANTED_ROLE").toString());}}
        List<Object> effective=new ArrayList<>();for(String subject:subjects){effective.add(Map.of("subject",subject,"system",dm?query(c,"SELECT * FROM DBA_SYS_PRIVS WHERE GRANTEE=?",subject):List.of(),"roles",dm?query(c,"SELECT * FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",subject):query(c,"SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=?",subject),"tables",dm?query(c,"SELECT * FROM DBA_TAB_PRIVS WHERE GRANTEE=?",subject):query(c,"SELECT * FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES WHERE GRANTEE=?",subject),"columns",dm?query(c,"SELECT * FROM DBA_COL_PRIVS WHERE GRANTEE=?",subject):query(c,"SELECT * FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE GRANTEE=?",subject)));}
        v.put("effectiveGrantSubjects",subjects);v.put("effectiveGrants",effective);
      }catch(SQLException e){errors.add(Map.of("account",name,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode()));}
    }
    return Map.of("format","SINGLE_DATABASE_LIVE_INVENTORY/v1","targetId",request.get("targetId").asText(),"databaseProduct",c.getMetaData().getDatabaseProductName(),"actualJdbc",true,"schemas",schemas,"accounts",accounts,"errors",errors,"complete",errors.isEmpty());
  }
  static Map<String,Object> perform(Connection c,JsonNode r)throws Exception {
    String action=r.path("action").asText();
    if(action.equals("inventory"))return inventory(c,r);
    if(action.equals("export")) {
      String url=c.getMetaData().getURL();if(!url.startsWith("jdbc:h2:file:")||!url.replace('\\','/').contains("/single-database-lifecycle-integrated-20261007/engineering-db/"))throw new IllegalArgumentException();
      StringBuilder script=new StringBuilder();try(Statement s=c.createStatement();ResultSet lines=s.executeQuery("SCRIPT")){while(lines.next())script.append(lines.getString(1)).append("\n");}
      if(script.length()>128*1024*1024)throw new IllegalArgumentException();return Map.of("nativeBase64",Base64.getEncoder().encodeToString(script.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"exportMethod","H2_SCRIPT_ENGINEERING_ONLY");
    }
    if(action.equals("catalog-controls-engineering")) {
      if(!c.getMetaData().getURL().replace('\\','/').contains("/single-database-lifecycle-integrated-20261007/engineering-db/"))throw new IllegalArgumentException();
      try(Statement s=c.createStatement()) {
        s.execute("CREATE SEQUENCE CONPROD.OWNED_ENGINEERING_SEQ START WITH 1");
        s.execute("CREATE VIEW CONPROD.OWNED_ENGINEERING_VIEW AS SELECT issuer,subject_id FROM CONPROD.id2_subject");
        s.execute("CREATE TRIGGER CONPROD.OWNED_ENGINEERING_TRIGGER BEFORE INSERT ON CONPROD.id2_subject FOR EACH ROW CALL 'InventoryOnlyTrigger'");
      }return Map.of("actualCatalogControls",true,"businessRowsWritten",0,"runtimeAdminGrant",false);
    }
    if(action.equals("probe-permission")) {
      String user=id(r.get("user").asText()),schema=id(r.get("schema").asText());
      if(!c.getMetaData().getURL().startsWith("jdbc:h2:file:")||System.getenv("LIFECYCLE_H2_"+user+"_PASSWORD")==null)throw new IllegalArgumentException();
      try(Connection p=DriverManager.getConnection(c.getMetaData().getURL(),user,System.getenv("LIFECYCLE_H2_"+user+"_PASSWORD"))){try {query(p,"SELECT COUNT(*) FROM "+schema+".ID2_SUBJECT");return Map.of("permitted",true,"actualJdbc",true);}catch(SQLException e){return Map.of("permitted",false,"actualJdbc",true,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode());}}
    }
    if(action.equals("restore-engineering")) {
      java.nio.file.Path target=java.nio.file.Path.of(r.get("restoreBase").asText()).toAbsolutePath().normalize(),allowed=java.nio.file.Path.of("docs/evidence/single-database-lifecycle-integrated-20261007/engineering-db").toAbsolutePath().normalize();
      if(!target.startsWith(allowed)||java.nio.file.Files.exists(java.nio.file.Path.of(target+".mv.db")))throw new IllegalArgumentException();
      java.nio.file.Files.createDirectories(target.getParent());String newUrl="jdbc:h2:file:"+target.toString().replace('\\','/')+";MODE=Oracle";
      byte[] nativeBytes=Base64.getDecoder().decode(r.get("nativeBase64").asText());if(nativeBytes.length>128*1024*1024)throw new IllegalArgumentException();
      try(Connection restored=DriverManager.getConnection(newUrl,"SA",System.getenv("LIFECYCLE_H2_SA_PASSWORD"))) {
        java.lang.reflect.Method run=Class.forName("org.h2.tools.RunScript").getMethod("execute",Connection.class,java.io.Reader.class);
        run.invoke(null,restored,new java.io.StringReader(new String(nativeBytes,java.nio.charset.StandardCharsets.UTF_8)));
        return inventory(restored,r);
      }finally{Arrays.fill(nativeBytes,(byte)0);}
    }
    if(!action.equals("execute"))throw new IllegalArgumentException();
    String schema=id(r.path("schema").asText()),password=System.getenv("LIFECYCLE_H2_"+schema+"_PASSWORD");
    if(!c.getMetaData().getURL().startsWith("jdbc:h2:file:")||password==null)throw new IllegalArgumentException();
    try(Connection owner=DriverManager.getConnection(c.getMetaData().getURL(),schema,password)){owner.setSchema(schema);owner.setAutoCommit(true);int done=0;for(JsonNode sql:r.get("statements")){try(Statement stmt=owner.createStatement()){stmt.setQueryTimeout(30);stmt.execute(sql.asText());done++;}}return Map.of("executedStatements",done);}
  }
  public static void main(String[] args)throws Exception {
    if(args.length==1&&args[0].equals("--session")) {
      try(Connection c=DriverManager.getConnection(System.getenv("LIFECYCLE_JDBC_URL"),System.getenv("LIFECYCLE_JDBC_USER"),System.getenv("LIFECYCLE_JDBC_PASSWORD"));java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))) {
        String line;while((line=reader.readLine())!=null){try {System.out.println(JSON.writeValueAsString(perform(c,JSON.readTree(line))));}catch(Exception e){System.out.println(JSON.writeValueAsString(Map.of("error","JDBC_OPERATION_REFUSED_OR_FAILED","sqlState",e instanceof SQLException?String.valueOf(((SQLException)e).getSQLState()):"GUARD","vendorCode",e instanceof SQLException?((SQLException)e).getErrorCode():0)));}System.out.flush();}
      }return;
    }
    JsonNode r=JSON.readTree(System.in);String url=System.getenv("LIFECYCLE_JDBC_URL"),user=System.getenv("LIFECYCLE_JDBC_USER"),password=System.getenv("LIFECYCLE_JDBC_PASSWORD");
    try(Connection c=DriverManager.getConnection(url,user,password)) {
      String action=r.path("action").asText();
      if(action.equals("inventory")){System.out.print(JSON.writeValueAsString(inventory(c,r)));return;}
      if(action.equals("export")) {
        if(!url.startsWith("jdbc:h2:file:")||!url.replace('\\','/').contains("/single-database-lifecycle-integrated-20261007/engineering-db/"))throw new IllegalArgumentException();
        StringBuilder script=new StringBuilder();try(Statement s=c.createStatement();ResultSet lines=s.executeQuery("SCRIPT")){while(lines.next()){script.append(lines.getString(1)).append("\n");if(script.length()>128*1024*1024)throw new IllegalArgumentException();}}
        System.out.print(JSON.writeValueAsString(Map.of("nativeBase64",Base64.getEncoder().encodeToString(script.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),"exportMethod","H2_SCRIPT_ENGINEERING_ONLY")));return;
      }
      if(!action.equals("execute")||List.of("SYSDBA","SYSAUDITOR","SYSSSO","SA").contains(user.toUpperCase())||!user.equalsIgnoreCase(r.path("schema").asText()))throw new IllegalArgumentException();
      c.setSchema(id(r.get("schema").asText()));c.setAutoCommit(true);int done=0;
      for(JsonNode sql:r.get("statements")){try(Statement s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql.asText());done++;}}
      System.out.print(JSON.writeValueAsString(Map.of("executedStatements",done,"ddlAtomicityClaimed",false)));
    }catch(Exception e){String state=e instanceof SQLException?((SQLException)e).getSQLState():"GUARD";int code=e instanceof SQLException?((SQLException)e).getErrorCode():0;System.out.print(JSON.writeValueAsString(Map.of("error","JDBC_OPERATION_REFUSED_OR_FAILED","sqlState",String.valueOf(state),"vendorCode",code)));System.exit(1);}
  }
}
