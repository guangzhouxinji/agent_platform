package cn.xinji.agentplatform.infrastructure;

import static cn.xinji.agentplatform.infrastructure.Dm8MigrationScripts.executeSafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SharedTrustStateDm8DesignProbeTest {
    @Test void compositeSourceKeyEpochAndCleanupOnIsolatedSchema() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && admin != null && password != null,
                "Isolated DM8 probe credentials are not configured");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "ST" + HexFormat.of().formatHex(random).toUpperCase();
        String service = "SV" + HexFormat.of().formatHex(random).toUpperCase();
        String maintainer = "MT" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean created = false;
        boolean serviceCreated = false, maintainerCreated = false;
        try (var control = DriverManager.getConnection(url, admin, password)) {
            executeSafe(control, "CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"", "create probe user");
            created = true;
            executeSafe(control, "GRANT RESOURCE TO " + schema, "probe schema resource");
            executeSafe(control, "CREATE USER " + service + " IDENTIFIED BY \"" + secret + "\"", "service probe user");
            serviceCreated = true;
            executeSafe(control, "CREATE USER " + maintainer + " IDENTIFIED BY \"" + secret + "\"", "maintainer probe user");
            maintainerCreated = true;
            try (var db = DriverManager.getConnection(url, schema, secret)) {
                try {
                executeSafe(db, "CREATE TABLE st_credential (issuer VARCHAR(40) NOT NULL, "
                        + "key_id VARCHAR(100) NOT NULL UNIQUE, domain_code VARCHAR(16) NOT NULL, "
                        + "principal_ref VARCHAR(200) NOT NULL, org_ref VARCHAR(200) NOT NULL, "
                        + "kind VARCHAR(10) NOT NULL CHECK (kind IN ('HUMAN','AGENT','JOB','MAINTAINER')), "
                        + "secret_sha CHAR(64) NOT NULL, status VARCHAR(10) NOT NULL "
                        + "CHECK (status IN ('ACTIVE','REVOKED')), epoch BIGINT NOT NULL CHECK (epoch >= 1), "
                        + "PRIMARY KEY(issuer,key_id))", "credential candidate");
                executeSafe(db, "CREATE TABLE st_grant (issuer VARCHAR(40) NOT NULL, domain_code VARCHAR(16) "
                        + "NOT NULL, principal_ref VARCHAR(200) NOT NULL, org_ref VARCHAR(200) NOT NULL, "
                        + "resource_type VARCHAR(50) NOT NULL, resource_id VARCHAR(100) NOT NULL, "
                        + "action_code VARCHAR(50) NOT NULL, status VARCHAR(10) NOT NULL "
                        + "CHECK (status IN ('ACTIVE','REVOKED')), epoch BIGINT NOT NULL CHECK (epoch >= 1), "
                        + "PRIMARY KEY(issuer,domain_code,principal_ref,org_ref,resource_type,resource_id,action_code))",
                        "grant candidate");
                executeSafe(db, "CREATE TABLE st_dataset (domain_code VARCHAR(16) NOT NULL, dataset_id VARCHAR(100) "
                        + "NOT NULL, manifest_id VARCHAR(100) NOT NULL, manifest_version BIGINT NOT NULL, "
                        + "scope_kind VARCHAR(20) NOT NULL CHECK (scope_kind IN ('RECORD_SCOPED','DATASET_SCOPED')), "
                        + "content_sha CHAR(64) NOT NULL, status VARCHAR(10) NOT NULL "
                        + "CHECK (status IN ('ACTIVE','REVOKED')), epoch BIGINT NOT NULL CHECK (epoch >= 1), "
                        + "PRIMARY KEY(domain_code,dataset_id,manifest_id,manifest_version))", "dataset candidate");
                executeSafe(db, "CREATE TABLE st_record (domain_code VARCHAR(16) NOT NULL, dataset_id VARCHAR(100) "
                        + "NOT NULL, manifest_id VARCHAR(100) NOT NULL, manifest_version BIGINT NOT NULL, "
                        + "record_kind VARCHAR(50) NOT NULL, record_id VARCHAR(100) NOT NULL, "
                        + "source_version VARCHAR(32) NOT NULL, content_sha CHAR(64) NOT NULL, "
                        + "status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')), "
                        + "epoch BIGINT NOT NULL CHECK (epoch >= 1), "
                        + "PRIMARY KEY(domain_code,dataset_id,manifest_id,manifest_version,record_kind,record_id,source_version), "
                        + "FOREIGN KEY(domain_code,dataset_id,manifest_id,manifest_version) "
                        + "REFERENCES st_dataset(domain_code,dataset_id,manifest_id,manifest_version))", "record candidate");
                executeSafe(db, "CREATE TABLE st_maintenance_event (event_id VARCHAR(36) NOT NULL PRIMARY KEY, "
                        + "object_key VARCHAR(500) NOT NULL, actor_ref VARCHAR(200) NOT NULL, "
                        + "before_epoch BIGINT NOT NULL CHECK (before_epoch >= 0), "
                        + "after_epoch BIGINT NOT NULL CHECK (after_epoch >= 1), "
                        + "request_sha CHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL, "
                        + "CHECK (after_epoch = before_epoch + 1))", "event candidate");
                executeSafe(control, "GRANT SELECT ON " + schema + ".st_dataset TO " + service,
                        "service read candidate");
                executeSafe(control, "GRANT SELECT, UPDATE ON " + schema + ".st_dataset TO " + maintainer,
                        "maintenance write candidate");
                executeSafe(db, "INSERT INTO st_credential VALUES "
                        + "('local','key-a','ENF','dev-enf-a','dev-enf-isolated','HUMAN','" + "c".repeat(64)
                        + "','ACTIVE',1)", "first credential");
                assertThatThrownBy(() -> executeSafe(db, "INSERT INTO st_credential VALUES "
                        + "('other','key-a','CON','dev-con-a','dev-con-isolated','HUMAN','" + "d".repeat(64)
                        + "','ACTIVE',1)", "cross-domain key reuse")).isInstanceOf(SQLException.class);
                executeSafe(db, "INSERT INTO st_grant VALUES ('local','ENF','dev-enf-a','dev-enf-isolated',"
                        + "'SIGNED_OBSERVATION','DEV-ENF-OBS-001@V1','CREATE_DEV_MATERIAL_JOB','ACTIVE',1)",
                        "exact grant");
                assertThatThrownBy(() -> executeSafe(db, "INSERT INTO st_grant VALUES "
                        + "('local','ENF','dev-enf-a','dev-enf-isolated','SIGNED_OBSERVATION',"
                        + "'DEV-ENF-OBS-001@V1','CREATE_DEV_MATERIAL_JOB','ACTIVE',1)", "duplicate grant"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> executeSafe(db, "INSERT INTO st_maintenance_event VALUES "
                        + "('00000000-0000-0000-0000-000000000001','dataset:dev-enf-a','maintainer',1,3,'"
                        + "e".repeat(64) + "',CURRENT_TIMESTAMP)", "skipped epoch"))
                        .isInstanceOf(SQLException.class);
                try (var insertDataset = db.prepareStatement("INSERT INTO st_dataset VALUES "
                        + "('ENF','dev-enf-a','DEV-ENF-MATERIAL-INPUT-01',1,'RECORD_SCOPED',?,'ACTIVE',1)");
                     var insertRecord = db.prepareStatement("INSERT INTO st_record VALUES "
                        + "('ENF','dev-enf-a','DEV-ENF-MATERIAL-INPUT-01',?,'OBSERVATION','DEV-ENF-OBS-001',"
                        + "'V1',?,'ACTIVE',1)")) {
                    insertDataset.setString(1, "a".repeat(64));
                    assertThat(insertDataset.executeUpdate()).isEqualTo(1);
                    insertRecord.setLong(1, 2);
                    insertRecord.setString(2, "b".repeat(64));
                    assertThatThrownBy(insertRecord::executeUpdate).isInstanceOf(SQLException.class);
                    insertRecord.setLong(1, 1);
                    assertThat(insertRecord.executeUpdate()).isEqualTo(1);
                    assertThatThrownBy(insertRecord::executeUpdate).isInstanceOf(SQLException.class);
                }
                try (var update = db.prepareStatement("UPDATE st_dataset SET status='REVOKED',epoch=epoch+1 "
                        + "WHERE domain_code='ENF' AND dataset_id='dev-enf-a' AND manifest_id='DEV-ENF-MATERIAL-INPUT-01' "
                        + "AND manifest_version=1 AND epoch=?")) {
                    update.setLong(1, 1);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                    assertThat(update.executeUpdate()).isZero();
                }
                try (var readOnly = DriverManager.getConnection(url, service, secret);
                     var maintenance = DriverManager.getConnection(url, maintainer, secret)) {
                    try (var rows = readOnly.createStatement().executeQuery("SELECT epoch FROM "
                            + schema + ".st_dataset")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(2);
                    }
                    assertThatThrownBy(() -> executeSafe(readOnly, "UPDATE " + schema
                            + ".st_dataset SET epoch=3 WHERE epoch=2", "service cannot write"))
                            .isInstanceOf(SQLException.class);
                    executeSafe(maintenance, "UPDATE " + schema + ".st_dataset SET epoch=3 WHERE epoch=2",
                            "maintenance can write");
                }
                assertThatThrownBy(() -> executeSafe(db, "DELETE FROM st_dataset", "parent first"))
                        .isInstanceOf(Exception.class);
                } finally {
                    for (String table : new String[] { "st_maintenance_event", "st_record", "st_dataset",
                            "st_grant", "st_credential" }) {
                        try { executeSafe(db, "DROP TABLE " + table + " CASCADE", "remove probe table"); }
                        catch (SQLException missingOrBlocked) { /* A partial CREATE may leave the table absent. */ }
                    }
                }
                try (var tables = db.prepareStatement("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'ST_%'");
                     var rows = tables.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        } finally {
            try (var control = DriverManager.getConnection(url, admin, password)) {
                if (serviceCreated) executeSafe(control, "DROP USER " + service, "drop service probe user");
                if (maintainerCreated) executeSafe(control, "DROP USER " + maintainer, "drop maintainer probe user");
                if (created) executeSafe(control, "DROP USER " + schema, "drop empty probe user");
            }
        }
    }
}
