package cn.xinji.agentplatform.enforcement.evidence;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class HandoffHttpTestSupport {
    private HandoffHttpTestSupport() { }

    static HttpResponse<String> request(HttpClient http, String method, String url,
                                        String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void browser(String base, Runnable withdrawal) throws Exception {
        Process process = new ProcessBuilder("node", "frontend/e2e/enforcement-handoff-v2-product.mjs",
                base).redirectErrorStream(true).start();
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null && !line.equals("ENF_HANDOFF_BROWSER_READY"))
                System.out.println(line);
            if (line == null) throw new IllegalStateException("HANDOFF browser did not reach V2 workface");
            withdrawal.run();
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            process.getOutputStream().close();
            boolean cleared = false;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                cleared |= line.equals("ENF_HANDOFF_BROWSER_WITHDRAWAL_CLEARED");
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0 || !cleared)
                throw new IllegalStateException("HANDOFF browser withdrawal verification failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
