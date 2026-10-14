package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductHumanCleanupScopeTest {
    private final ProductHumanCleanupScope.Policy policy = new ProductHumanCleanupScope.Policy("human-v3", 3, "a".repeat(64));
    private Instant now = Instant.parse("2026-10-13T00:00:00Z");
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    };
    private JdbcDataSource primary;
    private JdbcDataSource independent;
    private JdbcTemplate main;
    private JdbcTemplate authority;
    private ProductHumanCleanupScope scope;
    private boolean admitted;
    private int currentChecks;
    private int newTaskChecks;
    private int denyAt;
    private int expireTaskAt;

    @BeforeEach void setup() throws Exception {
        primary = source(); independent = source();
        main = new JdbcTemplate(primary); authority = new JdbcTemplate(independent);
        migrate(primary, "dm8/V032__product_identity.sql", "dm8/V034__product_consultation_initial_task.sql",
                "dm8/V109__product_human_request.sql", "dm8/V110__product_human_cleanup_scope.sql");
        migrate(independent, "identity-authority/V006__consultation_human_cleanup_scope.sql");
        main.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                + "VALUES('issuer','human','human','encoded','ACTIVE',1,?)", Timestamp.from(now));
        main.update("INSERT INTO con3_organization VALUES('organization','Organization','ACTIVE',1,?)", Timestamp.from(now));
        main.update("INSERT INTO con3_task VALUES('task','organization','issuer','human','AWAITING_GOAL',1,"
                + "?,'approved-task',1,?,?)", Timestamp.from(now.plusSeconds(3600)), "a".repeat(64), Timestamp.from(now));
        admitted = true;
        scope = create(main, authority);
    }

    @Test void persistsExactIndependentScopeAndRestoresOnlyUnchangedSnapshot() {
        var result = initialize();
        assertThat(newTaskChecks).isEqualTo(1);
        assertThat(currentChecks).isEqualTo(5);
        assertThat(scope.current("task", policy)).isEqualTo(result);
        scope.requireRestorable(result);
        assertThat(result.validUntil()).isEqualTo(now.plus(Duration.ofDays(30)));
        assertThat(main.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_event", Long.class)).isEqualTo(1);
        now = now.plusSeconds(120);
        assertThat(scope.current("task", policy).validUntil()).isEqualTo(result.validUntil());
        assertThat(main.queryForObject("SELECT event_json FROM con6_human_cleanup_event", String.class))
                .contains("maintainer").doesNotContain("token", "password");
    }

    @Test void missingScopeReadNeverInitializes() {
        assertThatThrownBy(() -> scope.current("task", policy)).isInstanceOf(IllegalStateException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Long.class)).isZero();
    }

    @Test void unknownIndependentHistoryAndMissingMaintenanceRightRefuseInitialization() {
        admitted = false;
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(main.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_scope", Long.class)).isZero();
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Long.class)).isZero();
    }

    @Test void existingHumanHistoryCannotInitialize() {
        main.update("INSERT INTO con6_human_package VALUES('package',1,'task','organization','scope',1,'issuer','human',"
                + "'{}',?,?,?)", "a".repeat(64), Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)));
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Long.class)).isZero();
    }

    @Test void duplicateInitializationCannotResetFiniteDeadline() {
        var before = initialize();
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(scope.current("task", policy)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"state='PENDING',intent_id='pending'", "generation=2",
            "policy_sha='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'",
            "history_sha='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'"})
    void independentDriftRejectsCurrentAndBackup(String change) {
        var before = initialize();
        authority.update("UPDATE id6_human_cleanup_scope SET " + change + " WHERE task_id='task'");
        assertThatThrownBy(() -> scope.current("task", policy)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.requireRestorable(before)).isInstanceOf(IllegalStateException.class);
    }

    @Test void primaryEventTamperingRejectsEvenWithMatchingProjectionDigest() {
        initialize();
        main.update("UPDATE con6_human_cleanup_event SET event_json=REPLACE(event_json,'maintainer','intruder')");
        assertThatThrownBy(() -> scope.current("task", policy)).isInstanceOf(IllegalStateException.class);
    }

    @Test void deletedAuthorityCannotBeRebuiltByReadOrBackup() {
        var backup = initialize();
        authority.update("DELETE FROM id6_human_cleanup_scope");
        assertThatThrownBy(() -> scope.requireRestorable(backup)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Long.class)).isZero();
    }

    @Test void exactExpiryRejectsWithoutReadRenewal() {
        var before = initialize(); now = before.validUntil();
        assertThatThrownBy(() -> scope.current("task", policy)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.requireRestorable(before)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "expiry"})
    void independentChangeDuringReadRejectsBeforeRelease(String change) {
        var before = initialize();
        var changing = new JdbcTemplate(independent) {
            private int reads;
            @Override public <T> java.util.List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.contains("FROM id6_human_cleanup_scope") && ++reads == 2) {
                    if (change.equals("expiry")) now = before.validUntil();
                    else authority.update("UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='later'");
                }
                return super.query(sql, mapper, args);
            }
        };
        assertThatThrownBy(() -> create(main, changing).current("task", policy)).isInstanceOf(IllegalStateException.class);
    }

    @Test void shortAuditLongTaskAndBackupOrUnapprovedYearReject() {
        assertThatThrownBy(() -> scope.initialize("task", policy, "maintainer", now.plusSeconds(3000), now,
                now.plusSeconds(3000))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.initialize("task", policy, "maintainer", now.plus(Duration.ofDays(365)),
                now.plus(Duration.ofDays(31)), now.plus(Duration.ofDays(30)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.initialize("task", policy, "maintainer", now.plus(Duration.ofDays(400)),
                now, now.plus(Duration.ofDays(366)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void primaryRollbackLeavesIndependentPendingInsteadOfReleasingUnknownIntent() {
        main.execute("ALTER TABLE con6_human_cleanup_event ADD CONSTRAINT fail_init CHECK(generation>1)");
        assertThatThrownBy(this::initialize).isInstanceOf(RuntimeException.class);
        assertThat(main.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_scope", Long.class)).isZero();
        assertThat(authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class)).isEqualTo("PENDING");
        assertThatThrownBy(() -> scope.current("task", policy)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingCurrentAdmissionDefaultsToDenyBeforeEitherScopeWrite() {
        scope = new ProductHumanCleanupScope(main, authority, new DataSourceTransactionManager(primary),
                new ObjectMapper().findAndRegisterModules(), clock, (t, o, p, a, n) -> {});
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessage("Current maintenance admission required");
        assertScopeState(0, null);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void outerTransactionWithoutSynchronizationCannotPublishIndependentReady(boolean independentTransaction) {
        var manager = new DataSourceTransactionManager(independentTransaction ? independent : primary);
        manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThatThrownBy(this::initialize).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Standalone admitted maintenance operation required");
            status.setRollbackOnly();
        });
        assertScopeState(0, null);
        assertThat(newTaskChecks).isZero();
        assertThat(currentChecks).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void currentAdmissionLossStopsAtEachBoundaryWithoutReplayingBirth(int stage) {
        denyAt = stage;
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessage("Controlled maintenance lease lost");
        assertThat(newTaskChecks).isEqualTo(1);
        assertThat(currentChecks).isEqualTo(stage);
        assertScopeState(stage < 3 ? 0 : 1, stage == 1 ? null : "PENDING");
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void taskExpiryDuringCurrentAdmissionStopsNecessaryWrites(int stage) {
        expireTaskAt = stage;
        assertThatThrownBy(this::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(newTaskChecks).isEqualTo(1);
        assertThat(currentChecks).isEqualTo(stage);
        assertScopeState(stage < 3 ? 0 : 1, stage == 1 ? null : "PENDING");
    }

    private void assertScopeState(long primaryCount, String independentState) {
        assertThat(main.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_scope", Long.class)).isEqualTo(primaryCount);
        assertThat(main.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_event", Long.class)).isEqualTo(primaryCount);
        if (independentState == null)
            assertThat(authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Long.class)).isZero();
        else assertThat(authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class)).isEqualTo(independentState);
    }

    @Test void mismatchedManagerAndSamePoolRejectBeforeOperations() {
        assertThatThrownBy(() -> new ProductHumanCleanupScope(main, authority, new DataSourceTransactionManager(independent),
                new ObjectMapper().findAndRegisterModules(), clock, (t, o, p, a, n) -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(main, main)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void reverseMigrationsRemoveOnlyNewScopeTables() throws Exception {
        initialize();
        migrate(primary, "dm8/U110__product_human_cleanup_scope.sql");
        migrate(independent, "identity-authority/U006__consultation_human_cleanup_scope.sql");
        assertThat(main.queryForObject("SELECT COUNT(*) FROM con3_task", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> scope.current("task", policy)).isInstanceOf(RuntimeException.class);
    }

    private ProductHumanCleanupScope.Snapshot initialize() {
        return scope.initialize("task", policy, "maintainer", now.plus(Duration.ofDays(365)),
                now.plus(Duration.ofDays(20)), now.plus(Duration.ofDays(30)));
    }
    private ProductHumanCleanupScope create(JdbcTemplate a, JdbcTemplate b) {
        return new ProductHumanCleanupScope(a, b, new DataSourceTransactionManager(primary), new ObjectMapper().findAndRegisterModules(), clock,
                new ProductHumanCleanupScope.InitializationAdmission() {
                    public void requireNewTaskWithoutHistory(String t, String o, ProductHumanCleanupScope.Policy p,
                            String actor, Instant at) {
                        newTaskChecks++;
                        if (!admitted) throw new IllegalStateException("No independent new-task evidence");
                    }
                    public void requireCurrent(Instant at) {
                        currentChecks++;
                        if (currentChecks == denyAt) throw new IllegalStateException("Controlled maintenance lease lost");
                        if (currentChecks == expireTaskAt) now = now.plusSeconds(3600);
                    }
                });
    }
    private static JdbcDataSource source() {
        var s = new JdbcDataSource(); s.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        s.setUser("sa"); return s;
    }
}
