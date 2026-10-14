package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PC-COMPARISON-WORKER-DELEGATION-01: real product calls, isolated H2 authority only. */
class ProductComparisonDelegationAuthorityTest {
    private static final String BINDING = "e".repeat(64);

    @Test void exactCompareReceiptReplayAndRevocation() throws Exception {
        var f = new Fixture(true, true);
        long before = f.watermark.currentGeneration();
        var issued = f.issue("compare-one", BINDING, receipt -> {
            assertThat(receipt.status()).isEqualTo("ACTIVE");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.authority.queryForObject("SELECT state FROM id2_authority_watermark",
                    String.class)).isEqualTo("PENDING");
            f.counts(1, 1, 1);
        });
        assertThat(issued.version()).isEqualTo(1);
        assertThat(f.watermark.currentGeneration()).isGreaterThan(before);
        assertThat(f.db.queryForObject("SELECT human_grant_id FROM con3_worker_delegation",
                String.class)).isEqualTo(f.humanCompare.grantId());
        assertThat(f.db.queryForObject("SELECT service_grant_id FROM con3_worker_delegation",
                String.class)).isEqualTo(f.serviceCompare.grantId());
        assertThat(f.issue("compare-one", BINDING, ignored -> { })).isEqualTo(issued);
        f.counts(1, 1, 1);
        assertThatThrownBy(() -> f.issue("compare-one", "f".repeat(64), ignored -> { }))
                .isInstanceOf(IllegalStateException.class);
        f.counts(1, 1, 1);
        var revoked = f.delegations.revokeComparison("task", issued.delegationId(), 1,
                f.human, "compare-revoke");
        assertThat(revoked.delegationId()).isEqualTo(issued.delegationId());
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.status()).isEqualTo("REVOKED");
        f.counts(1, 2, 2);
        assertThatThrownBy(() -> f.issue("compare-one", BINDING, ignored -> { }))
                .isInstanceOf(IllegalStateException.class);
        f.watermark.assertCurrent();
    }

    @Test void callbackFailureRollsBackDelegationEventReceiptAndReleasesWatermark() throws Exception {
        var f = new Fixture(true, true);
        long generation = f.watermark.currentGeneration();
        String digest = f.authority.queryForObject("SELECT history_sha FROM id2_authority_watermark",
                String.class);
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("binding insert failed");
        assertThatThrownBy(() -> f.issue("retry-binding", BINDING, receipt -> {
            calls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            f.counts(1, 1, 1);
            throw failure;
        })).isSameAs(failure);
        assertThat(calls.get()).isEqualTo(1);
        f.counts(0, 0, 0);
        assertThat(f.watermark.currentGeneration()).isEqualTo(generation);
        assertThat(f.authority.queryForObject("SELECT history_sha FROM id2_authority_watermark",
                String.class)).isEqualTo(digest);
        assertThat(f.authority.queryForObject("SELECT intent_id FROM id2_authority_watermark",
                String.class)).isNull();
        assertThat(f.issue("retry-binding", BINDING, ignored -> { }).status()).isEqualTo("ACTIVE");
        f.counts(1, 1, 1);
        f.watermark.assertCurrent();
    }

    @Test void writeGrantsCannotReplaceEitherCompareGrant() throws Exception {
        for (boolean humanCompare : new boolean[] {false, true}) {
            for (boolean serviceCompare : new boolean[] {false, true}) {
                if (humanCompare && serviceCompare) continue;
                var f = new Fixture(humanCompare, serviceCompare);
                f.writeGrants();
                var calls = new AtomicInteger();
                assertThatThrownBy(() -> f.issue("write-is-not-compare", BINDING,
                        ignored -> calls.incrementAndGet())).isInstanceOf(IllegalStateException.class);
                assertThat(calls.get()).isZero();
                f.counts(0, 0, 0);
                f.watermark.assertCurrent();
            }
        }
    }

    @Test void actionSpecificGrantsDoNotPermitCrossPathReplayOrRevocation() throws Exception {
        var f = new Fixture(true, true);
        f.writeGrants(); // Each action has its own current epoch; denial is not missing WRITE authority.
        var compare = f.issue("compare-key", BINDING, ignored -> { });
        assertThatThrownBy(() -> f.delegations.issue("task", f.service, f.until,
                f.human, "compare-key")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.delegations.revoke("task", compare.delegationId(), 1,
                f.human, "write-revoke-compare")).isInstanceOf(IllegalStateException.class);
        f.counts(1, 1, 1);
        var write = f.delegations.issue("task", f.service, f.until, f.human, "write-key");
        assertThatThrownBy(() -> f.issue("write-key", BINDING, ignored -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.delegations.revokeComparison("task", write.delegationId(), 1,
                f.human, "compare-revoke-write")).isInstanceOf(IllegalStateException.class);
        assertThat(f.delegations.revoke("task", write.delegationId(), 1, f.human,
                "write-revoke").status()).isEqualTo("REVOKED");
        assertThat(f.delegations.revokeComparison("task", compare.delegationId(), 1,
                f.human, "compare-revoke").status()).isEqualTo("REVOKED");
        f.counts(2, 4, 4);
        f.watermark.assertCurrent();
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF", "abc", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void bindingMustBeLowercaseSha256(String binding) throws Exception {
        var f = new Fixture(true, true);
        assertThatThrownBy(() -> f.issue("invalid-binding", binding, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        f.counts(0, 0, 0);
        f.watermark.assertCurrent();
    }

    enum Invalid {
        ISSUER, SID, JTI, HUMAN_DISABLED, HUMAN_NO_PASSWORD, SESSION_REVOKED, SESSION_EXPIRED,
        HUMAN_GRANT_REVOKED, SERVICE_GRANT_REVOKED, HUMAN_EPOCH, SERVICE_EPOCH,
        HUMAN_GRANT_TOO_SHORT, SERVICE_GRANT_TOO_SHORT, WORKLOAD_REVOKED, WORKLOAD_TOO_SHORT,
        NOT_AGENT, SERVICE_DISABLED, NOT_SERVICE, AUTHORIZATION_VERSION, DATA_SCOPE,
        TASK_STATE, TASK_TOO_SHORT, ORGANIZATION_DISABLED
    }

    @ParameterizedTest @EnumSource(Invalid.class)
    void currentAuthorityIsRequiredForNewIssueAndReplay(Invalid invalid) throws Exception {
        for (boolean replay : new boolean[] {false, true}) {
            var f = new Fixture(true, true);
            if (replay) f.issue("current-check", BINDING, ignored -> { });
            f.invalidate(invalid);
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> f.issue("current-check", BINDING,
                    ignored -> calls.incrementAndGet())).as("%s, replay=%s", invalid, replay)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isZero();
            f.counts(replay ? 1 : 0, replay ? 1 : 0, replay ? 1 : 0);
            f.watermark.assertCurrent();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Invalid.class, names = {"ISSUER", "SID", "JTI", "HUMAN_DISABLED",
            "HUMAN_NO_PASSWORD", "SESSION_REVOKED", "SESSION_EXPIRED", "HUMAN_GRANT_REVOKED",
            "HUMAN_EPOCH", "AUTHORIZATION_VERSION", "DATA_SCOPE"})
    void revokeRequiresCurrentHumanCompareAuthority(Invalid invalid) throws Exception {
        var f = new Fixture(true, true);
        f.writeGrants();
        var issued = f.issue("before-revoke", BINDING, ignored -> { });
        f.invalidate(invalid);
        assertThatThrownBy(() -> f.delegations.revokeComparison("task", issued.delegationId(), 1,
                f.human, "denied-revoke")).isInstanceOf(IllegalStateException.class);
        f.counts(1, 1, 1);
        assertThat(f.db.queryForObject("SELECT status FROM con3_worker_delegation",
                String.class)).isEqualTo("ACTIVE");
        f.watermark.assertCurrent();
    }

    @Test void wrongTaskVersionAndExpiryFailWithoutSideEffects() throws Exception {
        var f = new Fixture(true, true);
        assertThatThrownBy(() -> f.delegations.issueComparison("other", f.service, f.until,
                f.human, "other-task", BINDING, ignored -> { })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.delegations.issueComparison("task", f.service, f.clock.instant(),
                f.human, "expired", BINDING, ignored -> { })).isInstanceOf(IllegalArgumentException.class);
        var receipt = f.issue("right-task", BINDING, ignored -> { });
        assertThatThrownBy(() -> f.delegations.revokeComparison("other", receipt.delegationId(), 1,
                f.human, "other-revoke")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.delegations.revokeComparison("task", receipt.delegationId(), 2,
                f.human, "wrong-version")).isInstanceOf(IllegalStateException.class);
        f.counts(1, 1, 1);
        f.watermark.assertCurrent();
    }

    @Test void grantAndWorkloadExpiryMayEqualDelegatedUntil() throws Exception {
        var f = new Fixture(true, true);
        f.change("UPDATE id2_grant SET expires_at=? WHERE action_code='COMPARE'", Timestamp.from(f.until));
        f.change("UPDATE id2_workload SET expires_at=?", Timestamp.from(f.until));
        assertThat(f.issue("equal-expiry", BINDING, ignored -> { }).status()).isEqualTo("ACTIVE");
        f.counts(1, 1, 1);
        f.watermark.assertCurrent();
    }

    @Test void sessionLossInsideCallbackRollsBackTheWholeMainTransaction() throws Exception {
        var f = new Fixture(true, true);
        long generation = f.watermark.currentGeneration();
        assertThatThrownBy(() -> f.issue("late-session-loss", BINDING, receipt ->
                f.db.update("UPDATE id2_session SET status='REVOKED' WHERE sid='session'")))
                .isInstanceOf(IllegalStateException.class);
        f.counts(0, 0, 0);
        assertThat(f.db.queryForObject("SELECT status FROM id2_session WHERE sid='session'",
                String.class)).isEqualTo("ACTIVE");
        assertThat(f.watermark.currentGeneration()).isEqualTo(generation);
    }

    @Test void receiptDigestIsCoveredByIndependentAuthority() throws Exception {
        var f = new Fixture(true, true);
        f.issue("digest-covered", BINDING, ignored -> { });
        // Deliberate tamper, NOT an authorized watermark change.
        f.db.update("UPDATE con3_worker_delegation_receipt SET request_sha=?", "0".repeat(64));
        assertThatThrownBy(f.watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.issue("digest-covered", BINDING, ignored -> { }))
                .isInstanceOf(IllegalStateException.class);
        f.counts(1, 1, 1);
    }
    private static final class Fixture {
        final String issuer = "https://comparison-delegation.example.org";
        final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        final Instant expiry = now.plusSeconds(7200);
        final Instant grantExpiry = now.plusSeconds(6600);
        final Instant until = now.plusSeconds(6000);
        final JdbcTemplate db;
        final JdbcTemplate authority;
        final ProductGrantWatermark watermark;
        final ProductGrants grants;
        final ProductWorkerDelegations delegations;
        final String service;
        ProductGrants.Receipt humanCompare;
        ProductGrants.Receipt serviceCompare;
        Jwt human;

        Fixture(boolean withHumanCompare, boolean withServiceCompare) throws Exception {
            var primary = new JdbcDataSource();
            primary.setURL("jdbc:h2:mem:comparison-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            var independent = new JdbcDataSource();
            independent.setURL("jdbc:h2:mem:comparison-authority-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            try (var connection = primary.getConnection(); var separate = independent.getConnection()) {
                for (String name : new String[] {"V032__product_identity.sql",
                        "V034__product_consultation_initial_task.sql", "V041__product_task_authorization_version.sql",
                        "V042__product_consultation_goal.sql", "V046__product_workload_subject.sql",
                        "V047__product_task_worker_delegation.sql"})
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8", name)));
                ScriptUtils.executeSqlScript(separate, new FileSystemResource(
                        Path.of("db/identity-authority/V001__grant_watermark.sql")));
            }
            db = new JdbcTemplate(primary);
            authority = new JdbcTemplate(independent);
            authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            watermark = new ProductGrantWatermark(db, authority, issuer);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(primary));
            db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                    + "credential_version,created_at) VALUES(?,'human','human','encoded','ACTIVE',1,?)",
                    issuer, Timestamp.from(now));
            db.update("INSERT INTO id2_key_control VALUES(?,NULL,0)", issuer);
            db.update("INSERT INTO id2_signing_key VALUES('key',?,?,?,?,'ACTIVE',?,NULL)",
                    issuer, new byte[] {1}, new byte[] {1}, new byte[12], Timestamp.from(now));
            db.update("INSERT INTO id2_session VALUES('session','jti',?,'human','client','key',?,?,"
                    + "'ACTIVE',NULL)", issuer, Timestamp.from(now), Timestamp.from(expiry));
            db.update("INSERT INTO con3_organization VALUES('org','Organization','ACTIVE',1,?)",
                    Timestamp.from(now));
            db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                    + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                    + "VALUES('task','org',?,'human','IN_PROGRESS',1,?,'policy',1,?,?)", issuer,
                    Timestamp.from(expiry), "a".repeat(64), Timestamp.from(now));
            db.update("INSERT INTO con3_data_scope VALUES('scope','task','org',1,'TASK_METADATA')");
            service = new ProductWorkloadSubjects(db, tx, watermark, clock, issuer)
                    .register("b".repeat(64), "org", ProductWorkloadSubjects.Kind.AGENT, expiry,
                            "register", "maintainer#certificate").subjectId();
            grants = new ProductGrants(db, tx, clock);
            if (withHumanCompare) humanCompare = humanGrant("COMPARE");
            if (withServiceCompare) serviceCompare = watermark.change(() -> grants.issueComparisonWorkload(
                    issuer, service, "org", "task", grantExpiry, 0, "maintainer#certificate",
                    "service-compare", "d".repeat(64)));
            human = jwt(issuer, "session", "jti");
            delegations = new ProductWorkerDelegations(db, tx, watermark,
                    new ProductConsultationAccess(db, watermark, issuer, clock),
                    new JdbcProductTaskDirectory(db, clock), clock, issuer);
        }

        ProductGrants.Receipt humanGrant(String action) {
            var scope = new ProductGrants.Scope(issuer, "human", "org", "CONSULTATION", "task",
                    "scope:1", "TASK", "task", 1, action, "TASK_WORK");
            return watermark.change(() -> grants.issue(scope, grantExpiry, 0,
                    "maintainer#certificate", "human-" + action, "c".repeat(64)));
        }

        void writeGrants() {
            humanGrant("WRITE");
            watermark.change(() -> grants.issueWorkload(issuer, service, "org", "task", grantExpiry,
                    0, "maintainer#certificate", "service-write", "d".repeat(64)));
        }

        Jwt jwt(String tokenIssuer, String sid, String jti) {
            return Jwt.withTokenValue("test").header("alg", "RS256").issuer(tokenIssuer)
                    .subject("human").claim("jti", jti).claim("sid", sid).issuedAt(now).expiresAt(expiry).build();
        }

        ProductWorkerDelegations.Receipt issue(String key, String binding,
                Consumer<ProductWorkerDelegations.Receipt> callback) {
            return delegations.issueComparison("task", service, until, human, key, binding, callback);
        }

        void counts(int delegation, int event, int receipt) {
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_worker_delegation", Integer.class))
                    .isEqualTo(delegation);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_worker_delegation_event", Integer.class))
                    .isEqualTo(event);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_worker_delegation_receipt", Integer.class))
                    .isEqualTo(receipt);
        }

        void change(String sql, Object... args) {
            // Test-only current-authority mutation, published to the independent watermark.
            watermark.change(() -> db.update(sql, args));
        }

        void invalidate(Invalid invalid) {
            switch (invalid) {
                case ISSUER -> human = jwt("https://other.example.org", "session", "jti");
                case SID -> human = jwt(issuer, "not-session", "jti");
                case JTI -> human = jwt(issuer, "session", "not-jti");
                case HUMAN_DISABLED -> change("UPDATE id2_subject SET status='DISABLED' WHERE subject_id='human'");
                case HUMAN_NO_PASSWORD -> change("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL WHERE subject_id='human'");
                case SESSION_REVOKED -> change("UPDATE id2_session SET status='REVOKED' WHERE sid='session'");
                case SESSION_EXPIRED -> change("UPDATE id2_session SET issued_at=?,expires_at=? WHERE sid='session'", Timestamp.from(now.minusSeconds(120)), Timestamp.from(now.minusSeconds(60)));
                case HUMAN_GRANT_REVOKED, SERVICE_GRANT_REVOKED -> {
                    var grant = invalid == Invalid.HUMAN_GRANT_REVOKED ? humanCompare : serviceCompare;
                    watermark.change(() -> grants.revoke(grant.grantId(), 1, "maintainer#certificate",
                            "revoke-grant", "f".repeat(64)));
                }
                case HUMAN_EPOCH, SERVICE_EPOCH -> change("UPDATE id2_current_epoch SET authorization_epoch=authorization_epoch+1 WHERE subject_id=? AND action_code='COMPARE'",
                        invalid == Invalid.HUMAN_EPOCH ? "human" : service);
                case HUMAN_GRANT_TOO_SHORT, SERVICE_GRANT_TOO_SHORT -> change("UPDATE id2_grant SET expires_at=? WHERE grant_id=?",
                        Timestamp.from(until.minusSeconds(1)), invalid == Invalid.HUMAN_GRANT_TOO_SHORT ? humanCompare.grantId() : serviceCompare.grantId());
                case WORKLOAD_REVOKED -> change("UPDATE id2_workload SET status='REVOKED'");
                case WORKLOAD_TOO_SHORT -> change("UPDATE id2_workload SET expires_at=?", Timestamp.from(until.minusSeconds(1)));
                case NOT_AGENT -> change("UPDATE id2_workload SET workload_kind='SEARCH_JOB'");
                case SERVICE_DISABLED -> change("UPDATE id2_subject SET status='DISABLED' WHERE subject_id=?", service);
                case NOT_SERVICE -> change("UPDATE id2_subject SET subject_kind='HUMAN',password_hash='encoded' WHERE subject_id=?", service);
                case AUTHORIZATION_VERSION -> change("UPDATE con3_task SET authorization_version=2");
                case DATA_SCOPE -> change("UPDATE con3_data_scope SET scope_version=2");
                case TASK_STATE -> change("UPDATE con3_task SET state='AWAITING_GOAL'");
                case TASK_TOO_SHORT -> change("UPDATE con3_task SET expires_at=?", Timestamp.from(until.minusSeconds(1)));
                case ORGANIZATION_DISABLED -> change("UPDATE con3_organization SET status='DISABLED'");
            }
        }
    }
}
