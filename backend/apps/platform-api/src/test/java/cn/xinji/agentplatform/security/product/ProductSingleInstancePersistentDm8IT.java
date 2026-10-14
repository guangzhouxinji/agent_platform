package cn.xinji.agentplatform.security.product;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

/**
 * VER-AGENT-SINGLE-DB-PERSISTENT-01: owned engineering fixture only.
 * Three separate JVM invocations surround an externally orchestrated real stop/start and
 * encrypted native export/import. This IT never stops Docker or substitutes an in-memory restart.
 * No source, professional golden, business approval, or environment release is inferred here.
 */
class ProductSingleInstancePersistentDm8IT {
    private static final String TASK = "VER-AGENT-SINGLE-DB-PERSISTENT-01";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final List<String> OWNERS = List.of("CONPROD", "CONAUTH", "ENFPROD", "ENFAUTH", "ADJPROD", "ADJAUTH");
    private static final Pattern TABLE = Pattern.compile("(?im)^CREATE TABLE\\s+([a-z0-9_]+)");
    private static String url;
    private static String phase;
    private static Path baseline;

    private static String required(String key) {
        String value = System.getenv("SINGLE_DB_PERSISTENT_" + key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required owned engineering setting: " + key);
        return value;
    }
    private static String secret(String name) {
        String value = required(name + "_PASSWORD");
        if (!value.matches("[A-Za-z0-9_]{32,48}")) throw new IllegalArgumentException("Managed engineering secret format required");
        return value;
    }
    private static String issuer(String owner) {
        return "https://single-persistent-engineering.test/" + owner.substring(0, 3).toLowerCase(Locale.ROOT);
    }
    private static Path migration(String owner) {
        return Path.of(owner.endsWith("AUTH") ? "db/identity-authority/V001__grant_watermark.sql" : "db/dm8/V032__product_identity.sql");
    }
    private static List<String> tables(String owner) throws Exception {
        var matcher = TABLE.matcher(Files.readString(migration(owner)));
        var result = new ArrayList<String>();
        while (matcher.find()) result.add(matcher.group(1));
        assertThat(result).isNotEmpty();
        return result;
    }
    private static DataSource source(String user, String schema) {
        return new DriverManagerDataSource(url, user, secret(user)) {
            @Override public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                try { connection.setSchema(schema); return connection; }
                catch (SQLException failed) { connection.close(); throw failed; }
            }
        };
    }

    @BeforeAll static void preflight() throws Exception {
        assertThat(required("OWNER")).isEqualTo(TASK);
        phase = required("PHASE");
        assertThat(phase).isIn("initialize", "complete-owned-seed", "after-restart", "after-restore");
        url = required("URL");
        assertThat(url).isEqualTo("jdbc:dm://127.0.0.1:" + (phase.equals("after-restore") ? "5493" : "5492"));
        // Evidence is engineering-only, not a product recovery/release switch. The deployment
        // owner must also recheck this report against the actual mounted filesystem before launch.
        Path quotaPath = Path.of(required("STORAGE_PROOF")).toAbsolutePath().normalize();
        var evidenceRoot = Path.of("docs/evidence/single-database-persistent-20261007").toAbsolutePath().normalize();
        assertThat(quotaPath.startsWith(evidenceRoot)).isTrue();
        var quota = new Properties();
        try (var input = Files.newInputStream(quotaPath)) { quota.load(input); }
        assertThat(quota.getProperty("task")).isEqualTo(TASK);
        assertThat(quota.getProperty("resourceId")).isEqualTo(required("RESOURCE_ID"));
        assertThat(quota.getProperty("image")).isEqualTo("sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760");
        assertThat(quota.getProperty("overflowError")).isIn("ENOSPC", "EDQUOT");
        assertThat(Long.parseLong(quota.getProperty("effectiveCapacityBytes"))).isBetween(1L, 3L * 1024 * 1024 * 1024);
        assertThat(Long.parseLong(quota.getProperty("overflowWriteExit"))).isNotZero();
        assertThat(quota.getProperty("probeEvidenceSha256")).matches("[a-f0-9]{64}");
        var evidence = Path.of("docs/evidence/single-database-persistent-20261007").toAbsolutePath().normalize();
        baseline = Path.of(required("BASELINE")).toAbsolutePath().normalize();
        assertThat(baseline.getParent()).isEqualTo(evidence);
        assertThat(baseline.getFileName().toString()).isEqualTo("identity-baseline.properties");
        if (seeding()) {
            assertThat(Files.exists(baseline)).as("Never reseed or overwrite a prior baseline").isFalse();
            if (phase.equals("initialize")) initializeEmptyOwnedInstance(); else completeOwnedSeed();
        } else {
            assertThat(Files.isRegularFile(baseline)).as("Restart/restore may not create a new baseline").isTrue();
        }
    }

