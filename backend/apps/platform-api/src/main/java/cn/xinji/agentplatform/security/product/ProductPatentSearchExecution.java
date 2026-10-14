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
                   String confidenceStatus, String confidenceRuleVersion, String confidenceReason,
                   String status, String totalReliability,
                   List<ProductPatentSearchSourceBatch.Outcome> sourceOutcomes, String digestVersion) { }
    record Member(String memberId, long ordinal, String sourceId, String documentId,
                  String documentVersion, String sourceVersion, String rawSha,
                  String locatorRef, Set<String> matchFields, List<String> matchedTerms,
                  Double lexicalScore, Map<String, Double> fieldScores, Integer lexicalRank,
                  Double semanticScore, Integer semanticRank, Double fusionScore,
                  Double rerankScore, Integer rerankRank) { }
    record Page(String resultSetId, String attemptId, String methodVersion, String resultSha, boolean truncated,
                int candidateCount, int pageSize, String nextCursor,
                String confidenceStatus, String confidenceRuleVersion, String confidenceReason,
                List<Member> members, String status, String totalReliability,
                List<ProductPatentSearchSourceBatch.Outcome> sourceOutcomes,
                List<ProductPatentSearchCitations.Citation> citations, String digestVersion,
                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                ProductPatentFieldResultEvidence.Bundle fieldEvidence) {
        Page(String resultSetId,String attemptId,String methodVersion,String resultSha,boolean truncated,
                int candidateCount,int pageSize,String nextCursor,String confidenceStatus,String confidenceRuleVersion,
                String confidenceReason,List<Member> members,String status,String totalReliability,
                List<ProductPatentSearchSourceBatch.Outcome> sourceOutcomes,
                List<ProductPatentSearchCitations.Citation> citations,String digestVersion) {
            this(resultSetId,attemptId,methodVersion,resultSha,truncated,candidateCount,pageSize,nextCursor,
                    confidenceStatus,confidenceRuleVersion,confidenceReason,members,status,totalReliability,
                    sourceOutcomes,citations,digestVersion,null);
        }
    }
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
    private final PatentReranker fieldReranker;
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
                consultationAccess, retention, mapper, cursors, null, null, null, clock, maxResults);
    }
    ProductPatentSearchExecution(JdbcTemplate db, ProductWorkerAccess workers,
            ProductTaskWorkerExecution execution, ProductCollectedPatentTitles titles,
            ProductSourceAvailability availability, ProductSourceWatermark sourceWatermark,
            ProductTaskDirectory tasks, ProductConsultationAccess consultationAccess,
            ProductRetentionPolicy retention, ObjectMapper mapper, ProductPatentSearchCursor cursors,
            ProductSemanticIndex semantic, Clock clock, int maxResults) {
        this(db, workers, execution, titles, availability, sourceWatermark, tasks,
                consultationAccess, retention, mapper, cursors, semantic, null, null, clock, maxResults);
    }
    ProductPatentSearchExecution(JdbcTemplate db, ProductWorkerAccess workers,
            ProductTaskWorkerExecution execution, ProductCollectedPatentTitles titles,
            ProductSourceAvailability availability, ProductSourceWatermark sourceWatermark,
            ProductTaskDirectory tasks, ProductConsultationAccess consultationAccess,
            ProductRetentionPolicy retention, ObjectMapper mapper, ProductPatentSearchCursor cursors,
            ProductSemanticIndex semantic, PatentReranker reranker, PatentReranker fieldReranker, Clock clock, int maxResults) {
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
        this.fieldReranker = fieldReranker;
        this.mapper = mapper;
        this.clock = clock;
        this.maxResults = maxResults;
        db.queryForObject("SELECT COUNT(*) FROM con4_search_request", Integer.class);
    }
    Receipt execute(HttpServletRequest request, String taskId, String inputId, String delegationId,
            String attemptId, long fencingToken, String requestId) {
        try (var observation = new ProductPatentSearchObservation(mapper,taskId,inputId,attemptId,requestId)) {
            Receipt result=executeObserved(request,taskId,inputId,delegationId,attemptId,fencingToken,requestId,observation);
            observation.success(result);
            return result;
        }
    }
    private Receipt executeObserved(HttpServletRequest request, String taskId, String inputId, String delegationId,
            String attemptId, long fencingToken, String requestId, ProductPatentSearchObservation observation) {
        observation.stage(ProductPatentSearchObservation.Stage.AUTHORIZE);
        requireIdentity(taskId, inputId, delegationId, attemptId, fencingToken, requestId);
        ProductWorkerAccess.Authorized worker = workers.require(request, taskId, inputId, delegationId);
        Input input = input(taskId, inputId);
        ProductPatentSearchFilters filters = ProductPatentSearchFilters.parse(mapper, input.filtersJson());
        ProductPatentSearchFieldScope fieldScope;
        try { fieldScope=ProductPatentSearchFieldScope.from(input.fields(),filters); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Persisted search fields do not match the current query plan",invalid);
        }
        if (worker.taskVersion() != input.taskVersion())
            throw new IllegalStateException("Search fields do not match the current query plan");
        String requestSha = evidence.digest(taskId, inputId, delegationId, attemptId,
                Long.toString(fencingToken), requestId, input.inputSha());
        observation.stage(ProductPatentSearchObservation.Stage.REPLAY_CHECK);
        List<Prior> prior = prior(requestId);
        if (!prior.isEmpty()) {
            observation.replay();
            Prior stored = prior.getFirst();
            if (prior.size() != 1 || !stored.requestSha().equals(requestSha)
                    || !stored.attemptId().equals(attemptId) || stored.token() != fencingToken)
                throw new IllegalStateException("Search request conflicts with prior input");
            var scopes=db.query("SELECT scope_sha,filters_json,filter_version,filter_sha FROM con4_search_attempt WHERE attempt_id=?",
                    (rs,row)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},stored.attemptId());
            if(scopes.size()!=1 || !filters.version().equals(scopes.getFirst()[2])
                    || !filters.equals(ProductPatentSearchFilters.parse(mapper,scopes.getFirst()[1]))
                    || !evidence.digest(scopes.getFirst()[2],scopes.getFirst()[1]).equals(scopes.getFirst()[3])
                    || !scopes.getFirst()[0].equals(fieldScope.scopeSha(evidence,input.sources(),
                    scopes.getFirst()[2],scopes.getFirst()[1])))
                throw new IllegalStateException("Search request scope conflicts with committed input");
            observation.stage(ProductPatentSearchObservation.Stage.CURRENT_CHECK);
            assertSourcesCurrent(stored.attemptId());
            workers.require(request, taskId, inputId, delegationId);
            return receipt(stored.resultSetId());
        }
        observation.fresh();
        observation.stage(ProductPatentSearchObservation.Stage.SOURCE_CONTENT);
        var batches = new ProductPatentSearchSourceBatch();
        var loaded = fieldScope.legacyTitle() ? batches.load(input.sources(), filters, availability, titles)
                : batches.loadFields(input.sources(),filters,availability,titles,fieldScope.textFields());
        boolean exact = fieldScope.syntax(input.query()).exactNumber();
        if(semantic!=null && !exact) observation.stage(ProductPatentSearchObservation.Stage.SOURCE_INDEX);
        if(semantic!=null && !exact && fieldScope.legacyTitle())ProductTitleBuildProvenance.requireSchema(db);
        var batch = semantic == null || exact ? loaded : fieldScope.legacyTitle()
                ? batches.validateIndexes(loaded,semantic) : batches.validateIndexesFields(loaded,semantic,fieldScope.textFields());
        observation.sources(input.sources().size(),batch.successfulSources().size(),batch.failures().size());
        observation.sourceSnapshots(batch);
        observation.stage(ProductPatentSearchObservation.Stage.QUERY_PLAN);
        var fieldResult=fieldScope.legacyTitle()?null:new ProductPatentFieldSearchRanking(evidence,semantic,fieldReranker,maxResults)
                .rank(batch.snapshots().stream().filter(source->!batch.failures().containsKey(source.sourceId())).toList(),
                        input.query(),batch.fieldEvidence(),fieldScope.textFields());
        var prepared = fieldResult!=null ? fieldResult.queryPlan()
                : new cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryRetrieval()
                        .prepare(input.query(), batch.documents(), Math.max(1000, maxResults));
        var queryPlan = prepared.expansion();
        observation.queryPlan(queryPlan.version());
        observation.stage(ProductPatentSearchObservation.Stage.RETRIEVE_RANK);
        var ranked = fieldResult!=null?fieldResult.ranking():new ProductPatentSearchRanking(evidence, semantic, reranker, maxResults)
                .rank(batch.successfulSources(), queryPlan, prepared.documents(), prepared.lexical());
        observation.ranking(batch.documents().size(),prepared.lexical().candidateCount(),ranked);
        List<Member> members = ranked.members();
        String resultSetId = UUID.randomUUID().toString();
        String resultSha = evidence.resultDigest(members, ranked.truncated());
        Instant now = clock.instant();
        Instant expiresAt = min(input.expiresAt(), now.plus(retention.maxAge()));
        var titleWitnesses=fieldScope.legacyTitle() && semantic!=null && !exact ? ProductTitleBuildProvenance.capture(db,batch.snapshots(),batch.failures(),semantic.fieldModelVersion("TITLE"),now) : List.<ProductTitleBuildProvenance.Witness>of();
        observation.stage(ProductPatentSearchObservation.Stage.COMMIT);
        var lease = lease(taskId, inputId, attemptId, fencingToken);
        List<Member> committedMembers = members; execution.publish(request, taskId, inputId, delegationId, lease,
            () -> { if(!titleWitnesses.isEmpty() && !titleWitnesses.equals(ProductTitleBuildProvenance.capture(db,batch.snapshots(),batch.failures(),semantic.fieldModelVersion("TITLE"),clock.instant())))throw new IllegalStateException("TITLE_BUILD_CHANGED_BEFORE_COMMIT");
            if(fieldResult!=null) persistence.persistFields(worker,input.organization(),input.taskVersion(),input.inputSha(),
                input.query(),input.sources(),batch.snapshots(),batch.failures(),evidence.json(filters.evidence()),filters.version(),
                requestId,requestSha,attemptId,fencingToken,resultSetId,fieldResult,fieldScope,now,expiresAt,mapper);
            else persistence.persist(worker,
                input.organization(), input.taskVersion(), input.inputSha(), input.query(), input.sources(),
                batch.snapshots(), batch.failures(),
                evidence.json(filters.evidence()), filters.version(),
                requestId, requestSha, attemptId, fencingToken,
                resultSetId, committedMembers, ranked.candidateCount(), ranked.normalizedQuery(), queryPlan,
                 ranked.method(), ranked.ordering(), ranked.rerankModelVersion(), ranked.rerankStatus(),
                 batch.confidence(ranked.confidence()),
                 ranked.truncated(), resultSha, now, expiresAt);
            ProductTitleBuildProvenance.store(db,resultSetId,titleWitnesses); });
        observation.committed(resultSetId);
        observation.stage(ProductPatentSearchObservation.Stage.CURRENT_CHECK);
        workers.require(request, taskId, inputId, delegationId);
        assertSourcesCurrent(attemptId);
        return receipt(resultSetId);
    }
    Page read(String taskId, String resultSetId, int pageSize, String cursor, Jwt jwt) {
        try (var observation = new ProductPatentSearchObservation(mapper,taskId,null,null,null)) {
        observation.pageRead(resultSetId,pageSize);
        observation.stage(ProductPatentSearchObservation.Stage.AUTHORIZE);
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        ProductTaskDirectory.Task task = tasks.find(taskId).orElseThrow(ProductPatentSearchExecution::notFound);
        var facts = new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(), task.dataScope(),
                "TASK", task.id(), task.authorizationVersion());
        if (!task.expiresAt().isAfter(clock.instant()) || !consultationAccess.permits(jwt,
                facts, ProductAccessPolicy.Action.READ))
            throw notFound();
        observation.stage(ProductPatentSearchObservation.Stage.RESULT_BINDING);
        List<String> attempts = db.query("SELECT a.attempt_id FROM con4_search_attempt a "
                        + "JOIN con4_result_set r ON r.attempt_id=a.attempt_id "
                        + "WHERE r.result_set_id=? AND a.task_id=? AND a.task_version<=? AND a.expires_at>?",
                (rs, row) -> rs.getString(1), resultSetId, taskId, task.version(),
                Timestamp.from(clock.instant()));
        if (attempts.size() != 1) throw notFound();
        observation.stage(ProductPatentSearchObservation.Stage.SOURCE_CURRENT);
        var currentSources = assertSourcesCurrent(attempts.getFirst());
        ProductTaskDirectory.Task after = tasks.find(taskId).orElseThrow(ProductPatentSearchExecution::notFound);
        if (!task.equals(after)) throw notFound();
        observation.stage(ProductPatentSearchObservation.Stage.PAGE_CONTENT);
        var pages = new ProductPatentSearchPages(db, evidence, cursors, titles, currentSources);
        var prepared = pages.prepare(resultSetId, jwt.getSubject());
        long nextOrdinal = cursors.resolve(cursor, prepared.context(), pageSize);
        Page result = pages.read(resultSetId, pageSize, nextOrdinal, prepared);
        observation.stage(ProductPatentSearchObservation.Stage.CURRENT_CHECK);
        assertSourcesCurrent(attempts.getFirst());
        if (!task.equals(tasks.find(taskId).orElse(null))
                || !consultationAccess.permits(jwt, facts, ProductAccessPolicy.Action.READ)) throw notFound();
        observation.pageSuccess(result.members().size());
        return result;
        }
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
    private Map<String,ProductSourceAvailability.Active> assertSourcesCurrent(String attemptId) {
        return ProductPatentSearchCurrentSources.require(db,mapper,evidence,availability,sourceWatermark,attemptId);
    }
    private List<Prior> prior(String requestId) {
        return db.query("SELECT r.request_sha,r.attempt_id,a.fencing_token,r.result_set_id "
                        + "FROM con4_search_request r JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id "
                        + "WHERE r.request_id=?", (rs, row) -> new Prior(rs.getString(1), rs.getString(2),
                                rs.getLong(3), rs.getString(4)), requestId);
    }
    private Receipt receipt(String resultSetId) {
        return new ProductPatentSearchReceipts(db).read(resultSetId);
    }
    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static void requireIdentity(String taskId, String inputId, String delegationId,
            String attemptId, long token, String requestId) {
        if (blank(taskId) || blank(inputId) || blank(delegationId) || blank(attemptId) || token < 1
                || requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact search execution identity required");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}

