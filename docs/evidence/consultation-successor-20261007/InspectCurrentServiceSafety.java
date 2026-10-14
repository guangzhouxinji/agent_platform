import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.*;
/** Read-only safety inspection, digests only; never exposes Goal or source plaintext. */
public class InspectCurrentServiceSafety {
 public static void main(String[] a)throws Exception{
  if(a.length!=3||Files.exists(Path.of(a[2])))throw new IllegalArgumentException("NEW_OUTPUT_REQUIRED");
  var mapper=new ObjectMapper();var old=mapper.readTree(Path.of(a[0]).toFile());var meta=mapper.readTree(Path.of(a[1]).toFile());var out=new TreeMap<String,Object>();var tables=new TreeMap<String,Object>();var drift=new ArrayList<String>();var constraints=new ArrayList<Map<String,Object>>();
  try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5301","CONPROD",System.getenv("PROTECTION_INSPECT_PASSWORD"))){
   c.setSchema("CONPROD");c.setReadOnly(true);c.setAutoCommit(false);
   var names=CompareReleaseRestore.tables(c,"CONPROD");var expected=new TreeSet<String>();old.path("tables").fieldNames().forEachRemaining(expected::add);
   out.put("tableSetUnchanged",names.equals(expected));
   for(String name:names){var d=CompareReleaseRestore.read(c,name);var b=old.path("tables").path(name);var cols=new ArrayList<String>();b.path("columns").forEach(v->cols.add(v.asText()));boolean same=b.path("rows").asInt(-1)==d.rows()&&b.path("sha256").asText().equals(d.sha())&&cols.equals(d.columns()); if(!same)drift.add(name);tables.put(name,Map.of("rows",d.rows(),"sha256",d.sha(),"matchedPreviousProtection",same));}
   try(var s=c.createStatement()){s.setQueryTimeout(15);try(var r=s.executeQuery("SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM ALL_CONSTRAINTS WHERE OWNER='CONPROD' ORDER BY TABLE_NAME,CONSTRAINT_NAME")){while(r.next()){var row=new TreeMap<String,Object>();for(int i=1;i<=5;i++)row.put(r.getMetaData().getColumnName(i),r.getString(i));constraints.add(row);}}}c.rollback();
  }
  out.put("observedAt",Instant.now().toString());out.put("scope","MAINTENANCE_READ_ONLY_DIGEST_NO_GOAL_API_NO_BACKUP_OPEN");out.put("tableCount",tables.size());out.put("tables",tables);out.put("driftTables",drift);out.put("constraints",constraints);out.put("priorMetadataSha",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(a[1])))));out.put("databaseWrites",0);out.put("ddl",0);out.put("limitations",List.of("Digest equality excludes surviving net row changes, not transient writes later undone","No claim of all writers absent","Does not prove historical build closure or old JAR write safety"));mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(a[2]).toFile(),out);
 }
}
