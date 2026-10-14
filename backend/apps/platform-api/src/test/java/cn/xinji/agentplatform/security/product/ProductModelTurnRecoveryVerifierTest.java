package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;

/** Engineering-owned lawful associations and corrupt-history cases; NOT real historical samples or current access grants. */
class ProductModelTurnRecoveryVerifierTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private record Scenario(JdbcTemplate db, ProductModelTurnRecoveryVerifier verifier, String turn, String task, String result, String member) { }

    @Test void expiredProposalAndExplanationRestoreWithoutChangingRowsOrCurrentVersion() throws Exception {
        var proposal = scenario(false); var explanation = scenario(true);
        String retained=explanation.db().queryForObject("SELECT request_json FROM con3_model_turn",String.class);
        String originalSha=explanation.db().queryForObject("SELECT result_sha FROM con4_result_set",String.class);
        new ProductHistoricalResultIsolation(explanation.db()).require(explanation.result());
        assertThat(proposal.verifier().verify()).isEqualTo(1);
        assertThat(explanation.verifier().verify()).isEqualTo(1);
        assertThat(explanation.db().queryForObject("SELECT version FROM con3_task", Long.class)).isEqualTo(4);
        assertThat(explanation.db().queryForObject("SELECT COUNT(*) FROM con3_model_turn", Long.class)).isEqualTo(1);
        assertThat(explanation.db().queryForObject("SELECT request_json FROM con3_model_turn",String.class)).isEqualTo(retained);
        assertThat(explanation.db().queryForObject("SELECT result_sha FROM con4_result_set",String.class)).isEqualTo(originalSha);
        assertThat(explanation.db().queryForObject("SELECT COUNT(*) FROM con4_search_source",Long.class)).isEqualTo(1);
    }
    @Test void knownHistoricalFailureRemainsFailureEvenWhenContentWouldNowPass() throws Exception {
        var value = scenario(false);
        value.db().update("UPDATE con3_model_turn SET output_json=?", "{\"failureCode\":\"MODEL_OUTPUT_VALIDATION_FAILED\"}");
        assertThat(value.verifier().verify()).isEqualTo(1);
        assertThat(value.db().queryForObject("SELECT output_json FROM con3_model_turn", String.class)).contains("failureCode");
    }
    @Test void futureTaskVersionAndExpiryBeyondTaskReject() throws Exception {
        var value = scenario(false);
        value.db().update("UPDATE con3_model_turn SET task_version=5");
        rejected(value, "TASK_VERSION_OR_EXPIRY");
        value.db().update("UPDATE con3_model_turn SET task_version=2,expires_at=?",
                Timestamp.from(Instant.parse("2026-10-02T00:00:00Z")));
        rejected(value, "TASK_VERSION_OR_EXPIRY");
    }
    @Test void wrongResultTaskVersionAndShaReject() throws Exception {
        var value = scenario(true);
        value.db().update("UPDATE con4_search_attempt SET task_version=3"); rejected(value, "RESULT_BINDING");
        value.db().update("UPDATE con4_search_attempt SET task_version=2,task_id=?", UUID.randomUUID().toString());
        rejected(value, "RESULT_BINDING");
        value.db().update("UPDATE con4_search_attempt SET task_id=?", value.task());
        value.db().update("UPDATE con3_model_turn SET result_sha=?", "b".repeat(64)); rejected(value, "RESULT_BINDING");
    }
    @Test void alteredMemberDocumentVersionPageSizeAndTotalReject() throws Exception {
        for (String field : new String[]{"documentVersion", "memberId", "pageSize", "totalCandidates", "ordinal"}) {
            var value = scenario(true);
            var request = mapper.readTree(value.db().queryForObject("SELECT request_json FROM con3_model_turn", String.class));
            if (field.equals("pageSize") || field.equals("totalCandidates")) ((com.fasterxml.jackson.databind.node.ObjectNode) request).put(field, 2);
            else if (field.equals("ordinal")) ((com.fasterxml.jackson.databind.node.ObjectNode)request.path("members").get(0)).put(field, 9);
            else ((com.fasterxml.jackson.databind.node.ObjectNode)request.path("members").get(0)).put(field, "altered");
            value.db().update("UPDATE con3_model_turn SET request_json=?", mapper.writeValueAsString(request));
            assertThatThrownBy(value.verifier()::verify).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void rawProviderMissingWrongModelAndStoredOutputMismatchReject() throws Exception {
        var value = scenario(false);
        String original = value.db().queryForObject("SELECT raw_output FROM con3_model_turn", String.class);
        value.db().update("UPDATE con3_model_turn SET raw_output='{}'"); rejected(value, "PROVIDER_ORIGINAL");
        value.db().update("UPDATE con3_model_turn SET raw_output=?", original.replace("mechanical-provider", "different-provider"));
        rejected(value, "PROVIDER_ORIGINAL");
        value.db().update("UPDATE con3_model_turn SET raw_output=?,output_json=?", original, "{\"query\":\"changed\",\"questions\":[]}");
        rejected(value, "MODEL_OUTPUT_MISMATCH");
    }
    @Test void providerUnknownCitationAndUnknownFailureCodeReject() throws Exception {
        var value = scenario(true);
        value.db().update("UPDATE con3_model_turn SET raw_output=?", envelope("{\"answer\":\"mechanical evidence\",\"citations\":[\"invented\"]}"));
        rejected(value, "MODEL_OUTPUT_OR_CITATION");
        var failure = scenario(false);
        failure.db().update("UPDATE con3_model_turn SET output_json=?", "{\"failureCode\":\"APPROVED\"}");
        rejected(failure, "FAILURE_AUDIT");
    }
    @Test void duplicateJsonKeysTrailingInputAndWrongScopeReject() throws Exception {
        var value = scenario(false);
        String request = value.db().queryForObject("SELECT request_json FROM con3_model_turn", String.class);
        value.db().update("UPDATE con3_model_turn SET request_json=?", request + " {}"); rejected(value, "RETAINED_JSON");
        value.db().update("UPDATE con3_model_turn SET request_json=?", request.replace("\"kind\":\"PROPOSAL\"", "\"kind\":\"PROPOSAL\",\"kind\":\"PROPOSAL\""));
        rejected(value, "RETAINED_JSON");
        value.db().update("UPDATE con3_model_turn SET request_json=?", request.replace("PUBLIC_NONCONFIDENTIAL_RETRIEVAL_ONLY", "PRIVATE"));
        rejected(value, "MODEL_INPUT_SCOPE");
    }
    @Test void proposalCannotBorrowResultsAndUnknownPromptRejects() throws Exception {
        var value = scenario(false);
        value.db().update("UPDATE con3_model_turn SET result_set_id=?,result_sha=?", UUID.randomUUID().toString(), "a".repeat(64));
        rejected(value, "PROPOSAL_RESULT");
        value.db().update("UPDATE con3_model_turn SET result_set_id=NULL,result_sha=NULL,prompt_version='unknown'");
        rejected(value, "RETAINED_METADATA");
    }
    @Test void originalPolicyMutationRejectsWithoutPrintingContent() throws Exception {
        var value = scenario(false); Files.writeString(root.resolve("policy.json"), "SECRET_CONTENT");
        assertThatThrownBy(value.verifier()::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETENTION_ORIGINAL").hasMessageNotContaining("SECRET_CONTENT");
    }
    @Test void legacySchemaHasNoInventedModelRows() {
        assertThat(new ProductModelTurnRecoveryVerifier(database(), root.toAbsolutePath(), 1_000_000).verify()).isZero();
    }

    private void rejected(Scenario value, String reason) {
        assertThatThrownBy(value.verifier()::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(value.turn()).hasMessageContaining(reason);
    }
    private Scenario scenario(boolean explanation) throws Exception {
        var db = database();
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY,version BIGINT,expires_at TIMESTAMP)");
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V089__product_consultation_model_turn.sql"))
                .execute(db.getDataSource());
        searchSchema(db);
        String turn = UUID.randomUUID().toString(), task = UUID.randomUUID().toString(), result = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        db.update("INSERT INTO con3_task VALUES(?,?,?)", task, 4, Timestamp.from(created.plusSeconds(10800)));
        Files.writeString(root.resolve("policy.json"), "{\"purpose\":\"mechanical_component_only\"}");
        String policySha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("policy.json"))));
        var request = mapper.createObjectNode().put("kind", explanation ? "EXPLANATION" : "PROPOSAL")
                .put("scope", "PUBLIC_NONCONFIDENTIAL_RETRIEVAL_ONLY").put("goal", "mechanical component query");
        String output,resultSha=null;
        if (explanation) {
            resultSha=searchResult(db,task,result,member,created);
            request.put("pageSize", 1).put("totalCandidates", 1).putArray("members").addObject()
                    .put("memberId", member).put("documentId", "mechanical-document").put("documentVersion", "mechanical-version").put("ordinal", 0);
            output = "{\"answer\":\"mechanical evidence\",\"citations\":[\"" + member + "\"]}";
        } else { output = "{\"query\":\"mechanical component query\",\"questions\":[]}"; }
        db.update("INSERT INTO con3_model_turn VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", turn, task, 2,
                "mechanical-actor", explanation ? "EXPLANATION" : "PROPOSAL", "mechanical_request", "c".repeat(64),
                explanation ? result : null, resultSha, "mechanical-provider", "RETRIEVAL_DIALOGUE/v3",
                mapper.writeValueAsString(request), envelope(output), output, Timestamp.from(created), Timestamp.from(created.plusSeconds(7200)),
                "policy.json", 1, policySha);
        return new Scenario(db, new ProductModelTurnRecoveryVerifier(db, root.toAbsolutePath(), 1_000_000), turn, task, result, member);
    }
    /** Exact-publication lexical result needs no semantic build; original member/digest/source are fully recorded. */
    private String searchResult(JdbcTemplate db,String task,String result,String member,Instant created) throws Exception {
        String attempt=UUID.randomUUID().toString();
        byte[] bytes="ENGINEERING_OWN_MECHANICAL_PUBLICATION".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("mechanical-original.txt"),bytes);
        String rawSha=ProductVectorCodec.sha(bytes),locator="mechanical-original.txt#publication";
        var value=new ProductPatentSearchExecution.Member(member,0,"mechanical-source","mechanical-document",
                "mechanical-version","mechanical-source-version",rawSha,locator,Set.of("DOCUMENT_ID"),
                List.of("mechanical-document"),1.0,Map.of("DOCUMENT_ID",1.0),1,null,null,null,null,null);
        var codec=new ProductPatentSearchEvidenceCodec(mapper);
        String sha=codec.resultDigest(List.of(value),false);
        db.update("INSERT INTO con4_search_attempt(attempt_id,task_id,task_version,fencing_token,method_version,created_at,status) VALUES(?,?,?,?,?,?,?)",attempt,task,2,1,"EXACT_PUBLICATION/v1",Timestamp.from(created),"SUCCEEDED");
        db.update("INSERT INTO con4_result_set VALUES(?,?,?,?,?,?,?,?,?,?)",result,attempt,sha,1,0,"LOW_CONFIDENCE",
                "MECHANICAL_ONLY/v1","ENGINEERING_OWN_SAMPLE","EXACT","RESULT_DIGEST_DECIMAL_12/v2");
        db.update("INSERT INTO con4_search_source VALUES(?,?,?,?,?,?,?)",attempt,"mechanical-source","mechanical-contract",
                "mechanical-decision",7,"SUCCEEDED",null);
        db.update("INSERT INTO con4_result_member VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",result,member,
                "mechanical-document","mechanical-version",0,"mechanical-source","mechanical-source-version",rawSha,locator,
                "DOCUMENT_ID",codec.json(value.matchedTerms()),1.0,codec.json(value.fieldScores()),1,null,null,null,null,null);
        return sha;
    }
    private static void searchSchema(JdbcTemplate db) {
        db.execute("CREATE TABLE con4_search_attempt(attempt_id VARCHAR(36),task_id VARCHAR(36),task_version BIGINT,"
                +"fencing_token BIGINT,method_version VARCHAR,created_at TIMESTAMP,status VARCHAR)");
        db.execute("CREATE TABLE con4_result_set(result_set_id VARCHAR(36),attempt_id VARCHAR(36),result_sha VARCHAR(64),"
                +"candidate_count INT,truncated INT,confidence_status VARCHAR,confidence_rule_version VARCHAR,confidence_reason VARCHAR,"
                +"total_reliability VARCHAR,digest_version VARCHAR)");
        db.execute("CREATE TABLE con4_search_source(attempt_id VARCHAR(36),source_id VARCHAR,contract_version VARCHAR,"
                +"decision_id VARCHAR,source_epoch BIGINT,status VARCHAR,error_code VARCHAR)");
        db.execute("CREATE TABLE con4_result_member(result_set_id VARCHAR(36),member_id VARCHAR(36),document_id VARCHAR(50),"
                +"document_version VARCHAR(50),ordinal BIGINT,source_id VARCHAR,source_version VARCHAR,raw_sha VARCHAR,locator_ref VARCHAR,"
                +"match_fields VARCHAR,matched_terms_json VARCHAR,lexical_score DOUBLE,field_scores_json VARCHAR,lexical_rank INT,"
                +"semantic_score DOUBLE,semantic_rank INT,fusion_score DOUBLE,rerank_score DOUBLE,rerank_rank INT)");
    }
    @Test void alteredPersistedSearchDigestStillRejectsUnderProductionIsolation() throws Exception {
        var value=scenario(true);
        value.db().update("UPDATE con4_result_set SET result_sha=?","d".repeat(64));
        value.db().update("UPDATE con3_model_turn SET result_sha=?","d".repeat(64));
        assertThatThrownBy(value.verifier()::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Historical result evidence integrity differs");
    }
    private String envelope(String output) throws Exception {
        var value = mapper.createObjectNode().put("model", "mechanical-provider");
        value.putArray("choices").addObject().put("finish_reason", "stop").putObject("message").put("content", output);
        return mapper.writeValueAsString(value);
    }
    private JdbcTemplate database() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(source);
    }
}


