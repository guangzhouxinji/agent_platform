package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-time offline deployment entry point; never creates a member or a task grant. */
public final class ProductIdentityBootstrap {
    public record Manifest(String issuer, String subject, String organization, String certificatePath,
                           String trustCertificatePath, String certificateSubjectDn,
                           String certificateSha256, List<String> roles) { }

    private static final String EMPTY_HISTORY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private ProductIdentityBootstrap() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Provide one deployment manifest path");
        Manifest manifest = new ObjectMapper().readValue(Path.of(args[0]).toFile(), Manifest.class);
        String primaryUrl = requiredEnv("PRODUCT_IDENTITY_DB_URL");
        String authorityUrl = requiredEnv("PRODUCT_IDENTITY_AUTHORITY_DB_URL");
        String primaryUser = requiredEnv("PRODUCT_IDENTITY_DB_DEPLOY_USER");
        String authorityUser = requiredEnv("PRODUCT_IDENTITY_AUTHORITY_DEPLOY_USER");
        String serviceUser = requiredEnv("PRODUCT_IDENTITY_AUTHORITY_SERVICE_USER");
        String backupUser = requiredEnv("PRODUCT_IDENTITY_AUTHORITY_BACKUP_USER");
        if (primaryUrl.equals(authorityUrl) || primaryUser.equals(authorityUser))
            throw new IllegalArgumentException("Independent deployment databases and roles are required");
        var primary = new DriverManagerDataSource(primaryUrl, primaryUser,
                requiredEnv("PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD"));
        var authority = new DriverManagerDataSource(authorityUrl, authorityUser,
                requiredEnv("PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD"));
        bootstrap(new JdbcTemplate(primary), new JdbcTemplate(authority),
                new TransactionTemplate(new DataSourceTransactionManager(authority)), manifest,
                requiredEnv("PRODUCT_IDENTITY_TRUST_SHA256"), serviceUser, backupUser);
        System.out.println("Product identity initialization committed; member activation and grant remain separate.");
    }

    static void bootstrap(JdbcTemplate primary, JdbcTemplate authority, TransactionTemplate authorityTransaction,
                          Manifest manifest, String trustedCaFingerprint, String serviceUser,
                          String backupUser) throws Exception {
        validate(manifest);
        String service = databaseUser(serviceUser);
        String backup = databaseUser(backupUser);
        if (service.equals(backup))
            throw new IllegalArgumentException("Service, backup and deployment roles must be separate");
        X509Certificate trustedCa = certificate(manifest.trustCertificatePath());
        X509Certificate peer = certificate(manifest.certificatePath());
        trustedCa.checkValidity();
        peer.checkValidity();
        if (trustedCa.getBasicConstraints() < 0
                || !digest(trustedCa).equals(normalize(trustedCaFingerprint))
                || !digest(peer).equals(normalize(manifest.certificateSha256()))
                || !peer.getSubjectX500Principal().getName().equals(manifest.certificateSubjectDn())
                || peer.getExtendedKeyUsage() == null
                || !peer.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")
                || peer.getKeyUsage() == null || peer.getKeyUsage().length == 0 || !peer.getKeyUsage()[0])
            throw new IllegalArgumentException("Maintenance certificate does not match trusted deployment input");
        peer.verify(trustedCa.getPublicKey());
        try (var primaryConnection = primary.getDataSource().getConnection();
             var authorityConnection = authority.getDataSource().getConnection()) {
            if (primaryConnection.getMetaData().getURL().equals(authorityConnection.getMetaData().getURL()))
                throw new IllegalArgumentException("Authority must use another database instance");
            String deploymentUser = authorityConnection.getMetaData().getUserName();
            if (service.equalsIgnoreCase(deploymentUser) || backup.equalsIgnoreCase(deploymentUser))
                throw new IllegalArgumentException("Service, backup and deployment roles must be separate");
        }
        for (String table : List.of("id2_subject", "id2_subject_event", "id2_key_control", "id2_session",
                "id2_grant", "id2_grant_event", "id2_current_epoch")) {
            Integer count = primary.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            if (count == null || count != 0) throw new IllegalStateException("Identity domain is not empty");
        }
        Integer maintainers = authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class);
        Integer watermarks = authority.queryForObject("SELECT COUNT(*) FROM id2_authority_watermark", Integer.class);
        if (maintainers == null || maintainers != 0 || watermarks == null || watermarks != 0)
            throw new IllegalStateException("Identity domain already initialized or restored");
        // DM8 may commit DCL independently; prepare permissions before the atomic identity registration.
        authority.execute("GRANT SELECT,UPDATE ON id2_authority_watermark TO " + service);
        authority.execute("GRANT SELECT,UPDATE ON id2_maintainer TO " + service);
        authority.execute("GRANT INSERT ON id2_maintenance_audit TO " + service);
        for (String table : List.of("id2_authority_watermark", "id2_maintainer", "id2_maintenance_audit"))
            authority.execute("GRANT SELECT ON " + table + " TO " + backup);
        authorityTransaction.executeWithoutResult(status -> {
            Integer nowMaintainers = authority.queryForObject("SELECT COUNT(*) FROM id2_maintainer", Integer.class);
            Integer nowWatermarks = authority.queryForObject("SELECT COUNT(*) FROM id2_authority_watermark", Integer.class);
            if (nowMaintainers == null || nowMaintainers != 0 || nowWatermarks == null || nowWatermarks != 0)
                throw new IllegalStateException("Identity domain already initialized or restored");
            authority.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,certificate_sha,"
                            + "allowed_roles,status,version) VALUES(1,?,?,?,?,'ACTIVE',1)",
                    manifest.subject(), manifest.organization(), digestUnchecked(peer),
                    String.join(",", manifest.roles()));
            authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                    + "VALUES(?,0,'READY',NULL,?)", manifest.issuer(), EMPTY_HISTORY);
            authority.update("INSERT INTO id2_maintenance_audit(audit_id,subject_ref,certificate_sha,"
                            + "role_code,decision,occurred_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
                    java.util.UUID.randomUUID().toString(), manifest.subject(), digestUnchecked(peer),
                    "BOOTSTRAP", "BOOTSTRAP");
        });
        new ProductGrantWatermark(primary, authority, manifest.issuer());
    }

    private static void validate(Manifest manifest) {
        if (manifest == null || manifest.issuer() == null || !manifest.issuer().startsWith("https://")
                || manifest.issuer().endsWith("/") || blank(manifest.subject())
                || blank(manifest.organization()) || blank(manifest.certificatePath())
                || blank(manifest.trustCertificatePath()) || blank(manifest.certificateSubjectDn())
                || manifest.roles() == null || manifest.roles().isEmpty()
                || manifest.roles().stream().anyMatch(java.util.Objects::isNull)
                || manifest.roles().size() != Set.copyOf(manifest.roles()).size()
                || !Set.of("SUBJECTS", "GRANTS", "KEYS", "SOURCES").containsAll(manifest.roles()))
            throw new IllegalArgumentException("Exact accountable identity and duties are required");
        normalize(manifest.certificateSha256());
    }

    private static X509Certificate certificate(String path) throws Exception {
        try (var input = java.nio.file.Files.newInputStream(Path.of(path))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static String digest(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static String digestUnchecked(X509Certificate certificate) {
        try { return digest(certificate); }
        catch (Exception failure) { throw new IllegalStateException("Certificate digest unavailable", failure); }
    }

    private static String normalize(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Pinned SHA-256 certificate fingerprint required");
        return fingerprint.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (blank(value)) throw new IllegalArgumentException("Missing deployment setting: " + name);
        return value;
    }

    private static String databaseUser(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{2,63}"))
            throw new IllegalArgumentException("Pre-created database role name required");
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
