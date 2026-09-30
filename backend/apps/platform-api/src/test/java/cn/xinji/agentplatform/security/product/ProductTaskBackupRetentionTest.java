package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductTaskBackupRetentionTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void expiredAndRevokedHistoryDoesNotShortenAnApprovedBackup() throws Exception {
        var db = database();
        Instant now = Instant.now();
        String evidenceSha = save("retention-v1.json", "approved historical evidence");
        db.update("INSERT INTO con3_organization VALUES('org')");
        db.update("INSERT INTO con3_task VALUES(?,?,?)", "retention-v1.json", evidenceSha,
                now.minus(Duration.ofHours(3)));
        db.update("INSERT INTO con3_task VALUES(?,?,?)", "retention-v1.json", evidenceSha,
                now.plus(Duration.ofHours(1)));
        db.update("INSERT INTO con4_source_contract VALUES(?,?,?,?)", "retention-v1.json",
                evidenceSha, now.minus(Duration.ofHours(2)), "WITHDRAWN");
        db.update("INSERT INTO con4_source_contract VALUES(?,?,?,?)", "retention-v1.json",
                evidenceSha, now.plus(Duration.ofHours(1)), "ACTIVE");
        db.update("INSERT INTO con4_source_product_decision VALUES(?,?,?)", "org",
                "retention-v1.json", evidenceSha);
        var decision = new ProductTaskBackupRetention.Decision(1, "accountable-backup-operator",
                List.of("org"), Duration.ofHours(2), now.plus(Duration.ofHours(3)),
                Map.of("retention-v1.json", evidenceSha), true, true);
        String policySha = save("backup-policy-v1.json", mapper.writeValueAsString(decision));
        var retention = new ProductTaskBackupRetention(new ProductSourceEvidenceFiles(root.toAbsolutePath(),
                1_000_000), mapper, Clock.systemUTC());
        retention.require(db, "backup-policy-v1.json", policySha, 1, now.plus(Duration.ofMinutes(40)));
        assertThatThrownBy(() -> retention.require(db, "backup-policy-v1.json", policySha, 1,
                now.plus(Duration.ofHours(4)))).isInstanceOf(IllegalStateException.class);
        db.update("INSERT INTO con3_message VALUES(?,?)", "unapproved.json", "b".repeat(64));
        assertThatThrownBy(() -> retention.require(db, "backup-policy-v1.json", policySha, 1,
                now.plus(Duration.ofMinutes(40)))).isInstanceOf(IllegalStateException.class);
        db.update("DELETE FROM con3_message");
        Files.writeString(root.resolve("backup-policy-v1.json"), "changed");
        assertThatThrownBy(() -> retention.require(db, "backup-policy-v1.json", policySha, 1,
                now.plus(Duration.ofMinutes(40)))).isInstanceOf(IllegalStateException.class);
    }

    private JdbcTemplate database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_organization(organization_id VARCHAR(100))");
        db.execute("CREATE TABLE con3_task(retention_ref VARCHAR(100),retention_evidence_sha VARCHAR(64),"
                + "expires_at TIMESTAMP)");
        db.execute("CREATE TABLE con4_source_contract(retention_ref VARCHAR(100),"
                + "retention_evidence_sha VARCHAR(64),valid_until TIMESTAMP,status VARCHAR(20))");
        db.execute("CREATE TABLE con4_source_product_decision(organization_id VARCHAR(100),"
                + "retention_ref VARCHAR(100),retention_evidence_sha VARCHAR(64))");
        db.execute("CREATE TABLE con3_message(retention_ref VARCHAR(100),retention_evidence_sha VARCHAR(64))");
        db.execute("CREATE TABLE con3_goal_revision(retention_ref VARCHAR(100),"
                + "retention_evidence_sha VARCHAR(64))");
        return db;
    }

    private String save(String name, String content) throws Exception {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve(name), bytes);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
