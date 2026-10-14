import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import java.security.*;import java.nio.charset.StandardCharsets;import com.fasterxml.jackson.databind.ObjectMapper;
/** Approved protection-purpose complete row digest comparison. Never publishes protected values. */
public class CompareReleaseRestore {
 record Table(int rows,String sha,List<String> columns){}
 public static void main(String[]a)throws Exception{
  if(a.length!=1||Files.exists(Path.of(a[0])))throw new IllegalArgumentException("NEW_REPORT_REQUIRED");var out=new LinkedHashMap<String,Object>();var reports=new TreeMap<String,Object>();
  try(var before=DriverManager.getConnection("jdbc:dm://127.0.0.1:5301","CONPROD",System.getenv("PROTECTION_INSPECT_PASSWORD"));var after=DriverManager.getConnection("jdbc:dm://127.0.0.1:5494","CONREL2",System.getenv("RELEASE_RESTORE_OWNER"))){
   before.setSchema("CONPROD");after.setSchema("CONREL2");before.setReadOnly(true);after.setReadOnly(true);before.setAutoCommit(false);after.setAutoCommit(false);
   var bt=tables(before,"CONPROD");var at=tables(after,"CONREL2");if(!bt.equals(at))throw new IllegalStateException("TABLE_SET_MISMATCH");
   for(String name:bt){var x=read(before,name);var y=read(after,name);if(!x.equals(y))throw new IllegalStateException("TABLE_BYTES_MISMATCH "+name);reports.put(name,Map.of("rows",x.rows(),"sha256",x.sha(),"columns",x.columns(),"matched",true));}before.rollback();after.rollback();
  }
  out.put("observedAt",Instant.now().toString());out.put("protectionPurposeOnly",true);out.put("tableCount",reports.size());out.put("tables",reports);out.put("databaseWrites",0);out.put("independentAuthorityRestored",false);out.put("expiredGoalBusinessRead",false);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(a[0]).toFile(),out);
 }
 static Set<String>tables(Connection c,String schema)throws Exception{var out=new TreeSet<String>();try(var s=c.prepareStatement("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=?")){s.setString(1,schema);try(var r=s.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;}
 static Table read(Connection c,String t)throws Exception{if(!t.matches("[A-Z][A-Z0-9_]+"))throw new IllegalArgumentException("INVALID_TABLE");var hashes=new ArrayList<String>();var cols=new ArrayList<String>();try(var s=c.createStatement()){s.setQueryTimeout(30);try(var r=s.executeQuery("SELECT * FROM "+t)){var m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)cols.add(m.getColumnName(i)+":"+m.getColumnType(i)+":"+m.getPrecision(i)+":"+m.getScale(i));while(r.next()){var d=MessageDigest.getInstance("SHA-256");for(int i=1;i<=m.getColumnCount();i++){String v=r.getString(i);byte[] b=(v==null?"N:":"S:"+v).getBytes(StandardCharsets.UTF_8);d.update((b.length+":").getBytes(StandardCharsets.UTF_8));d.update(b);}hashes.add(HexFormat.of().formatHex(d.digest()));}}}Collections.sort(hashes);var d=MessageDigest.getInstance("SHA-256");for(String h:hashes)d.update(h.getBytes(StandardCharsets.US_ASCII));return new Table(hashes.size(),HexFormat.of().formatHex(d.digest()),cols);}
}
