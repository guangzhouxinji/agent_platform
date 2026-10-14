package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;

/** Technical event only: never changes authorization, transaction results, or business failures. */
final class ProductPatentSearchObservation implements AutoCloseable {
    enum Stage { AUTHORIZE, REPLAY_CHECK, SOURCE_CONTENT, SOURCE_INDEX, QUERY_PLAN,
        RETRIEVE_RANK, COMMIT, CURRENT_CHECK, RESULT_BINDING, SOURCE_CURRENT, PAGE_CONTENT }
    private final ObjectMapper mapper;
    private final LongSupplier timer;
    private final Consumer<String> output;
    private final Map<String,Object> event = new LinkedHashMap<>();
    private final Map<String,Long> stages = new LinkedHashMap<>();
    private final long started;
    private long stageStarted;
    private Stage stage;
    private boolean closed;

    ProductPatentSearchObservation(ObjectMapper mapper, String task, String input, String attempt,
            String request) {
        this(mapper, System::nanoTime,
                value -> LoggerFactory.getLogger(ProductPatentSearchObservation.class).info("{}", value),
                task, input, attempt, request);
    }
    ProductPatentSearchObservation(ObjectMapper mapper, LongSupplier timer, Consumer<String> output,
            String task, String input, String attempt, String request) {
        this.mapper=mapper; this.timer=timer; this.output=output; started=timer.getAsLong();
        event.put("event","PATENT_SEARCH_EXECUTION/v1"); event.put("taskId",task);
        event.put("inputId",input); event.put("attemptId",attempt); event.put("requestId",request);
        event.put("outcome","FAILED");
    }
    void stage(Stage next) {
        long now=timer.getAsLong(); finishStage(now); stage=next; stageStarted=now;
    }
    void replay() { event.put("execution","PERSISTED_REPLAY"); }
    void pageRead(String resultSetId, int pageSize) {
        event.put("event","PATENT_SEARCH_PAGE_READ/v1");
        event.remove("inputId"); event.remove("attemptId"); event.remove("requestId");
        event.put("resultSetId",resultSetId); event.put("pageSize",pageSize);
        var timingId=ProductPatentSearchTransportObservation.currentTimingId();
        if(timingId!=null) event.put("timingId",timingId);
    }
    void pageSuccess(int memberCount) {
        event.put("outcome","SUCCEEDED"); event.put("memberCount",memberCount);
    }
    void fresh() { event.put("execution","NEW"); }
    void sources(int requested, int succeeded, int failed) {
        event.put("requestedSourceCount",requested); event.put("successfulSourceCount",succeeded);
        event.put("failedSourceCount",failed);
    }
    void sourceSnapshots(ProductPatentSearchSourceBatch.Batch batch) {
        event.put("sources",batch.snapshots().stream().map(source -> Map.of(
                "sourceId",source.sourceId(),"sourceVersion",source.sourceVersion(),
                "contractVersion",source.contractVersion(),"sourceEpoch",source.epoch(),
                "status",batch.failures().containsKey(source.sourceId())?"FAILED":"SUCCEEDED")).toList());
    }
    void queryPlan(String version) { event.put("queryPlanVersion",version); }
    void ranking(int documents, int lexical, ProductPatentSearchRanking.Result result) {
        event.put("documentPoolCount",documents); event.put("lexicalCandidateCount",lexical);
        event.put("candidateCount",result.candidateCount()); event.put("memberCount",result.members().size());
        event.put("methodVersion",result.method()); event.put("rerankStatus",result.rerankStatus());
        event.put("rerankModelVersion",result.rerankModelVersion());
    }
    void committed(String result) { event.put("committed",true); event.put("resultSetId",result); }
    void success(ProductPatentSearchExecution.Receipt receipt) {
        event.put("outcome","SUCCEEDED"); event.put("resultSetId",receipt.resultSetId());
        event.put("status",receipt.status()); event.put("totalReliability",receipt.totalReliability());
        event.put("memberCount",receipt.resultCount()); event.put("methodVersion",receipt.methodVersion());
    }
    private void finishStage(long now) {
        if(stage!=null) stages.merge(stage.name(),Math.max(0,now-stageStarted),Long::sum);
    }
    @Override public void close() {
        if(closed) return;
        closed=true;
        try {
            long now=timer.getAsLong(); finishStage(now);
            event.put("stageNanos",stages); event.put("durationNanos",Math.max(0,now-started));
            if(!"SUCCEEDED".equals(event.get("outcome"))) {
                event.put("failureStage",stage==null?"REQUEST_VALIDATION":stage.name());
                event.put("failureCode","PATENT_SEARCH_PAGE_READ/v1".equals(event.get("event"))
                        ? "SEARCH_PAGE_READ_FAILED" : "SEARCH_EXECUTION_FAILED");
            }
            output.accept(mapper.writeValueAsString(event));
        } catch(RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Logging is not the transaction or its receipt; retain the original return/exception.
        }
    }
}
