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
        clock.now = now.plusSeconds(60);
        assertThat(cursors.issue(context, 20, 20)).isEqualTo(token);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_result_cursor", Integer.class)).isEqualTo(1);
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
        String fresh = cursors.issue(context, 20, 20);
        assertThat(fresh).isNotEqualTo(token);
        assertThat(cursors.resolve(fresh, context, 20)).isEqualTo(20);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_result_cursor", Integer.class)).isEqualTo(2);
    }

    @Test void reuseRequiresEveryBindingAndCannotExtendOrExceedContextExpiry() {
        Instant now = Instant.parse("2026-10-01T01:00:00Z");
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:cursor-bindings-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_result_cursor(cursor_id VARCHAR(36) PRIMARY KEY,"
                + "result_set_id VARCHAR(36),next_ordinal BIGINT,subject_id VARCHAR(200),"
                + "task_version BIGINT,input_revision VARCHAR(100),scope_sha VARCHAR(64),"
                + "source_epoch_sha VARCHAR(64),expires_at TIMESTAMP,method_version VARCHAR(100),page_size INT)");
        var clock = new MutableClock(now);
        var cursors = new ProductPatentSearchCursor(db, "c".repeat(32).getBytes(), Duration.ofMinutes(15), clock);
        var context = new ProductPatentSearchCursor.Context("result", "method", "subject", 2,
                "input", "a".repeat(64), "b".repeat(64), now.plusSeconds(3600));
        String first = cursors.issue(context, 20, 20);
        var variants = java.util.List.of(
                new ProductPatentSearchCursor.Context("other", "method", "subject", 2, "input", context.scopeSha(), context.sourceEpochSha(), context.expiresAt()),
                new ProductPatentSearchCursor.Context("result", "other", "subject", 2, "input", context.scopeSha(), context.sourceEpochSha(), context.expiresAt()),
                new ProductPatentSearchCursor.Context("result", "method", "other", 2, "input", context.scopeSha(), context.sourceEpochSha(), context.expiresAt()),
                new ProductPatentSearchCursor.Context("result", "method", "subject", 3, "input", context.scopeSha(), context.sourceEpochSha(), context.expiresAt()),
                new ProductPatentSearchCursor.Context("result", "method", "subject", 2, "other", context.scopeSha(), context.sourceEpochSha(), context.expiresAt()),
                new ProductPatentSearchCursor.Context("result", "method", "subject", 2, "input", "c".repeat(64), context.sourceEpochSha(), context.expiresAt()),
                new ProductPatentSearchCursor.Context("result", "method", "subject", 2, "input", context.scopeSha(), "d".repeat(64), context.expiresAt()));
        for (var variant : variants) {
            assertThat(cursors.issue(variant, 20, 20)).isNotEqualTo(first);
            assertThatThrownBy(() -> cursors.resolve(first, variant, 20))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        }
        assertThat(cursors.issue(context, 10, 20)).isNotEqualTo(first);
        assertThat(cursors.issue(context, 20, 40)).isNotEqualTo(first);
        var tighter = new ProductPatentSearchCursor.Context(context.resultSetId(), context.methodVersion(),
                context.subjectId(), context.taskVersion(), context.inputRevision(), context.scopeSha(),
                context.sourceEpochSha(), now.plusSeconds(120));
        assertThat(cursors.issue(tighter, 20, 20)).isNotEqualTo(first);
        assertThat(db.queryForObject("SELECT MAX(expires_at) FROM con4_result_cursor WHERE "
                + "result_set_id='result' AND method_version='method' AND subject_id='subject' "
                + "AND task_version=2 AND input_revision='input' AND scope_sha=? AND source_epoch_sha=? "
                + "AND page_size=20 AND next_ordinal=20 AND expires_at<=?", java.sql.Timestamp.class,
                context.scopeSha(), context.sourceEpochSha(), java.sql.Timestamp.from(tighter.expiresAt()))
                .toInstant()).isEqualTo(tighter.expiresAt());
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
