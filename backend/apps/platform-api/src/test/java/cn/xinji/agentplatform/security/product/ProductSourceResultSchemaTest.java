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
        script(source, "db/dm8/V079__product_search_digest_version.sql");
        script(source, "db/dm8/V084__product_search_field_evidence.sql");
        script(source, "db/dm8/V076__product_search_selection.sql");
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
        assertThatThrownBy(() -> db.update("INSERT INTO con4_selection_revision(selection_id,task_id,revision,"
                        + "task_version,result_set_id,result_set_version,result_sha,actor_subject,request_id,"
                        + "request_sha,created_at,expires_at) VALUES(?,?,1,1,?,2,?,'member','key',?,?,?)",
                UUID.randomUUID().toString(), task, result, sha, sha, created, expiry))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO con4_search_field_scope(attempt_id,result_set_id,"
                        + "text_fields_json,models_json,scope_sha) VALUES(?,?,?,?,?)",attempt,result,
                "[\"ABSTRACT\",\"CLAIMS\"]","{}",sha)).isInstanceOf(RuntimeException.class);
        db.update("UPDATE con4_result_set SET digest_version='RESULT_DIGEST_FIELDS_DECIMAL_12/v3' WHERE result_set_id=?",result);
        db.update("INSERT INTO con4_search_field_scope(attempt_id,result_set_id,text_fields_json,models_json,scope_sha) "
                + "VALUES(?,?,?,?,?)",attempt,result,"[\"ABSTRACT\",\"CLAIMS\"]","{}",sha);
        assertThatThrownBy(() -> script(source,"db/dm8/U084__product_search_field_evidence.sql"))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_search_field_scope",Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> db.update("INSERT INTO con4_result_field_evidence(result_set_id,member_id,"
                + "fields_json,evidence_sha) VALUES(?,?,?,?)",result,"unknown-member","{}",sha))
                .isInstanceOf(RuntimeException.class);
        // Mechanical schema-row cleanup only; no product data or immutable evidence is being downgraded.
        db.update("DELETE FROM con4_search_field_scope WHERE attempt_id=?",attempt);
        verifyFieldStore(source,db,attempt,result,sha,decision,created.toInstant());
        db.update("UPDATE con4_result_set SET digest_version='RESULT_DIGEST_PRE_STORAGE/v1' WHERE result_set_id=?",result);
        script(source, "db/dm8/U084__product_search_field_evidence.sql");
        script(source, "db/dm8/U076__product_search_selection.sql");
        script(source, "db/dm8/U079__product_search_digest_version.sql");
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

    /** Storage/transaction fault C only. No Source ACTIVE, product identity or professional patent claim. */
    private static void verifyFieldStore(JdbcDataSource source,JdbcTemplate db,String attempt,String result,String sha,
            String decision,Instant executedAt) throws Exception {
        String method="HYBRID_FIELD_RRF_COMPLETE_CE/v1", order="COMPLETE_CE_SCORE_DESC_FIELD_RRF_DESC_DOCUMENT_KEY/v1";
        db.update("UPDATE con4_search_attempt SET method_version=?,rerank_model_version='complete-CE/v1',"
                + "rerank_status='SUCCEEDED' WHERE attempt_id=?",method,attempt);
        // New schema fixture pool has exactly one complete self-owned source document, not the schema default zero.
        db.update("UPDATE con4_result_set SET ordering_version=?,candidate_count=1,total_reliability='EXACT' WHERE result_set_id=?",order,result);
        var active=new ProductSourceAvailability.Active("source-1","contract-1","capture-1",decision,1,2,
                "engineering/field-manifest.json",sha,sha);
        var doc=new cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery.Document(
                active.sourceId(),"document-1","version-1",active.sourceVersion(),sha,"raw/doc#abstract",
                java.util.Map.of("ABSTRACT","self-owned battery engineering projection","CLAIMS","self-owned battery engineering claims"));
        var locators=java.util.Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims");
        var witness=ProductFieldWitnessEngineeringFixture.build(source,db,active,
                new cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence(doc,locators),
                java.util.Set.of("ABSTRACT","CLAIMS"),java.time.Clock.fixed(executedAt.minusSeconds(1),java.time.ZoneOffset.UTC));
        db.update("UPDATE con4_result_member SET locator_ref='raw/doc#abstract',match_fields='ABSTRACT,CLAIMS',"
                + "lexical_score=1,matched_terms_json='[\"battery\"]',field_scores_json='{\"ABSTRACT\":1}',lexical_rank=1,"
                + "semantic_score=?,semantic_rank=1,fusion_score=0.02,rerank_score=2,rerank_rank=1 WHERE result_set_id=?",1.0/61,result);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper(); var codec=new ProductPatentSearchEvidenceCodec(mapper);
        var members=ProductPatentSearchPages.members(db,codec,result); String member=members.getFirst().memberId();
        var facts=new ProductPatentFieldResultEvidence.Fields(java.util.Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims"),
                java.util.Map.of("ABSTRACT",new ProductPatentFieldResultEvidence.Signal(
                        witness.index().searchField(active.sourceId(),"ABSTRACT","battery",1).getFirst().semanticScore(),
                        1,"raw/doc#abstract",witness.models().get("ABSTRACT")),
                        "CLAIMS",new ProductPatentFieldResultEvidence.Signal(
                        witness.index().searchField(active.sourceId(),"CLAIMS","battery",1).getFirst().semanticScore(),
                        1,"raw/doc#claims",witness.models().get("CLAIMS"))));
        // Compare the contract's exact DECIMAL_12 persisted representation, not pre-encoding doubles.
        facts=mapper.convertValue(ProductPatentFieldResultEvidence.canonical(facts),ProductPatentFieldResultEvidence.Fields.class);
        var bundle=new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,
                java.util.List.of("ABSTRACT","CLAIMS"),witness.models(),
                method,order,"complete-CE/v1","SUCCEEDED",java.util.Map.of(member,facts));
        db.update("UPDATE con4_result_set SET result_sha=? WHERE result_set_id=?",
                ProductPatentFieldResultEvidence.digest(codec,bundle,members,false),result);
        var store=new ProductPatentFieldEvidencePersistence(db,mapper);
        assertThatThrownBy(() -> store.save(attempt,result,sha,bundle,members,false))
                .hasMessage("Field evidence requires the fenced business transaction");
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        assertThatThrownBy(() -> tx.execute(status -> {
            store.save(attempt,result,sha,bundle,members,false); throw new IllegalStateException("rollback component C");
        })).hasMessage("rollback component C");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_search_field_scope",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_result_field_evidence",Integer.class)).isZero();
        tx.execute(status -> { store.save(attempt,result,sha,bundle,members,false); return null; });
        assertThat(store.read(result)).isEqualTo(bundle);
        assertThatThrownBy(() -> tx.execute(status -> {
            store.save(attempt,result,sha,bundle,members,false); return null;
        })).isInstanceOf(RuntimeException.class);
        String original=db.queryForObject("SELECT fields_json FROM con4_result_field_evidence WHERE result_set_id=?",String.class,result);
        db.update("UPDATE con4_result_field_evidence SET fields_json='{}' WHERE result_set_id=?",result);
        assertThatThrownBy(() -> store.read(result)).hasMessage("Field member evidence digest differs");
        db.update("UPDATE con4_result_field_evidence SET fields_json=? WHERE result_set_id=?",original,result);
        assertThat(store.read(result)).isEqualTo(bundle);
        var receipt=new ProductPatentSearchReceipts(db).read(result);
        assertThat(receipt.candidateCount()).isEqualTo(1);
        assertThat(receipt.sourceOutcomes()).containsExactly(
                new ProductPatentSearchSourceBatch.Outcome(active.sourceId(),active.contractVersion(),active.decisionId(),
                        active.epoch(),"SUCCEEDED",null));
        assertThat(db.update("UPDATE con4_result_set SET candidate_count=0 WHERE result_set_id=?",result)).isEqualTo(1);
        assertThatThrownBy(()->new ProductPatentSearchReceipts(db).read(result))
                .isInstanceOf(IllegalStateException.class).hasMessage("Historical result evidence integrity differs");
        assertThat(db.update("UPDATE con4_result_set SET candidate_count=1 WHERE result_set_id=?",result)).isEqualTo(1);
        assertThat(new ProductPatentSearchReceipts(db).read(result)).isEqualTo(receipt);
        ProductPatentFieldPageAssertions.verify(db,mapper,result,bundle);
        // End this new ephemeral hybrid fixture before the unchanged exact-publication recovery helper
        // creates its own verification facts. This is test teardown, never historical source repair.
        assertThat(db.update("DELETE FROM con4_source_verification WHERE source_id=? AND contract_version=? "
                + "AND verifier_ref='SELF_OWNED_FIELD_WITNESS_ENGINEERING_NO_EXTERNAL_MODEL'",
                active.sourceId(),active.contractVersion())).isEqualTo(1);
        ProductPatentFieldRecoveryAssertions.verify(source,db,mapper,attempt,result);
        db.update("DELETE FROM con4_result_field_evidence WHERE result_set_id=?",result);
        db.update("DELETE FROM con4_search_field_scope WHERE attempt_id=?",attempt);
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
