import java.sql.*;
public class FullFixturePreparation {
 public static void main(String[] args) throws Exception {
  IsolatedGrantFixturePreparation.main(args);
  try(var job=IsolatedGrantFixturePreparation.create("AGENT_JOBPACK_JOB_PACK_DM8")){}
  AgentPreparePublicKb.main(args);
  if(!"jdbc:dm://127.0.0.1:5311".equals(System.getenv("HISTORICAL_URL")))throw new IllegalArgumentException("Exact readback endpoint required");
  try(var c=DriverManager.getConnection(System.getenv("HISTORICAL_URL"),System.getenv("HISTORICAL_USER"),System.getenv("HISTORICAL_PASSWORD"));var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('ENF10_DOCUMENT_RULE_ATTEMPT','ENF10_DOCUMENT_RULE_ATTEMPT_EVENT')")) {
   r.next();if(r.getInt(1)!=2)throw new IllegalStateException("Historical migration absent: do not run full suite against original schema");
   System.out.println("Original facility tables present: full fixture must take SELECT-only path");
  }
 }
}
