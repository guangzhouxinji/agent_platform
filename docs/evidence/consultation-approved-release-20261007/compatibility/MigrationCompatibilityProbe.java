import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Independent H2-only evidence. Does not load application classes or external JDBC drivers. */
public class MigrationCompatibilityProbe {
 static Path out; static int seq=0,failures=0; static List<String> events=new ArrayList<>();
 static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
 static void event(String id,boolean pass,String detail){if(!pass)failures++;events.add("{\"id\":"+q(id)+",\"passed\":"+pass+",\"detail\":"+q(detail)+"}");}
 static Connection db() throws Exception {return DriverManager.getConnection("jdbc:h2:mem:compat"+(seq++)+";MODE=Oracle;DB_CLOSE_DELAY=-1","sa","");}
 static List<String> sql(Path p)throws Exception{return Arrays.stream(Files.readString(p).replaceAll("(?m)--[^\\r\\n]*","").split(";")).map(String::trim).filter(x->!x.isEmpty()).toList();}
 static void exec(Connection c,String s)throws SQLException{try(Statement st=c.createStatement()){st.execute(s);}}
 static void apply(Connection c,Path p)throws Exception{for(String s:sql(p))exec(c,s);}
 static List<Path> paths(String name)throws Exception{return Files.readAllLines(out.resolve(name)).stream().filter(x->!x.isBlank()).map(Path::of).toList();}
 static boolean hasTable(Connection c,String table)throws Exception{try(ResultSet rs=c.getMetaData().getTables(null,"PUBLIC",table.toUpperCase(),null)){return rs.next();}}
 static Set<String> cols(Connection c,String table)throws Exception{Set<String>s=new TreeSet<>();try(ResultSet rs=c.getMetaData().getColumns(null,"PUBLIC",table.toUpperCase(),null)){while(rs.next())s.add(rs.getString("COLUMN_NAME"));}return s;}
 static Map<String,String> shape(Connection c)throws Exception{Map<String,String>s=new TreeMap<>();try(ResultSet rs=c.getMetaData().getColumns(null,"PUBLIC",null,null)){while(rs.next())s.put(rs.getString("TABLE_NAME")+"."+rs.getString("COLUMN_NAME"),rs.getString("TYPE_NAME")+":"+rs.getInt("COLUMN_SIZE")+":"+rs.getInt("DECIMAL_DIGITS")+":"+rs.getInt("NULLABLE"));}return s;}

 static Set<String> indexes(Connection c)throws Exception{Set<String>s=new TreeSet<>();try(var tables=c.getMetaData().getTables(null,"PUBLIC",null,new String[]{"TABLE"})){while(tables.next()){String t=tables.getString("TABLE_NAME");try(var rs=c.getMetaData().getIndexInfo(null,"PUBLIC",t,false,false)){while(rs.next())if(rs.getString("COLUMN_NAME")!=null)s.add(t+"|"+rs.getString("INDEX_NAME")+"|"+rs.getShort("ORDINAL_POSITION")+"|"+rs.getString("COLUMN_NAME")+"|"+rs.getBoolean("NON_UNIQUE"));}}}return s;}
 static String stringMap(Map<String,String>map){return "{"+String.join(",",map.entrySet().stream().map(e->q(e.getKey())+":"+q(e.getValue())).toList())+"}";}
 static String stringList(Collection<String>list){return "["+String.join(",",list.stream().map(MigrationCompatibilityProbe::q).toList())+"]";}

