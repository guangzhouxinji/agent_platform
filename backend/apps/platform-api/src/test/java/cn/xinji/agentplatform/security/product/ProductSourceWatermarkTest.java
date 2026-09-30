package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSourceWatermarkTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final String SOURCE = "source-1";
    private static final String DECISION = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64);

    @Test void independentEpochRejectsPendingAndPrimaryDriftAndKeepsWithdrawalCurrent() throws Exception {
        JdbcDataSource primarySource = database();
        JdbcDataSource authoritySource = database();
        script(primarySource, "db/dm8/V036__product_source_candidates.sql");
        script(primarySource, "db/dm8/V037__product_source_admission.sql");
        script(primarySource, "db/dm8/V039__product_source_verification.sql");
        script(primarySource, "db/dm8/V053__product_source_use_decision.sql");
        script(authoritySource, "db/identity-authority/V003__consultation_source_watermark.sql");
        script(authoritySource, "db/identity-authority/V004__consultation_source_recovery.sql");
        JdbcTemplate primary = new JdbcTemplate(primarySource);
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        ProductSourceWatermark watermark = new ProductSourceWatermark(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)));

        assertThatThrownBy(() -> watermark.change(SOURCE, intent -> {
            throw new IllegalStateException("Rejected before primary commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id4_source_watermark", Integer.class))
                .isZero();

        watermark.change(SOURCE, intent -> {
            primary.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,"
                            + "declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,"
                            + "retention_version,retention_evidence_sha,collected_at,valid_until,status,"
                            + "row_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", SOURCE,
                    "contract-1", "capture-1", "{}", HASH, "rights-ref", HASH, "retention-ref", 1,
                    HASH, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(3600)), "ACTIVE", 2,
                    Timestamp.from(NOW));
            primary.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,"
                            + "rights_ref,rights_sha,capability_ref,capability_sha,responsible_party,"
                            + "maintainer_ref,coverage_sha,valid_until,decided_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    DECISION, SOURCE, "contract-1", "rights-ref", HASH, "capability-ref", HASH,
                    "rights-owner", "maintainer", HASH, Timestamp.from(NOW.plusSeconds(3600)),
                    Timestamp.from(NOW));
            primary.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,valid_until) "
                    + "VALUES(?,?,?,?)", DECISION, "TITLE", "QUERY", Timestamp.from(NOW.plusSeconds(3600)));
            primary.update("INSERT INTO con4_source_product_decision(decision_id,decision_version,"
                    + "decision_ref,decision_sha,rights_evidence_status,organization_id,task_id,"
                    + "scope_id,retention_ref,retention_version,retention_evidence_sha) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", DECISION, "v1", "decision.json", HASH,
                    "UNRESOLVED", "org", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "retention-ref", 1, HASH);
            primary.update("INSERT INTO con4_source_verification(verification_id,source_id,"
                            + "contract_version,source_version,candidate_row_version,manifest_ref,"
                            + "manifest_sha,records_sha,inventory_sha,record_count,raw_count,verifier_ref,"
                            + "verified_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(),
                    SOURCE, "contract-1", "capture-1", 1, "captured/manifest.json", HASH,
                    HASH, HASH, 44, 47, "verifier", Timestamp.from(NOW));
            primary.update("INSERT INTO con4_source_current(source_id,contract_version,decision_id,"
                            + "source_epoch,status,row_version,history_sha,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    SOURCE, "contract-1", DECISION, 1, "ACTIVE", 1, "0".repeat(64), Timestamp.from(NOW));
            event(primary, "ADMIT", 0, 1, 0, 1, intent);
            return null;
        });
        assertThat(watermark.current(SOURCE)).isEqualTo(new ProductSourceWatermark.Current(
                1, "ACTIVE", 1, "contract-1", DECISION));
        var availability = new ProductSourceAvailability(primary, watermark,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(availability.require(SOURCE, "TITLE", "QUERY").sourceVersion())
                .isEqualTo("capture-1");
        assertThatThrownBy(() -> availability.require(SOURCE, "CLAIMS", "DISPLAY"))
                .isInstanceOf(IllegalStateException.class);
        primary.update("UPDATE con4_source_verification SET manifest_sha=? WHERE source_id=?",
                "b".repeat(64), SOURCE);
        assertThatThrownBy(() -> availability.require(SOURCE, "TITLE", "QUERY"))
                .isInstanceOf(IllegalStateException.class);
        primary.update("UPDATE con4_source_verification SET manifest_sha=? WHERE source_id=?", HASH, SOURCE);

        String interrupted = UUID.randomUUID().toString();
        authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id=? WHERE source_id=?",
                interrupted, SOURCE);
        assertThatThrownBy(() -> watermark.current(SOURCE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductSourceWatermark(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ProductSourceWatermark.forRecovery(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)))
                .recover(SOURCE, interrupted, "source-operator#certificate")).isEqualTo("S1_RELEASE");
        assertThat(watermark.current(SOURCE).epoch()).isEqualTo(1);

        primary.update("UPDATE con4_source_field_use SET use_code='DISPLAY' WHERE decision_id=?", DECISION);
        assertThatThrownBy(() -> watermark.current(SOURCE)).isInstanceOf(IllegalStateException.class);
        primary.update("UPDATE con4_source_field_use SET use_code='QUERY' WHERE decision_id=?", DECISION);

        String previousDigest = authority.queryForObject("SELECT history_sha FROM id4_source_watermark "
                + "WHERE source_id=?", String.class, SOURCE);
        watermark.change(SOURCE, intent -> {
            primary.update("UPDATE con4_source_contract SET status='WITHDRAWN',row_version=3 "
                    + "WHERE source_id=? AND contract_version='contract-1'", SOURCE);
            primary.update("UPDATE con4_source_current SET source_epoch=2,status='WITHDRAWN',"
                    + "row_version=2 WHERE source_id=?", SOURCE);
            event(primary, "WITHDRAW", 1, 2, 1, 2, intent);
            return null;
        });
        assertThat(watermark.current(SOURCE)).isEqualTo(new ProductSourceWatermark.Current(
                2, "WITHDRAWN", 2, "contract-1", DECISION));
        String committedIntent = primary.queryForObject("SELECT intent_id FROM con4_source_authority_event "
                + "WHERE source_id=? AND after_epoch=2", String.class, SOURCE);
        authority.update("UPDATE id4_source_watermark SET source_epoch=1,state='PENDING',intent_id=?,"
                + "history_sha=? WHERE source_id=?", committedIntent, previousDigest, SOURCE);
        var recovery = ProductSourceWatermark.forRecovery(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)));
        assertThatThrownBy(() -> recovery.recover(SOURCE, UUID.randomUUID().toString(),
                "source-operator#certificate")).isInstanceOf(IllegalStateException.class);
        primary.update("UPDATE con4_source_authority_event SET before_history_sha=? "
                + "WHERE source_id=? AND after_epoch=2", HASH, SOURCE);
        assertThatThrownBy(() -> recovery.recover(SOURCE, committedIntent,
                "source-operator#certificate")).isInstanceOf(IllegalStateException.class);
        assertThat(authority.queryForObject("SELECT state FROM id4_source_watermark WHERE source_id=?",
                String.class, SOURCE)).isEqualTo("PENDING");
        primary.update("UPDATE con4_source_authority_event SET before_history_sha=? "
                + "WHERE source_id=? AND after_epoch=2", previousDigest, SOURCE);
        assertThat(recovery.recover(SOURCE, committedIntent, "source-operator#certificate"))
                .isEqualTo("S2_ADVANCE");
        assertThat(watermark.current(SOURCE).epoch()).isEqualTo(2);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id4_source_recovery_event",
                Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> watermark.assertUnchanged(SOURCE, 1)).isInstanceOf(IllegalStateException.class);
        primary.update("UPDATE con4_source_current SET source_epoch=1 WHERE source_id=?", SOURCE);
        assertThatThrownBy(watermark::assertAllCurrent).isInstanceOf(IllegalStateException.class);
    }

    private static void event(JdbcTemplate db, String operation, long beforeEpoch,
            long afterEpoch, long beforeVersion, long afterVersion, ProductSourceWatermark.Intent intent) {
        db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,"
                        + "decision_id,operation,before_epoch,after_epoch,before_version,after_version,"
                        + "intent_id,before_history_sha,actor_ref,request_sha,occurred_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), SOURCE, "contract-1", DECISION, operation, beforeEpoch,
                afterEpoch, beforeVersion, afterVersion, intent.id(), intent.beforeHistorySha(),
                "maintainer", HASH, Timestamp.from(NOW));
    }

    private static JdbcDataSource database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-watermark-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
