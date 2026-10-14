import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only current DM8 reference collection, never a backup, restoration or model request. */
public final class VerifyConsultationModelRetention {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Isolated product class root required");
        try (var loader = new MeasureConsultationFramingCandidate.ProductLoader(
                new java.net.URL[]{Path.of(args[0]).toUri().toURL()})) {
            var method = loader.loadClass("cn.xinji.agentplatform.security.product.ProductModelTurnRetentionReferences")
                    .getDeclaredMethod("collect", JdbcTemplate.class, Map.class);
            method.setAccessible(true);
            var primary = database("CONPROD", "FRAME_PRIMARY_PASSWORD");
            var backup = database("CONPROD_BKP", "MODEL_RETENTION_BACKUP_PASSWORD");
            long rows = primary.queryForObject("SELECT COUNT(*) FROM con3_model_turn", Long.class);
            Map<String, String> ownerRefs = new TreeMap<>(), backupRefs = new TreeMap<>();
            method.invoke(null, primary, ownerRefs); method.invoke(null, backup, backupRefs);
            if (rows == 0 || ownerRefs.isEmpty() || !ownerRefs.equals(backupRefs)
                    || rows != backup.queryForObject("SELECT COUNT(*) FROM con3_model_turn", Long.class))
                throw new IllegalStateException("Current model reference visibility mismatch");
            String canonical = new ObjectMapper().writeValueAsString(ownerRefs);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            System.out.println(new ObjectMapper().writeValueAsString(Map.of("modelRows", rows,
                    "distinctPolicyReferences", ownerRefs.size(), "referenceSetSha256", sha,
                    "ownerAndFinalBackupIdentityEqual", true, "databaseWrites", 0, "modelCalls", 0,
                    "scope", "REAL_CURRENT_DM8_MODEL_RETENTION_COLLECTION_NOT_BACKUP_RESTORE_OR_HTTP")));
        }
    }
    static JdbcTemplate database(String user, String secretName) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:dm://127.0.0.1:5301", user,
                MeasureConsultationFramingCandidate.required(secretName)) {
            @Override public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                try { connection.setSchema("CONPROD"); connection.setReadOnly(true); return connection; }
                catch (SQLException failure) { connection.close(); throw failure; }
            }
        });
    }
}