 static List<String> constraintNames(Connection c)throws Exception{List<String>s=new ArrayList<>();try(var st=c.createStatement();var rs=st.executeQuery("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='PUBLIC'")){while(rs.next())s.add(rs.getString(1));}return s;}
 static void rejected(Connection c,String id,String s,String expectedState)throws Exception{try{exec(c,s);event(id,false,"unexpected success");}catch(SQLException e){event(id,expectedState==null||e.getSQLState().equals(expectedState),"SQLState="+e.getSQLState()+" expected="+expectedState);}}
 static void supplement(Connection c)throws Exception{supplement(c,"metadata-columns.tsv");}
 static void supplement(Connection c,String file)throws Exception{
  Map<String,List<String[]>>tables=new TreeMap<>();for(String line:Files.readAllLines(out.resolve(file))){var a=line.split("\t");tables.computeIfAbsent(a[0],x->new ArrayList<>()).add(a);}
  for(var e:tables.entrySet()){
   if(!hasTable(c,e.getKey())){var fields=e.getValue().stream().map(x->x[1]+" "+x[2]).toList();exec(c,"CREATE TABLE "+e.getKey()+"("+String.join(",",fields)+")");}
   else{var existing=cols(c,e.getKey());for(var col:e.getValue())if(!existing.contains(col[1]))exec(c,"ALTER TABLE "+e.getKey()+" ADD "+col[1]+" "+col[2]);}
  }
 }
 static String query(Connection c,String s){try(var ps=c.prepareStatement(s)){
  int n=ps.getParameterMetaData().getParameterCount();for(int i=1;i<=n;i++)ps.setNull(i,Types.NULL);
  try(var rs=ps.executeQuery()){var m=rs.getMetaData();List<String>cols=new ArrayList<>();for(int i=1;i<=m.getColumnCount();i++)cols.add(m.getColumnLabel(i)+":"+m.getColumnTypeName(i)+":"+m.getPrecision(i)+":"+m.getScale(i));return "OK|"+String.join(",",cols);}
 }catch(SQLException e){return "UNRESOLVED|"+e.getSQLState()+"|"+e.getErrorCode();}}
 static void fullShape(List<Path>base,List<Path>forward)throws Exception{
  try(var c=db()){
   for(Path p:base){apply(c,p);event("baseline_"+p.getFileName(),true,"Empty independent H2 baseline only; never live replay");}
   var before=shape(c);var constraints=constraintNames(c);var beforeIndexes=indexes(c);
   for(Path p:forward){apply(c,p);event("forward_"+p.getFileName(),true,"Unchanged SQL executed in H2 Oracle mode; no statement adaptation");}
   var after=shape(c);event("baseline_columns_preserved",before.entrySet().stream().allMatch(x->Objects.equals(after.get(x.getKey()),x.getValue())),"Type/precision/nullable shape compared for "+before.size()+" baseline columns");
   event("baseline_constraints_preserved",constraintNames(c).containsAll(constraints),"Named constraints retained; ck_con4_field_use semantics separately expanded");
   event("baseline_index_shapes_preserved",indexes(c).containsAll(beforeIndexes),"Compared "+beforeIndexes.size()+" baseline index columns/order/uniqueness; H2 only");
   Files.writeString(out.resolve("h2-schema-shapes.json"),"{\"beforeColumns\":"+stringMap(before)+",\"afterColumns\":"+stringMap(after)+",\"beforeConstraintNames\":"+stringList(constraints)+",\"afterConstraintNames\":"+stringList(constraintNames(c))+",\"beforeIndexes\":"+stringList(beforeIndexes)+",\"afterIndexes\":"+stringList(indexes(c))+"}\n");
   long added=after.keySet().stream().map(x->x.substring(0,x.indexOf('.'))).distinct().filter(t->before.keySet().stream().noneMatch(k->k.startsWith(t+"."))).count();event("nine_added_tables",added==9,"Added table count="+added);
  }
  // Query shape only: snapshot additions do not assert original keys/checks/row content.
  try(var c=db()){
   for(Path p:base)apply(c,p);supplement(c);
   List<String>selects=Files.readAllLines(out.resolve("old-jar-selects.txt"));List<String>pre=new ArrayList<>();for(String s:selects)pre.add(query(c,s));
   for(Path p:forward)apply(c,p);
   var authority=db();supplement(authority,"authority-columns.tsv");
   int good=0,unknown=0,regressions=0;List<String>results=new ArrayList<>();
   for(int i=0;i<selects.size();i++){String statement=selects.get(i);boolean auth=statement.matches("(?is).*\\b(?:id2_authority_watermark|id2_maintainer)\\b.*");String post=auth?query(authority,statement):query(c,statement);if(auth)pre.set(i,query(authority,statement));boolean ok=pre.get(i).startsWith("OK|")&&post.equals(pre.get(i));if(ok)good++;else if(!pre.get(i).startsWith("OK|"))unknown++;else regressions++;
    results.add("{\"databaseShape\":"+q(auth?"AUTHORITY_UNCHANGED_NO_MIGRATION":"BUSINESS_PRE_POST")+",\"queryIndex\":"+i+",\"before\":"+q(pre.get(i))+",\"after\":"+q(post)+",\"shapePreserved\":"+ok+"}");}
   Files.writeString(out.resolve("old-jar-query-results.json"),"{\"scope\":\"H2 snapshot-assisted schema-only empty SELECT execution, NULL parameters; not HTTP/data mapping/authorization\",\"queries\":["+String.join(",\n",results)+"],\"preserved\":"+good+",\"unresolvedBefore\":"+unknown+",\"regressions\":"+regressions+"}\n");
   event("old_jar_resolved_select_shapes_preserved",regressions==0,"Preserved="+good+", unresolved before="+unknown+", regressions="+regressions+"; unresolved are NOT passes");
  }
 }
 static Path version(List<Path>list,String v){return list.stream().filter(x->x.getFileName().toString().startsWith(v)).findFirst().orElseThrow();}
 static void v92(List<Path>f,List<Path>r)throws Exception{
  Path p=version(f,"V092");List<String>stmts=sql(p);
  try(var c=db()){
   exec(c,"CREATE TABLE con4_source_field_use(use_code VARCHAR(16) NOT NULL,CONSTRAINT ck_con4_field_use CHECK(use_code IN ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE')))");
   for(String use:List.of("QUERY","SAVE","INDEX","DISPLAY","DOWNLOAD","MODEL","SHARE"))exec(c,"INSERT INTO con4_source_field_use VALUES('"+use+"')");
   rejected(c,"v092_comparison_denied_before","INSERT INTO con4_source_field_use VALUES('COMPARISON')","23513");
   for(int i=0;i<stmts.size();i++){
    exec(c,stmts.get(i));rejected(c,"v092_unknown_denied_after_step_"+(i+1),"INSERT INTO con4_source_field_use VALUES('UNKNOWN')","23513");
    if(i==0)rejected(c,"v092_comparison_denied_during_first_overlap","INSERT INTO con4_source_field_use VALUES('COMPARISON')","23513");
    else{exec(c,"INSERT INTO con4_source_field_use VALUES('COMPARISON')");exec(c,"DELETE FROM con4_source_field_use WHERE use_code='COMPARISON'");event("v092_comparison_permitted_step_"+(i+1),true,"SQL admissible only, not authorization or source activation");}
   }
   exec(c,"INSERT INTO con4_source_field_use VALUES('COMPARISON')");rejected(c,"u092_nonempty_comparison_refused",sql(version(r,"U092")).getFirst(),"23513");
   event("u092_refusal_keeps_comparison",constraintNames(c).contains("CK_CON4_FIELD_USE"),"Active expanded constraint retained on H2 failed first statement");
   exec(c,"DELETE FROM con4_source_field_use WHERE use_code='COMPARISON'");apply(c,version(r,"U092"));rejected(c,"u092_empty_comparison_denied_again","INSERT INTO con4_source_field_use VALUES('COMPARISON')","23513");
  }
  try(var c=db()){rejected(c,"v092_missing_table_refused",stmts.getFirst(),"42S02");}
  try(var c=db()){
   exec(c,"CREATE TABLE con4_source_field_use(use_code VARCHAR(16))");exec(c,stmts.getFirst());rejected(c,"v092_missing_old_constraint_refused",stmts.get(1),"90057");
   event("v092_partial_DDL_requires_operator_reconciliation",constraintNames(c).contains("CK_CON4_FIELD_USE_EXPAND"),"Missing original constraint leaves added expanded constraint: not transaction rollback; stop and reconcile");
  }
 }
 static void parentFixture(Connection c)throws Exception{
  exec(c,"CREATE TABLE con3_worker_delegation(delegation_id VARCHAR(36) PRIMARY KEY)");
  exec(c,"CREATE TABLE con5_comparison_input(task_id VARCHAR(36),comparison_id VARCHAR(36),preparation_id VARCHAR(36),PRIMARY KEY(task_id,comparison_id),UNIQUE(comparison_id,preparation_id))");
  exec(c,"CREATE TABLE con5_comparison_candidate(comparison_id VARCHAR(36),candidate_id VARCHAR(64),PRIMARY KEY(comparison_id,candidate_id))");
  exec(c,"CREATE TABLE con5_preparation(task_id VARCHAR(36),preparation_id VARCHAR(36),PRIMARY KEY(task_id,preparation_id))");
  exec(c,"INSERT INTO con3_worker_delegation VALUES('d')");exec(c,"INSERT INTO con5_comparison_input VALUES('t','c','p')");exec(c,"INSERT INTO con5_comparison_candidate VALUES('c','x')");exec(c,"INSERT INTO con5_preparation VALUES('t','p')");
 }
 static List<String> order=List.of("con5_comparison_delegation","con5_candidate_attempt","con5_candidate_lease","con5_matrix_invocation","con5_matrix_report","con5_matrix_row","con5_matrix_citation","con5_matrix_receipt","con5_preparation_request");
 static Object value(String table,String col,int type){
  return switch(col.toLowerCase()){
   case "delegation_id"->"d";case "task_id"->"t";case "comparison_id"->"c";case "candidate_id"->"x";case "preparation_id"->"p";case "attempt_id"->"a";case "report_id"->"r";
   case "status"->table.equals("con5_matrix_invocation")?"RESERVED":"ACTIVE";
   case "created_at"->Timestamp.valueOf("2026-10-07 00:00:00");case "expires_at","lease_until"->Timestamp.valueOf("2026-10-07 01:00:00");
   case "cancel_epoch","ordinal","row_ordinal","citation_ordinal"->0L;
   default->type==Types.BIGINT||type==Types.INTEGER?1L:col.toLowerCase().endsWith("sha")?"a".repeat(64):"offline-key-only";
  };
 }
 static void insert(Connection c,String table,Map<String,Object>changes)throws Exception{
  List<String>names=new ArrayList<>();List<Object>vals=new ArrayList<>();try(var rs=c.getMetaData().getColumns(null,"PUBLIC",table.toUpperCase(),null)){
   while(rs.next()){String n=rs.getString("COLUMN_NAME");names.add(n);vals.add(changes.containsKey(n.toLowerCase())?changes.get(n.toLowerCase()):value(table,n,rs.getInt("DATA_TYPE")));}}
  try(var ps=c.prepareStatement("INSERT INTO "+table+"("+String.join(",",names)+") VALUES("+String.join(",",Collections.nCopies(names.size(),"?"))+")")){for(int i=0;i<vals.size();i++)ps.setObject(i+1,vals.get(i));ps.executeUpdate();}
 }
 static Connection fixture(List<Path>f)throws Exception{var c=db();parentFixture(c);for(Path p:f)if(!p.getFileName().toString().startsWith("V092"))apply(c,p);return c;}
 static void negative(List<Path>f,String table,String col,Object v,String state)throws Exception{
  try(var c=fixture(f)){
   for(String t:order){if(t.equals(table))break;insert(c,t,Map.of());}
   try{insert(c,table,Collections.singletonMap(col,v));event(table+"_reject_"+col+"_"+v,false,"unexpected success");}
   catch(SQLException e){event(table+"_reject_"+col+"_"+v,state.equals(e.getSQLState()),"SQLState="+e.getSQLState()+" expected="+state);}
  }
 }
 static void graph(List<Path>f,List<Path>r)throws Exception{
  try(var c=fixture(f)){
   for(String t:order){insert(c,t,Map.of());event(t+"_positive_insert",true,"Exact new-table DDL; preexisting parents are synthetic key-only shapes, not domain validation");
    try{insert(c,t,Map.of());event(t+"_duplicate_refused",false,"unexpected success");}catch(SQLException e){event(t+"_duplicate_refused",e.getSQLState().equals("23505"),"SQLState="+e.getSQLState());}}
   for(String v:List.of("U101","U100","U099","U098")){String first=sql(version(r,v)).getFirst();rejected(c,v+"_nonempty_first_statement_refused",first,"23513");}
   for(String t:order)event(t+"_retained_after_reverse_refusal",hasTable(c,t),"No DROP executed after guard refusal");
  }
  for(String t:order)negative(f,t,switch(t){case "con5_preparation_request"->"task_id";case "con5_matrix_row","con5_matrix_citation","con5_matrix_report"->"report_id";case "con5_comparison_delegation"->"delegation_id";case "con5_candidate_lease"->"comparison_id";default->"attempt_id";},null,"23502");
  unique(f,"con5_candidate_attempt",Map.of("attempt_id","a2"));unique(f,"con5_matrix_report",Map.of("report_id","r2"));unique(f,"con5_matrix_row",Map.of("ordinal",1L));unique(f,"con5_matrix_receipt",Map.of("attempt_id","a2"));unique(f,"con5_preparation_request",Map.of("request_id","request2"));
  negative(f,order.get(0),"delegation_id","missing","23506");negative(f,order.get(0),"expires_at",Timestamp.valueOf("2026-10-07 00:00:00"),"23513");
  negative(f,order.get(1),"task_version",0L,"23513");negative(f,order.get(1),"cancel_epoch",-1L,"23513");negative(f,order.get(1),"fencing_token",0L,"23513");negative(f,order.get(1),"status","UNKNOWN","23513");negative(f,order.get(1),"lease_until",Timestamp.valueOf("2026-10-07 00:00:00"),"23513");negative(f,order.get(1),"candidate_id","missing","23506");negative(f,order.get(1),"delegation_id","missing","23506");negative(f,order.get(1),"task_id","missing","23506");
  negative(f,order.get(2),"fencing_token",2L,"23506");negative(f,order.get(2),"status","UNKNOWN","23513");negative(f,order.get(2),"fencing_token",0L,"23513");
  negative(f,order.get(3),"status","UNKNOWN","23513");negative(f,order.get(3),"attempt_id","missing","23506");
  negative(f,order.get(4),"fencing_token",2L,"23506");negative(f,order.get(4),"expires_at",Timestamp.valueOf("2026-10-07 00:00:00"),"23513");negative(f,order.get(4),"fencing_token",0L,"23513");negative(f,order.get(4),"task_id","missing","23506");
  negative(f,order.get(5),"ordinal",-1L,"23513");negative(f,order.get(5),"report_id","missing","23506");
  negative(f,order.get(6),"row_ordinal",1L,"23506");negative(f,order.get(6),"citation_ordinal",-1L,"23513");
  negative(f,order.get(7),"report_id","missing","23506");negative(f,order.get(7),"attempt_id","missing","23506");
  negative(f,order.get(8),"preparation_id","missing","23506");negative(f,order.get(8),"task_id","missing","23506");
  // Required precondition graph deliberately absent: unchanged DDL must fail rather than infer baseline.
  for(Path p:f)if(!p.getFileName().toString().startsWith("V092"))try(var c=db()){rejected(c,p.getFileName()+"_missing_parent_refused",sql(p).getFirst(),"42S02");}
  try(var c=fixture(f)){for(String v:List.of("U101","U100","U099","U098"))apply(c,version(r,v));event("empty_new_tables_reverse_in_H2",order.stream().allMatch(t->{try{return !hasTable(c,t);}catch(Exception e){throw new RuntimeException(e);}}),"Only empty offline schema; not an operational rollback procedure");}
 }

