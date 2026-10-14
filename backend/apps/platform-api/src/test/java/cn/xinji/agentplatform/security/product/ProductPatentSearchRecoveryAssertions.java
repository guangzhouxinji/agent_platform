package cn.xinji.agentplatform.security.product;

import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Controlled corruption checks on the real-capture C transaction, not a deployment acceptance. */
final class ProductPatentSearchRecoveryAssertions {
    static void verify(JdbcTemplate db, Path root, ProductSourceAvailability.Active active) {
        db.execute("CREATE TABLE IF NOT EXISTS con4_source_verification(source_id VARCHAR(200),contract_version VARCHAR(100),"
                + "source_version VARCHAR(100),manifest_ref VARCHAR(500),manifest_sha VARCHAR(64),records_sha VARCHAR(64))");
        if(db.queryForObject("SELECT COUNT(*) FROM con4_source_verification WHERE source_id=? AND contract_version=?",Integer.class,active.sourceId(),active.contractVersion())==0)
        db.update("INSERT INTO con4_source_verification VALUES(?,?,?,?,?,?)",active.sourceId(),active.contractVersion(),
                active.sourceVersion(),active.manifestRef(),active.manifestSha(),active.recordsSha());
        var verifier=new ProductPatentSearchRecoveryVerifier(db,root,10_000_000);
        assertThatCode(verifier::verify).doesNotThrowAnyException();
        db.update("UPDATE con4_result_set SET digest_version='RESULT_DIGEST_PRE_STORAGE/v1'");
        // A forced legacy encoding must not pass: no original legacy model artifacts are supplied
        // for the explicitly mechanical current-build fixture. Keep the hard recovery rejection.
        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_result_set SET digest_version='RESULT_DIGEST_DECIMAL_12/v2'");
        String sha=db.queryForObject("SELECT result_sha FROM con4_result_set",String.class);
        db.update("UPDATE con4_result_set SET result_sha=?","0".repeat(64));
        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_result_set SET result_sha=?",sha);
        String id=db.queryForObject("SELECT member_id FROM con4_result_member WHERE ordinal=0",String.class);
        db.update("UPDATE con4_result_member SET ordinal=999 WHERE member_id=?",id);
        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_result_member SET ordinal=0 WHERE member_id=?",id);
        String selection=db.queryForObject("SELECT selection_id FROM con4_selection_revision WHERE revision=1",String.class);
        String raw=db.queryForObject("SELECT raw_sha FROM con4_selection_member WHERE selection_id=?",String.class,selection);
        db.update("UPDATE con4_selection_member SET raw_sha=? WHERE selection_id=?","0".repeat(64),selection);
        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_selection_member SET raw_sha=? WHERE selection_id=?",raw,selection);
        String locator=db.queryForObject("SELECT locator_ref FROM con4_result_member WHERE member_id=?",String.class,id);
        db.update("UPDATE con4_result_member SET locator_ref=? WHERE member_id=?","capture/raw/wrong.json#title",id);
        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_result_member SET locator_ref=? WHERE member_id=?",locator,id);
        db.update("UPDATE con4_search_request SET request_sha=?","0".repeat(64));
        assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_search_request SET request_sha=(SELECT request_sha FROM con4_search_attempt)");
        assertThatCode(verifier::verify).doesNotThrowAnyException();
        db.update("UPDATE con3_task SET version=3");
        assertThatCode(verifier::verify).doesNotThrowAnyException();
        db.update("UPDATE con3_task SET version=2");
    }
}

