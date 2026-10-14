import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Safe current-row audit using the final recovery verifier. Never restores or rewrites records. */
public final class AuditConsultationModelRecovery {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Isolated classes and existing evidence root required");
        try (var loader = new MeasureConsultationFramingCandidate.ProductLoader(
                new java.net.URL[]{Path.of(args[0]).toUri().toURL()});
             var authority = DriverManager.getConnection("jdbc:dm://127.0.0.1:5302", "CONAUTH_SVC",
                    MeasureConsultationFramingCandidate.required("FRAME_AUTHORITY_PASSWORD"))) {
            authority.setSchema("CONAUTH"); authority.setReadOnly(true);
            var before = MeasureConsultationFramingCandidate.state(authority);
            if (!"READY".equals(before.state()) || before.intent() != null)
                throw new IllegalStateException("Authority pending; no current-row audit");
            var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:dm://127.0.0.1:5301", "CONPROD",
                    MeasureConsultationFramingCandidate.required("FRAME_PRIMARY_PASSWORD")) {
                @Override public Connection getConnection() throws SQLException {
                    var connection = super.getConnection();
                    try { connection.setSchema("CONPROD"); connection.setReadOnly(true); return connection; }
                    catch (SQLException failed) { connection.close(); throw failed; }
                }
            });
            var type = loader.loadClass("cn.xinji.agentplatform.security.product.ProductModelTurnRecoveryVerifier");
            var constructor = type.getDeclaredConstructor(JdbcTemplate.class, Path.class, long.class);
            constructor.setAccessible(true);
            var verifier = constructor.newInstance(db, Path.of(args[1]).toAbsolutePath(), 536870912L);
            var check = type.getDeclaredMethod("verifyTurn", String.class); check.setAccessible(true);
            List<String> ids = db.queryForList("SELECT turn_id FROM con3_model_turn ORDER BY turn_id", String.class);
            List<Map<String,String>> rejected = new ArrayList<>(); int passed = 0;
            for (String rawId : ids) {
                String id = UUID.fromString(rawId).toString();
                try { check.invoke(verifier, id); passed++; }
                catch (InvocationTargetException failure) {
                    String message = failure.getCause().getMessage(), reason = "VERIFIER_REJECTED";
                    String prefix = "Recovered model turn " + id + ": ";
                    if (message != null && message.startsWith(prefix)
                            && message.substring(prefix.length()).matches("[A-Z_]{1,64}"))
                        reason = message.substring(prefix.length());
                    rejected.add(Map.of("turnId", id, "reason", reason));
                }
            }
            if (!before.equals(MeasureConsultationFramingCandidate.state(authority))
                    || ids.size() != db.queryForObject("SELECT COUNT(*) FROM con3_model_turn", Integer.class))
                throw new IllegalStateException("Audit state changed; no stable evidence");
            System.out.println(new ObjectMapper().writeValueAsString(Map.of("rows", ids.size(),
                    "passed", passed, "rejected", rejected, "generation", before.generation(),
                    "authorityUnchanged", true, "databaseWrites", 0, "modelCalls", 0,
                    "scope", "CURRENT_REAL_MODEL_HISTORY_READONLY_NOT_AUTHENTICATED_BACKUP_OR_PHYSICAL_RESTORE")));
        }
    }
}
