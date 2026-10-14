package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Original-key-only consumption over real SQL and signed identities; no HTTP or physical deployment claim. */
class ProductHumanRequestLookupTest {
    @TempDir Path root;
    ProductHumanRequestApplicationFixture fixture;
    @BeforeEach void setup() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture(); fixture.setup(root, true);
    }

    @Test void readsOriginalResultUsingOnlyCurrentIdentityTaskAndKey() {
        var original = fixture.request();
        assertThat(lookup()).isEqualTo(original);
        rows(1);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void laterTaskVersionAndRemovedCurrentGoalCannotReplaceOriginalCoordinates() {
        var original = fixture.request();
        fixture.base.db.update("UPDATE con3_task SET version=8 WHERE task_id=?", fixture.base.task.id());
        fixture.base.db.update("DELETE FROM con3_goal_current");
        assertThat(lookup()).isEqualTo(original);
        assertThat(lookup().body().manifest().taskVersion()).isEqualTo(7);
        rows(1);
    }

    @Test void expiredMalformedBodyReturnsOnlyReceiptWithoutParsingOrRecreatingAnything() {
        var original = fixture.request();
        fixture.base.clock.now = original.body().manifest().references().facts().expiresAt();
        fixture.base.db.update("UPDATE con6_human_package SET manifest_json='expired invalid JSON'");
        fixture.base.db.update("UPDATE con6_human_handling SET state='CLAIMED',processing_version=2");
        var result = lookup();
        assertThat(result.body()).isNull();
        assertThat(result.receipt()).isEqualTo(original.receipt());
        assertThat(fixture.base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class)).isEqualTo("expired invalid JSON");
        assertThat(fixture.base.db.queryForObject("SELECT state FROM con6_human_handling", String.class)).isEqualTo("CLAIMED");
        rows(1);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void unknownKeyIsNotFoundAndCannotCreateARequest() {
        assertThatThrownBy(this::lookup).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        rows(0);
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @Test void anotherAuthenticatedAndTaskAuthorizedHumanCannotReadRequestersOriginalKey() {
        fixture.request();
        var base = fixture.base; var issuer = base.decoder.decode(base.token).getIssuer().toString();
        base.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                + "VALUES(?,'other','other','encoded','ACTIVE',1,CURRENT_TIMESTAMP)", issuer);
        String other = ReflectionTestUtils.invokeMethod(base, "token", "other", UUID.randomUUID().toString(),
                base.decoder.decode(base.token).getHeaders().get("kid").toString());
        var grants = new ProductGrants(base.db, new TransactionTemplate(base.manager), base.clock);
        for (String action : List.of("READ", "WRITE")) {
            base.watermark.change(() -> grants.issue(new ProductGrants.Scope(issuer, "other", base.task.organization(),
                    "CONSULTATION", base.task.id(), base.task.dataScope(), "TASK", base.task.id(), 1, action, "TASK_WORK"),
                    base.task.expiresAt(), 0, "maintainer", "other-" + action, "a".repeat(64)));
        }
        assertThatThrownBy(() -> fixture.application.lookup(other, base.task.id(), "human-original-key"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        rows(1);
    }

    @Test void currentReadRevocationRejectsEvenAnExistingOriginalKey() {
        fixture.request();
        fixture.base.db.update("UPDATE id2_grant SET status='REVOKED' WHERE action_code='READ'");
        assertThatThrownBy(this::lookup).isInstanceOf(RuntimeException.class);
        rows(1);
    }

    @Test void internallyConsistentReceiptDigestStillMustMatchTheStoredOriginalCommand() {
        fixture.request();
        fixture.base.db.update("UPDATE con6_human_receipt SET request_sha=?", "f".repeat(64));
        fixture.base.db.update("UPDATE con6_human_event SET request_sha=?", "f".repeat(64));
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class).hasMessage("Human request evidence changed");
        rows(1);
    }

    private ProductHumanRequestApplication.Result lookup() {
        return fixture.application.lookup(fixture.base.token, fixture.base.task.id(), "human-original-key");
    }
    private void rows(int expected) {
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(expected);
    }
}
