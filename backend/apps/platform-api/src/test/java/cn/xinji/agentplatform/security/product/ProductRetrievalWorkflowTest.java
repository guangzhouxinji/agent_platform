package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductRetrievalWorkflowTest {
    private final Instant now=Instant.parse("2026-10-04T00:00:00Z");
    private ProductTaskGoalReader.GoalView goal(List<String> ids,List<String> fields) {
        return new ProductTaskGoalReader.GoalView("task",3,"goal",2,"input","QUERY","public goal","brazing",
                ids,fields,List.of(),now.minusSeconds(2),now.plusSeconds(600));
    }
    private ProductRetrievalWorkspace.Source source(long version,Instant until,List<String> fields) {
        return new ProductRetrievalWorkspace.Source("source","scope",1,version,21,21,"contract","decision",until,fields,"grant");
    }
    @Test void exactCurrentAndOneStepContinuationPreserveScopeAndExpiry() {
        for (long version:List.of(2L,3L)) {
            var original=source(version,now.plusSeconds(120),List.of("TITLE"));
            assertEquals(List.of(original),ProductRetrievalWorkflow.bind(goal(List.of("source"),List.of("TITLE")),
                    List.of(original),now));
        }
    }
    @Test void missingDifferentDuplicateExpiredOldOrFutureScopeFailsClosed() {
        var input=goal(List.of("source"),List.of("TITLE"));
        for (var rows:List.of(List.<ProductRetrievalWorkspace.Source>of(),
                List.of(source(1,now.plusSeconds(120),List.of("TITLE"))),
                List.of(source(4,now.plusSeconds(120),List.of("TITLE"))),
                List.of(source(3,now,List.of("TITLE"))),
                List.of(source(3,now.plusSeconds(120),List.of("CLAIMS"))),
                List.of(source(3,now.plusSeconds(120),List.of("TITLE")),
                        source(3,now.plusSeconds(120),List.of("TITLE")))))
            assertThrows(IllegalStateException.class,()->ProductRetrievalWorkflow.bind(input,rows,now));
        assertThrows(IllegalStateException.class,()->ProductRetrievalWorkflow.bind(
                goal(List.of("other-source"),List.of("TITLE")),List.of(source(3,now.plusSeconds(120),List.of("TITLE"))),now));
    }
    @Test void noFieldExpansionFromExistingTitleAuthorization() {
        assertThrows(IllegalStateException.class,()->ProductRetrievalWorkflow.bind(
                goal(List.of("source"),List.of("TITLE","CLAIMS")),
                List.of(source(3,now.plusSeconds(120),List.of("TITLE"))),now));
    }
    @Test void insecureOrNonOriginTransportNeverLoadsCredentials() {
        for(String origin:List.of("http://localhost:5443","https://user@localhost:5443",
                "https://localhost:5443/path","https://localhost:5443?redirect=evil"))
            assertThrows(IllegalArgumentException.class,()->ProductRetrievalMtls.create(java.net.URI.create(origin),
                    java.nio.file.Path.of("nonexistent"),new char[0],java.nio.file.Path.of("nonexistent"),new char[0],
                    new com.fasterxml.jackson.databind.ObjectMapper()));
    }
}
