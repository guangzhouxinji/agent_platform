import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

/** One rejected row's structural flags; never prints model content, goal, raw envelope or secrets. */
public final class InspectConsultationModelRecoveryMetadata {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Exact rejected turn required");
        String turn = UUID.fromString(args[0]).toString();
        try (var connection = DriverManager.getConnection("jdbc:dm://127.0.0.1:5301", "CONPROD",
                MeasureConsultationFramingCandidate.required("FRAME_PRIMARY_PASSWORD"))) {
            connection.setSchema("CONPROD"); connection.setReadOnly(true);
            try (var statement = connection.prepareStatement("SELECT model_id,prompt_version,created_at,raw_output "
                    + "FROM con3_model_turn WHERE turn_id=?")) {
                statement.setString(1, turn);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Exact row absent");
                    String raw = rows.getString(4);
                    var root = new ObjectMapper().readTree(raw);
                    var choice = root.path("choices").path(0);
                    var flags = new LinkedHashMap<String,Object>();
                    flags.put("turnId", turn); flags.put("rawChars", raw.length());
                    flags.put("rawJsonObject", root.isObject()); flags.put("hasProviderModel", root.path("model").isTextual());
                    flags.put("providerModelMatchesRow", rows.getString(1).equals(root.path("model").asText()));
                    flags.put("hasChoicesArray", root.path("choices").isArray());
                    flags.put("hasChoiceContent", choice.path("message").path("content").isTextual());
                    flags.put("hasRootContent", root.path("content").isTextual());
                    flags.put("hasOriginalNotCapturedMarker", root.has("rawOutputNotCaptured") || root.has("originalNotCaptured"));
                    flags.put("databaseWrites", 0); flags.put("scope", "ONE_EXISTING_REJECTED_ROW_METADATA_ONLY");
                    if (rows.next()) throw new IllegalStateException("Exact row ambiguous");
                    System.out.println(new ObjectMapper().writeValueAsString(flags));
                }
            }
        }
    }
}
