import java.nio.file.*;
import java.sql.*;
import java.util.regex.*;

/** H2 diagnosis of a source-level fixture contradiction, never a DM8 acceptance test. */
public final class FixtureContractDiagnosis70 {
  public static void main(String[] args) throws Exception {
    if(args.length!=0)throw new IllegalArgumentException("NO_ARGUMENTS");
    String v32=Files.readString(Path.of("db/dm8/V032__product_identity.sql"));
    String v46=Files.readString(Path.of("db/dm8/V046__product_workload_subject.sql"));
    String source=Files.readString(Path.of("scripts/java/ConsultationActivationRecoveryFixtureGuard.java"));
    Matcher match=Pattern.compile("\"( WHERE issuer<>[^\"]+login_name<>[^\"]+)\"").matcher(source);
    if(!match.find())throw new AssertionError("ACTUAL_SUBJECT_GUARD_REQUIRED");
    String predicate=match.group(1);
    if(match.find())throw new AssertionError("UNIQUE_SUBJECT_GUARD_REQUIRED");
    try(Connection c=DriverManager.getConnection("jdbc:h2:mem:actr70;DB_CLOSE_DELAY=-1")) {
      try(Statement s=c.createStatement()) {
        String first=v32.substring(0,v32.indexOf(';'));
        if(!first.startsWith("CREATE TABLE id2_subject ("))throw new AssertionError();
        s.execute(first);
        String[] statements=v46.split(";");
        if(statements.length<4)throw new AssertionError();
        for(int i=0;i<4;i++) {
          if(!statements[i].trim().startsWith("ALTER TABLE id2_subject "))throw new AssertionError();
          s.execute(statements[i]);
        }
      }
      String insert="INSERT INTO id2_subject (issuer,subject_id,login_name,status,credential_version,created_at,subject_kind,activation_sha,activation_expires_at) VALUES ('urn:actr66:fixture','actr66-fixture','actr66-fixture','PENDING',1,TIMESTAMP '2000-01-01 00:00:00','HUMAN',?,?)";
      try(PreparedStatement q=c.prepareStatement(insert)) {
        q.setNull(1,Types.CHAR);q.setNull(2,Types.TIMESTAMP);
        try {q.executeUpdate();throw new AssertionError("OLD_NULL_FIXTURE_MUST_FAIL_CHECK");}
        catch(SQLException expected) {
          if(!"23513".equals(expected.getSQLState()))throw expected;
          System.out.println("PASS old PENDING/null fixture rejected by original V046 CHECK; H2 SQLSTATE="+expected.getSQLState());
        }
        q.setString(1,"0".repeat(64));q.setTimestamp(2,Timestamp.valueOf("2000-01-01 00:00:00"));
        if(q.executeUpdate()!=1)throw new AssertionError();
      }
      try(Statement q=c.createStatement();ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM id2_subject"+predicate)) {
        if(!rows.next()||rows.getLong(1)!=1||rows.next())throw new AssertionError("ACTUAL_GUARD_CONTRADICTION_REQUIRED");
        System.out.println("PASS valid fixed expired synthetic PENDING row accepted by original CHECK but rejected by actual source guard predicate");
      }
      System.out.println("DIAGNOSIS70=2; H2_ONLY=true; DM8_CONNECTIONS=0; PRODUCT_CHANGES=0; PHYSICAL_D_PASSED=false");
    }
  }
}
