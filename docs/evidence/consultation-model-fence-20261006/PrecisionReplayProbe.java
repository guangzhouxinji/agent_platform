package cn.xinji.agentplatform.security.product;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
/** Standalone component counterexample, never a product endpoint or model substitute. */
public class PrecisionReplayProbe {
 public static void main(String[] args)throws Exception {
  var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
  var fixture=new ProductConsultationModelFixture(db,Instant.parse("2026-10-06T00:00:00.123456789Z"));
  try {fixture.actualExplanationPersistsAndReplaysWithoutSendingRawTaskText();System.out.println("PERSISTED_REPLAY_BYTES=PASS");}
  catch(org.opentest4j.AssertionFailedError failure) {System.out.println("PERSISTED_REPLAY_BYTES=FAIL: initial response and persisted replay differ at timestamp precision");System.exit(1);}
 }
}
