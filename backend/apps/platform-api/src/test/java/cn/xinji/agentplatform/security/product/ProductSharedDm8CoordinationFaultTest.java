package cn.xinji.agentplatform.security.product;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/** Explicit shared-instance engineering; old independent-mode tests/Assumptions are unchanged. */
class ProductSharedDm8CoordinationFaultTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) Path privateEvidence;
    @Test void sourceDeathAfterPendingRecoversExactS1()throws Exception { sourceS1("source-pending"); }
    @Test void sourceDeathDuringUncommittedTransactionRollsBackThenRecoversS1()throws Exception { sourceS1("source-uncommitted"); }
    void sourceS1(String stage)throws Exception {
        try(var f=new SharedDm8FaultFixture()) {
            f.crash(stage,privateEvidence.resolve("stage.txt"));
            var a=f.pools.authority;var db=f.pools.primary;
            String intent=a.queryForObject("SELECT intent_id FROM id4_source_watermark",String.class);
            String digest=a.queryForObject("SELECT history_sha FROM id4_source_watermark",String.class);
            assertThat(a.queryForObject("SELECT state FROM id4_source_watermark",String.class)).isEqualTo("PENDING");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract",Integer.class)).isZero();
            assertThatThrownBy(()->new ProductSourceWatermark(db,a,f.pools.tx)).isInstanceOf(IllegalStateException.class);
            var recovery=ProductSourceWatermark.forRecovery(db,a,f.pools.tx);
            assertThatThrownBy(()->recovery.recover(SharedDm8FaultSourceInput.SOURCE,UUID.randomUUID().toString(),"owned-engineer")).isInstanceOf(IllegalStateException.class);
            assertThat(a.queryForObject("SELECT intent_id FROM id4_source_watermark",String.class)).isEqualTo(intent);
            assertThat(recovery.recover(SharedDm8FaultSourceInput.SOURCE,intent,"owned-engineer")).isEqualTo("S1_RELEASE");
            assertThat(a.queryForObject("SELECT COUNT(*) FROM id4_source_watermark",Integer.class)).isZero();
            assertThat(a.queryForObject("SELECT before_history_sha FROM id4_source_recovery_event WHERE intent_id=?",String.class,intent)).isEqualTo(digest);
            assertThat(a.queryForObject("SELECT after_epoch FROM id4_source_recovery_event WHERE intent_id=?",Long.class,intent)).isZero();
            new ProductSourceWatermark(db,a,f.pools.tx).assertAllCurrent();f.complete();
        }
    }
    @Test void sourceDeathAfterPrimaryCommitRecoversS2OnlyWithExactIntentAndHistory()throws Exception {
        try(var f=new SharedDm8FaultFixture()) {
            f.crash("source-committed",privateEvidence.resolve("stage.txt"));
            var db=f.pools.primary;var a=f.pools.authority;
            String intent=a.queryForObject("SELECT intent_id FROM id4_source_watermark",String.class);
            String before=a.queryForObject("SELECT history_sha FROM id4_source_watermark",String.class);
            assertThat(a.queryForObject("SELECT source_epoch FROM id4_source_watermark",Long.class)).isZero();
            assertThat(db.queryForObject("SELECT source_epoch FROM con4_source_current",Long.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT intent_id FROM con4_source_authority_event",String.class)).isEqualTo(intent);
            assertThat(db.queryForObject("SELECT before_history_sha FROM con4_source_authority_event",String.class)).isEqualTo(before);
            assertThatThrownBy(()->new ProductSourceWatermark(db,a,f.pools.tx)).isInstanceOf(IllegalStateException.class);
            var recovery=ProductSourceWatermark.forRecovery(db,a,f.pools.tx);
            assertThatThrownBy(()->recovery.recover(SharedDm8FaultSourceInput.SOURCE,"wrong-intent","owned-engineer")).isInstanceOf(IllegalStateException.class);
            assertThat(recovery.recover(SharedDm8FaultSourceInput.SOURCE,intent,"owned-engineer")).isEqualTo("S2_ADVANCE");
            assertThat(new ProductSourceWatermark(db,a,f.pools.tx).current(SharedDm8FaultSourceInput.SOURCE).epoch()).isEqualTo(1);
            assertThat(a.queryForObject("SELECT after_epoch FROM id4_source_recovery_event WHERE intent_id=?",Long.class,intent)).isEqualTo(1);
            assertThat(a.queryForObject("SELECT history_sha FROM id4_source_watermark",String.class)).isEqualTo(db.queryForObject("SELECT history_sha FROM con4_source_current",String.class));
            f.complete();
        }
    }
    @Test void unknownPrimaryHistoryDoesNotReleasePendingAfterActualChildDeath()throws Exception {
        try(var f=new SharedDm8FaultFixture()) {
            f.crash("source-pending",privateEvidence.resolve("stage.txt"));
            var db=f.pools.primary;var a=f.pools.authority;
            String intent=a.queryForObject("SELECT intent_id FROM id4_source_watermark",String.class);
            String prior=a.queryForObject("SELECT history_sha FROM id4_source_watermark",String.class);
            SharedDm8FaultSourceInput.admit(db,Instant.now(),new ProductSourceWatermark.Intent(intent,prior)); // Owned counterexample: current/event persisted without the required history seal.
            assertThatThrownBy(()->ProductSourceWatermark.forRecovery(db,a,f.pools.tx).recover(SharedDm8FaultSourceInput.SOURCE,intent,"owned-engineer")).isInstanceOf(IllegalStateException.class);
            assertThat(a.queryForObject("SELECT state FROM id4_source_watermark",String.class)).isEqualTo("PENDING");
            assertThat(a.queryForObject("SELECT intent_id FROM id4_source_watermark",String.class)).isEqualTo(intent);
            assertThat(a.queryForObject("SELECT history_sha FROM id4_source_watermark",String.class)).isEqualTo(prior);
            assertThat(a.queryForObject("SELECT COUNT(*) FROM id4_source_recovery_event",Integer.class)).isZero();f.complete();
        }
    }
    @Test void grantDeathBeforePrimaryCommitLeavesDurablePending()throws Exception { grant("grant-uncommitted",false); }
    @Test void grantDeathAfterPrimaryCommitCannotReleasePendingWithoutApprovedRecovery()throws Exception { grant("grant-committed",true); }
    void grant(String stage,boolean committed)throws Exception {
        try(var f=new SharedDm8FaultFixture()) {
            f.crash(stage,privateEvidence.resolve("stage.txt"));var a=f.pools.authority;var db=f.pools.primary;
            String intent=a.queryForObject("SELECT intent_id FROM id2_authority_watermark",String.class);
            String digest=a.queryForObject("SELECT history_sha FROM id2_authority_watermark",String.class);
            assertThat(a.queryForObject("SELECT state FROM id2_authority_watermark",String.class)).isEqualTo("PENDING");
            assertThat(a.queryForObject("SELECT generation FROM id2_authority_watermark",Long.class)).isZero();
            assertThat(db.queryForObject("SELECT status FROM id2_subject",String.class)).isEqualTo(committed?"DISABLED":"ACTIVE");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_subject_event",Integer.class)).isEqualTo(committed?1:0);
            assertThatThrownBy(()->new ProductGrantWatermark(db,a,SharedDm8FaultFixture.ISSUER)).isInstanceOf(IllegalStateException.class);
            f.reconnect();assertThatThrownBy(()->new ProductGrantWatermark(f.pools.primary,f.pools.authority,SharedDm8FaultFixture.ISSUER)).isInstanceOf(IllegalStateException.class);
            assertThat(f.pools.authority.queryForObject("SELECT intent_id FROM id2_authority_watermark",String.class)).isEqualTo(intent);
            assertThat(f.pools.authority.queryForObject("SELECT history_sha FROM id2_authority_watermark",String.class)).isEqualTo(digest);f.complete();
        }
    }
}
