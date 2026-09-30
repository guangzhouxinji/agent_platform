package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductSourceResultSchemaTest {
    @Test void resultsRequirePersistedTaskAndReverseLeavesPredecessors() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-results-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        for (String migration : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V036__product_source_candidates.sql",
                "V037__product_source_admission.sql", "V038__product_task_source_scope.sql",
                "V039__product_source_verification.sql", "V040__product_source_results.sql"})
            script(source, "db/dm8/" + migration);
        JdbcTemplate db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_execution_lease(attempt_id VARCHAR(36) UNIQUE)");
        script(source, "db/dm8/V063__product_search_execution_binding.sql");
        script(source, "db/dm8/V064__product_search_ranking_evidence.sql");
        script(source, "db/dm8/V065__product_search_stable_cursor.sql");
        script(source, "db/dm8/V067__product_search_semantic_index.sql");
        script(source, "db/dm8/V068__product_search_hybrid_evidence.sql");
        script(source, "db/dm8/V069__product_search_query_expansion.sql");
        script(source, "db/dm8/V070__product_semantic_vector_snapshot_binding.sql");
        script(source, "db/dm8/V071__product_search_rerank_evidence.sql");
        script(source, "db/dm8/V073__product_search_filter_evidence.sql");
        script(source, "db/dm8/V074__product_search_confidence.sql");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_search_attempt", Integer.class)).isZero();
        assertThatThrownBy(() -> db.update("INSERT INTO con4_search_attempt(attempt_id,task_id,"
                        + "organization_id,task_version,input_revision,input_sha,actor_ref,request_sha,"
                        + "original_query,actual_query,scope_sha,method_version,status,retention_ref,"
                        + "retention_version,retention_evidence_sha,created_at,expires_at,fencing_token) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "organization-1", 1, "revision-1", "a".repeat(64),
                "member", "a".repeat(64), "query", "query", "a".repeat(64), "literal-v1",
                "SUCCEEDED", "retention", 1, "a".repeat(64),
                Timestamp.from(Instant.parse("2026-09-27T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-28T00:00:00Z")), 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO con4_result_member(result_set_id,attempt_id,member_id,"
                        + "ordinal,source_id,contract_version,document_id,document_version,source_version,"
                        + "raw_sha,locator_ref,match_fields,match_sha) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                0, "source-1", "contract-1",
                "document-1", "version-1", "capture-1", "a".repeat(64), "pointer", "TITLE",
                "a".repeat(64))).isInstanceOf(RuntimeException.class);
        String sha = "a".repeat(64);
        Timestamp created = Timestamp.from(Instant.parse("2026-09-27T00:00:00Z"));
        Timestamp expiry = Timestamp.from(Instant.parse("2026-09-29T00:00:00Z"));
        String task = UUID.randomUUID().toString();
        String decision = UUID.randomUUID().toString();
        String attempt = UUID.randomUUID().toString();
        String otherAttempt = UUID.randomUUID().toString();
        String result = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES('issuer','member','member','hash','ACTIVE',1,?)",
                created);
        db.update("INSERT INTO con3_organization(organization_id,name,status,version,created_at) "
                + "VALUES('organization-1','organization','ACTIVE',1,?)", created);
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                + "VALUES(?,'organization-1','issuer','member','AWAITING_GOAL',1,?,'retention',1,?,?)",
                task, expiry, sha, created);
        db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,"
                + "declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,retention_version,"
                + "retention_evidence_sha,collected_at,valid_until,status,row_version,created_at) "
                + "VALUES('source-1','contract-1','capture-1','{}',?,'rights',?,'retention',1,"
                + "?,? ,?,'ACTIVE',2,?)", sha, sha, sha, created, expiry, created);
        db.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,rights_ref,"
                + "rights_sha,capability_ref,capability_sha,responsible_party,maintainer_ref,"
                + "coverage_sha,valid_until,decided_at) VALUES(?,'source-1','contract-1',"
                + "'rights',?,'capability',?,'responsible','maintainer',?,?,?)",
                decision, sha, sha, sha, expiry, created);
        db.update("INSERT INTO con3_execution_lease VALUES(?)", attempt);
        db.update("INSERT INTO con3_execution_lease VALUES(?)", otherAttempt);
        for (String id : new String[] {attempt, otherAttempt}) {
            db.update("INSERT INTO con4_search_attempt(attempt_id,task_id,organization_id,task_version,"
                    + "input_revision,input_sha,actor_ref,request_sha,original_query,actual_query,"
                    + "scope_sha,method_version,status,retention_ref,retention_version,"
                    + "retention_evidence_sha,created_at,expires_at,fencing_token) VALUES(?,?,'organization-1',1,"
                    + "'revision-1',?,'member',?,'query','query',?,'literal-v1','SUCCEEDED',"
                    + "'retention',1,?,?,?,1)", id, task, sha, sha, sha, sha, created, expiry);
        }
        db.update("INSERT INTO con4_search_source(attempt_id,source_id,contract_version,decision_id,"
                + "source_epoch,coverage_sha,status) VALUES(?,'source-1','contract-1',?,1,?,'SUCCEEDED')",
                attempt, decision, sha);
        db.update("INSERT INTO con4_result_set(result_set_id,attempt_id,ordering_version,truncated,"
                + "total_reliability,result_sha) VALUES(?,?,'document-id-v1',0,'UNKNOWN',?)",
                result, attempt, sha);
        assertThatThrownBy(() -> db.update("INSERT INTO con4_result_member(result_set_id,attempt_id,"
                        + "member_id,ordinal,source_id,contract_version,document_id,document_version,"
                        + "source_version,raw_sha,locator_ref,match_fields,match_sha) "
                        + "VALUES(?,?,?,0,'source-1','contract-1','document-1','version-1',"
                        + "'capture-1',?,'pointer','TITLE',?)", result, otherAttempt,
                UUID.randomUUID().toString(), sha, sha)).isInstanceOf(RuntimeException.class);
        db.update("INSERT INTO con4_result_member(result_set_id,attempt_id,member_id,ordinal,source_id,"
                + "contract_version,document_id,document_version,source_version,raw_sha,locator_ref,"
                + "match_fields,match_sha) VALUES(?,?,?,0,'source-1','contract-1','document-1',"
                + "'version-1','capture-1',?,'pointer','TITLE',?)", result, attempt,
                UUID.randomUUID().toString(), sha, sha);
        assertThatThrownBy(() -> db.update("INSERT INTO con4_search_request(request_id,request_sha,"
                        + "attempt_id,result_set_id,created_at) VALUES('request-1',?,?,?,?)",
                sha, otherAttempt, result, created)).isInstanceOf(RuntimeException.class);
        db.update("INSERT INTO con4_search_request(request_id,request_sha,attempt_id,result_set_id,"
                + "created_at) VALUES('request-1',?,?,?,?)", sha, attempt, result, created);
        assertThat(db.queryForObject("SELECT candidate_count FROM con4_result_set WHERE result_set_id=?",
                Integer.class, result)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME="
                + "'CON4_RESULT_MEMBER' AND COLUMN_NAME='LEXICAL_SCORE'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME="
                + "'CON4_RESULT_CURSOR' AND COLUMN_NAME='PAGE_SIZE'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT confidence_status FROM con4_result_set WHERE result_set_id=?",
                String.class, result)).isEqualTo("NORMAL");
        script(source, "db/dm8/U074__product_search_confidence.sql");
        script(source, "db/dm8/U073__product_search_filter_evidence.sql");
        script(source, "db/dm8/U071__product_search_rerank_evidence.sql");
        script(source, "db/dm8/U070__product_semantic_vector_snapshot_binding.sql");
        script(source, "db/dm8/U069__product_search_query_expansion.sql");
        script(source, "db/dm8/U068__product_search_hybrid_evidence.sql");
        script(source, "db/dm8/U067__product_search_semantic_index.sql");
        script(source, "db/dm8/U065__product_search_stable_cursor.sql");
        script(source, "db/dm8/U064__product_search_ranking_evidence.sql");
        script(source, "db/dm8/U063__product_search_execution_binding.sql");
        script(source, "db/dm8/U040__product_source_results.sql");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME='CON4_SEARCH_ATTEMPT'", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME='CON4_SOURCE_VERIFICATION'", Integer.class)).isEqualTo(1);
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
