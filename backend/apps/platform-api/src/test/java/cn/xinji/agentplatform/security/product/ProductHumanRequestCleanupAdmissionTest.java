package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

/** Real two-store request rows and signed component identity; no cleanup writer or DM8 claim. */
class ProductHumanRequestCleanupAdmissionTest {
    @TempDir Path root;
    private ProductHumanRequestApplicationFixture fixture;
    private ProductHumanRequestApplication.Result original;
    private ManifestReads reads;

    @BeforeEach void setup() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true);
        original = fixture.request();
        reads = new ManifestReads(fixture.base.db);
        ReflectionTestUtils.setField(fixture.application, "store",
                new ProductHumanRequestStore(reads, fixture.mapper, fixture.base.clock));
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "missingAuthority", "missingPrimary", "expired"})
    void unavailableCurrentScopeRejectsBeforeSelectingOrDecodingMalformedManifest(String state) {
        malformed();
        switch (state) {
            case "pending" -> fixture.base.authority.update(
                    "UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='component-cleanup'");
            case "missingAuthority" -> fixture.base.authority.update("DELETE FROM id6_human_cleanup_scope");
            case "missingPrimary" -> {
                fixture.base.db.update("DELETE FROM con6_human_cleanup_event");
                fixture.base.db.update("DELETE FROM con6_human_cleanup_scope");
            }
            case "expired" -> {
                var until = fixture.base.clock.instant().plusMillis(1);
                for (var db : List.of(fixture.base.db, fixture.base.authority))
                    db.update("UPDATE " + (db == fixture.base.db ? "con6" : "id6")
                            + "_human_cleanup_scope SET valid_until=?", Timestamp.from(until));
                fixture.base.clock.now = until;
            }
            default -> throw new AssertionError(state);
        }
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class);
        noManifestRead();
        assertThat(fixture.base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class))
                .isEqualTo("unreadable component manifest");
        unchangedRequestRows();
    }

    @Test void validCurrentScopeStillReachesTheStrictManifestDecoder() {
        malformed();
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class)
                .hasMessage("Comparison persisted JSON is invalid");
        assertThat(reads.selections).isEqualTo(1);
        assertThat(reads.bodyColumnReads).isPositive();
        unchangedRequestRows();
    }

    @Test void scopeBecomingPendingAtThePreBodyRecheckPreventsManifestSelection() {
        malformed();
        var changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true)) fixture.base.authority.update(
                    "UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='late-component-cleanup'");
            return call.callRealMethod();
        }).when(fixture.cleanup).assertUnchanged(any());
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class);
        assertThat(changed).isTrue();
        noManifestRead();
        unchangedRequestRows();
    }

    @ParameterizedTest @ValueSource(strings = {"present", "malformed", "null"})
    void nullAccessDeadlineWithoutAnAuthenticatedClearCannotAuthorizeReceiptOnly(String body) {
        fixture.base.db.update("UPDATE con6_human_package SET body_access_until=NULL");
        if (body.equals("malformed")) malformed();
        if (body.equals("null")) {
            // Only this owned H2 fixture models a claimed cleared poststate; no production DDL is added.
            fixture.base.db.execute("ALTER TABLE con6_human_package ALTER COLUMN manifest_json DROP NOT NULL");
            fixture.base.db.update("UPDATE con6_human_package SET manifest_json=NULL");
        }
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class);
        noManifestRead();
        assertThat(fixture.base.db.queryForObject("SELECT generation FROM con6_human_cleanup_scope", Long.class))
                .isEqualTo(1L);
        assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_cleanup_event", Integer.class))
                .isEqualTo(1);
        unchangedRequestRows();
    }

    @Test void originalBodyDeadlineReturnsOnlyTheOriginalReceiptWithoutManifestAccessOrRenewal() {
        malformed();
        fixture.base.clock.now = ProductHumanRequestStore.bodyAccessUntil(original.body().manifest());
        assertThat(fixture.base.clock.instant()).isBefore(original.receipt().auditUntil());
        var result = lookup();
        assertThat(result.body()).isNull();
        assertThat(result.receipt()).isEqualTo(original.receipt());
        noManifestRead();
        unchangedRequestRows();
        verifyNoInteractions(fixture.comparisons, fixture.files);
    }

    @ParameterizedTest @ValueSource(strings = {"READ", "WRITE"})
    void expiredBodyDoesNotWeakenCurrentTaskPermissionsForMinimalReceipt(String action) {
        malformed();
        fixture.base.clock.now = ProductHumanRequestStore.bodyAccessUntil(original.body().manifest());
        fixture.base.watermark.change(() -> fixture.base.db.update(
                "UPDATE id2_grant SET status='REVOKED' WHERE action_code=?", action));
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class)
                .hasMessage("Current Task " + action + " permission required");
        noManifestRead();
        unchangedRequestRows();
    }

    @Test void expiredOriginalReceiptCannotBecomeReadableThroughTheMinimalBranch() {
        malformed();
        var auditUntil = original.receipt().createdAt().plusSeconds(50);
        fixture.base.db.update("UPDATE con6_human_receipt SET audit_until=?", Timestamp.from(auditUntil));
        fixture.base.db.update("UPDATE con6_human_event SET audit_until=?", Timestamp.from(auditUntil));
        fixture.base.clock.now = auditUntil;
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class);
        noManifestRead();
        unchangedRequestRows();
    }

    private ProductHumanRequestApplication.Result lookup() {
        return fixture.application.lookup(fixture.base.token, fixture.base.task.id(), "human-original-key");
    }

    private void malformed() {
        fixture.base.db.update("UPDATE con6_human_package SET manifest_json='unreadable component manifest'");
    }

    private void noManifestRead() {
        assertThat(reads.selections).isZero();
        assertThat(reads.bodyColumnReads).isZero();
    }

    private void unchangedRequestRows() {
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class))
                    .isEqualTo(1);
    }

    private static final class ManifestReads extends JdbcTemplate {
        int selections;
        int bodyColumnReads;

        ManifestReads(JdbcTemplate original) { super(original.getDataSource()); }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (!sql.startsWith("SELECT manifest_json,")) return super.query(sql, mapper, args);
            selections++;
            return super.query(sql, (result, row) -> mapper.mapRow(observe(result), row), args);
        }

        private ResultSet observe(ResultSet result) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        if (args != null && args.length > 0 && (Integer.valueOf(1).equals(args[0])
                                || "manifest_json".equalsIgnoreCase(String.valueOf(args[0])))
                                && List.of("getString", "getClob", "getCharacterStream", "getObject").contains(method.getName()))
                            bodyColumnReads++;
                        try { return method.invoke(result, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
