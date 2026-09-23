package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.center.contract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

final class CenterPatentApiStubServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicReference<StubResponse> response = new AtomicReference<>(new StubResponse(200, "{}", Duration.ZERO));
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    CenterPatentApiStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(int status, String body) {
        response.set(new StubResponse(status, body, Duration.ZERO));
    }

    void respondAfter(Duration delay, int status, String body) {
        response.set(new StubResponse(status, body, delay));
    }

    List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), copyHeaders(exchange),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        StubResponse current = response.get();
        if (!current.delay().isZero()) {
            try {
                Thread.sleep(current.delay().toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(current.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
        return Map.copyOf(copy);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record CapturedRequest(String method, String path, String rawQuery,
                           Map<String, List<String>> headers, String body) {}
    private record StubResponse(int status, String body, Duration delay) {}
}
