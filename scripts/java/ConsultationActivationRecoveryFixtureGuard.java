import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Current isolated fixture metadata checks, not a native restore-equivalence proof. */
public final class ConsultationActivationRecoveryFixtureGuard {
  public static final String CONTRACT_SHA="3c97aa455f8a212ddc445b6d8dd896db5d3303b23aec4cf03027474b4ca6ee34";
  private final JsonNode contract;
  public ConsultationActivationRecoveryFixtureGuard(Path contractFile)throws Exception {
    Path p=contractFile.toAbsolutePath().normalize();if(!p.equals(p.toRealPath())||Files.isSymbolicLink(p))refuse("FIXTURE_CONTRACT_REDIRECT");
    byte[] bytes;try(var in=Files.newInputStream(p)){bytes=in.readNBytes(65537);}
    if(bytes.length>65536||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(CONTRACT_SHA))refuse("FIXTURE_CONTRACT_SHA_MISMATCH");
    contract=new ObjectMapper().readTree(bytes);if(!contract.path("format").asText().equals("ACTR66_FIXED_FIXTURE_CONTRACT/v1"))refuse("FIXTURE_CONTRACT_VERSION");
  }
  static void refuse(String code)throws Exception {throw new Exception(code);}
  static void require(boolean ok,String code)throws Exception{if(!ok)refuse(code);}
  static String normalize(String text)throws Exception {
    if(text==null)return null;StringBuilder b=new StringBuilder();boolean quote=false;
    for(int i=0;i<text.length();i++){
      char ch=text.charAt(i);if(ch=='\''){b.append(ch);if(quote&&i+1<text.length()&&text.charAt(i+1)=='\''){b.append(text.charAt(++i));}else quote=!quote;}
      else if(quote)b.append(ch);
      else if(ch=='"'){int end=text.indexOf('"',i+1);require(end>i+1,"FIXTURE_IDENTIFIER_QUOTE_MALFORMED");String id=text.substring(i+1,end);require(id.matches("[A-Z][A-Z0-9_]*"),"FIXTURE_QUOTED_IDENTIFIER_UNSUPPORTED");b.append(id);i=end;}
      else if(!Character.isWhitespace(ch))b.append(Character.toUpperCase(ch));
    }
    require(!quote,"FIXTURE_CHECK_QUOTE_MALFORMED");return b.toString();
  }
  static String outer(String text){while(text.startsWith("(")&&text.endsWith(")")){int depth=0;boolean entire=true;for(int i=0;i<text.length()-1;i++){char c=text.charAt(i);if(c=='(')depth++;if(c==')')depth--;if(depth==0){entire=false;break;}}if(!entire)break;text=text.substring(1,text.length()-1);}return text;}
  private static long count(Connection c,String sql,String owner)throws Exception {
    try(PreparedStatement q=c.prepareStatement(sql)){q.setQueryTimeout(15);if(owner!=null)q.setString(1,owner);try(ResultSet rs=q.executeQuery()){require(rs.next(),"FIXTURE_COUNT_MISSING");long n=rs.getLong(1);require(!rs.wasNull()&&n>=0&&!rs.next(),"FIXTURE_COUNT_INVALID");return n;}}
  }
  private static String table(String schema,String table)throws Exception {require(Set.of("ID2_SUBJECT","ID2_SUBJECT_EVENT","ID2_ACTIVATION_RECOVERY_RECEIPT").contains(table),"FIXTURE_TABLE_FORBIDDEN");return "\""+schema+"\"."+table;}
  private void columns(Connection c,String schema,String t)throws Exception {
    columns(c,schema,t,contract.path("tables").path(t).path("columns"));
  }
  private void columns(Connection c,String schema,String t,JsonNode wanted)throws Exception {
    List<Map<String,String>> actual=new ArrayList<>();
    try(ResultSet rs=c.getMetaData().getColumns(null,schema,t,"%")){
      while(rs.next()){
        require(schema.equals(rs.getString("TABLE_SCHEM"))&&t.equals(rs.getString("TABLE_NAME")),"FIXTURE_COLUMN_OWNER_DRIFT");
        Map<String,String> row=new HashMap<>();for(String k:List.of("COLUMN_NAME","DATA_TYPE","COLUMN_SIZE","NULLABLE","COLUMN_DEF","ORDINAL_POSITION"))row.put(k,rs.getString(k));actual.add(row);
      }
    }
    actual.sort(Comparator.comparingInt(x->Integer.parseInt(x.get("ORDINAL_POSITION"))));require(actual.size()==wanted.size(),"FIXTURE_COLUMNS_CHANGED");
    for(int i=0;i<wanted.size();i++)for(String k:List.of("COLUMN_NAME","DATA_TYPE","COLUMN_SIZE","NULLABLE","COLUMN_DEF","ORDINAL_POSITION")){
      String expected=wanted.get(i).get(k).isNull()?null:wanted.get(i).get(k).asText();String observed=actual.get(i).get(k);
      require(Objects.equals(k.equals("COLUMN_DEF")?normalize(expected):expected,k.equals("COLUMN_DEF")?normalize(observed):observed),"FIXTURE_COLUMN_CONTRACT_DRIFT");
    }
  }
  private void keys(Connection c,String schema,String t)throws Exception {
    SortedMap<Integer,String> pk=new TreeMap<>();
    try(ResultSet rs=c.getMetaData().getPrimaryKeys(null,schema,t)){while(rs.next()){
      require(schema.equals(rs.getString("TABLE_SCHEM"))&&t.equals(rs.getString("TABLE_NAME")),"FIXTURE_PK_OWNER_DRIFT");require(pk.put((int)rs.getShort("KEY_SEQ"),rs.getString("COLUMN_NAME"))==null,"FIXTURE_PK_DUPLICATE");
    }}
    List<String> wanted=new ArrayList<>();contract.path("primaryKeys").path(t).forEach(x->wanted.add(x.asText()));require(new ArrayList<>(pk.values()).equals(wanted)&&pk.keySet().equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1,wanted.size()).boxed().toList())),"FIXTURE_PK_DRIFT");
    SortedMap<Integer,String> fk=new TreeMap<>();String fkName=null;
    try(ResultSet rs=c.getMetaData().getImportedKeys(null,schema,t)){while(rs.next()){
      require(schema.equals(rs.getString("FKTABLE_SCHEM"))&&schema.equals(rs.getString("PKTABLE_SCHEM"))&&t.equals(rs.getString("FKTABLE_NAME"))&&"ID2_SUBJECT".equals(rs.getString("PKTABLE_NAME")),"FIXTURE_CROSS_SCHEMA_FK");
      String n=rs.getString("FK_NAME");if(fkName==null)fkName=n;require(Objects.equals(fkName,n),"FIXTURE_EXTRA_FK");
      require(rs.getShort("DELETE_RULE")==DatabaseMetaData.importedKeyNoAction&&rs.getShort("UPDATE_RULE")==DatabaseMetaData.importedKeyNoAction,"FIXTURE_FK_ACTION_DRIFT");
      require(Objects.equals(rs.getString("FKCOLUMN_NAME"),rs.getString("PKCOLUMN_NAME")),"FIXTURE_FK_COLUMN_DRIFT");require(fk.put((int)rs.getShort("KEY_SEQ"),rs.getString("FKCOLUMN_NAME"))==null,"FIXTURE_FK_DUPLICATE");
    }}
    if(t.equals("ID2_SUBJECT"))require(fk.isEmpty(),"FIXTURE_EXTRA_SUBJECT_FK");else require(fk.equals(new TreeMap<>(Map.of(1,"ISSUER",2,"SUBJECT_ID"))),"FIXTURE_FK_DRIFT");
  }
  private void indexes(Connection c,String schema,String t)throws Exception {
    List<String> actual=new ArrayList<>();
    try(PreparedStatement q=c.prepareStatement("SELECT INDEX_NAME,INDEX_TYPE,UNIQUENESS FROM DBA_INDEXES WHERE OWNER=? AND TABLE_NAME=?")){
      q.setQueryTimeout(15);q.setString(1,schema);q.setString(2,t);try(ResultSet rs=q.executeQuery()){while(rs.next()){
        String n=rs.getString(1),kind=rs.getString(2),unique=rs.getString(3);List<String> columns=new ArrayList<>();
        try(PreparedStatement cols=c.prepareStatement("SELECT COLUMN_NAME FROM DBA_IND_COLUMNS WHERE INDEX_OWNER=? AND INDEX_NAME=? ORDER BY COLUMN_POSITION")){
          cols.setQueryTimeout(15);cols.setString(1,schema);cols.setString(2,n);try(ResultSet row=cols.executeQuery()){while(row.next())columns.add(row.getString(1));}
        }
        actual.add(kind+":"+unique+":"+String.join(",",columns));
      }}
    }
    List<String> wanted=new ArrayList<>();for(JsonNode ix:contract.path("indexShapes").path(t)){List<String> cols=new ArrayList<>();ix.path("columns").forEach(x->cols.add(x.asText()));wanted.add(ix.path("type").asText()+":"+ix.path("uniqueness").asText()+":"+String.join(",",cols));}
    Collections.sort(wanted);Collections.sort(actual);require(actual.equals(wanted),"FIXTURE_INDEX_SHAPE_DRIFT");
  }
  private void constraints(Connection c,String schema,Set<String> tables)throws Exception {
    Map<String,Map<String,String>> actual=new HashMap<>();Map<String,Map<String,Integer>> counts=new HashMap<>();
    try(PreparedStatement q=c.prepareStatement("SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,SEARCH_CONDITION,STATUS,VALIDATED FROM DBA_CONSTRAINTS WHERE OWNER=?")){
      q.setQueryTimeout(15);q.setString(1,schema);try(ResultSet rs=q.executeQuery()){while(rs.next()){
        String t=rs.getString(1),name=rs.getString(2),kind=rs.getString(3);require(tables.contains(t)&&"ENABLED".equals(rs.getString(5))&&"VALIDATED".equals(rs.getString(6)),"FIXTURE_CONSTRAINT_DISABLED_OR_EXTRA");
        require(Set.of("C","P","U","R").contains(kind),"FIXTURE_CONSTRAINT_KIND_DRIFT");counts.computeIfAbsent(t,k->new HashMap<>()).merge(kind,1,Integer::sum);
        if(kind.equals("C")){String expression=normalize(rs.getString(4));require(expression!=null&&actual.computeIfAbsent(t,k->new HashMap<>()).put(name,expression)==null,"FIXTURE_CHECK_DUPLICATE");}
      }}
    }
    for(String t:tables){
      Map<String,String> got=actual.getOrDefault(t,Map.of());Map<String,String> wanted=new HashMap<>();contract.path("tables").path(t).path("checks").fields().forEachRemaining(x->{try{wanted.put(x.getKey(),normalize(x.getValue().asText()));}catch(Exception bad){throw new IllegalArgumentException("FIXTURE_CONTRACT_INVALID");}});
      if(t.equals("ID2_SUBJECT_EVENT")){
        String operation=got.get("CK_ID2_SUBJECT_EVENT_OPERATION");if(operation!=null){String old="OPERATIONIN('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE')";require(outer(operation).equals(old)||outer(operation).equals(old.substring(0,old.length()-1)+",'REISSUE')"),"FIXTURE_OPERATION_DRIFT");wanted.put("CK_ID2_SUBJECT_EVENT_OPERATION",operation);}
      }
      if(t.equals("ID2_ACTIVATION_RECOVERY_RECEIPT")){
        String versions=got.get("CK_ID2_ACTIVATION_VERSIONS");require(versions!=null&&Set.of("EXPECTED_VERSION>=1ANDRESULT_VERSION=EXPECTED_VERSION+1","EXPECTED_VERSION>=1ANDRESULT_VERSION=(EXPECTED_VERSION+1)").contains(outer(versions)),"FIXTURE_RECEIPT_VERSIONS_DRIFT");wanted.put("CK_ID2_ACTIVATION_VERSIONS",versions);
      }
      require(got.equals(wanted),"FIXTURE_CHECK_CONTRACT_DRIFT");Map<String,Integer> kinds=counts.getOrDefault(t,Map.of());Map<String,Integer> wantedKinds=new HashMap<>(Map.of("C",wanted.size(),"P",1));wantedKinds.put(t.equals("ID2_SUBJECT")?"U":"R",1);require(kinds.equals(wantedKinds),"FIXTURE_CONSTRAINT_SET_DRIFT");
    }
  }
  void assertTableShape(Connection c,String schema,String t,JsonNode wantedColumns)throws Exception {
    table(schema,t);columns(c,schema,t,wantedColumns);keys(c,schema,t);indexes(c,schema,t);
  }
  public void assertCurrent(Connection c,ConsultationActivationRecoveryJdbcStep.Scope scope,ConsultationActivationRecoveryJdbcStep.Operation operation)throws Exception {
    String schema=scope.namespace().schema();require(count(c,"SELECT COUNT(*) FROM V$SESSIONS",null)==1,"SOLE_CURRENT_DB_SESSION_REQUIRED");
    Set<String> tables=new TreeSet<>();try(ResultSet rs=c.getMetaData().getTables(null,schema,"%",new String[]{"TABLE"})){while(rs.next()){require(schema.equals(rs.getString("TABLE_SCHEM")),"FIXTURE_TABLE_OWNER_DRIFT");require(tables.add(rs.getString("TABLE_NAME")),"FIXTURE_DUPLICATE_TABLE");}}
    Set<String> base=Set.of("ID2_SUBJECT","ID2_SUBJECT_EVENT");require(tables.containsAll(base)&&tables.size()<=3&&tables.stream().allMatch(t->base.contains(t)||t.equals("ID2_ACTIVATION_RECOVERY_RECEIPT")),"EXACT_FIXTURE_TABLES_REQUIRED");
    require(count(c,"SELECT COUNT(*) FROM DBA_OBJECTS WHERE OWNER=? AND NOT (OBJECT_TYPE='SCH' AND OBJECT_NAME=OWNER) AND OBJECT_TYPE NOT IN ('TABLE','INDEX','CONSTRAINT')",schema)==0,"EXTRA_FIXTURE_OBJECTS_FORBIDDEN");
    for(String t:tables){columns(c,schema,t);keys(c,schema,t);indexes(c,schema,t);}constraints(c,schema,tables);
    long subjects=count(c,"SELECT COUNT(*) FROM "+table(schema,"ID2_SUBJECT"),null),events=count(c,"SELECT COUNT(*) FROM "+table(schema,"ID2_SUBJECT_EVENT"),null),receipts=tables.contains("ID2_ACTIVATION_RECOVERY_RECEIPT")?count(c,"SELECT COUNT(*) FROM "+table(schema,"ID2_ACTIVATION_RECOVERY_RECEIPT"),null):0;
    require(subjects<=1&&events<=1&&receipts<=1,"BOUNDED_FIXTURE_COUNTS_REQUIRED");
    if(scope.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY)require(subjects==0&&events==0&&receipts==0,"EMPTY_CASE_DATA_CHANGED");
    if(scope.namespace()!=ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT)require(receipts==0,"RECEIPT_CASE_BINDING_REQUIRED");
    if(scope.namespace()!=ConsultationActivationRecoveryJdbcStep.Namespace.EVENT)require(events==0,"EVENT_CASE_BINDING_REQUIRED");
    if(operation==ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE&&scope.namespace()!=ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY)require(subjects==1&&(scope.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT?receipts==1:events==1),"NONEMPTY_CASE_INPUTS_REQUIRED");
    if(subjects>0)require(count(c,"SELECT COUNT(*) FROM "+table(schema,"ID2_SUBJECT")+subjectFixturePredicate(),null)==0,"ONLY_SELF_AUTHORED_SUBJECT_FIXTURE_ALLOWED");
    if(events>0)require(count(c,"SELECT COUNT(*) FROM "+table(schema,"ID2_SUBJECT_EVENT")+" WHERE issuer<>'urn:actr66:fixture' OR subject_id<>'actr66-fixture' OR operation<>'REISSUE' OR before_version<>1 OR after_version<>2 OR actor_ref<>'actr66-fixture' OR event_id<>'actr66-fixture'",null)==0,"ONLY_SELF_AUTHORED_EVENT_FIXTURE_ALLOWED");
    if(receipts>0)require(count(c,"SELECT COUNT(*) FROM "+table(schema,"ID2_ACTIVATION_RECOVERY_RECEIPT")+" WHERE issuer<>'urn:actr66:fixture' OR subject_id<>'actr66-fixture' OR actor_ref<>'actr66-fixture' OR request_id<>'actr66-fixture' OR expected_version<>1 OR result_version<>2",null)==0,"ONLY_SELF_AUTHORED_RECEIPT_FIXTURE_ALLOWED");
    require(count(c,"SELECT COUNT(*) FROM V$SESSIONS",null)==1,"DB_SESSION_EXCLUSIVITY_LOST");
  }
  static String subjectFixturePredicate() {
    // Fixed expired engineering data satisfies the original CHECK without issuing a credential.
    return " WHERE issuer<>'urn:actr66:fixture' OR subject_id<>'actr66-fixture' OR login_name<>'actr66-fixture' OR subject_kind<>'HUMAN' OR status<>'PENDING' OR password_hash IS NOT NULL OR activation_sha IS NULL OR activation_sha<>'"+"0".repeat(64)+"' OR activation_expires_at IS NULL OR activation_expires_at<>TIMESTAMP '2000-01-01 00:00:00' OR disabled_at IS NOT NULL OR credential_version<>1";
  }
}
