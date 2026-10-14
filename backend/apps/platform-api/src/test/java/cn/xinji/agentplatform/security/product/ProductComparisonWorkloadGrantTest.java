package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Mechanical command checks; not real DM8, service TLS, Worker execution or professional acceptance. */
class ProductComparisonWorkloadGrantTest {
    private static final String ISSUER = "https://comparison-worker.example.org";
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(1800);
    private static final String SHA = "a".repeat(64);
    private JdbcTemplate db;
    private ProductGrantWatermark watermark;
    private ProductGrants grants;
    private String subject;

    @BeforeEach void setup() throws Exception {
        var primary = database();
        var independent = database();
        try (var connection = primary.getConnection(); var authority = independent.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql", "V041__product_task_authorization_version.sql",
                    "V042__product_consultation_goal.sql", "V046__product_workload_subject.sql"})
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", name)));
            ScriptUtils.executeSqlScript(authority, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        db = new JdbcTemplate(primary);
        var authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", ISSUER,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        watermark = new ProductGrantWatermark(db, authority, ISSUER);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(primary));
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        grants = new ProductGrants(db, tx, clock);
        subject = new ProductWorkloadSubjects(db, tx, watermark, clock, ISSUER)
                .register("b".repeat(64), "organization", ProductWorkloadSubjects.Kind.AGENT,
                        NOW.plusSeconds(3600), "register", "maintainer#certificate").subjectId();
        db.update("INSERT INTO con3_organization VALUES('organization','Organization','ACTIVE',1,?)",
                Timestamp.from(NOW));
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                        + "version,authorization_version,expires_at,retention_ref,retention_version,"
                        + "retention_evidence_sha,created_at) VALUES('task-1','organization',?,?,'IN_PROGRESS',"
                        + "1,7,?,'policy',1,?,?)", ISSUER, subject, Timestamp.from(NOW.plusSeconds(3600)),
                SHA, Timestamp.from(NOW));
        db.update("INSERT INTO con3_data_scope VALUES('scope-1','task-1','organization',3,'TASK_METADATA')");
    }

    private ProductGrants.Receipt compare(String request, String sha) {
        return watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject, "organization",
                "task-1", UNTIL, 0, "maintainer#certificate", request, sha));
    }

    @Test void exactCompareUsesCurrentTaskAndMetadataWithAuditedReplay() {
        var receipt = compare("compare", SHA);
        assertThat(receipt.status()).isEqualTo("ACTIVE");
        assertThat(receipt.epoch()).isEqualTo(1);
        assertThat(compare("compare", SHA)).isEqualTo(receipt);
        assertThat(db.queryForMap("SELECT domain_code,task_scope,data_scope,resource_type,resource_id,"
                + "resource_version,action_code,purpose FROM id2_grant WHERE grant_id=?", receipt.grantId()))
                .containsEntry("DOMAIN_CODE", "CONSULTATION").containsEntry("TASK_SCOPE", "task-1")
                .containsEntry("DATA_SCOPE", "scope-1:3").containsEntry("RESOURCE_TYPE", "TASK")
                .containsEntry("RESOURCE_ID", "task-1").containsEntry("RESOURCE_VERSION", 7L)
                .containsEntry("ACTION_CODE", "COMPARE").containsEntry("PURPOSE", "TASK_WORK");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event WHERE operation='ISSUE' "
                + "AND maintainer_ref='maintainer#certificate'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT password_hash FROM id2_subject WHERE subject_id=?",
                String.class, subject)).isNull();
        watermark.assertCurrent();
    }

    @Test void writeRemainsSeparateAndCannotReplayCompareEvenWithSameSha() {
        var comparison = compare("compare", SHA);
        assertThatThrownBy(() -> watermark.change(() -> grants.issueWorkload(ISSUER, subject, "organization",
                "task-1", UNTIL, 0, "maintainer#certificate", "compare", SHA)))
                .isInstanceOf(IllegalStateException.class);
        var write = watermark.change(() -> grants.issueWorkload(ISSUER, subject, "organization",
                "task-1", UNTIL, 0, "maintainer#certificate", "write", SHA));
        assertThat(write.grantId()).isNotEqualTo(comparison.grantId());
        assertThat(write.epoch()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT action_code || ':' || purpose FROM id2_grant WHERE grant_id=?",
                String.class, write.grantId())).isEqualTo("WRITE:TASK_WORK");
        assertThatThrownBy(() -> compare("write", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_current_epoch", Integer.class)).isEqualTo(2);
        watermark.assertCurrent();
    }

    @Test void payloadConflictAndCurrentGrantConflictLeaveNoPartialEvents() {
        compare("compare", SHA);
        assertThatThrownBy(() -> compare("compare", "c".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> compare("another", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);
        watermark.assertCurrent();
    }

    static Stream<String> unavailable() {
        return Stream.of(
                "UPDATE con3_task SET state='AWAITING_GOAL'",
                "UPDATE con3_task SET expires_at=created_at + INTERVAL '10' MINUTE",
                "UPDATE con3_organization SET status='DISABLED'",
                "UPDATE id2_workload SET status='REVOKED'",
                "UPDATE id2_workload SET organization='other-organization'",
                "UPDATE id2_workload SET workload_kind='SEARCH_JOB'",
                "UPDATE id2_workload SET expires_at=created_at + INTERVAL '10' MINUTE",
                "UPDATE id2_subject SET status='DISABLED'",
                "UPDATE id2_subject SET subject_kind='HUMAN',password_hash='encoded'",
                "DELETE FROM con3_data_scope");
    }

    @ParameterizedTest @MethodSource("unavailable")
    void invalidCurrentScopeCannotIssue(String mutation) {
        watermark.change(() -> db.update(mutation));
        assertThatThrownBy(() -> compare("compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    @ParameterizedTest @MethodSource("unavailable")
    void replayRechecksTaskWorkloadOrganizationAndService(String mutation) {
        compare("compare", SHA);
        watermark.change(() -> db.update(mutation));
        assertThatThrownBy(() -> compare("compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);
        watermark.assertCurrent();
    }

    static Stream<String> versionChanges() {
        return Stream.of("UPDATE con3_task SET authorization_version=8",
                "UPDATE con3_data_scope SET scope_version=4");
    }

    @ParameterizedTest @MethodSource("versionChanges")
    void replayCannotReturnOldTaskOrDataScopeGrant(String mutation) {
        compare("compare", SHA);
        db.update(mutation);
        assertThatThrownBy(() -> compare("compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);
        watermark.assertCurrent();
    }

    @Test void wrongTaskOrganizationIssuerEpochAndExpiredRequestReject() {
        assertThatThrownBy(() -> watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject,
                "other", "task-1", UNTIL, 0, "maintainer", "wrong-org", SHA)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject,
                "organization", "missing-task", UNTIL, 0, "maintainer", "wrong-task", SHA)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> watermark.change(() -> grants.issueComparisonWorkload("other-issuer", subject,
                "organization", "task-1", UNTIL, 0, "maintainer", "wrong-issuer", SHA)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject,
                "organization", "task-1", UNTIL, 2, "maintainer", "wrong-epoch", SHA)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject,
                "organization", "task-1", NOW, 0, "maintainer", "expired", SHA)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    @Test void revokedCompareCannotReplayAndRenewalRequiresItsCurrentEpoch() {
        var first = compare("compare", SHA);
        watermark.change(() -> grants.revoke(first.grantId(), 1, "maintainer", "revoke", SHA));
        assertThatThrownBy(() -> compare("compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> compare("renew-wrong", SHA)).isInstanceOf(IllegalStateException.class);
        var renewal = watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject, "organization",
                "task-1", UNTIL, 2, "maintainer", "renew", SHA));
        assertThat(renewal.epoch()).isEqualTo(3);
        assertThat(renewal.grantId()).isNotEqualTo(first.grantId());
        watermark.assertCurrent();
    }

    @Test void unresolvedIndependentWatermarkCannotIssue() {
        watermark.independentDatabase().update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='lost'");
        assertThatThrownBy(() -> compare("compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isZero();
    }

    @Test void eventFailureRollsBackGrantAndEpochBeforeWatermarkPublication() {
        assertThatThrownBy(() -> watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject,
                "organization", "task-1", UNTIL, 0, "m".repeat(201), "event-failure", SHA)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_current_epoch", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isZero();
        watermark.assertCurrent();
    }

    @Test void subMicrosecondExpiryDoesNotPreventIssuanceOrReplay() {
        Instant expiry = UNTIL.plusNanos(123456789);
        var receipt = watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject, "organization",
                "task-1", expiry, 0, "maintainer", "fractional-expiry", SHA));
        assertThat(watermark.change(() -> grants.issueComparisonWorkload(ISSUER, subject, "organization",
                "task-1", expiry, 0, "maintainer", "fractional-expiry", SHA))).isEqualTo(receipt);
        watermark.assertCurrent();
    }

    @Test void wrongWorkloadPurposeCannotReplayEvenIfStoredConstraintIsDamaged() {
        compare("compare", SHA);
        // Deliberately corrupted mechanical fixture; the production schema is not changed.
        db.execute("ALTER TABLE id2_workload DROP CONSTRAINT ck_id2_workload_purpose");
        watermark.change(() -> db.update("UPDATE id2_workload SET allowed_purpose='SOURCE_QUERY'"));
        assertThatThrownBy(() -> compare("compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> compare("new-compare", SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);
        watermark.assertCurrent();
    }

    private static JdbcDataSource database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:comparison-grant-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }
}
