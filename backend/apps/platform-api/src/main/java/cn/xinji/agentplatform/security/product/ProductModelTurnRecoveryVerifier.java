package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Historical model evidence only. No inference, current permission grant, rewrite or failure promotion. */
final class ProductModelTurnRecoveryVerifier {
    private record Member(String id, String document, String version, long ordinal) { }
    private final JdbcTemplate db;
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    ProductModelTurnRecoveryVerifier(JdbcTemplate db, Path root, long maxBytes) {
        this.db = db; this.files = new ProductSourceEvidenceFiles(root, maxBytes);
    }

    long verify() {
        if (!ProductModelTurnRetentionReferences.present(db)) return 0;
        List<String> ids = db.queryForList("SELECT turn_id FROM con3_model_turn ORDER BY turn_id", String.class);
        for (String id : ids) verifyTurn(id);
        return ids.size();
    }

    ProductHistoricalResultIsolation.Report report() {
        var gaps=new java.util.ArrayList<ProductHistoricalResultIsolation.Gap>();
        if(ProductModelTurnRetentionReferences.present(db))
            for(String id:db.queryForList("SELECT turn_id FROM con3_model_turn ORDER BY turn_id",String.class)) {
                try { verifyTurn(id); }
                catch(ProductHistoricalResultIsolation.Unverified missing) { gaps.addAll(missing.gaps()); }
            }
        return new ProductHistoricalResultIsolation(db).report(gaps,gaps.isEmpty());
    }

    void verifyTurn(String id) {
        if (!uuid(id)) throw new IllegalStateException("Recovered model turn: INVALID_TURN_ID");
        int[] count = {0};
        db.query("SELECT m.*,t.version recovery_task_version,t.expires_at recovery_task_expiry "
                + "FROM con3_model_turn m LEFT JOIN con3_task t ON t.task_id=m.task_id WHERE m.turn_id=?",
                (RowCallbackHandler) row -> { check(id, row); count[0]++; }, id);
        if (count[0] != 1) invalid(id, "TURN_CARDINALITY");
        for(String result:db.query("SELECT result_set_id FROM con3_model_turn WHERE turn_id=? AND result_set_id IS NOT NULL",
                (rs,n)->rs.getString(1),id)) new ProductHistoricalResultIsolation(db).require(result);
    }

    private void check(String id, ResultSet row) throws SQLException {
        String taskId = row.getString("task_id"), kind = row.getString("kind");
        long version = row.getLong("task_version"), currentVersion = row.getLong("recovery_task_version");
        var created = row.getTimestamp("created_at"); var expiry = row.getTimestamp("expires_at");
        var taskExpiry = row.getTimestamp("recovery_task_expiry");
        if (!uuid(taskId) || version < 1 || currentVersion < version || created == null || expiry == null
                || taskExpiry == null || !expiry.after(created) || expiry.after(taskExpiry)) invalid(id, "TASK_VERSION_OR_EXPIRY");
        if (kind == null || !Set.of("PROPOSAL", "EXPLANATION").contains(kind)
                || blank(row.getString("actor_subject")) || blank(row.getString("model_id"))
                || blank(row.getString("prompt_version"))
                || row.getLong("retention_version") < 1 || !sha(row.getString("request_sha"))
                || !sha(row.getString("retention_evidence_sha"))
                || row.getString("request_id") == null || !row.getString("request_id").matches("[A-Za-z0-9_-]{1,128}")
                || !Set.of("RETRIEVAL_DIALOGUE/v1", "RETRIEVAL_DIALOGUE/v2", "RETRIEVAL_DIALOGUE/v3")
                    .contains(row.getString("prompt_version"))) invalid(id, "RETAINED_METADATA");
        try { files.verified(row.getString("retention_ref"), row.getString("retention_evidence_sha")); }
        catch (RuntimeException missing) { invalid(id, "RETENTION_ORIGINAL"); }
        JsonNode request = tree(id, row.getString("request_json"));
        if (!kind.equals(request.path("kind").asText())
                || !"PUBLIC_NONCONFIDENTIAL_RETRIEVAL_ONLY".equals(request.path("scope").asText())
                || !request.path("goal").isTextual() || request.path("goal").asText().isBlank()) invalid(id, "MODEL_INPUT_SCOPE");
        Set<String> members;
        if ("PROPOSAL".equals(kind)) {
            if (row.getString("result_set_id") != null || row.getString("result_sha") != null
                    || request.has("members")) invalid(id, "PROPOSAL_RESULT");
            members = Set.of();
        } else if ("EXPLANATION".equals(kind)) {
            members = result(id, taskId, version, row.getString("result_set_id"), row.getString("result_sha"), request);
        } else { invalid(id, "MODEL_KIND"); return; }
        String raw = row.getString("raw_output");
        if (raw == null || raw.length() > 131072) invalid(id, "PROVIDER_ORIGINAL");
        JsonNode provider = tree(id, raw), choice = provider.path("choices").path(0);
        if (!row.getString("model_id").equals(provider.path("model").asText())
                || !"stop".equals(choice.path("finish_reason").asText())
                || !choice.path("message").path("content").isTextual()) invalid(id, "PROVIDER_ORIGINAL");
        JsonNode output = tree(id, row.getString("output_json"));
        if (output.has("failureCode")) {
            if (output.size() != 1 || !"MODEL_OUTPUT_VALIDATION_FAILED".equals(output.path("failureCode").asText()))
                invalid(id, "FAILURE_AUDIT");
            return; // Historical rejection remains rejection even if a later parser would accept its content.
        }
        JsonNode parsed;
        try { parsed = ProductRetrievalDialogueOutput.parse(mapper, choice.path("message").path("content").asText(), kind, members); }
        catch (RuntimeException invalidOutput) { invalid(id, "MODEL_OUTPUT_OR_CITATION"); return; }
        if (!parsed.equals(output)) invalid(id, "MODEL_OUTPUT_MISMATCH");
    }

