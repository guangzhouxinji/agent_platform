package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductPatentSearchObservationTest {
    @Test void pageEventMeasuresOnlyReadStagesWithoutExecutionOrSensitiveInputs() throws Exception {
        try(var event=observe("must-not-log-request")) {
            event.pageRead("result",20);
            event.stage(ProductPatentSearchObservation.Stage.AUTHORIZE); nanos.set(10);
            event.stage(ProductPatentSearchObservation.Stage.PAGE_CONTENT); nanos.set(25);
            event.stage(ProductPatentSearchObservation.Stage.CURRENT_CHECK); nanos.set(30);
            event.pageSuccess(20);
        }
        var event=mapper.readTree(events.getFirst());
        assertEquals("PATENT_SEARCH_PAGE_READ/v1",event.path("event").asText());
        assertEquals(15,event.path("stageNanos").path("PAGE_CONTENT").asLong());
        assertEquals(20,event.path("memberCount").asInt());
        for(String key:List.of("requestId","inputId","attemptId","execution","committed","cursor",
                "query","subject","scope","failureCode")) assertFalse(event.has(key),key);
        assertFalse(event.path("stageNanos").has("RETRIEVE_RANK"));
    }
    @Test void pageFailureAndOutputFailureNeverReplaceOriginalRejection() throws Exception {
        var failure=new IllegalStateException("private-page-query-token");
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var event=observe("not-logged")) {
                event.pageRead("result",20);
                event.stage(ProductPatentSearchObservation.Stage.CURRENT_CHECK); throw failure;
            }
        }));
        var event=mapper.readTree(events.getFirst());
        assertEquals("SEARCH_PAGE_READ_FAILED",event.path("failureCode").asText());
        assertFalse(events.getFirst().contains("private-page"));
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var observed=new ProductPatentSearchObservation(mapper,nanos::get,
                    ignored->{throw new IllegalArgumentException("sink");},"t",null,null,null)) {
                observed.pageRead("result",20); throw failure;
            }
        }));
    }
    private final ObjectMapper mapper=new ObjectMapper();
    private final AtomicLong nanos=new AtomicLong();
    private final List<String> events=new ArrayList<>();
    private ProductPatentSearchObservation observe(String request) {
        return new ProductPatentSearchObservation(mapper,nanos::get,events::add,"task","input","attempt",request);
    }
    private ProductPatentSearchExecution.Receipt receipt() {
        return new ProductPatentSearchExecution.Receipt("attempt",1,"result","method",2,3,false,"sha",
                "NORMAL","rule","reason","SUCCEEDED","EXACT",List.of(),"RESULT_DIGEST_DECIMAL_12/v2");
    }
    @Test void freshRecordsOnlyExecutedStagesAndCommit() throws Exception {
        try(var event=observe("request")) {
            event.fresh(); event.stage(ProductPatentSearchObservation.Stage.AUTHORIZE); nanos.set(12);
            event.stage(ProductPatentSearchObservation.Stage.COMMIT); nanos.set(30);
            event.committed("result"); event.success(receipt());
        }
        var event=mapper.readTree(events.getFirst());
        assertEquals("NEW",event.path("execution").asText());
        assertEquals(12,event.path("stageNanos").path("AUTHORIZE").asLong());
        assertEquals(18,event.path("stageNanos").path("COMMIT").asLong());
        assertFalse(event.path("stageNanos").has("SOURCE_INDEX"));
        assertTrue(event.path("committed").asBoolean()); assertFalse(event.has("failureStage"));
    }
    @Test void replayDoesNotInventRetrievalOrFreshCommit() throws Exception {
        try(var event=observe("request")) {
            event.replay(); event.stage(ProductPatentSearchObservation.Stage.REPLAY_CHECK);
            nanos.set(5); event.success(receipt());
        }
        var event=mapper.readTree(events.getFirst());
        assertEquals("PERSISTED_REPLAY",event.path("execution").asText());
        assertFalse(event.has("committed"));
        assertFalse(event.path("stageNanos").has("RETRIEVE_RANK"));
    }
    @Test void currentFailurePreservesActualCommitAndNeverLogsExceptionMessage() throws Exception {
        var failure=new IllegalStateException("secret-query-password-token");
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var event=observe("request")) {
                event.stage(ProductPatentSearchObservation.Stage.COMMIT); event.committed("result");
                event.stage(ProductPatentSearchObservation.Stage.CURRENT_CHECK); throw failure;
            }
        }));
        var event=mapper.readTree(events.getFirst());
        assertTrue(event.path("committed").asBoolean());
        assertEquals("CURRENT_CHECK",event.path("failureStage").asText());
        assertEquals("SEARCH_EXECUTION_FAILED",event.path("failureCode").asText());
        assertFalse(events.getFirst().contains("secret"));
    }
    @Test void identifiersAreJsonEncodedAndCloseIsIdempotent() throws Exception {
        String request="request\n\"outcome\":\"SUCCEEDED\"";
        var event=observe(request); event.close(); event.close();
        assertEquals(1,events.size()); assertFalse(events.getFirst().contains("\n"));
        assertEquals(request,mapper.readTree(events.getFirst()).path("requestId").asText());
        assertEquals("FAILED",mapper.readTree(events.getFirst()).path("outcome").asText());
    }
    @Test void outputFailureDoesNotReplaceBusinessFailureOrSuccessfulReturn() {
        var failure=new IllegalStateException("business");
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var event=new ProductPatentSearchObservation(mapper,nanos::get,
                    ignored->{throw new IllegalArgumentException("sink");},"t","i","a","r")) { throw failure; }
        }));
        assertDoesNotThrow(()->{
            try(var event=new ProductPatentSearchObservation(mapper,nanos::get,
                    ignored->{throw new IllegalArgumentException("sink");},"t","i","a","r")) { event.success(receipt()); }
        });
    }
}
