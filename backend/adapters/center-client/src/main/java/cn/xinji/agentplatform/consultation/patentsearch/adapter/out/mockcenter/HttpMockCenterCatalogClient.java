package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** HTTP-only client for the development mock center. */
public final class HttpMockCenterCatalogClient implements MockCenterCatalogClient {
    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;

    public HttpMockCenterCatalogClient(String baseUrl, ObjectMapper json, Duration connectTimeout, Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.json = json;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override public Metadata metadata() {
        return convert(get(baseUrl + "/api/dev/mock-center/patents/metadata", false).orElseThrow(), Metadata.class);
    }

    @Override public Page search(String text, String publicationNumber, int page, int pageSize) {
        String query = "?page=" + page + "&pageSize=" + pageSize
                + value("&text=", text) + value("&publicationNumber=", publicationNumber);
        return convert(get(baseUrl + "/api/dev/mock-center/patents" + query, false).orElseThrow(), Page.class);
    }

    @Override public Optional<PatentRecord> find(String recordId) {
        return get(baseUrl + "/api/dev/mock-center/patents/" + encode(recordId), true)
                .map(node -> convert(node, PatentRecord.class));
    }

    private Optional<JsonNode> get(String uri, boolean allowNotFound) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(timeout).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (allowNotFound && response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Mock center returned HTTP " + response.statusCode());
            }
            JsonNode data = json.readTree(response.body()).get("data");
            if (data == null || data.isNull()) throw new IllegalStateException("Mock center response has no data");
            return Optional.of(data);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock center request interrupted", failure);
        } catch (Exception failure) {
            throw failure instanceof IllegalStateException state ? state : new IllegalStateException("Mock center request failed", failure);
        }
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        return json.convertValue(value, type);
    }

    private static String value(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
