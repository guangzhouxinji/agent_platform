import java.sql.*;import java.nio.file.*;import java.time.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Protection-purpose metadata inventory; no Goal/body/result material is read. */
public class InspectProtectionCoverage {
 public static void main(String[] a)throws Exception{
  if(a.length!=1||Files.exists(Path.of(a[0])))throw new IllegalArgumentException("NEW_REPORT_REQUIRED");
  var out=new LinkedHashMap<String,Object>();out.put("observedAt",Instant.now().toString());
  var refs=new TreeMap<String,String>();var contracts=new TreeMap<String,String>();var counts=new LinkedHashMap<String,Integer>();
  try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5301","CONPROD",System.getenv("PROTECTION_INSPECT_PASSWORD"))){
   c.setReadOnly(true);c.setAutoCommit(false);
   for(String table:List.of("con3_task","con4_source_contract","con4_source_product_decision","con3_message","con3_goal_revision","con3_model_turn","con3_model_invocation")){
    try(var s=c.prepareStatement("SELECT retention_ref,retention_evidence_sha FROM CONPROD."+table)){s.setQueryTimeout(20);int n=0;try(var r=s.executeQuery()){while(r.next()){String ref=r.getString(1),sha=r.getString(2);String old=refs.putIfAbsent(ref,sha);if(old!=null&&!old.equals(sha))throw new IllegalStateException("RETENTION_CONFLICT");n++;}}counts.put(table,n);}
   }
   for(String q:List.of("SELECT evidence_ref,evidence_sha FROM CONPROD.con4_source_contract","SELECT rights_ref,rights_sha FROM CONPROD.con4_source_admission","SELECT capability_ref,capability_sha FROM CONPROD.con4_source_admission","SELECT decision_ref,decision_sha FROM CONPROD.con4_source_product_decision")){
    try(var s=c.prepareStatement(q)){s.setQueryTimeout(20);try(var r=s.executeQuery()){while(r.next()){String old=contracts.putIfAbsent(r.getString(1),r.getString(2));if(old!=null&&!old.equals(r.getString(2)))throw new IllegalStateException("CONTRACT_CONFLICT");}}}
   }
   try(var s=c.prepareStatement("SELECT DISTINCT organization_id FROM CONPROD.con3_organization UNION SELECT DISTINCT organization_id FROM CONPROD.con4_source_product_decision")){try(var r=s.executeQuery()){var orgs=new TreeSet<String>();while(r.next())orgs.add(r.getString(1));out.put("organizations",orgs);}}
   c.rollback();
  }
  out.put("retentionReferences",refs);out.put("rightsAndContractReferences",contracts);out.put("rowCounts",counts);out.put("goalAndBusinessBodiesRead",false);out.put("writes",0);
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(a[0]).toFile(),out);
 }
}
