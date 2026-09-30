package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductPatentSearchCursorTest {
    @Test void signedCursorBindsResultMemberPageAndExpiry() {
        Instant now = Instant.parse("2026-09-29T02:00:00Z");
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:cursor-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_result_cursor(cursor_id VARCHAR(36) PRIMARY KEY,"
                + "result_set_id VARCHAR(36),next_ordinal BIGINT,subject_id VARCHAR(200),"
                + "task_version BIGINT,input_revision VARCHAR(100),scope_sha VARCHAR(64),"
                + "source_epoch_sha VARCHAR(64),expires_at TIMESTAMP,method_version VARCHAR(100),"
                + "page_size INT)");
        var clock = new MutableClock(now);
        var cursors = new ProductPatentSearchCursor(db, "c".repeat(32).getBytes(),
                Duration.ofMinutes(15), clock);
        var context = new ProductPatentSearchCursor.Context(UUID.randomUUID().toString(),
                "BM25F/v1", "member", 2, "input", "a".repeat(64), "b".repeat(64),
                now.plusSeconds(3600));

        String token = cursors.issue(context, 20, 20);
        assertThat(cursors.resolve(token, context, 20)).isEqualTo(20);
        assertThatThrownBy(() -> cursors.resolve(token + "x", context, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        assertThatThrownBy(() -> cursors.resolve(token, context, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        var otherMember = new ProductPatentSearchCursor.Context(context.resultSetId(),
                context.methodVersion(), "other", context.taskVersion(), context.inputRevision(),
                context.scopeSha(), context.sourceEpochSha(), context.expiresAt());
        assertThatThrownBy(() -> cursors.resolve(token, otherMember, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        clock.now = now.plusSeconds(901);
        assertThatThrownBy(() -> cursors.resolve(token, context, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410");
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
