import java.sql.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Read-only receipt reconciliation: one exact self-used subject, never password/activation/hash. */
public final class ReadNormalSubject51 {
 public static void main(String[] args)throws Exception {
  String subject=System.getenv("NORMAL_SUBJECT_ID51");if(subject==null||!subject.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException();
  Map<String,Object> out=new TreeMap<>();out.put("capturedAt",java.time.Instant.now().toString());out.put("executedWrites",0);out.put("issuer","https://consultation.agent-platform.internal:5446");out.put("subjectId",subject);
  try(Connection c=DriverManager.getConnection(System.getenv("LIFECYCLE_JDBC_URL"),System.getenv("LIFECYCLE_JDBC_USER"),System.getenv("LIFECYCLE_JDBC_PASSWORD"))){c.setReadOnly(true);
   try(var s=c.prepareStatement("SELECT LOGIN_NAME,STATUS,CREDENTIAL_VERSION,ACTIVATION_EXPIRES_AT,CASE WHEN STATUS='PENDING' AND ACTIVATION_EXPIRES_AT>CURRENT_TIMESTAMP THEN 'VALID' WHEN STATUS='PENDING' THEN 'EXPIRED' ELSE 'NOT_PENDING' END AS ACTIVATION_STATE FROM CONPROD.ID2_SUBJECT WHERE ISSUER=? AND SUBJECT_ID=?")){
    s.setQueryTimeout(10);s.setMaxRows(2);s.setString(1,(String)out.get("issuer"));s.setString(2,subject);
    try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("EXACT_SUBJECT_MISSING");out.put("loginName",r.getString(1));out.put("status",r.getString(2));out.put("credentialVersion",r.getString(3));out.put("activationExpiresAt",r.getString(4));out.put("activationStateComparedToCurrentDatabaseTime",r.getString(5));if(r.next())throw new IllegalStateException("EXACT_SUBJECT_AMBIGUOUS");}
   }
   try(var s=c.prepareStatement("SELECT CURRENT_TIMESTAMP AS SESSION_NOW FROM DUAL")){s.setQueryTimeout(10);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException();out.put("databaseSessionNow",r.getString(1));}}
  }
  out.put("scope","actual read-only state of the original registered subject; no grants or credential changes");System.out.println(new ObjectMapper().writeValueAsString(out));
 }
}
