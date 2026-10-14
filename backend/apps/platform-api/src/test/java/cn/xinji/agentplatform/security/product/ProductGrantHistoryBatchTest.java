package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Byte-contract comparison only; not an identity/source admission or business acceptance. */
class ProductGrantHistoryBatchTest {
    private static final String ISSUER = "https://batch-component.example";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test void completeHistoryKeepsUnicodeNullTimestampAndNumericEncodingBeyondFirstBatch() throws Exception {
        JdbcTemplate db = database(false);
        var grants = new ProductGrants(db, new TransactionTemplate(
                new DataSourceTransactionManager(db.getDataSource())), Clock.fixed(NOW, ZoneOffset.UTC));
        for (int i = 0; i < 300; i++) {
            var scope = new ProductGrants.Scope(ISSUER, "member", "组织𠮷", "CONSULTATION",
                    "task-" + i, "数".repeat(300), "TASK", "task-" + i, 1, "READ", "TASK_WORK");
            grants.issue(scope, NOW.plusSeconds(3600).plusNanos(i % 2 == 0 ? 0 : 123456000),
                    0, "component-maintainer", "request-" + i, "a".repeat(64));
        }
        db.update("INSERT INTO id2_subject_event(event_id,issuer,subject_id,operation,before_version,"
                + "after_version,actor_ref,occurred_at) VALUES(?,?,?,'DISABLE',1,2,?,?)",
                UUID.randomUUID().toString(), ISSUER, "member", "component-maintainer", Timestamp.from(NOW));
        db.update("UPDATE id2_subject SET status='DISABLED',credential_version=2,disabled_at=NULL WHERE issuer=?", ISSUER);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, false, false)).isEqualTo(serial(db, 5));
        String previous = serial(db, 5);
        String last = db.queryForObject("SELECT MAX(grant_id) FROM id2_grant", String.class);
        db.update("UPDATE id2_grant SET status='REVOKED' WHERE grant_id=?", last);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, false, false))
                .isEqualTo(serial(db, 5)).isNotEqualTo(previous);
        db.update("UPDATE id2_subject SET disabled_at=? WHERE issuer=?",
                Timestamp.from(NOW.plusNanos(654321000)), ISSUER);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, false, false)).isEqualTo(serial(db, 5));
    }

    @Test void optionalWorkloadHistoryUsesOriginalTimestampTypeAndEmptySections() throws Exception {
        JdbcTemplate db = database(true);
        db.update("INSERT INTO id2_workload(issuer,subject_id,certificate_sha,organization,workload_kind,"
                + "allowed_purpose,status,version,expires_at,created_at) VALUES(?,?,?,?,'SEARCH_JOB',"
                + "'TASK_WORK','ACTIVE',1,?,?)", ISSUER, "member", "a".repeat(64), "组织",
                Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW));
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, true, false)).isEqualTo(serial(db, 9));
        db.update("UPDATE id2_workload SET status='REVOKED',version=2,expires_at=?",
                Timestamp.from(NOW.plusSeconds(3600).plusNanos(123456000)));
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, true, false)).isEqualTo(serial(db, 9));
    }

    @Test void singleSortKeepsEveryCompositeEpochKeyAndNativeCollationOrder() throws Exception {
        JdbcTemplate db = database(false);
        // Deliberately reverse insertion order and vary all seven original ordering fields.
        String[][] choices = {{"a", "A"}, {"组织𠮷", "组织"}, {"CONSULTATION", "CONSULTATION_ALT"},
                {"TASK", "TASK_ALT"}, {"resource-2", "resource-10"}, {"WRITE", "READ"},
                {"TASK_WORK", "TASK_ALT"}};
        for (int bits = 127; bits >= 0; bits--) {
            Object[] values = new Object[8];
            values[0] = ISSUER;
            for (int field = 0; field < 7; field++)
                values[field + 1] = choices[field][(bits >>> field) & 1];
            db.update("INSERT INTO id2_current_epoch(issuer,subject_id,organization,domain_code,"
                    + "resource_type,resource_id,action_code,purpose,authorization_epoch,source_epoch,"
                    + "current_grant_id) VALUES(?,?,?,?,?,?,?,?,10,2,NULL)", values);
        }
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, false, false)).isEqualTo(serial(db, 5));
        String before = serial(db, 5);
        db.update("UPDATE id2_current_epoch SET source_epoch=100 WHERE subject_id='a' AND action_code='WRITE'");
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, false, false))
                .isEqualTo(serial(db, 5)).isNotEqualTo(before);
    }

    @Test void fixedReadPlansDoNotRetainIssuerRowsOrOptionalSchemaSelection() throws Exception {
        JdbcTemplate db = database(true);
        String empty = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest());
        String first = ProductGrantHistoryBatch.digest(db, ISSUER, false, false);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER + "/other", true, false)).isEqualTo(empty);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, true, false)).isEqualTo(serial(db, 9));
        db.update("INSERT INTO id2_subject_event(event_id,issuer,subject_id,operation,before_version,"
                + "after_version,actor_ref,occurred_at) VALUES(?,?,?,'DISABLE',1,2,?,?)",
                UUID.randomUUID().toString(), ISSUER, "member", "component-maintainer", Timestamp.from(NOW));
        db.update("UPDATE id2_subject SET status='DISABLED',credential_version=2 WHERE issuer=?", ISSUER);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, false, false))
                .isEqualTo(serial(db, 5)).isNotEqualTo(first);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER + "/other", false, false)).isEqualTo(empty);
        assertThat(ProductGrantHistoryBatch.digest(db, ISSUER, true, false)).isEqualTo(serial(db, 9));
    }

    private static JdbcTemplate database(boolean workload) throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:batch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/dm8/V032__product_identity.sql"));
            if (workload) ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("db/dm8/V046__product_workload_subject.sql"));
        }
        var db = ProductWatermarkJdbc.buffered(new JdbcTemplate(ds));
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES(?,?,?,?,'ACTIVE',1,?)", ISSUER, "member",
                "component-member", "encoded", Timestamp.from(NOW));
        return db;
    }

    private static String serial(JdbcTemplate db, int sections) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (int section = 0; section < sections; section++) {
            String sql = ProductGrantHistoryBatch.QUERIES.get(section);
            Object[] parameters = sql.chars().filter(c -> c == '?').mapToObj(c -> ISSUER).toArray();
            db.query(sql, rs -> {
                for (int field = 1; field <= rs.getMetaData().getColumnCount(); field++) {
                    String value = rs.getString(field);
                    hash.update((value == null ? "-1:" : value.length() + ":" + value)
                            .getBytes(StandardCharsets.UTF_8));
                }
            }, parameters);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