    private static void initializeEmptyOwnedInstance() throws Exception {
        try (var admin = DriverManager.getConnection(url, "SYSDBA", secret("SYSDBA"))) {
            for (String owner : OWNERS) {
                try (var query = admin.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME IN (?,?)")) {
                    query.setString(1, owner); query.setString(2, owner + "_SVC");
                    try (var rows = query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).as("Refuse existing or partial fixture").isZero(); }
                }
            }
            for (String owner : OWNERS) {
                try (var sql = admin.createStatement()) {
                    sql.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + secret(owner) + "\"");
                    sql.execute("CREATE USER " + owner + "_SVC IDENTIFIED BY \"" + secret(owner + "_SVC") + "\"");
                    sql.execute("GRANT RESOURCE TO " + owner);
                }
                try (var connection = source(owner, owner).getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration(owner)));
                    for (String table : tables(owner)) {
                        String rights = owner.endsWith("AUTH") ? (table.equals("id2_maintainer") ? "SELECT" :
                                table.equals("id2_authority_watermark") ? "SELECT,UPDATE" : "SELECT,INSERT") : "SELECT,INSERT,UPDATE,DELETE";
                        try (var sql = connection.createStatement()) { sql.execute("GRANT " + rights + " ON " + table + " TO " + owner + "_SVC"); }
                    }
                }
                if (owner.endsWith("AUTH")) new JdbcTemplate(source(owner, owner)).update(
                        "INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer(owner), EMPTY);
            }
        }
        completeOwnedSeed();
    }

    private static boolean seeding() { return phase.equals("initialize") || phase.equals("complete-owned-seed"); }

    /** Explicit repair of the recorded, partial engineering setup; never a product recovery bypass. */
    private static void completeOwnedSeed() throws Exception {
        for (String owner : OWNERS) if (!owner.endsWith("AUTH")) {
            try (var connection = source(owner, owner).getConnection(); var statement = connection.createStatement()) {
                // DM8 requires REFERENCES on an owned parent when a dedicated service inserts FK rows.
                // Only the four same-domain V032 FK targets; no cross-schema or system privilege.
                for (String table : List.of("id2_subject", "id2_key_control", "id2_signing_key", "id2_grant"))
                    statement.execute("GRANT REFERENCES ON " + table + " TO " + owner + "_SVC");
            }
        }        for (int index = 0; index < OWNERS.size(); index += 2) {
            String business = OWNERS.get(index), authority = OWNERS.get(index + 1);
            var jdbc = new JdbcTemplate(source(business + "_SVC", business));
            var auth = new JdbcTemplate(source(authority + "_SVC", authority));
            var watermark = new ProductGrantWatermark(jdbc, auth, issuer(business));
            // Permit only the exact zero/one-row partial fixture recorded by the failed seed.
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM id2_subject", Integer.class);
            int exact = jdbc.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND login_name=? " +
                    "AND status='DISABLED' AND credential_version=1 AND password_hash IS NULL", Integer.class,
                    issuer(business), "persistent-engineering-disabled", "no-login-engineering");
            assertThat(count).isBetween(0, 1); assertThat(exact).isEqualTo(count);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM id2_subject_event", Integer.class)).isZero();
            assertThat(watermark.currentGeneration()).isZero();
            // Nonempty, permanently disabled engineering subject; no usable login or business grant.
            watermark.change(() -> {
                if (count == 0) jdbc.update("INSERT INTO id2_subject(issuer,subject_id,login_name,status,credential_version,created_at,disabled_at) " +
                        "VALUES(?,? ,?,'DISABLED',1,?,?)", issuer(business), "persistent-engineering-disabled", "no-login-engineering",
                        Timestamp.valueOf("2026-10-07 00:00:00"), Timestamp.valueOf("2026-10-07 00:00:00"));
                jdbc.update("INSERT INTO id2_subject_event(event_id,issuer,subject_id,operation,before_version,after_version,actor_ref,occurred_at) " +
                        "VALUES(?,?,?,'DISABLE',0,1,?,?)", UUID.randomUUID().toString(), issuer(business), "persistent-engineering-disabled",
                        TASK, Timestamp.valueOf("2026-10-07 00:00:00"));
                return null;
            });
            assertThat(watermark.currentGeneration()).isEqualTo(1);
        }
    }

    @Test void persistedSixSchemaSnapshotAndThreeIssuerBoundaries() throws Exception {
        var snapshot = snapshot();
        if (seeding()) {
            try (var output = Files.newOutputStream(baseline, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                snapshot.store(output, "Owned engineering digest only; not business or source acceptance");
            }
        } else {
            var expected = new Properties();
            try (var input = Files.newInputStream(baseline)) { expected.load(input); }
            assertThat(snapshot).as("Exact committed account/schema/issuer/table digests after external " + phase).isEqualTo(expected);
        }
        for (int index = 0; index < OWNERS.size(); index += 2) {
            String business = OWNERS.get(index), authority = OWNERS.get(index + 1);
            var jdbc = new JdbcTemplate(source(business + "_SVC", business));
            var configuration = new ProductGrantWatermarkConfiguration();
            try {
                var watermark = configuration.productGrantWatermark(jdbc, url, business, business + "_SVC", "shared-instance", "",
                        authority, authority + "_SVC", secret(authority + "_SVC"), issuer(business));
                var pool = (HikariDataSource) watermark.independentDatabase().getDataSource();
                assertThat(watermark.currentGeneration()).isEqualTo(1);
                try (var connection = pool.getConnection()) { assertThat(connection.getSchema()).isEqualTo(authority); }
                for (String foreign : OWNERS) if (!foreign.equals(business)) {
                    String table = foreign.endsWith("AUTH") ? "id2_authority_watermark" : "id2_subject";
                    assertThatThrownBy(() -> jdbc.queryForObject("SELECT COUNT(*) FROM " + foreign + "." + table, Integer.class))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThatThrownBy(() -> jdbc.update("UPDATE " + foreign + "." + table + " SET issuer=issuer WHERE 1=0"))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
                for (String foreign : OWNERS) if (!foreign.equals(authority)) {
                    String table = foreign.endsWith("AUTH") ? "id2_authority_watermark" : "id2_subject";
                    assertThatThrownBy(() -> watermark.independentDatabase().queryForObject("SELECT COUNT(*) FROM " + foreign + "." + table, Integer.class))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
                assertThatThrownBy(() -> jdbc.execute("CREATE TABLE " + business + ".forbidden_persistent_ddl(id INTEGER)"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(() -> new ProductGrantWatermark(jdbc, watermark.independentDatabase(), "https://wrong-persistent-issuer.test"))
                        .isInstanceOf(IllegalStateException.class);
                configuration.destroy();
                assertThat(pool.isClosed()).isTrue();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE status='DISABLED'", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isZero();
            } finally { configuration.destroy(); }
        }
        assertThat(snapshot()).as("Read and rejection checks leave the committed fixture unchanged").isEqualTo(snapshot);
    }

    @Test void unfinishedIntentAndHistoryCorruptionRemainFailClosed() throws Exception {
        var before = snapshot();
        for (int index = 0; index < OWNERS.size(); index += 2) {
            String business = OWNERS.get(index), authority = OWNERS.get(index + 1);
            var jdbc = new JdbcTemplate(source(business + "_SVC", business));
            var auth = new JdbcTemplate(source(authority + "_SVC", authority));
            var watermark = new ProductGrantWatermark(jdbc, auth, issuer(business));
            String history = auth.queryForObject("SELECT history_sha FROM id2_authority_watermark WHERE issuer=?", String.class, issuer(business));
            assertThat(history).isNotEqualTo(EMPTY);
            try {
                assertThat(auth.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='owned-negative' WHERE issuer=? AND state='READY'", issuer(business))).isEqualTo(1);
                assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
                assertThat(auth.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL,history_sha=? WHERE issuer=?", "0".repeat(64), issuer(business))).isEqualTo(1);
                assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
            } finally {
                // Only this owned engineering fixture is repaired; production recovery must never infer this repair.
                auth.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL,history_sha=? WHERE issuer=?", history, issuer(business));
            }
            assertThat(watermark.currentGeneration()).isEqualTo(1);
            assertThatThrownBy(() -> watermark.assertUnchanged(0)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(snapshot()).as("Owned negative probes restored exactly; crash requires manual investigation").isEqualTo(before);
    }

    private static Properties snapshot() throws Exception {
        var result = new Properties(); result.setProperty("task", TASK);
        try (var admin = DriverManager.getConnection(url, "SYSDBA", secret("SYSDBA"))) {
            for (String owner : OWNERS) {
                for (String user : List.of(owner, owner + "_SVC")) {
                    try (var query = admin.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?")) {
                        query.setString(1, user);
                        try (var rows = query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); result.setProperty("account." + user, "present"); }
                    }
                }
                result.setProperty(owner + ".issuer", issuer(owner));
                // Each owner and each service must still log in using its own vault secret after restore.
                try (var login = source(owner + "_SVC", owner).getConnection()) { assertThat(login.getSchema()).isEqualTo(owner); }
                try (var connection = source(owner, owner).getConnection()) {
                    for (String table : tables(owner)) {
                        var rowHashes = new ArrayList<String>();
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
                            while (rows.next()) {
                                var digest = MessageDigest.getInstance("SHA-256");
                                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                                    Object value = rows.getObject(column);
                                    byte[] bytes;
                                    if (value == null) { digest.update((byte) 0); continue; }
                                    digest.update((byte) 1);
                                    if (value instanceof Blob blob) bytes = blob.getBytes(1, Math.toIntExact(blob.length()));
                                    else if (value instanceof byte[] binary) bytes = binary;
                                    else if (value instanceof Timestamp timestamp) bytes = timestamp.toLocalDateTime().toString().getBytes(StandardCharsets.UTF_8);
                                    else bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                                }
                                rowHashes.add(HexFormat.of().formatHex(digest.digest()));
                            }
                        }
                        Collections.sort(rowHashes);
                        var digest = MessageDigest.getInstance("SHA-256");
                        for (String hash : rowHashes) digest.update(HexFormat.of().parseHex(hash));
                        result.setProperty(owner + "." + table + ".rows", String.valueOf(rowHashes.size()));
                        result.setProperty(owner + "." + table + ".sha256", HexFormat.of().formatHex(digest.digest()));
                    }
                }
            }
        }
        return result;
    }
}



