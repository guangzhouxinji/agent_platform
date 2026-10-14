package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
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

/** Real request rows and signed identity; controlled clock boundary during the body SELECT, not HTTP/DM8. */
class ProductHumanRequestReadOrderTest {
    @TempDir Path root;
    private ProductHumanRequestApplicationFixture fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true);
    }

    @ParameterizedTest @ValueSource(strings = {"READ", "WRITE"})
    void revokedTaskPermissionRejectsBeforeMalformedBodyIsDecoded(String action) {
        fixture.request();
        malformed();
        fixture.base.watermark.change(() -> fixture.base.db.update(
                "UPDATE id2_grant SET status='REVOKED' WHERE action_code=?", action));
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class)
                .hasMessage("Current Task " + action + " permission required").hasNoCause();
        unchanged();
    }

    @Test void admittedCurrentBodyReachesStrictJsonDecoder() {
        fixture.request();
        malformed();
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class)
                .hasMessage("Comparison persisted JSON is invalid");
        unchanged();
    }

    @Test void deadlineReachedDuringBodySelectRejectsBeforeMalformedJsonDecoding() {
        var original = fixture.request();
        malformed();
        var deadline = ProductHumanRequestStore.bodyAccessUntil(original.body().manifest());
        fixture.base.clock.now = deadline.minusNanos(1);
        var selected = new AtomicBoolean();
        var db = new JdbcTemplate(fixture.base.source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.startsWith("SELECT manifest_json,")) {
                    selected.set(true);
                    fixture.base.clock.now = deadline;
                }
                return super.query(sql, mapper, args);
            }
        };
        ReflectionTestUtils.setField(fixture.application, "store",
                new ProductHumanRequestStore(db, fixture.mapper, fixture.base.clock));
        assertThatThrownBy(this::lookup).isInstanceOf(IllegalStateException.class)
                .hasMessage("Human request persistence closure invalid").hasNoCause();
        assertThat(selected).isTrue();
        unchanged();
    }

    private ProductHumanRequestApplication.Result lookup() {
        return fixture.application.lookup(fixture.base.token, fixture.base.task.id(), "human-original-key");
    }
    private void malformed() {
        fixture.base.db.update("UPDATE con6_human_package SET manifest_json='unreadable body'");
    }
    private void unchanged() {
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(1);
        assertThat(fixture.base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class)).isEqualTo("unreadable body");
    }
}
