import java.nio.file.*;
import java.sql.*;

/** Original CHECK and actual guard predicate on H2. Not DM8 metadata or reverse acceptance. */
public final class ConsultationActivationRecoveryFixtureContractTest {
  static long rejected(Connection c)throws Exception {
    try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM id2_subject"+ConsultationActivationRecoveryFixtureGuard.subjectFixturePredicate())) {
      if(!r.next())throw new AssertionError();long count=r.getLong(1);if(r.next())throw new AssertionError();return count;
    }
  }
  public static void main(String[] args)throws Exception {
    if(args.length!=0)throw new IllegalArgumentException("NO_ARGUMENTS");
    String v32=Files.readString(Path.of("db/dm8/V032__product_identity.sql"));
    String[] v46=Files.readString(Path.of("db/dm8/V046__product_workload_subject.sql")).split(";");
    try(Connection c=DriverManager.getConnection("jdbc:h2:mem:actr71;DB_CLOSE_DELAY=-1")) {
      try(Statement s=c.createStatement()) {
        String first=v32.substring(0,v32.indexOf(';'));if(!first.startsWith("CREATE TABLE id2_subject ("))throw new AssertionError();s.execute(first);
        if(v46.length<4)throw new AssertionError();for(int i=0;i<4;i++){if(!v46[i].trim().startsWith("ALTER TABLE id2_subject "))throw new AssertionError();s.execute(v46[i]);}
      }
      String insert="INSERT INTO id2_subject (issuer,subject_id,login_name,status,credential_version,created_at,subject_kind,activation_sha,activation_expires_at) VALUES ('urn:actr66:fixture','actr66-fixture','actr66-fixture','PENDING',1,TIMESTAMP '2000-01-01 00:00:00','HUMAN',?,?)";
      try(PreparedStatement q=c.prepareStatement(insert)) {
        q.setNull(1,Types.CHAR);q.setNull(2,Types.TIMESTAMP);
        try{q.executeUpdate();throw new AssertionError("ORIGINAL_CHECK_REQUIRED");}catch(SQLException expected){if(!"23513".equals(expected.getSQLState()))throw expected;}
        System.out.println("PASS fixture71 original CHECK rejects old NULL fixture");
        q.setString(1,"0".repeat(64));q.setTimestamp(2,Timestamp.valueOf("2000-01-01 00:00:00"));if(q.executeUpdate()!=1||rejected(c)!=0)throw new AssertionError();
        System.out.println("PASS fixture71 exact expired fixed fixture passes original CHECK and actual predicate");
      }
      try(Statement s=c.createStatement()) {
        s.executeUpdate("UPDATE id2_subject SET activation_sha='"+"1".repeat(64)+"'");if(rejected(c)!=1)throw new AssertionError();
        System.out.println("PASS fixture71 nonfixed digest passes CHECK but actual predicate refuses");
        s.executeUpdate("UPDATE id2_subject SET activation_sha='"+"0".repeat(64)+"',activation_expires_at=TIMESTAMP '2000-01-02 00:00:00'");if(rejected(c)!=1)throw new AssertionError();
        System.out.println("PASS fixture71 nonfixed expiry passes CHECK but actual predicate refuses");
      }
      System.out.println("FIXTURE71=4; H2_ONLY=true; DM8_CONNECTIONS=0; METADATA_ACCEPTANCE=false; PHYSICAL_D_PASSED=false");
    }
  }
}
