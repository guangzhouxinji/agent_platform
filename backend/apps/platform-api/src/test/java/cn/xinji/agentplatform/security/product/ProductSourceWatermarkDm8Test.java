package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSourceWatermarkDm8Test {
    private static final String SOURCE = "isolated-source";
    private static final String CONTRACT = "capture-1";
    private static final String DECISION = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64);

    @Test void twoDm8InstancesRecoverExactIntentAndRejectOldPrimary() throws Exception {
        String mainUrl = System.getenv("DM8_JDBC_URL");
        String authorityUrl = System.getenv("IDENTITY_AUTHORITY_DM8_JDBC_URL");
        String mainUser = System.getenv("DM8_JDBC_USER");
        String mainPassword = System.getenv("DM8_JDBC_PASSWORD");
        String authorityUser = System.getenv("IDENTITY_AUTHORITY_DM8_USER");
        String authorityPassword = System.getenv("IDENTITY_AUTHORITY_DM8_PASSWORD");
        Assumptions.assumeTrue(mainUrl != null && authorityUrl != null && mainUser != null
                && mainPassword != null && authorityUser != null && authorityPassword != null
                && !mainUrl.equals(authorityUrl), "Two independent DM8 instances are required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "SW" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean mainCreated = false;
        boolean authorityCreated = false;
        try {
            create(mainUrl, mainUser, mainPassword, schema, secret);
            mainCreated = true;
            create(authorityUrl, authorityUser, authorityPassword, schema, secret);
            authorityCreated = true;
            try (var mainConnection = DriverManager.getConnection(mainUrl, schema, secret);
                 var authorityConnection = DriverManager.getConnection(authorityUrl, schema, secret)) {
                try {
                    for (String migration : List.of("V036__product_source_candidates.sql",
                            "V037__product_source_admission.sql",
                            "V039__product_source_verification.sql",
                            "V053__product_source_use_decision.sql"))
                        ScriptUtils.executeSqlScript(mainConnection,
                                new FileSystemResource(Path.of("db/dm8/" + migration)));
                    for (String migration : List.of("V003__consultation_source_watermark.sql",
                            "V004__consultation_source_recovery.sql"))
                        ScriptUtils.executeSqlScript(authorityConnection,
                                new FileSystemResource(Path.of("db/identity-authority/" + migration)));
                    var mainSource = new DriverManagerDataSource(mainUrl, schema, secret);
                    var db = new JdbcTemplate(mainSource);
                    var authority = new JdbcTemplate(new DriverManagerDataSource(
                            authorityUrl, schema, secret));
                    var transaction = new TransactionTemplate(new DataSourceTransactionManager(mainSource));
                    var watermark = new ProductSourceWatermark(db, authority, transaction);
                    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                    watermark.change(SOURCE, intent -> {
                        admit(db, now, intent);
                        return null;
                    });
                    assertThat(watermark.current(SOURCE).epoch()).isEqualTo(1);
                    String s1 = UUID.randomUUID().toString();
                    authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id=? "
                            + "WHERE source_id=?", s1, SOURCE);
                    assertThatThrownBy(() -> new ProductSourceWatermark(db, authority, transaction))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(ProductSourceWatermark.forRecovery(db, authority, transaction)
                            .recover(SOURCE, s1, "isolated-recovery-operator"))
                            .isEqualTo("S1_RELEASE");
                    String prior = authority.queryForObject("SELECT history_sha FROM id4_source_watermark "
                            + "WHERE source_id=?", String.class, SOURCE);
                    watermark.change(SOURCE, intent -> {
                        db.update("UPDATE con4_source_contract SET status='WITHDRAWN',row_version=3 "
                                + "WHERE source_id=? AND contract_version=?", SOURCE, CONTRACT);
                        db.update("UPDATE con4_source_current SET source_epoch=2,status='WITHDRAWN',"
                                + "row_version=2 WHERE source_id=?", SOURCE);
                        event(db, "WITHDRAW", 1, 2, 1, 2, intent, now);
                        return null;
                    });
                    String intent = db.queryForObject("SELECT intent_id FROM con4_source_authority_event "
                            + "WHERE source_id=? AND after_epoch=2", String.class, SOURCE);
                    authority.update("UPDATE id4_source_watermark SET source_epoch=1,state='PENDING',"
                            + "intent_id=?,history_sha=? WHERE source_id=?", intent, prior, SOURCE);
                    assertThat(ProductSourceWatermark.forRecovery(db, authority, transaction)
                            .recover(SOURCE, intent, "isolated-recovery-operator"))
                            .isEqualTo("S2_ADVANCE");
                    assertThat(new ProductSourceWatermark(db, authority, transaction)
                            .current(SOURCE).status()).isEqualTo("WITHDRAWN");
                    assertThat(authority.queryForObject("SELECT COUNT(*) FROM id4_source_recovery_event",
                            Integer.class)).isEqualTo(2);
                    db.update("UPDATE con4_source_current SET source_epoch=1 WHERE source_id=?", SOURCE);
                    assertThatThrownBy(() -> new ProductSourceWatermark(db, authority, transaction))
                            .isInstanceOf(IllegalStateException.class);
                } finally {
                    for (String table : List.of("id4_source_recovery_event", "id4_source_watermark"))
                        drop(authorityConnection, table);
                    for (String table : List.of("con4_source_verification_request", "con4_source_verification",
                            "con4_source_authority_request", "con4_source_authority_event",
                            "con4_source_current", "con4_source_field_use",
                            "con4_source_product_decision", "con4_source_admission",
                            "con4_source_request", "con4_source_event", "con4_source_contract"))
                        drop(mainConnection, table);
                }
            }
        } finally {
            if (authorityCreated) remove(authorityUrl, authorityUser, authorityPassword, schema);
            if (mainCreated) remove(mainUrl, mainUser, mainPassword, schema);
        }
    }

    private static void admit(JdbcTemplate db, Instant now, ProductSourceWatermark.Intent intent) {
        db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,"
                        + "declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,"
                        + "retention_version,retention_evidence_sha,collected_at,valid_until,status,"
                        + "row_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", SOURCE,
                CONTRACT, "observed-1", "{}", HASH, "rights", HASH, "retention", 1, HASH,
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                "ACTIVE", 2, Timestamp.from(now));
        db.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,rights_ref,"
                        + "rights_sha,capability_ref,capability_sha,responsible_party,maintainer_ref,"
                        + "coverage_sha,valid_until,decided_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", DECISION,
                SOURCE, CONTRACT, "rights", HASH, "capability", HASH, "responsible", "maintainer",
                HASH, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));
        db.update("INSERT INTO con4_source_product_decision(decision_id,decision_version,"
                + "decision_ref,decision_sha,rights_evidence_status,organization_id,task_id,"
                + "scope_id,retention_ref,retention_version,retention_evidence_sha) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", DECISION, "v1", "decision.json", HASH,
                "UNRESOLVED", "organization", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "retention", 1, HASH);
        db.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,valid_until) "
                + "VALUES(?,?,?,?)", DECISION, "TITLE", "QUERY", Timestamp.from(now.plusSeconds(3600)));
        db.update("INSERT INTO con4_source_current(source_id,contract_version,decision_id,source_epoch,"
                        + "status,row_version,history_sha,updated_at) VALUES(?,?,?,1,'ACTIVE',1,?,?)",
                SOURCE, CONTRACT, DECISION, "0".repeat(64), Timestamp.from(now));
        event(db, "ADMIT", 0, 1, 0, 1, intent, now);
    }

    private static void event(JdbcTemplate db, String operation, long beforeEpoch, long afterEpoch,
            long beforeVersion, long afterVersion, ProductSourceWatermark.Intent intent, Instant now) {
        db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,"
                        + "decision_id,operation,before_epoch,after_epoch,before_version,after_version,"
                        + "intent_id,before_history_sha,actor_ref,request_sha,occurred_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), SOURCE,
                CONTRACT, DECISION, operation, beforeEpoch, afterEpoch, beforeVersion, afterVersion,
                intent.id(), intent.beforeHistorySha(), "maintainer", HASH, Timestamp.from(now));
    }

    private static void create(String url, String user, String password, String schema, String secret)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password);
             var command = connection.createStatement()) {
            command.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
            command.execute("GRANT RESOURCE TO " + schema);
        }
    }

    private static void remove(String url, String user, String password, String schema)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password);
             var command = connection.createStatement()) {
            command.execute("DROP USER " + schema);
        }
    }

    private static void drop(java.sql.Connection connection, String table) {
        try (var command = connection.createStatement()) { command.execute("DROP TABLE " + table); }
        catch (SQLException absent) { /* A failed migration can leave a table absent. */ }
    }
}
