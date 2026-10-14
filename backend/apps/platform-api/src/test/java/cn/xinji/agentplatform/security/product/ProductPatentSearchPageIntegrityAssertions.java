package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Controlled corruption of a real-capture C result, not formal identity or deployment evidence. */
final class ProductPatentSearchPageIntegrityAssertions {
    private ProductPatentSearchPageIntegrityAssertions() { }
    static void verify(ProductPatentSearchExecution service, JdbcTemplate db, Jwt jwt,
            ProductPatentSearchExecution.Receipt receipt) {
        String member = db.queryForObject("SELECT member_id FROM con4_result_member "
                + "WHERE result_set_id=? AND ordinal=?", String.class,
                receipt.resultSetId(), receipt.resultCount() - 1);
        Double original = db.queryForObject("SELECT lexical_score FROM con4_result_member "
                + "WHERE member_id=?", Double.class, member);
        int cursors = db.queryForObject("SELECT COUNT(*) FROM con4_result_cursor", Integer.class);
        try {
            db.update("UPDATE con4_result_member SET lexical_score=? WHERE member_id=?",
                    original == null ? 1.0 : original + 1.0, member);
            // The last candidate can be outside this page. Verify the complete persisted result.
            rejected(service, jwt, receipt, "Historical result evidence integrity differs");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_result_cursor", Integer.class))
                    .isEqualTo(cursors);
        } finally {
            db.update("UPDATE con4_result_member SET lexical_score=? WHERE member_id=?", original, member);
        }
        try {
            db.update("UPDATE con4_result_set SET result_sha=? WHERE result_set_id=?",
                    "0".repeat(64), receipt.resultSetId());
            rejected(service, jwt, receipt, "Historical result evidence integrity differs");
        } finally {
            db.update("UPDATE con4_result_set SET result_sha=? WHERE result_set_id=?",
                    receipt.resultSha(), receipt.resultSetId());
        }
        try {
            db.update("UPDATE con4_result_set SET digest_version='RESULT_DIGEST_PRE_STORAGE/v1' "
                    + "WHERE result_set_id=?", receipt.resultSetId());
            rejected(service, jwt, receipt, "Committed search result integrity differs");
        } finally {
            db.update("UPDATE con4_result_set SET digest_version=? WHERE result_set_id=?",
                    receipt.digestVersion(), receipt.resultSetId());
        }
        assertThat(service.read("task", receipt.resultSetId(), 20, null, jwt).resultSha())
                .isEqualTo(receipt.resultSha());
    }
    private static void rejected(ProductPatentSearchExecution service, Jwt jwt,
            ProductPatentSearchExecution.Receipt receipt, String expectedIntegrityFailure) {
        assertThatThrownBy(() -> service.read("task", receipt.resultSetId(), 20, null, jwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedIntegrityFailure);
    }
}
