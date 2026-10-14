package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 pre-installs actual privileges and exercises schema/users/transactions with shared DCL forbidden;
 * only the advertised URL is a DM8 metadata test double.
 * This is not evidence of DM8 installation or recovery admission. */
class ProductIdentityBootstrapSharedInstanceTest {
    private static final String DM_URL = "jdbc:dm://localhost:5236/PRODUCT";
    @TempDir static Path directory;
    private static ProductIdentityBootstrap.Manifest manifest;
    private static X509Certificate peer;
    private static String caFingerprint;
    private static final AtomicInteger dclAttempts = new AtomicInteger();
    @BeforeEach void resetDclTripwire() { dclAttempts.set(0); }

    @BeforeAll static void certificates() throws Exception {
        Path caStore = directory.resolve("ca.p12");
        Path memberStore = directory.resolve("maintainer.p12");
        Path caCert = directory.resolve("ca.cer");
        Path peerCert = directory.resolve("maintainer.cer");
        Path request = directory.resolve("maintainer.csr");
        Path signed = directory.resolve("signed.cer");
        String password = "isolated-password-123";
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=Isolated Deployment CA", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-ext", "bc:c", "-keystore", caStore.toString(),
                "-storepass", password, "-keypass", password, "-noprompt");
        keytool("-exportcert", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", password,
                "-file", caCert.toString());
        keytool("-genkeypair", "-alias", "maintainer", "-dname", "CN=Isolated Maintainer", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-keystore", memberStore.toString(),
                "-storepass", password, "-keypass", password, "-noprompt");
        keytool("-certreq", "-alias", "maintainer", "-keystore", memberStore.toString(),
                "-storepass", password, "-file", request.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", password,
                "-infile", request.toString(), "-outfile", signed.toString(), "-validity", "2",
                "-ext", "KU=digitalSignature", "-ext", "EKU=clientAuth");
        java.nio.file.Files.copy(signed, peerCert);
        peer = certificate(peerCert);
        caFingerprint = digest(certificate(caCert));
        manifest = new ProductIdentityBootstrap.Manifest("https://identity.example.org", "named-operator",
                "organization-1", peerCert.toString(), caCert.toString(),
                peer.getSubjectX500Principal().getName(), digest(peer),
                List.of("SUBJECTS", "GRANTS", "KEYS", "SOURCES"));

    }

    private static ProductIdentityBootstrapSharedInstance.Settings settings() {
        return settings("shared-instance", DM_URL, DM_URL, "P_OWNER", "A_OWNER",
                "P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME", "A_BACKUP");
    }

    private static ProductIdentityBootstrapSharedInstance.Settings settings(String topology, String pUrl,
            String aUrl, String pSchema, String aSchema, String pOwner, String aOwner,
            String pRuntime, String aRuntime, String backup) {
        return new ProductIdentityBootstrapSharedInstance.Settings(topology, pUrl, aUrl, pSchema, aSchema,
                pOwner, aOwner, pRuntime, aRuntime, backup);
    }

    @Test void explicitTopologyExactDmUrlDistinctSchemasAndAllFiveRolesAreMandatory() {
        for (String topology : new String[] {"", "independent", "SHARED_INSTANCE"})
            assertThatThrownBy(() -> settings(topology, DM_URL, DM_URL, "P_OWNER", "A_OWNER",
                    "P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME", "A_BACKUP"))
                    .isInstanceOf(IllegalArgumentException.class);
        for (String url : new String[] {"", "jdbc:h2:mem:test", DM_URL + "?alias=true"})
            assertThatThrownBy(() -> settings("shared-instance", DM_URL, url, "P_OWNER", "A_OWNER",
                    "P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME", "A_BACKUP"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings("shared-instance", DM_URL, DM_URL, "P_OWNER", "p_owner",
                "P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME", "A_BACKUP"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String duplicate : List.of("P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME"))
            assertThatThrownBy(() -> settings("shared-instance", DM_URL, DM_URL, "P_OWNER", "A_OWNER",
                    "P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME", duplicate))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings("shared-instance", DM_URL, DM_URL, "P_OWNER", "A_OWNER",
                "P_OWNER", "A_OWNER", "a_owner", "A_RUNTIME", "A_BACKUP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.main(new String[] {"manifest.json"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sharedRegistrationPreservesEmptyDomainDutiesWatermarkAndIndependentBackup() throws Exception {
        Fixture f = new Fixture();
        f.bootstrap();
        assertThat(f.authority.queryForObject("SELECT subject_ref FROM id2_maintainer", String.class))
                .isEqualTo(manifest.subject());
        assertThat(f.authority.queryForObject("SELECT organization FROM id2_maintainer", String.class))
                .isEqualTo(manifest.organization());
        assertThat(f.authority.queryForObject("SELECT allowed_roles FROM id2_maintainer", String.class))
                .isEqualTo("SUBJECTS,GRANTS,KEYS,SOURCES");
        assertThat(f.authority.queryForObject("SELECT certificate_sha FROM id2_maintainer", String.class))
                .isEqualTo(digest(peer));
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit WHERE decision='BOOTSTRAP'",
                Integer.class)).isEqualTo(1);
        for (String table : List.of("id2_subject", "id2_subject_event", "id2_key_control", "id2_session",
                "id2_grant", "id2_grant_event", "id2_current_epoch"))
            assertThat(f.primary.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        JdbcTemplate backup = new JdbcTemplate(f.backup);
        JdbcTemplate service = new JdbcTemplate(f.service);
        assertThat(backup.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isEqualTo(1);
        assertThat(backup.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit", Integer.class)).isEqualTo(1);
        assertThat(backup.queryForObject("SELECT generation FROM id2_authority_watermark", Long.class)).isZero();
        assertThat(backup.queryForObject("SELECT state FROM id2_authority_watermark", String.class)).isEqualTo("READY");
        assertThat(backup.queryForObject("SELECT history_sha FROM id2_authority_watermark", String.class))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(service.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> backup.update("UPDATE id2_authority_watermark SET generation=1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> service.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit", Integer.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(f::bootstrap).isInstanceOf(IllegalStateException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit", Integer.class)).isEqualTo(1);
        var watermark = new ProductGrantWatermark(f.primary, f.authority, manifest.issuer());
        assertThat(watermark.currentGeneration()).isZero();
        f.authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unresolved'");
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
        f.authority.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL,history_sha=?", "0".repeat(64));
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
    }

    @Test void everyActualConnectionMustMatchExactUrlSchemaAndUserBeforeDcl() throws Exception {
        Fixture f = new Fixture();
        for (DataSource wrong : List.of(advertise(f.raw("A_OWNER", "A_OWNER"), DM_URL + "?alias=true", false),
                advertise(f.raw("A_OWNER", "P_OWNER"), DM_URL, false), f.primaryOwner, f.backup)) {
            assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                    wrong, f.runtime, f.service, f.backup, manifest, caFingerprint))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                f.authorityOwner, f.primaryOwner, f.service, f.backup, manifest, caFingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                f.authorityOwner, f.runtime, f.authorityOwner, f.backup, manifest, caFingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                f.authorityOwner, f.runtime, f.service, f.service, manifest, caFingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isZero();
        assertThatThrownBy(() -> new JdbcTemplate(f.service).queryForObject(
                "SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isInstanceOf(DataAccessException.class);
    }

    @Test void legacyPackageEntryStillRejectsSameUrlEvenWithDifferentSchemas() throws Exception {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> ProductIdentityBootstrap.bootstrap(f.primary, f.authority,
                new TransactionTemplate(new DataSourceTransactionManager(f.authorityOwner)), manifest,
                caFingerprint, "A_RUNTIME", "A_BACKUP")).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isZero();
    }

    @Test void certificatePinAndRolesRemainMandatoryOnSharedEntry() throws Exception {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                f.authorityOwner, f.runtime, f.service, f.backup, manifest, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        var bad = new ProductIdentityBootstrap.Manifest(manifest.issuer(), manifest.subject(), manifest.organization(),
                manifest.certificatePath(), manifest.trustCertificatePath(), manifest.certificateSubjectDn(),
                manifest.certificateSha256(), List.of("KEYS", "KEYS"));
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                f.authorityOwner, f.runtime, f.service, f.backup, bad, caFingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_authority_watermark", Integer.class)).isZero();
    }

    @Test void nonemptyAuthorityAndPrimaryFailClosedWithoutRegistration() throws Exception {
        Fixture f = new Fixture();
        f.authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", manifest.issuer(), "0".repeat(64));
        assertThatThrownBy(f::bootstrap).isInstanceOf(IllegalStateException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isZero();
        f.authority.update("DELETE FROM id2_authority_watermark");
        f.primary.update("INSERT INTO id2_key_control(issuer,version) VALUES(?,0)", manifest.issuer());
        assertThatThrownBy(f::bootstrap).isInstanceOf(IllegalStateException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isZero();
    }

    @Test void everyMissingInstalledPrivilegeFailsBeforeRegistrationWithZeroDcl() throws Exception {
        for (String[] required : new String[][] {
                {"ID2_AUTHORITY_WATERMARK", "A_RUNTIME", "SELECT"},
                {"ID2_AUTHORITY_WATERMARK", "A_RUNTIME", "UPDATE"},
                {"ID2_MAINTAINER", "A_RUNTIME", "SELECT"},
                {"ID2_MAINTAINER", "A_RUNTIME", "UPDATE"},
                {"ID2_MAINTENANCE_AUDIT", "A_RUNTIME", "INSERT"},
                {"ID2_AUTHORITY_WATERMARK", "A_BACKUP", "SELECT"},
                {"ID2_MAINTAINER", "A_BACKUP", "SELECT"},
                {"ID2_MAINTENANCE_AUDIT", "A_BACKUP", "SELECT"}}) {
            Fixture f = new Fixture();
            new JdbcTemplate(f.raw("A_OWNER", "A_OWNER")).execute("REVOKE " + required[2]
                    + " ON " + required[0] + " FROM " + required[1]);
            assertThatThrownBy(f::bootstrap).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Existing authority runtime and backup privileges required");
            for (String table : List.of("id2_maintainer", "id2_authority_watermark", "id2_maintenance_audit"))
                assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            assertThat(dclAttempts.get()).isZero();
        }
    }

    @Test void authorityRegistrationRollsBackTogetherWhileInstalledPermissionsRemain() throws Exception {
        Fixture f = new Fixture();
        DataSource failingOwner = advertise(f.raw("A_OWNER", "A_OWNER"), DM_URL, true, true);
        assertThatThrownBy(() -> ProductIdentityBootstrapSharedInstance.bootstrap(settings(), f.primaryOwner,
                failingOwner, f.runtime, f.service, f.backup, manifest, caFingerprint))
                .isInstanceOf(DataAccessException.class);
        for (String table : List.of("id2_maintainer", "id2_authority_watermark", "id2_maintenance_audit"))
            assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        assertThat(new JdbcTemplate(f.service).queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class))
                .isZero();
        assertThat(new JdbcTemplate(f.backup).queryForObject("SELECT COUNT(*) FROM id2_authority_watermark", Integer.class))
                .isZero();
    }

    private static final class Fixture {
        final String url = "jdbc:h2:mem:shared-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        final DataSource primaryOwner, authorityOwner, runtime, service, backup;
        final JdbcTemplate primary, authority;
        Fixture() throws Exception {
            JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
            for (String user : List.of("P_OWNER", "A_OWNER", "P_RUNTIME", "A_RUNTIME", "A_BACKUP"))
                admin.execute("CREATE USER " + user + " PASSWORD 'test-password'");
            admin.execute("CREATE SCHEMA P_OWNER AUTHORIZATION P_OWNER");
            admin.execute("CREATE SCHEMA A_OWNER AUTHORIZATION A_OWNER");
            primaryOwner = advertise(raw("P_OWNER", "P_OWNER"), DM_URL, false);
            authorityOwner = advertise(raw("A_OWNER", "A_OWNER"), DM_URL, true);
            runtime = advertise(raw("P_RUNTIME", "P_OWNER"), DM_URL, false);
            service = advertise(raw("A_RUNTIME", "A_OWNER"), DM_URL, false);
            backup = advertise(raw("A_BACKUP", "A_OWNER"), DM_URL, false);
            try (var c = primaryOwner.getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
            }
            try (var c = authorityOwner.getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            }
            // Real pre-installation fixture, not a shared-bootstrap success stub.
            JdbcTemplate installer = new JdbcTemplate(raw("A_OWNER", "A_OWNER"));
            installer.execute("GRANT SELECT,UPDATE ON id2_authority_watermark TO A_RUNTIME");
            installer.execute("GRANT SELECT,UPDATE ON id2_maintainer TO A_RUNTIME");
            installer.execute("GRANT INSERT ON id2_maintenance_audit TO A_RUNTIME");
            for (String table : List.of("id2_authority_watermark", "id2_maintainer", "id2_maintenance_audit"))
                installer.execute("GRANT SELECT ON " + table + " TO A_BACKUP");
            primary = new JdbcTemplate(primaryOwner);
            authority = new JdbcTemplate(authorityOwner);
        }
        DataSource raw(String user, String schema) {
            var ds = new DriverManagerDataSource(url.replace(";DB_CLOSE_DELAY=-1", ""), user, "test-password");
            ds.setSchema(schema);
            return ds;
        }
        void bootstrap() throws Exception {
            ProductIdentityBootstrapSharedInstance.bootstrap(settings(), primaryOwner, authorityOwner,
                    runtime, service, backup, manifest, caFingerprint);
        }
    }

    private static DataSource advertise(DataSource delegate, String url, boolean forbidDcl) {
        return advertise(delegate, url, forbidDcl, false);
    }

    private static DataSource advertise(DataSource delegate, String url, boolean forbidDcl,
                                        boolean failRegistration) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return wrap(delegate.getConnection());
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                return wrap(delegate.getConnection(user, password));
            }
            private Connection wrap(Connection c) {
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    try {
                        if (failRegistration && method.getName().equals("prepareStatement") && args != null
                                && args[0] instanceof String sql
                                && sql.startsWith("INSERT INTO id2_maintenance_audit"))
                            throw new SQLException("Injected registration audit failure", "42000");
                        Object result = method.invoke(c, args);
                        if (method.getName().equals("getMetaData")) {
                            DatabaseMetaData metadata = (DatabaseMetaData) result;
                            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                                    new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> {
                                if (m.getName().equals("getURL")) return url;
                                try { return m.invoke(metadata, a); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        if (method.getName().equals("createStatement")) {
                            Statement statement = (Statement) result;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                    new Class<?>[] {Statement.class}, (p, m, a) -> {
                                if (m.getName().equals("execute") && a != null && a[0] instanceof String sql
                                        && (sql.stripLeading().startsWith("GRANT ")
                                                || sql.stripLeading().startsWith("REVOKE "))) {
                                    dclAttempts.incrementAndGet();
                                    if (forbidDcl) throw new SQLException("Shared bootstrap DCL forbidden", "42000");
                                }
                                try { return m.invoke(statement, a); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
    }

    private static X509Certificate certificate(Path path) throws Exception {
        try (var input = java.nio.file.Files.newInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static String digest(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static void keytool(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException("Certificate setup failed: " + output);
    }
}
