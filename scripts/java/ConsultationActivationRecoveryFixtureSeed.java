import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Fixed isolated seeds only. No main, credentials, retries or caller-supplied SQL. */
public final class ConsultationActivationRecoveryFixtureSeed {
  public static final String PLAN_SHA="d1faa9f7d55d3a7cd75cb730475c82e5cbda7f7d0f650cb41ca666bc0957754e";
  public enum Kind { SUBJECT, RECEIPT, EVENT }
  private final JsonNode plan;
  public ConsultationActivationRecoveryFixtureSeed(Path file)throws Exception {
    Path p=file.toAbsolutePath().normalize();
    for(Path part=p;part!=null;part=part.getParent()) {
      if(Files.isSymbolicLink(part)||!part.equals(part.toRealPath()))throw new Exception("SEED_PLAN_REDIRECT");
    }
    byte[] bytes;try(var in=Files.newInputStream(p)){bytes=in.readNBytes(65537);}
    if(bytes.length>65536||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(PLAN_SHA))throw new Exception("SEED_PLAN_SHA_CHANGED");
    plan=ConsultationActivationRecoveryJdbcEntry.JSON.readTree(bytes);
    if(!plan.path("format").asText().equals("ACTR66_FIXED_PREPARATION_PLAN/v1"))throw new Exception("SEED_PLAN_FORMAT");
  }
  private static void require(boolean ok,String code)throws Exception {if(!ok)throw new Exception(code);}
  private static long count(Connection c,String sql)throws Exception {
    try(Statement q=c.createStatement()) {
      q.setQueryTimeout(15);try(ResultSet rs=q.executeQuery(sql)) {
        require(rs.next(),"SEED_COUNT_MISSING");long n=rs.getLong(1);
        require(!rs.wasNull()&&n>=0&&!rs.next(),"SEED_COUNT_INVALID");return n;
      }
    }
  }
  private static void connection(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,boolean auto)throws Exception {
    var md=c.getMetaData();
    require("SYSDBA".equals(md.getUserName())&&"8.1.4.170".equals(md.getDatabaseProductVersion())&&md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("dm")&&"jdbc:dm://agent-platform-single-final-restore-20261009:5236".equals(md.getURL())&&s.namespace().schema().equals(c.getSchema())&&c.getAutoCommit()==auto,"SEED_EXACT_CONNECTION_REQUIRED");
    require(count(c,"SELECT COUNT(*) FROM V$SESSIONS")==1,"SEED_SOLE_SESSION_REQUIRED");
    try(PreparedStatement q=c.prepareStatement("SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME='COMPATIBLE_MODE'")) {
      q.setQueryTimeout(15);try(ResultSet rs=q.executeQuery()) {
        require(rs.next()&&"0".equals(rs.getString(1))&&!rs.next(),"SEED_COMPATIBLE_MODE_REQUIRED");
      }
    }
  }
  private static void binding(ConsultationActivationRecoveryJdbcStep.Scope s,Kind kind)throws Exception {
    require(s.namespace()!=ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY,"SEED_EMPTY_CASE_FORBIDDEN");
    require(kind==Kind.SUBJECT||kind==Kind.RECEIPT&&s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.RECEIPT||kind==Kind.EVENT&&s.namespace()==ConsultationActivationRecoveryJdbcStep.Namespace.EVENT,"SEED_CASE_BINDING_REQUIRED");
  }
  private static void rows(Connection c,Kind kind,boolean inserted)throws Exception {
    String table=switch(kind){case SUBJECT->"id2_subject";case RECEIPT->"id2_activation_recovery_receipt";case EVENT->"id2_subject_event";};
    require(count(c,"SELECT COUNT(*) FROM "+table)==(inserted?1:0),"SEED_EXACT_COUNT_REQUIRED");
    if(kind!=Kind.SUBJECT) {
      require(count(c,"SELECT COUNT(*) FROM id2_subject")==1,"SEED_SUBJECT_REQUIRED");
      require(count(c,"SELECT COUNT(*) FROM id2_subject"+ConsultationActivationRecoveryFixtureGuard.subjectFixturePredicate()+" OR created_at<>TIMESTAMP '2000-01-01 00:00:00'")==0,"SEED_FIXED_SUBJECT_REQUIRED");
    }
    require(count(c,"SELECT COUNT(*) FROM id2_subject_event")==((kind==Kind.EVENT&&inserted)?1:0),"SEED_EVENT_COUNT_DRIFT");
    if(kind!=Kind.SUBJECT)require(count(c,"SELECT COUNT(*) FROM id2_activation_recovery_receipt")==((kind==Kind.RECEIPT&&inserted)?1:0),"SEED_RECEIPT_COUNT_DRIFT");
    if(!inserted)return;
    String time="TIMESTAMP '2000-01-01 00:00:00'";
    String predicate=switch(kind) {
      case SUBJECT->ConsultationActivationRecoveryFixtureGuard.subjectFixturePredicate()+" OR created_at<>"+time;
      case RECEIPT->" WHERE issuer<>'urn:actr66:fixture' OR actor_ref<>'actr66-fixture' OR request_id<>'actr66-fixture' OR subject_id<>'actr66-fixture' OR expected_version<>1 OR result_version<>2 OR activation_expires_at<>"+time+" OR occurred_at<>"+time;
      case EVENT->" WHERE event_id<>'actr66-fixture' OR issuer<>'urn:actr66:fixture' OR subject_id<>'actr66-fixture' OR operation<>'REISSUE' OR before_version<>1 OR after_version<>2 OR actor_ref<>'actr66-fixture' OR occurred_at<>"+time;
    };
    require(count(c,"SELECT COUNT(*) FROM "+table+predicate)==0,"SEED_FIXED_ROW_DRIFT");
  }
  public void execute(Connection c,ConsultationActivationRecoveryJdbcStep.Scope s,Kind kind,ConsultationActivationRecoveryJdbcStep.LiveGuard guard,ConsultationActivationRecoveryJdbcStep.Journal journal)throws Exception {
    Objects.requireNonNull(c);Objects.requireNonNull(s);Objects.requireNonNull(kind);Objects.requireNonNull(guard);Objects.requireNonNull(journal);
    synchronized(c) {
      binding(s,kind);guard.assertCurrent(s);connection(c,s,true);rows(c,kind,false);
      JsonNode seed=plan.path("seeds").get(kind.ordinal());
      String step=seed.path("step").asText(),hash=seed.path("sqlSha256").asText(),commit=seed.path("commitStep").asText(),commitHash=seed.path("commitSha256").asText();
      boolean transaction=false,commitAttempted=false;String pending=null;
      try {
        // INSERT and JDBC commit each require their own durable PREPARED acknowledgment.
        journal.appendAndSync(s,step,"PREPARED",hash);pending=step;
        guard.assertCurrent(s);connection(c,s,true);rows(c,kind,false);
        c.setAutoCommit(false);transaction=true;
        try(Statement q=c.createStatement()){q.setQueryTimeout(30);require(q.executeUpdate(seed.path("sql").asText())==1,"SEED_INSERT_COUNT_REQUIRED");}
        rows(c,kind,true);journal.appendAndSync(s,step,"COMPLETED",hash);pending=null;
        journal.appendAndSync(s,commit,"PREPARED",commitHash);pending=commit;
        guard.assertCurrent(s);connection(c,s,false);rows(c,kind,true);
        commitAttempted=true;c.commit();
        journal.appendAndSync(s,commit,"COMPLETED",commitHash);pending=null;
        c.setAutoCommit(true);guard.assertCurrent(s);connection(c,s,true);rows(c,kind,true);
      } catch(Exception error) {
        if(transaction&&!commitAttempted)try{c.rollback();}catch(Exception rollback){error.addSuppressed(rollback);}
        if(pending!=null)try{journal.appendAndSync(s,pending,"UNKNOWN",pending.equals(step)?hash:commitHash);}catch(Exception journalError){error.addSuppressed(journalError);}
        throw new ConsultationActivationRecoveryJdbcStep.Failure(commitAttempted?"SEED_COMMIT_OUTCOME_UNKNOWN_STOP_REQUIRED":"SEED_PRECOMMIT_FAILED_STOP_REQUIRED",commitAttempted?"UNKNOWN":"NOT_COMMITTED_OR_RECONCILIATION_REQUIRED",error);
      }
    }
  }
}
