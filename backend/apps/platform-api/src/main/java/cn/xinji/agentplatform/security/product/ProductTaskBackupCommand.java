package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline authenticated seal/open stages; native export and writer quiescence are deployment duties. */
public final class ProductTaskBackupCommand {
    private ProductTaskBackupCommand() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("seal", "open").contains(args[0]))
            throw new IllegalArgumentException("Select seal or open");
        byte[] key = Base64.getDecoder().decode(required("PRODUCT_TASK_BACKUP_KEY_BASE64"));
        var bundle = new ProductTaskBackupBundle(new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC());
        if ("open".equals(args[0])) {
            var manifest = bundle.open(Path.of(required("PRODUCT_TASK_BACKUP_PACKAGE")),
                    Path.of(required("PRODUCT_TASK_BACKUP_ISOLATED_TARGET")), key,
                    positiveLong("PRODUCT_TASK_BACKUP_MAX_BYTES"));
            System.out.println("Authenticated isolated extraction: batch=" + manifest.snapshot().batchId()
                    + " entries=" + manifest.entries().size());
            return;
        }
        JdbcTemplate primary = new JdbcTemplate(source("PRODUCT_TASK_BACKUP_DB"));
        JdbcTemplate independent = new JdbcTemplate(source("PRODUCT_TASK_BACKUP_AUTHORITY"));
        String issuer = required("PRODUCT_TASK_BACKUP_ISSUER");
        var grant = new ProductGrantWatermark(primary, independent, issuer);
        var source = new ProductSourceWatermark(primary, independent,
                new TransactionTemplate(new DataSourceTransactionManager(primary.getDataSource())));
        var authority = new ProductTaskBackupAuthority(primary, independent, grant, source,
                issuer, Clock.systemUTC());
        Path root = Path.of(required("PRODUCT_TASK_BACKUP_EVIDENCE_ROOT"));
        long maxFileBytes = positiveLong("PRODUCT_TASK_BACKUP_EVIDENCE_MAX_BYTES");
        String retentionRef = required("PRODUCT_TASK_BACKUP_RETENTION_REF");
        String retentionSha = required("PRODUCT_TASK_BACKUP_RETENTION_SHA256");
        long retentionVersion = positiveLong("PRODUCT_TASK_BACKUP_RETENTION_VERSION");
        var retention = new ProductTaskBackupRetention(new ProductSourceEvidenceFiles(root, maxFileBytes),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        Instant expiresAt = Instant.parse(required("PRODUCT_TASK_BACKUP_EXPIRES_AT"));
        retention.require(primary, retentionRef, retentionSha, retentionVersion, expiresAt);
        List<String> references = references(primary, root, maxFileBytes);
        if (!references.contains(retentionRef)) {
            var included = new ArrayList<>(references);
            included.add(retentionRef);
            references = included.stream().distinct().sorted().toList();
        }
        var snapshot = authority.capture(required("PRODUCT_TASK_BACKUP_BATCH_ID"),
                required("PRODUCT_TASK_BACKUP_DB_SCHEMA"), required("PRODUCT_TASK_BACKUP_KEY_ID"),
                expiresAt, retentionRef, retentionVersion);
        Path target = Path.of(required("PRODUCT_TASK_BACKUP_PACKAGE")).toAbsolutePath();
        Path pending = target.resolveSibling(target.getFileName() + ".pending");
        if (Files.exists(target) || Files.exists(pending))
            throw new IllegalStateException("Backup target already exists");
        try {
            var manifest = bundle.seal(Path.of(required("PRODUCT_TASK_BACKUP_NATIVE_EXPORT")),
                    root, references, pending, key, snapshot);
            authority.assertUnchanged(snapshot);
            var currentReferences = new ArrayList<>(references(primary, root, maxFileBytes));
            currentReferences.add(retentionRef);
            if (!references.equals(currentReferences.stream().distinct().sorted().toList()))
                throw new IllegalStateException("Backup evidence references changed");
            retention.require(primary, retentionRef, retentionSha, retentionVersion, expiresAt);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Authenticated DB+file package: batch=" + snapshot.batchId()
                    + " entries=" + manifest.entries().size());
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static List<String> references(JdbcTemplate db, Path root, long maxBytes) {
        var originals = new ProductSourceRecoveryFiles(db, root, maxBytes).references();
        Map<String, String> refs = new LinkedHashMap<>();
        var files = new ProductSourceEvidenceFiles(root, maxBytes);
        for (String query : List.of(
                "SELECT evidence_ref,evidence_sha FROM con4_source_contract",
                "SELECT rights_ref,rights_sha FROM con4_source_admission",
                "SELECT capability_ref,capability_sha FROM con4_source_admission",
                "SELECT decision_ref,decision_sha FROM con4_source_product_decision",
                "SELECT retention_ref,retention_evidence_sha FROM con3_task",
                "SELECT retention_ref,retention_evidence_sha FROM con4_source_contract",
                "SELECT retention_ref,retention_evidence_sha FROM con4_source_product_decision",
                "SELECT retention_ref,retention_evidence_sha FROM con3_message",
                "SELECT retention_ref,retention_evidence_sha FROM con3_goal_revision")) {
            db.query(query, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                String ref = row.getString(1);
                String sha = row.getString(2);
                String prior = refs.putIfAbsent(ref, sha);
                if (prior != null && !prior.equalsIgnoreCase(sha))
                    throw new IllegalStateException("Conflicting retained evidence digest");
            });
        }
        for (var item : refs.entrySet()) files.verified(item.getKey(), item.getValue());
        var all = new ArrayList<>(originals);
        all.addAll(refs.keySet());
        return all.stream().distinct().sorted().toList();
    }

    private static DriverManagerDataSource source(String prefix) {
        String url = required(prefix + "_URL");
        String schema = required(prefix + "_SCHEMA");
        if (!schema.matches("[A-Za-z][A-Za-z0-9_]{2,63}"))
            throw new IllegalArgumentException("Exact backup schema required");
        return new DriverManagerDataSource(url, required(prefix + "_USER"),
                required(prefix + "_PASSWORD")) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                try {
                    connection.setSchema(schema.toUpperCase(java.util.Locale.ROOT));
                    connection.setReadOnly(true);
                    return connection;
                } catch (java.sql.SQLException failure) {
                    connection.close();
                    throw failure;
                }
            }
        };
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static long positiveLong(String name) {
        long value = Long.parseLong(required(name));
        if (value < 1) throw new IllegalArgumentException("Positive " + name + " required");
        return value;
    }
}
