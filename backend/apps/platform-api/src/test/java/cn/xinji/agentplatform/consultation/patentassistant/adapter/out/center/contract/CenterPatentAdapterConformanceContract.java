package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.center.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentSourceException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class CenterPatentAdapterConformanceContract {
    private CenterPatentApiStubServer server;

    protected abstract PatentAssistantSourcePort createSubject(String baseUrl, int requestTimeoutMs);

    protected abstract String successResponse();

    protected CenterPatentApiStubServer server() {
        return server;
    }

    @BeforeEach
    void startStub() throws Exception {
        server = new CenterPatentApiStubServer();
    }

    @AfterEach
    void stopStub() {
        server.close();
    }

    @Test
    void conformsToNormalizedPagedReadOnlySearchContract() {
        server.respond(200, successResponse());
        PatentAssistantSourcePort subject = createSubject(server.baseUrl(), 2_000);
        PatentAssistantSourcePort.SearchPage page = subject.search(
                new PatentAssistantSourcePort.SearchRequest("水性粘结剂涂布", 2, 5, "CN"));

        assertEquals(2, page.page());
        assertEquals(5, page.pageSize());
        assertEquals(1, page.sources().size());
        assertNull(page.sources().getFirst().applicationNumber());
        assertNull(page.sources().getFirst().excerpt());
        assertFalse(subject.status().externalModelUseAllowed());
        assertTrue(server.requests().stream().noneMatch(request ->
                java.util.Set.of("PUT", "PATCH", "DELETE").contains(request.method())));
    }

    @Test
    void mapsStableAuthenticationRateAndUpstreamErrors() {
        PatentAssistantSourcePort subject = createSubject(server.baseUrl(), 2_000);
        Map<Integer, PatentSourceException.Code> cases = Map.of(
                401, PatentSourceException.Code.UPSTREAM_UNAUTHENTICATED,
                403, PatentSourceException.Code.UPSTREAM_FORBIDDEN,
                429, PatentSourceException.Code.RATE_LIMITED,
                503, PatentSourceException.Code.UPSTREAM_FAILURE);
        cases.forEach((status, code) -> {
            server.respond(status, "{\"secret\":\"contract-secret\"}");
            PatentSourceException error = assertThrows(PatentSourceException.class,
                    () -> subject.search(new PatentAssistantSourcePort.SearchRequest("水性粘结剂涂布", 1, 5, null)));
            assertEquals(code, error.code());
            assertFalse(error.getMessage().contains("contract-secret"));
        });
    }

    @Test
    void rejectsMalformedAndOversizedResponsesAndMapsTimeout() {
        PatentAssistantSourcePort subject = createSubject(server.baseUrl(), 2_000);
        server.respond(200, "not-json");
        assertEquals(PatentSourceException.Code.MALFORMED_RESPONSE,
                assertThrows(PatentSourceException.class,
                        () -> subject.search(new PatentAssistantSourcePort.SearchRequest("水性粘结剂涂布", 1, 5, null))).code());

        server.respond(200, "{\"padding\":\"" + "x".repeat(5_000_100) + "\"}");
        assertEquals(PatentSourceException.Code.MALFORMED_RESPONSE,
                assertThrows(PatentSourceException.class,
                        () -> subject.search(new PatentAssistantSourcePort.SearchRequest("水性粘结剂涂布", 1, 5, null))).code());

        PatentAssistantSourcePort shortTimeoutSubject = createSubject(server.baseUrl(), 100);
        server.respondAfter(Duration.ofMillis(300), 200, successResponse());
        assertEquals(PatentSourceException.Code.TIMEOUT,
                assertThrows(PatentSourceException.class,
                        () -> shortTimeoutSubject.search(new PatentAssistantSourcePort.SearchRequest("水性粘结剂涂布", 1, 5, null))).code());
    }
}