 static void unique(List<Path>f,String table,Map<String,Object>changes)throws Exception{
  try(var c=fixture(f)){for(String t:order)insert(c,t,Map.of());try{insert(c,table,changes);event(table+"_nonPK_unique",false,"unexpected success");}catch(SQLException e){event(table+"_nonPK_unique",e.getSQLState().equals("23505"),"SQLState="+e.getSQLState()+" mutated="+changes.keySet());}}
 }
 static class MemoryDataSource implements javax.sql.DataSource {
  final Connection c;MemoryDataSource(Connection c){this.c=c;}
  public Connection getConnection(){return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{if(m.getName().equals("close"))return null;try{return m.invoke(c,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
  public Connection getConnection(String u,String p){return getConnection();}public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter p){}public int getLoginTimeout(){return 0;}public void setLoginTimeout(int t){}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}public <T>T unwrap(Class<T>c)throws SQLException{throw new SQLException("unsupported");}public boolean isWrapperFor(Class<?>c){return false;}
 }
 static void guardCall(java.lang.reflect.Constructor<?> ctor,Object jdbc,boolean enabled,String id,String expected)throws Exception{
  try{ctor.newInstance(jdbc,enabled);event(id,expected==null,"actual fixed-byte guard constructor succeeded; calculationEnabled="+enabled);}
  catch(java.lang.reflect.InvocationTargetException e){Throwable cause=e.getCause();event(id,expected!=null&&Objects.equals(cause.getMessage(),expected),"actual fixed-byte guard cause="+cause.getClass().getName()+":"+cause.getMessage());}
 }
 static void guard(List<Path>base,List<Path>f)throws Exception{
  List<java.net.URL>urls=new ArrayList<>();urls.add(out.resolve("new-guard/classes").toUri().toURL());try(var files=Files.list(out.resolve("new-guard/lib"))){for(Path p:files.toList())urls.add(p.toUri().toURL());}
  try(var loader=new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new),MigrationCompatibilityProbe.class.getClassLoader());var c=db()){
   Class<?> jdbcClass=loader.loadClass("org.springframework.jdbc.core.JdbcTemplate");Object jdbc=jdbcClass.getConstructor(javax.sql.DataSource.class).newInstance(new MemoryDataSource(c));Class<?> guardClass=loader.loadClass("cn.xinji.agentplatform.security.product.ProductComparisonSchemaGuard");var ctor=guardClass.getDeclaredConstructor(jdbcClass,boolean.class);ctor.setAccessible(true);
   for(Path p:base)apply(c,p);apply(c,version(f,"V092"));
   guardCall(ctor,jdbc,false,"fixed_guard_disabled_requires_V098","COMPARISON_SCHEMA_REQUIRED:V098:con5_comparison_delegation");
   apply(c,version(f,"V098"));guardCall(ctor,jdbc,false,"fixed_guard_disabled_requires_V099","COMPARISON_SCHEMA_REQUIRED:V099:con5_candidate_attempt");
   apply(c,version(f,"V099"));guardCall(ctor,jdbc,false,"fixed_guard_disabled_requires_V101","COMPARISON_SCHEMA_REQUIRED:V101:con5_preparation_request");
   apply(c,version(f,"V101"));guardCall(ctor,jdbc,false,"fixed_guard_disabled_without_V100",null);
   guardCall(ctor,jdbc,true,"fixed_guard_enabled_requires_V100","COMPARISON_SCHEMA_REQUIRED:V100:con5_matrix_invocation");
   apply(c,version(f,"V100"));guardCall(ctor,jdbc,true,"fixed_guard_enabled_all_shapes",null);guardCall(ctor,jdbc,false,"fixed_guard_disabled_all_shapes",null);
   exec(c,"ALTER TABLE con5_candidate_attempt DROP COLUMN originals_sha");guardCall(ctor,jdbc,false,"fixed_guard_disabled_missing_INPUT_column_refused","COMPARISON_SCHEMA_REQUIRED:V099:con5_candidate_attempt");
  }
 }

 public static void main(String[]args)throws Exception{
  out=Path.of(args[0]).toAbsolutePath();Class.forName("org.h2.Driver");var base=paths("baseline-paths.txt");var f=paths("forward-paths.txt");var r=paths("reverse-paths.txt");
  try{fullShape(base,f);v92(f,r);graph(f,r);guard(base,f);}catch(Exception e){event("probe_unhandled_exception",false,e.getClass().getName()+": "+e.getMessage());}
  Files.writeString(out.resolve("migration-results.json"),"{\"scope\":\"H2 2.2.224 Oracle mode only; no DM8/network/credentials; exact forward SQL, key-only parents for new-table row constraint tests\",\"assertions\":"+events.size()+",\"failures\":"+failures+",\"results\":["+String.join(",\n",events)+"]}\n");
  System.out.println("Assertions="+events.size()+" failures="+failures);if(failures>0)System.exit(1);
 }
}
