package cn.xinji.agentplatform.consultation.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.xinji.agentplatform.consultation.answer.adapter.out.openai.OpenAiConsultationAnswerAdapter.ModelEndpoint;
import cn.xinji.agentplatform.config.ConsultationAgentModelProperties;
import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExternalModelIntentAdvisorTest {
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @AfterEach void afterEach() { stopServer(); }

    @Test void acceptsOnlyConfiguredIntentAndConfiguredSlots() throws Exception {
        ExternalModelIntentAdvisor advisor = advisor("{\"intents\":[{\"intentId\":\"TRADEMARK_SIMILAR_SEARCH\",\"slots\":{\"trademarkName\":\"清源\",\"category\":\"3\",\"categoryOrGoodsServices\":\"洗发水\",\"toolUrl\":\"https://bad.example\"}}]}");

        var values = advisor.advise("检索清源商标，第3类化妆品", List.of("上一轮提到清源"), "trace-1");

        assertEquals(1, values.size());
        assertEquals("TRADEMARK_SIMILAR_SEARCH", values.getFirst().intentId());
        assertEquals(java.util.Map.of("trademarkName", "清源", "category", "3"), values.getFirst().slots());
        org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("requiredSlotGroups"));
        org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("检索清源商标，第3类化妆品"));
    }

    @Test void discardsUnknownIntentAndMalformedModelResponse() throws Exception {
        ExternalModelIntentAdvisor unknown = advisor("{\"intents\":[{\"intentId\":\"DELETE_EVERYTHING\",\"slots\":{}}]}");
        assertEquals(List.of(), unknown.advise("随便处理", List.of(), "trace-2"));
        stopServer();
        ExternalModelIntentAdvisor malformed = advisor("not-json");
        assertEquals(List.of(), malformed.advise("查询专利", List.of(), "trace-3"));
    }

    private ExternalModelIntentAdvisor advisor(String content) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = mapper.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of("message", java.util.Map.of("content", content)))));
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ManagedModelConfigurationService models = mock(ManagedModelConfigurationService.class);
        ConsultationAgentModelProperties properties = new ConsultationAgentModelProperties();
        properties.getRuntime().setEnabled(true);
        properties.getRuntime().setModelChain(List.of("test-model"));
        when(models.runtimeModelChain(List.of("test-model"))).thenReturn(List.of("test-model"));
        when(models.endpoint("test-model")).thenReturn(new ModelEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"), "test-key", "test-model"));
        return new ExternalModelIntentAdvisor(models, mapper, new DailyIntentContractCatalog(mapper), properties);
    }

    private void stopServer() { if (server != null) { server.stop(0); server = null; } }
}
