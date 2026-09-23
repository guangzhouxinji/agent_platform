package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.surechembl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort.SearchRequest;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentSourceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SureChemblPatentAssistantSourceAdapterTest {
    @Test
    void normalizesBoundsAndDeduplicatesDynamicCandidates() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"status":"OK","data":{"results":{"total_hits":22,"documents":[
                      {"docId":"CN-1-A","metadata":{"pd":"20240102","pn":"CN-1-A","titles":[{"lang":"en","titles":["Water based binder coating"]},{"lang":"zh","titles":["水性粘结剂涂布方法"]}]},"pa":"申请人甲"},
                      {"docId":"CN-1-A-DUP","metadata":{"pd":"20240102","pn":"CN-1-A","titles":[{"lang":"en","titles":["Duplicate"]}]},"pa":"申请人甲"},
                      {"docId":"US-2-A1","metadata":{"pd":"20231231","pn":"US-2-A1","titles":[{"lang":"en","titles":["Battery drying process"]}]},"pa":"Applicant B"}
                    ]}}}
                    """);
        });
        try {
            var adapter = adapter(server, 2_000);
            var result = adapter.search(new SearchRequest("水性粘结剂涂布 \") OR *:* (\"", 2, 10, "CN"));
            assertEquals(2, result.sources().size());
            assertEquals("P1", result.sources().getFirst().citation());
            assertEquals("水性粘结剂涂布方法", result.sources().getFirst().title());
            assertEquals("申请人甲", result.sources().getFirst().applicant());
            assertEquals("2024-01-02", result.sources().getFirst().publicationDate());
            assertFalse(adapter.status().externalModelUseAllowed());
            assertTrue(rawQuery.get().contains("itemsPerPage=10"));
            assertTrue(rawQuery.get().contains("page=2"));
            assertTrue(java.net.URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8).contains("pnctry:CN"));
            assertEquals(22L, result.totalHits());
            assertTrue(result.hasNext());
            assertFalse(rawQuery.get().contains("*"));
            assertFalse(rawQuery.get().contains(":"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsRateLimitAndMalformedResponse() throws Exception {
        HttpServer limited = server(exchange -> respond(exchange, 429, "{}"));
        try {
            PatentSourceException exception = assertThrows(PatentSourceException.class,
                    () -> adapter(limited, 2_000).search(request()));
            assertEquals(PatentSourceException.Code.RATE_LIMITED, exception.code());
        } finally {
            limited.stop(0);
        }

        HttpServer unavailable = server(exchange -> respond(exchange, 503, "{}"));
        try {
            PatentSourceException exception = assertThrows(PatentSourceException.class,
                    () -> adapter(unavailable, 2_000).search(request()));
            assertEquals(PatentSourceException.Code.UPSTREAM_FAILURE, exception.code());
        } finally {
            unavailable.stop(0);
        }

        HttpServer malformed = server(exchange -> respond(exchange, 200, "not-json"));
        try {
            PatentSourceException exception = assertThrows(PatentSourceException.class,
                    () -> adapter(malformed, 2_000).search(request()));
            assertEquals(PatentSourceException.Code.MALFORMED_RESPONSE, exception.code());
        } finally {
            malformed.stop(0);
        }
    }

    @Test
    void mapsRequestTimeout() throws Exception {
        HttpServer server = server(exchange -> {
            try {
                Thread.sleep(300);
                respond(exchange, 200, "{\"status\":\"OK\",\"data\":{\"results\":{\"documents\":[]}}}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            PatentSourceException exception = assertThrows(PatentSourceException.class,
                    () -> adapter(server, 100).search(request()));
            assertEquals(PatentSourceException.Code.TIMEOUT, exception.code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loadsAbstractDetailWithoutClaimsOrDescriptions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/document/", exchange -> respond(exchange, 200, """
                {"status":"OK","data":{"patentDocument":{
                  "abstracts":[{"content":"<p>公开摘要内容</p>"}],
                  "claims":[{"content":"不得返回的权利要求"}],
                  "descriptions":[{"content":"不得返回的说明书"}]
                }}}
                """));
        server.start();
        try {
            var detail = adapter(server, 2_000).detail("CN-1-A");
            assertEquals("PARTIAL", detail.abstractState());
            assertEquals("公开摘要内容", detail.excerpt());
            assertFalse(detail.excerpt().contains("权利要求"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void distinguishesUpstreamAuthenticationAndAuthorizationFailures() throws Exception {
        HttpServer unauthenticated = server(exchange -> respond(exchange, 401, "{}"));
        try {
            assertEquals(PatentSourceException.Code.UPSTREAM_UNAUTHENTICATED,
                    assertThrows(PatentSourceException.class,
                            () -> adapter(unauthenticated, 2_000).search(request())).code());
        } finally {
            unauthenticated.stop(0);
        }
        HttpServer forbidden = server(exchange -> respond(exchange, 403, "{}"));
        try {
            assertEquals(PatentSourceException.Code.UPSTREAM_FORBIDDEN,
                    assertThrows(PatentSourceException.class,
                            () -> adapter(forbidden, 2_000).search(request())).code());
        } finally {
            forbidden.stop(0);
        }
    }

    private static SureChemblPatentAssistantSourceAdapter adapter(HttpServer server, int requestTimeoutMs) {
        return new SureChemblPatentAssistantSourceAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(), 1_000, requestTimeoutMs,
                10, new ObjectMapper());
    }

    private static SearchRequest request() {
        return new SearchRequest("水性粘结剂涂布", 1, 5, null);
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/content", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
