package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSubjectsTest {
    @Test void oneTimeActivationAndDisablingCannotRestoreAnOldAccount() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:subjects-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/dm8/V032__product_identity.sql")));
        }
        JdbcTemplate db = new JdbcTemplate(source);
        Clock clock = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);
        ProductSubjects subjects = new ProductSubjects(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), new BCryptPasswordEncoder(),
                clock, "https://identity.example.org", Duration.ofMinutes(30), 12);

        var registration = subjects.register("verified-project-member", "security-maintainer");
        assertThat(db.queryForObject("SELECT status FROM id2_subject WHERE subject_id=?", String.class,
                registration.subjectId())).isEqualTo("PENDING");
        assertThatThrownBy(() -> subjects.activate(registration.subjectId(), "wrong-token",
                "a-strong-password-1")).isInstanceOf(ProductSubjects.InvalidActivationException.class);
        subjects.activate(registration.subjectId(), registration.oneTimeActivation(), "a-strong-password-1");
        assertThatThrownBy(() -> subjects.activate(registration.subjectId(), registration.oneTimeActivation(),
                "a-strong-password-1")).isInstanceOf(ProductSubjects.InvalidActivationException.class);
        assertThat(db.queryForObject("SELECT password_hash FROM id2_subject WHERE subject_id=?", String.class,
                registration.subjectId())).startsWith("$2").doesNotContain("a-strong-password-1");
        subjects.disable(registration.subjectId(), "security-maintainer");
        assertThat(db.queryForObject("SELECT status FROM id2_subject WHERE subject_id=?", String.class,
                registration.subjectId())).isEqualTo("DISABLED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_subject_event WHERE subject_id=?",
                Integer.class, registration.subjectId())).isEqualTo(3);
        assertThatThrownBy(() -> subjects.disable(registration.subjectId(), "security-maintainer"))
                .isInstanceOf(IllegalStateException.class);
    }
}
