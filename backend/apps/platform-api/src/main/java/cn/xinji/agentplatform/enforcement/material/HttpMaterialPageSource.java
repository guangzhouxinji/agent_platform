package cn.xinji.agentplatform.enforcement.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Deployment-owned source endpoints; clients cannot submit a destination URL. */
public final class HttpMaterialPageSource implements MaterialPageSource {
    private final Map<String, URI> endpoints;
    private final Map<String, String> credentials;
    private final HttpClient client;
    private final ObjectMapper json;

    public HttpMaterialPageSource(Map<String, String> endpoints, Map<String, String> credentials,
                                  HttpClient client, ObjectMapper json) {
        this.endpoints = endpoints.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> {
                    URI uri = URI.create(entry.getValue());
                    if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                            || uri.getQuery() != null || uri.getFragment() != null)
                        throw new IllegalArgumentException("Source endpoint must be an HTTPS base URL");
                    return uri;
                }));
        this.client = client;
        this.json = json;
        this.credentials = Map.copyOf(credentials);
    }

    @Override public Page fetch(String sourceId, String afterCursor) {
        throw new MaterialPageSource.Failure("CREDENTIAL_REF_MISSING", "Source credential reference required");
    }

    @Override public Page fetch(String sourceId, String afterCursor, String credentialRef) {
        URI endpoint = endpoints.get(sourceId);
        if (endpoint == null) throw new MaterialPageSource.Failure("CONNECTOR_MISSING", "No approved source connector");
        String credential = credentials.get(credentialRef);
        if (credential == null || credential.isBlank() || credential.contains("\r") || credential.contains("\n"))
            throw new MaterialPageSource.Failure("CREDENTIAL_UNAVAILABLE", "Source credential unavailable");
        URI target = URI.create(endpoint + (endpoint.toString().contains("?") ? "&" : "?")
                + "after=" + URLEncoder.encode(afterCursor, StandardCharsets.UTF_8));
        try {
            var response = client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").header("Authorization", "Bearer " + credential)
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw new MaterialPageSource.Failure(
                        response.statusCode() == 429 ? "RATE_LIMITED" : "SOURCE_HTTP_FAILURE",
                        response.statusCode() == 429 ? "Source rate limited" : "Source unavailable");
                bytes = stream.readNBytes(2_000_001);
            }
            if (bytes.length > 2_000_000) throw new MaterialPageSource.Failure("PAGE_TOO_LARGE", "Source page too large");
            Page page = json.readValue(bytes, Page.class);
            if (page == null || page.entries() == null || page.entries().size() > 1000)
                throw new MaterialPageSource.Failure("PAGE_MALFORMED", "Source page malformed");
            return page;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MaterialPageSource.Failure("SOURCE_INTERRUPTED", "Source interrupted", interrupted);
        } catch (java.io.IOException failed) {
            throw new MaterialPageSource.Failure("SOURCE_IO_FAILURE", "Source unavailable", failed);
        }
    }
}
