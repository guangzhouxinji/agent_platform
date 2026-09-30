package cn.xinji.agentplatform.security.product;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp; import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
final class ProductPatentSearchExecution {
    record Receipt(String attemptId, long fencingToken, String resultSetId, String methodVersion, int resultCount,
                   int candidateCount, boolean truncated, String resultSha,
                   String confidenceStatus, String confidenceRuleVersion, String confidenceReason) { }
    record Member(String memberId, long ordinal, String sourceId, String documentId,
                  String documentVersion, String sourceVersion, String rawSha,
                  String locatorRef, Set<String> matchFields, List<String> matchedTerms,
                  Double lexicalScore, Map<String, Double> fieldScores, Integer lexicalRank,
                  Double semanticScore, Integer semanticRank, Double fusionScore,
                  Double rerankScore, Integer rerankRank) { }
    record Page(String resultSetId, String attemptId, String methodVersion, String resultSha, boolean truncated,
                int candidateCount, int pageSize, String nextCursor,
                String confidenceStatus, String confidenceRuleVersion, String confidenceReason,
                List<Member> members) { }
    private record Input(String organization, long taskVersion, String inputSha, String query,
                         List<String> sources, List<String> fields, String filtersJson,
                         Instant expiresAt) { }
    private record Prior(String requestSha, String attemptId, long token, String resultSetId) { }
    private final JdbcTemplate db;
    private final ProductWorkerAccess workers;
    private final ProductTaskWorkerExecution execution;
    private final ProductCollectedPatentTitles titles;
    private final ProductSourceAvailability availability;
    private final ProductSourceWatermark sourceWatermark;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess consultationAccess;
    private final ProductRetentionPolicy retention;
    private final ProductPatentSearchEvidenceCodec evidence;
    private final ProductPatentSearchPersistence persistence;
    private final ProductPatentSearchCursor cursors;
    private final ProductSemanticIndex semantic;
    private final PatentReranker reranker;
    private final PatentQueryExpansion expansion = new PatentQueryExpansion();
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxResults;
    ProductPatentSearchExecution(JdbcTemplate db, ProductWorkerAccess workers,
            ProductTaskWorkerExecution execution, ProductCollectedPatentTitles titles,
            ProductSourceAvailability availability, ProductSourceWatermark sourceWatermark,
            ProductTaskDirectory tasks, ProductConsultationAccess consultationAccess,
            ProductRetentionPolicy retention, ObjectMapper mapper, ProductPatentSearchCursor cursors,
            Clock clock, int maxResults) {
        this(db, workers, execution, titles, availability, sourceWatermark, tasks,
                consultationAccess, retention, mapper, cursors, null, null, clock, maxResults);
    }
    ProductPatentSearchExecution(JdbcTemplate db, ProductWorkerAccess workers,
            ProductTaskWorkerExecution execution, ProductCollectedPatentTitles titles,
            ProductSourceAvailability availability, ProductSourceWatermark sourceWatermark,
            ProductTaskDirectory tasks, ProductConsultationAccess consultationAccess,
            ProductRetentionPolicy retention, ObjectMapper mapper, ProductPatentSearchCursor cursors,
            ProductSemanticIndex semantic, Clock clock, int maxResults) {
        this(db, workers, execution, titles, availability, sourceWatermark, tasks,
                consultationAccess, retention, mapper, cursors, semantic, null, clock, maxResults);
    }
    ProductPatentSearchExecution(JdbcTemplate db, ProductWorkerAccess workers,
            ProductTaskWorkerExecution execution, ProductCollectedPatentTitles titles,
            ProductSourceAvailability availability, ProductSourceWatermark sourceWatermark,
            ProductTaskDirectory tasks, ProductConsultationAccess consultationAccess,
            ProductRetentionPolicy retention, ObjectMapper mapper, ProductPatentSearchCursor cursors,
            ProductSemanticIndex semantic, PatentReranker reranker, Clock clock, int maxResults) {
        if (maxResults < 1) throw new IllegalArgumentException("Positive search result limit required");
        this.db = db;
        this.workers = workers;
        this.execution = execution;
        this.titles = titles;
        this.availability = availability;
        this.sourceWatermark = sourceWatermark;
        this.tasks = tasks;
        this.consultationAccess = consultationAccess;
        this.retention = retention;
        this.evidence = new ProductPatentSearchEvidenceCodec(mapper);
        this.persistence = new ProductPatentSearchPersistence(db, evidence, retention);
        this.cursors = cursors;
        this.semantic = semantic;
        this.reranker = reranker;
        this.mapper = mapper;
        this.clock = clock;
        this.maxResults = maxResults;
        db.queryForObject("SELECT COUNT(*) FROM con4_search_request", Integer.class);
    }
    Receipt execute(HttpServletRequest request, String taskId, String inputId, String delegationId,
            String attemptId, long fencingToken, String requestId) {
        requireIdentity(taskId, inputId, delegationId, attemptId, fencingToken, requestId);
        ProductWorkerAccess.Authorized worker = workers.require(request, taskId, inputId, delegationId);
        Input input = input(taskId, inputId);
        ProductPatentSearchFilters filters = ProductPatentSearchFilters.parse(mapper, input.filtersJson());
        var requiredFields = new java.util.LinkedHashSet<String>();
        requiredFields.add("TITLE");
        requiredFields.addAll(filters.requiredSourceFields());
        if (worker.taskVersion() != input.taskVersion()
                || input.fields().size() != requiredFields.size()
                || !requiredFields.equals(new java.util.LinkedHashSet<>(input.fields())))
            throw new IllegalStateException("Search fields do not match the current query plan");
        String requestSha = evidence.digest(taskId, inputId, delegationId, attemptId,
                Long.toString(fencingToken), requestId, input.inputSha());
        List<Prior> prior = prior(requestId);
        if (!prior.isEmpty()) {
            Prior stored = prior.getFirst();
            if (prior.size() != 1 || !stored.requestSha().equals(requestSha)
                    || !stored.attemptId().equals(attemptId) || stored.token() != fencingToken)
                throw new IllegalStateException("Search request conflicts with prior input");
            assertSourcesCurrent(stored.attemptId());
            workers.require(request, taskId, inputId, delegationId);
            return receipt(stored.resultSetId());
        }
        var documents = new ArrayList<PatentLexicalQuery.Document>();
        var activeSources = new ArrayList<ProductSourceAvailability.Active>();
        for (String sourceId : input.sources()) {
            var active = availability.require(sourceId, "TITLE", "QUERY");
            filters.requireAuthorized(availability, active);
            activeSources.add(active);
            documents.addAll(titles.load(active, filters));
        }
        var queryPlan = expansion.expand(input.query());
        PatentBm25fQuery.Result lexical = new PatentBm25fQuery().search(documents,
                queryPlan.retrievalQuery(), Set.of("TITLE"), Math.max(1000, maxResults));
        var ranked = new ProductPatentSearchRanking(evidence, semantic, reranker, maxResults)
                .rank(input.sources(), queryPlan, documents, lexical);
        List<Member> members = ranked.members();
        String resultSetId = UUID.randomUUID().toString();
        String resultSha = evidence.resultDigest(members, ranked.truncated());
        Instant now = clock.instant();
        Instant expiresAt = min(input.expiresAt(), now.plus(retention.maxAge()));
        var lease = lease(taskId, inputId, attemptId, fencingToken);
        List<Member> committedMembers = members; execution.publish(request, taskId, inputId, delegationId, lease,
            () -> persistence.persist(worker,
                input.organization(), input.taskVersion(), input.inputSha(), input.query(), input.sources(), activeSources,
                evidence.json(filters.evidence()), filters.version(),
                requestId, requestSha, attemptId, fencingToken,
                resultSetId, committedMembers, ranked.candidateCount(), ranked.normalizedQuery(), queryPlan,
                 ranked.method(), ranked.ordering(), ranked.rerankModelVersion(), ranked.rerankStatus(),
                 ranked.confidence(),
                 ranked.truncated(), resultSha, now, expiresAt));
        workers.require(request, taskId, inputId, delegationId);
        assertSourcesCurrent(attemptId);
        return receipt(resultSetId);
    }
    Page read(String taskId, String resultSetId, int pageSize, String cursor, Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        ProductTaskDirectory.Task task = tasks.find(taskId).orElseThrow(ProductPatentSearchExecution::notFound);
        if (!task.expiresAt().isAfter(clock.instant()) || !consultationAccess.permits(jwt,
                new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(), task.dataScope(),
                        "TASK", task.id(), task.authorizationVersion()), ProductAccessPolicy.Action.READ))
            throw notFound();
        List<String> attempts = db.query("SELECT a.attempt_id FROM con4_search_attempt a "
                        + "JOIN con4_result_set r ON r.attempt_id=a.attempt_id "
                        + "WHERE r.result_set_id=? AND a.task_id=? AND a.task_version=? AND a.expires_at>?",
                (rs, row) -> rs.getString(1), resultSetId, taskId, task.version(),
                Timestamp.from(clock.instant()));
        if (attempts.size() != 1) throw notFound();
        assertSourcesCurrent(attempts.getFirst());
        ProductTaskDirectory.Task after = tasks.find(taskId).orElseThrow(ProductPatentSearchExecution::notFound);
        if (!task.equals(after)) throw notFound();
        ProductPatentSearchCursor.Context context = cursorContext(resultSetId, jwt.getSubject());
        long nextOrdinal = cursors.resolve(cursor, context, pageSize);
        return page(resultSetId, pageSize, nextOrdinal, context);
    }
    private Input input(String taskId, String inputId) {
        List<Input> rows = db.query("SELECT t.organization_id,t.version,i.content_sha,i.query_text,"
                        + "i.source_scope_json,i.requested_fields_json,i.filters_json,i.expires_at FROM con3_task t "
                        + "JOIN con3_goal_current c ON c.task_id=t.task_id AND c.task_version=t.version "
                        + "JOIN con3_search_input i ON i.task_id=c.task_id AND i.goal_id=c.goal_id "
                        + "AND i.goal_revision=c.revision AND i.task_version=c.task_version "
                        + "WHERE t.task_id=? AND i.input_id=? AND t.state='IN_PROGRESS' AND t.expires_at>? "
                        + "AND i.expires_at>?", (rs, row) -> new Input(rs.getString(1), rs.getLong(2),
                                rs.getString(3), rs.getString(4), evidence.nonEmptyStringList(rs.getString(5)),
                                evidence.nonEmptyStringList(rs.getString(6)), rs.getString(7),
                                rs.getTimestamp(8).toInstant()),
                taskId, inputId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        if (rows.size() != 1) throw new IllegalStateException("Current confirmed search input missing");
        return rows.getFirst();
    }
    private ProductTaskExecutionFence.Lease lease(String taskId, String inputId,
            String attemptId, long token) {
        List<ProductTaskExecutionFence.Lease> rows = db.query("SELECT attempt_id,fencing_token,lease_until "
                        + "FROM con3_execution_lease WHERE task_id=? AND input_id=? AND attempt_id=? "
                        + "AND fencing_token=? AND status='ACTIVE' AND lease_until>?",
                (rs, row) -> new ProductTaskExecutionFence.Lease(rs.getString(1), rs.getLong(2),
                        rs.getTimestamp(3).toInstant()), taskId, inputId, attemptId, token,
                Timestamp.from(clock.instant()));
        if (rows.size() != 1) throw new IllegalStateException("Current execution fence missing");
        return rows.getFirst();
    }
    private void assertSourcesCurrent(String attemptId) {
        List<String> filterRows = db.query("SELECT filters_json FROM con4_search_attempt WHERE attempt_id=?",
                (rs, row) -> rs.getString(1), attemptId);
        if (filterRows.size() != 1) throw new IllegalStateException("Persisted search filter evidence missing");
        ProductPatentSearchFilters filters = ProductPatentSearchFilters.parse(mapper, filterRows.getFirst());
        List<Object[]> rows = db.query("SELECT source_id,contract_version,decision_id,source_epoch "
                        + "FROM con4_search_source WHERE attempt_id=? AND status='SUCCEEDED'",
                (rs, row) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)},
                attemptId);
        if (rows.isEmpty()) throw new IllegalStateException("Persisted search has no successful source");
        for (Object[] row : rows) {
            var active = availability.require((String) row[0], "TITLE", "QUERY");
            filters.requireAuthorized(availability, active);
            if (!active.contractVersion().equals(row[1]) || !active.decisionId().equals(row[2])
                    || active.epoch() != (long) row[3])
                throw new IllegalStateException("Persisted search source is stale");
            sourceWatermark.assertUnchanged(active.sourceId(), active.epoch());
        }
    }
    private List<Prior> prior(String requestId) {
        return db.query("SELECT r.request_sha,r.attempt_id,a.fencing_token,r.result_set_id "
                        + "FROM con4_search_request r JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id "
                        + "WHERE r.request_id=?", (rs, row) -> new Prior(rs.getString(1), rs.getString(2),
                                rs.getLong(3), rs.getString(4)), requestId);
    }
    private Receipt receipt(String resultSetId) {
        List<Receipt> rows = db.query("SELECT a.attempt_id,a.fencing_token,r.result_set_id,a.method_version,"
                        + "COUNT(m.member_id),r.candidate_count,r.truncated,r.result_sha,r.confidence_status,"
                        + "r.confidence_rule_version,r.confidence_reason FROM con4_result_set r "
                        + "JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id "
                        + "LEFT JOIN con4_result_member m ON m.result_set_id=r.result_set_id "
                        + "WHERE r.result_set_id=? GROUP BY a.attempt_id,a.fencing_token,r.result_set_id,"
                        + "a.method_version,r.candidate_count,r.truncated,r.result_sha,"
                        + "r.confidence_status,r.confidence_rule_version,r.confidence_reason",
                (rs, row) -> new Receipt(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7) == 1,
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)), resultSetId);
        if (rows.size() != 1) throw new IllegalStateException("Committed search receipt missing");
        return rows.getFirst();
    }
    private Page page(String resultSetId, int pageSize, long nextOrdinal,
            ProductPatentSearchCursor.Context context) {
        Receipt receipt = receipt(resultSetId);
        List<Member> rows = db.query("SELECT member_id,ordinal,source_id,document_id,document_version,"
                        + "source_version,raw_sha,locator_ref,match_fields,matched_terms_json,lexical_score,"
                        + "field_scores_json,lexical_rank,semantic_score,semantic_rank,fusion_score,"
                        + "rerank_score,rerank_rank "
                        + "FROM con4_result_member "
                        + "WHERE result_set_id=? ORDER BY ordinal",
                (rs, row) -> new Member(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), Set.of(rs.getString(9)), evidence.stringList(rs.getString(10)),
                        nullableDouble(rs, 11), evidence.scoreMap(rs.getString(12)), nullableInt(rs, 13),
                        nullableDouble(rs, 14), nullableInt(rs, 15), nullableDouble(rs, 16),
                        nullableDouble(rs, 17), nullableInt(rs, 18)), resultSetId);
        int start = Math.toIntExact(Math.min(nextOrdinal, rows.size()));
        int end = Math.min(rows.size(), start + pageSize);
        String nextCursor = end < rows.size() ? cursors.issue(context, pageSize, end) : null;
        return new Page(resultSetId, receipt.attemptId(), receipt.methodVersion(), receipt.resultSha(),
                receipt.truncated(), receipt.candidateCount(), pageSize, nextCursor,
                receipt.confidenceStatus(), receipt.confidenceRuleVersion(), receipt.confidenceReason(),
                List.copyOf(rows.subList(start, end)));
    }
    private ProductPatentSearchCursor.Context cursorContext(String resultSetId, String subjectId) {
        List<ProductPatentSearchCursor.Context> rows = db.query("SELECT r.result_set_id,a.method_version,"
                        + "a.task_version,a.input_revision,a.scope_sha,a.expires_at FROM con4_result_set r "
                        + "JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id WHERE r.result_set_id=?",
                (rs, row) -> new ProductPatentSearchCursor.Context(rs.getString(1), rs.getString(2),
                        subjectId, rs.getLong(3), rs.getString(4), rs.getString(5),
                        sourceEpochSha(rs.getString(1)), rs.getTimestamp(6).toInstant()), resultSetId);
        if (rows.size() != 1) throw notFound();
        return rows.getFirst();
    }
    private String sourceEpochSha(String resultSetId) {
        List<String> values = db.query("SELECT s.source_id,s.contract_version,s.decision_id,s.source_epoch "
                        + "FROM con4_search_source s JOIN con4_result_set r ON r.attempt_id=s.attempt_id "
                        + "WHERE r.result_set_id=? ORDER BY s.source_id", (rs, row) -> rs.getString(1)
                                + "\u0000" + rs.getString(2) + "\u0000" + rs.getString(3)
                                + "\u0000" + rs.getLong(4), resultSetId);
        if (values.isEmpty()) throw new IllegalStateException("Result source evidence missing");
        return evidence.digest(values.toArray(String[]::new));
    }
    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static Double nullableDouble(java.sql.ResultSet rows, int column) throws java.sql.SQLException {
        double value = rows.getDouble(column);
        return rows.wasNull() ? null : value;
    }
    private static Integer nullableInt(java.sql.ResultSet rows, int column) throws java.sql.SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static void requireIdentity(String taskId, String inputId, String delegationId,
            String attemptId, long token, String requestId) {
        if (blank(taskId) || blank(inputId) || blank(delegationId) || blank(attemptId) || token < 1
                || requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact search execution identity required");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
