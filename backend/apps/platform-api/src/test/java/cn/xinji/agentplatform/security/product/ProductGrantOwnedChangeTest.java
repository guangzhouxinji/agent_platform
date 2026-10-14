package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProductGrantOwnedChangeTest {
    @Test void onlyOwningThreadAndWatermarkCanUseTheLiveIntent() throws Exception {
        var f = new ProductTaskCreationFixture();
        var other = new ProductGrantWatermark(f.db, f.authority, ProductTaskCreationFixture.ISSUER);
        var saved = new AtomicReference<ProductGrantWatermark.OwnedChange>();
        f.watermark.changeOwned(owned -> {
            saved.set(owned);
            owned.requireCurrent(f.watermark);
            assertThrows(IllegalStateException.class, () -> owned.requireCurrent(other));
            assertThrows(IllegalStateException.class, () -> f.watermark.assertCurrent());
            assertTrue(CompletableFuture.supplyAsync(() -> {
                try { owned.requireCurrent(f.watermark); return false; }
                catch (IllegalStateException expected) { return true; }
            }).join());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> saved.get().requireCurrent(f.watermark));
        f.watermark.assertCurrent();
    }

    @Test void proofClosesWhenOperationFailsAndCannotBeReusedInAnotherChange() throws Exception {
        var f = new ProductTaskCreationFixture();
        var saved = new AtomicReference<ProductGrantWatermark.OwnedChange>();
        assertThrows(IllegalArgumentException.class, () -> f.watermark.changeOwned(owned -> {
            saved.set(owned); throw new IllegalArgumentException("controlled rollback");
        }));
        f.watermark.changeOwned(owned -> {
            owned.requireCurrent(f.watermark);
            assertThrows(IllegalStateException.class, () -> saved.get().requireCurrent(f.watermark));
            return null;
        });
    }

    @Test void changedIndependentIntentCannotAuthorizeTheOwner() throws Exception {
        for (String change : java.util.List.of("intent_id='other'", "generation=generation+1",
                "history_sha='" + "f".repeat(64) + "'", "state='READY',intent_id=NULL")) {
            var f = new ProductTaskCreationFixture();
            assertThrows(IllegalStateException.class, () -> f.watermark.changeOwned(owned -> {
                f.authority.update("UPDATE id2_authority_watermark SET " + change);
                owned.requireCurrent(f.watermark);
                return null;
            }));
        }
    }

    @Test void ownedCreateReadsActualGrantWhileOrdinaryAccessStillRejectsPending() throws Exception {
        var f = new ProductTaskCreationFixture(); f.grant();
        var facts = new ProductConsultationAccess.ResourceFacts("org", "org", "org:1", "ORGANIZATION", "org", 1);
        f.watermark.changeOwned(owned -> f.transactions.execute(status -> {
            assertTrue(f.access.permitsOwnedCreate(f.jwt, facts, owned));
            assertThrows(IllegalStateException.class, () -> f.access.permits(f.jwt, facts,
                    cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy.Action.CREATE));
            f.db.update("UPDATE id2_session SET status='REVOKED'");
            assertFalse(f.access.permitsOwnedCreate(f.jwt, facts, owned));
            status.setRollbackOnly();
            return null;
        }));
    }

    @Test void ownedProofCannotAuthorizeOutsideBoundTransactionOrForTaskResource() throws Exception {
        var f = new ProductTaskCreationFixture(); f.grant();
        var facts = new ProductConsultationAccess.ResourceFacts("org", "org", "org:1", "ORGANIZATION", "org", 1);
        f.watermark.changeOwned(owned -> {
            assertThrows(IllegalStateException.class, () -> f.access.permitsOwnedCreate(f.jwt, facts, owned));
            return f.transactions.execute(status -> {
                var task = new ProductConsultationAccess.ResourceFacts("org", "task", "scope:1", "TASK", "task", 1);
                assertThrows(IllegalStateException.class, () -> f.access.permitsOwnedCreate(f.jwt, task, owned));
                return null;
            });
        });
    }
}
