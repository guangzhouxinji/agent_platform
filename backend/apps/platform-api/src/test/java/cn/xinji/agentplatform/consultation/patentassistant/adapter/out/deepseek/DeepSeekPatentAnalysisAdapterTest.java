package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.deepseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantService;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekPatentAnalysisAdapterTest {
    private static final PatentAssistantSource SOURCE = new PatentAssistantSource("P1", "PUBLIC-1", "CN1A", "CN1",
            "公开候选", "申请人", "2024-01-01", "https://example.test/CN1A", "PARTIAL", "知识图谱", "公开摘要");

    @Test
    void sendsOnlyConfiguredRequestAndReadsAssistantContent() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"辅助分析[P1]\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new DeepSeekPatentAnalysisAdapter(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                    "test-key", "deepseek-chat", new ObjectMapper(), HttpClient.newHttpClient(), Duration.ofSeconds(3));
            assertEquals("辅助分析[P1]", adapter.analyze("知识图谱专利检索", List.of(SOURCE), "trace-1"));
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(body.get().contains("知识图谱专利检索"));
            assertTrue(body.get().contains("PUBLIC-1") || body.get().contains("公开候选"));
            assertFalse(body.get().contains("test-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesCallWithoutApiKey() {
        var adapter = new DeepSeekPatentAnalysisAdapter("https://api.deepseek.com/chat/completions", "", "deepseek-chat", new ObjectMapper());
        assertFalse(adapter.status().configured());
        assertThrows(PatentAssistantService.ModelUnavailableException.class,
                () -> adapter.analyze("知识图谱专利检索", List.of(SOURCE), "trace-1"));
    }

    @Test
    void extractsBoundedEditableFeaturesFromJsonOnlyResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = "{\\\"features\\\":[\\\"水性粘结剂\\\",\\\"双区烘干\\\",\\\"水性粘结剂\\\"]}";
            byte[] response = ("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new DeepSeekPatentAnalysisAdapter(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                    "test-key", "deepseek-chat", new ObjectMapper(), HttpClient.newHttpClient(), Duration.ofSeconds(3));
            assertEquals(List.of("水性粘结剂", "双区烘干"),
                    adapter.extractFeatures("水性粘结剂并进行双区烘干", "TECHNICAL_REFERENCE", "trace-2").features());
            assertTrue(body.get().contains("水性粘结剂并进行双区烘干"));
            assertFalse(body.get().contains("公开候选"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedEmptyAndOverLimitFeatureResponsesWithoutFallback() throws Exception {
        Queue<String> contents = new ArrayDeque<>(List.of(
                " ",
                "not-json",
                "{\"features\":[]}",
                "{\"features\":[\"特征01\",\"特征02\",\"特征03\",\"特征04\",\"特征05\",\"特征06\",\"特征07\",\"特征08\",\"特征09\"]}"));
        HttpServer server = featureServer(200, contents, 0);
        try {
            var adapter = adapter(server, Duration.ofSeconds(2));
            for (int attempt = 0; attempt < 4; attempt++) {
                var failure = assertThrows(PatentAssistantService.ModelUnavailableException.class,
                        () -> adapter.extractFeatures("用户输入", "TECHNICAL_REFERENCE", "trace-invalid"));
                assertEquals("INVALID_FEATURE_RESPONSE", failure.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesUpstreamStatusAndUsesStableTimeoutFailure() throws Exception {
        HttpServer limited = featureServer(429, new ArrayDeque<>(List.of("{}")), 0);
        try {
            var failure = assertThrows(PatentAssistantService.ModelUnavailableException.class,
                    () -> adapter(limited, Duration.ofSeconds(2)).extractFeatures("用户输入", "TECHNICAL_REFERENCE", "trace-429"));
            assertEquals("MODEL_UPSTREAM_429", failure.getMessage());
        } finally {
            limited.stop(0);
        }

        HttpServer unavailable = featureServer(503, new ArrayDeque<>(List.of("{}")), 0);
        try {
            var failure = assertThrows(PatentAssistantService.ModelUnavailableException.class,
                    () -> adapter(unavailable, Duration.ofSeconds(2)).extractFeatures("用户输入", "TECHNICAL_REFERENCE", "trace-503"));
            assertEquals("MODEL_UPSTREAM_503", failure.getMessage());
        } finally {
            unavailable.stop(0);
        }

        HttpServer slow = featureServer(200, new ArrayDeque<>(List.of("{\"features\":[\"有效特征\"]}")), 250);
        try {
            var failure = assertThrows(PatentAssistantService.ModelUnavailableException.class,
                    () -> adapter(slow, Duration.ofMillis(30)).extractFeatures("用户输入", "TECHNICAL_REFERENCE", "trace-timeout"));
            assertEquals("MODEL_CALL_FAILED", failure.getMessage());
        } finally {
            slow.stop(0);
        }
    }

    private static DeepSeekPatentAnalysisAdapter adapter(HttpServer server, Duration timeout) {
        return new DeepSeekPatentAnalysisAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                "test-key", "deepseek-chat", new ObjectMapper(), HttpClient.newHttpClient(), timeout);
    }

    private static HttpServer featureServer(int status, Queue<String> contents, long delayMillis) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                String content = contents.isEmpty() ? "" : contents.remove();
                byte[] response = new ObjectMapper().writeValueAsBytes(java.util.Map.of(
                        "choices", List.of(java.util.Map.of("message", java.util.Map.of("content", content)))));
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
