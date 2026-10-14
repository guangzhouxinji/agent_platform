package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductIdentityBootstrapTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneTimeInitializationUsesCertificateAndNeverCreatesAMemberOrGrant(boolean retention) throws Exception {
        assertThatThrownBy(() -> cn.xinji.agentplatform.AgentPlatformApplication.main(
                new String[] {"--identity-bootstrap"})).isInstanceOf(IllegalArgumentException.class);
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

        JdbcDataSource primarySource = source("bootstrap-main-");
        JdbcDataSource authoritySource = source("bootstrap-authority-");
        try (var connection = primarySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try (var connection = authoritySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        JdbcTemplate primary = new JdbcTemplate(primarySource);
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        authority.execute("CREATE USER RUNTIME_SERVICE PASSWORD 'runtime-password'");
        authority.execute("CREATE USER BACKUP_READER PASSWORD 'backup-password'");
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(authoritySource));
        X509Certificate certificate = certificate(peerCert);
        var manifest = new ProductIdentityBootstrap.Manifest("https://identity.example.org", "named-operator",
                "organization-1", peerCert.toString(), caCert.toString(),
                certificate.getSubjectX500Principal().getName(), digest(certificate),
                retention ? List.of("HUMAN_RETENTION") : List.of("SUBJECTS", "GRANTS", "KEYS", "SOURCES"));

        assertThatThrownBy(() -> ProductIdentityBootstrap.bootstrap(primary, authority, transactions, manifest,
                "0".repeat(64), "RUNTIME_SERVICE", "BACKUP_READER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isZero();
        assertThatThrownBy(() -> ProductIdentityBootstrap.bootstrap(primary, authority, transactions, manifest,
                digest(certificate(caCert)), "RUNTIME_SERVICE", "MISSING_BACKUP"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isZero();
        ProductIdentityBootstrap.bootstrap(primary, authority, transactions, manifest, digest(certificate(caCert)),
                "RUNTIME_SERVICE", "BACKUP_READER");
        assertThat(authority.queryForObject("SELECT subject_ref FROM id2_maintainer", String.class))
                .isEqualTo("named-operator");
        assertThat(authority.queryForObject("SELECT organization FROM id2_maintainer", String.class))
                .isEqualTo("organization-1");
        assertThat(authority.queryForObject("SELECT allowed_roles FROM id2_maintainer", String.class))
                .isEqualTo(retention ? "HUMAN_RETENTION" : "SUBJECTS,GRANTS,KEYS,SOURCES");
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit "
                + "WHERE decision='BOOTSTRAP'", Integer.class)).isEqualTo(1);
        assertThat(primary.queryForObject("SELECT COUNT(*) FROM id2_subject", Integer.class)).isZero();
        assertThat(primary.queryForObject("SELECT COUNT(*) FROM id2_grant", Integer.class)).isZero();
        String regularUrl = authoritySource.getURL().replace(";DB_CLOSE_DELAY=-1", "");
        var runtime = new JdbcTemplate(new DriverManagerDataSource(regularUrl,
                "RUNTIME_SERVICE", "runtime-password"));
        var backup = new JdbcTemplate(new DriverManagerDataSource(regularUrl,
                "BACKUP_READER", "backup-password"));
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class)).isEqualTo(1);
        assertThat(backup.queryForObject("SELECT COUNT(*) FROM id2_authority_watermark", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> runtime.update("INSERT INTO id2_maintainer "
                + "(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                + "VALUES(2,'forbidden','forbidden',?,'KEYS','ACTIVE',1)", "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> backup.update("UPDATE id2_authority_watermark SET generation=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> ProductIdentityBootstrap.bootstrap(primary, authority, transactions, manifest,
                digest(certificate(caCert)), "RUNTIME_SERVICE", "BACKUP_READER"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit", Integer.class))
                .isEqualTo(1);
    }

    private static JdbcDataSource source(String prefix) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + prefix + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
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