    private Set<String> result(String id, String taskId, long version, String resultId, String resultSha, JsonNode request) {
        if (!uuid(resultId) || !sha(resultSha)) invalid(id, "RESULT_BINDING");
        Integer matches = db.queryForObject("SELECT COUNT(*) FROM con4_result_set r JOIN con4_search_attempt a "
                + "ON a.attempt_id=r.attempt_id WHERE r.result_set_id=? AND r.result_sha=? AND a.task_id=? AND a.task_version=?",
                Integer.class, resultId, resultSha, taskId, version);
        if (matches == null || matches != 1) invalid(id, "RESULT_BINDING");
        var expected = db.query(connection -> {
            var statement = connection.prepareStatement("SELECT member_id,document_id,document_version,ordinal "
                    + "FROM con4_result_member WHERE result_set_id=? ORDER BY ordinal");
            statement.setString(1, resultId); statement.setMaxRows(20); return statement;
        }, (row, n) -> new Member(row.getString(1), row.getString(2), row.getString(3), row.getLong(4)));
        Long total = db.queryForObject("SELECT COUNT(*) FROM con4_result_member WHERE result_set_id=?", Long.class, resultId);
        JsonNode actual = request.path("members");
        if (!actual.isArray() || actual.size() != expected.size() || !request.path("pageSize").isIntegralNumber()
                || request.path("pageSize").asLong() != expected.size() || !request.path("totalCandidates").isIntegralNumber()
                || total == null || request.path("totalCandidates").asLong() != total) invalid(id, "RESULT_PAGE");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < expected.size(); i++) {
            Member member = expected.get(i); JsonNode item = actual.get(i);
            if (!member.id().equals(item.path("memberId").asText()) || !ids.add(member.id())
                    || !member.document().equals(item.path("documentId").asText())
                    || !member.version().equals(item.path("documentVersion").asText())
                    || (item.has("ordinal") && (!item.path("ordinal").isIntegralNumber()
                        || item.path("ordinal").asLong() != member.ordinal()))) invalid(id, "RESULT_MEMBER_VERSION");
        }
        return ids;
    }

    private JsonNode tree(String id, String text) {
        try { JsonNode value = mapper.readTree(text); if (value == null || !value.isObject()) invalid(id, "RETAINED_JSON"); return value; }
        catch (Exception corrupt) { invalid(id, "RETAINED_JSON"); return null; }
    }
    private static boolean uuid(String value) { try { return UUID.fromString(value).toString().equals(value); } catch (Exception invalid) { return false; } }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-fA-F]{64}"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void invalid(String id, String reason) { throw new IllegalStateException("Recovered model turn " + id + ": " + reason); }
}


