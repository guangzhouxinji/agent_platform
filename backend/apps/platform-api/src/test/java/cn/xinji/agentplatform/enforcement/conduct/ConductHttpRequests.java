package cn.xinji.agentplatform.enforcement.conduct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class ConductHttpRequests {
    private ConductHttpRequests() { }

    static HttpResponse<String> request(HttpClient client, String method, String url,
                                        String token, String body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (contentType != null) builder.header("Content-Type", contentType);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> api(HttpClient client, String method, String url,
                                    String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void browser(String base, Runnable withdrawal) throws Exception {
        var process = new ProcessBuilder("node", "frontend/e2e/enforcement-next-v2-product.mjs",
                base, "conduct", "enf-conduct-operator", "unique-conduct-password-1",
                "CONDUCT-APPROVED", "2", "CONDUCT-HTTP-ANALYSIS", "3")
                .redirectErrorStream(true).start();
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null && !line.equals("ENF_CONDUCT_BROWSER_READY"))
                System.out.println(line);
            if (line == null) throw new IllegalStateException("Conduct browser did not read V2 product");
            withdrawal.run();
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            process.getOutputStream().close();
            boolean cleared = false;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                cleared |= line.equals("ENF_CONDUCT_BROWSER_WITHDRAWAL_CLEARED");
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0 || !cleared)
                throw new IllegalStateException("Conduct browser withdrawal verification failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
