package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRetrievalConfidence;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists one fenced patent-search result transaction. */
final class ProductPatentSearchPersistence {
    private final JdbcTemplate db;
    private final ProductPatentSearchEvidenceCodec evidence;
    private final ProductRetentionPolicy retention;

    ProductPatentSearchPersistence(JdbcTemplate db, ProductPatentSearchEvidenceCodec evidence,
            ProductRetentionPolicy retention) {
        this.db = db;
        this.evidence = evidence;
        this.retention = retention;
    }

    void persist(ProductWorkerAccess.Authorized worker, String organization, long taskVersion,
            String inputSha, String originalQuery, List<String> sourceIds,
            List<ProductSourceAvailability.Active> activeSources,
            String filtersJson, String filterVersion,
            String requestId, String requestSha, String attemptId, long fencingToken,
            String resultSetId, List<ProductPatentSearchExecution.Member> members,
            int candidateCount, String normalizedQuery, PatentQueryExpansion.Plan queryPlan,
            String method, String ordering,
            String rerankModelVersion, String rerankStatus,
            PatentRetrievalConfidence.Assessment confidence,
            boolean truncated, String resultSha, Instant now, Instant expiresAt) {
        String scopeSha = evidence.digest(String.join("\u0000", sourceIds), "TITLE",
                filterVersion, filtersJson);
        db.update("INSERT INTO con4_search_attempt(attempt_id,task_id,organization_id,task_version,"
                        + "input_revision,input_sha,actor_ref,request_sha,original_query,actual_query,scope_sha,"
                        + "method_version,status,retention_ref,retention_version,retention_evidence_sha,"
                        + "created_at,expires_at,fencing_token,query_plan_version,expansions_json,expansion_sha,"
                        + "rerank_model_version,rerank_status,filter_version,filters_json,filter_sha) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                attemptId, worker.taskId(), organization, taskVersion, worker.inputId(), inputSha,
                worker.serviceSubject(), requestSha, originalQuery, normalizedQuery, scopeSha,
                method, "SUCCEEDED", retention.reference(), retention.version(), retention.evidenceSha256(),
                Timestamp.from(now), Timestamp.from(expiresAt), fencingToken, queryPlan.version(),
                evidence.json(queryPlan.expansions()),
                evidence.digest(queryPlan.version(), evidence.json(queryPlan.expansions())),
                rerankModelVersion, rerankStatus, filterVersion, filtersJson,
                evidence.digest(filterVersion, filtersJson));
        for (var source : activeSources)
            db.update("INSERT INTO con4_search_source VALUES(?,?,?,?,?,?,?,NULL)", attemptId,
                    source.sourceId(), source.contractVersion(), source.decisionId(), source.epoch(),
                    evidence.digest(source.manifestSha(), source.recordsSha()), "SUCCEEDED");
        db.update("INSERT INTO con4_result_set(result_set_id,attempt_id,ordering_version,truncated,"
                        + "total_reliability,result_sha,candidate_count,confidence_status,"
                        + "confidence_rule_version,confidence_reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                resultSetId, attemptId, ordering, truncated ? 1 : 0,
                truncated ? "LOWER_BOUND" : "EXACT", resultSha, candidateCount,
                confidence.status().name(), confidence.ruleVersion(), confidence.reason());
        for (var member : members)
            db.update("INSERT INTO con4_result_member(result_set_id,attempt_id,member_id,ordinal,source_id,"
                            + "contract_version,document_id,document_version,source_version,raw_sha,locator_ref,"
                            + "match_fields,match_sha,lexical_score,matched_terms_json,field_scores_json,"
                            + "lexical_rank,semantic_score,semantic_rank,fusion_score,rerank_score,rerank_rank) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", resultSetId, attemptId,
                    member.memberId(), member.ordinal(), member.sourceId(), contract(activeSources, member.sourceId()),
                    member.documentId(), member.documentVersion(), member.sourceVersion(), member.rawSha(),
                    member.locatorRef(), "TITLE", evidence.digest(member.documentId(), member.documentVersion(), "TITLE"),
                    member.lexicalScore(), evidence.json(member.matchedTerms()), evidence.json(member.fieldScores()),
                    member.lexicalRank(), member.semanticScore(), member.semanticRank(), member.fusionScore(),
                    member.rerankScore(), member.rerankRank());
        db.update("INSERT INTO con4_search_request VALUES(?,?,?,?,?)", requestId, requestSha,
                attemptId, resultSetId, Timestamp.from(now));
    }

    private static String contract(List<ProductSourceAvailability.Active> sources, String sourceId) {
        return sources.stream().filter(source -> source.sourceId().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Result source escaped approved scope"))
                .contractVersion();
    }
}
