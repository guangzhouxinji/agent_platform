package cn.xinji.agentplatform.security.product;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** A FAILED lease alone cannot prove that an external model call is safe to repeat. */
final class ProductComparisonFailureProof {
    record Failure(String code) { }
    private record Invocation(String status, String configuration, String raw, String sha) { }
    private ProductComparisonFailureProof() { }
    static Failure inspect(JdbcTemplate db,ProductComparisonStartCommands.Command command) {
        if ("FAILED".equals(command.status()) && ProductComparisonHandoffStore.ERROR.equals(command.errorCode()))
            return ProductComparisonHandoffStore.proven(db,command) ? new Failure(ProductComparisonHandoffStore.ERROR) : null;
        if (command.attemptId()==null || command.status().equals("COMMITTED") || command.fencingToken()==null || command.leaseUntil()==null) return null;
        Long count=db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt WHERE attempt_id=? AND task_id=? AND comparison_id=? "
                + "AND candidate_id=? AND input_sha=? AND task_version=? AND cancel_epoch=? AND worker_subject=? AND delegation_id=? "
                + "AND fencing_token=? AND lease_until=? AND request_id=? AND status='FAILED'",Long.class,command.attemptId(),command.scope().task(),
                command.scope().comparison(),command.scope().candidate(),command.scope().inputSha(),command.scope().taskVersion(),command.scope().cancelEpoch(),
                command.managed().serviceSubject(),command.delegationId(),command.fencingToken(),java.sql.Timestamp.from(command.leaseUntil()),"start:"+command.commandId());
        return count!=null && count==1 ? inspect(db,command.attemptId(),command.managed().configurationVersion()) : null;
    }
    // Callers hold the Task lock and independently verify exact attempt/lease coordinates.
    static Failure inspect(JdbcTemplate db, String attempt, String configuration) {
        List<String> errors = db.queryForList("SELECT error_code FROM con5_candidate_attempt WHERE attempt_id=? AND status='FAILED'", String.class, attempt);
        if (errors.size() != 1) return null;
        return beforeFailure(db,attempt,configuration,errors.getFirst());
    }
    static Failure beforeFailure(JdbcTemplate db,String attempt,String configuration,String error) {
        var rows = db.query("SELECT status,configuration_version,response_json,response_sha FROM con5_matrix_invocation WHERE attempt_id=?",
                (rs, n) -> new Invocation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), attempt);
        // In this product every model dispatch first durably reserves an invocation.
        if (rows.isEmpty()) return new Failure("CANDIDATE_FAILED_BEFORE_MODEL_DISPATCH");
        if (rows.size() != 1) return null;
        var value = rows.getFirst();
        if (!"REJECTED".equals(value.status()) || (configuration != null && !configuration.equals(value.configuration()))) return null;
        if ("EXECUTION_WINDOW_INSUFFICIENT".equals(error) && value.raw() == null && value.sha() == null)
            return new Failure("EXECUTION_WINDOW_INSUFFICIENT");
        if ("MATRIX_RESPONSE_INVALID".equals(error) && value.raw() != null && value.sha() != null
                && value.sha().matches("[a-f0-9]{64}") && ProductComparisonJson.hash(value.raw()).equals(value.sha()))
            return new Failure("MATRIX_RESPONSE_INVALID");
        return null; // RESERVED/RECEIVED, ambiguous rejection or corrupted evidence must remain fenced.
    }
}
