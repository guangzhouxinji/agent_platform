package cn.xinji.agentplatform.enforcement.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Serves project-authored source bytes over a real TLS socket with deterministic failure timing. */
final class MaterialPageHttpsSource implements AutoCloseable {
    static final String CREDENTIAL_REF = "m01-source";
    private static final String TOKEN = "m01-private-source-token";
    private final HttpsServer server;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final JsonNode records;
    private boolean rateLimited;
    private volatile CountDownLatch paused;
    private volatile CountDownLatch resume;
    private volatile boolean failNextRecord;

    MaterialPageHttpsSource(Path root) throws Exception {
        Path store = root.resolve("source.p12");
        var keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "source",
                "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "enf-source-test", "-keypass",
                "enf-source-test", "-noprompt").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) throw new IllegalStateException(output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(store)) {
            keys.load(input, "enf-source-test".toCharArray());
        }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "enf-source-test".toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        client = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build();
        records = json.readTree(java.nio.file.Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/observations.json"))).get("records");
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/page", exchange -> {
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String after = exchange.getRequestURI().getRawQuery();
            after = after == null ? "" : java.net.URLDecoder.decode(after.substring("after=".length()),
                    StandardCharsets.UTF_8);
            int status = 200;
            MaterialPageSource.Page page;
            if (after.equals("START")) page = new MaterialPageSource.Page(
                    List.of(new MaterialPageSource.Entry("p1-1", input(0))), false, null);
            else if (after.equals("p1-1")) page = new MaterialPageSource.Page(List.of(
                    new MaterialPageSource.Entry("p2-1", input(1)),
                    new MaterialPageSource.Entry("p2-2", input(0))), false, "SOURCE_RECORD_FAILURE");
            else if (after.equals("p2-2") && !rateLimited) {
                rateLimited = true;
                status = 429;
                page = null;
            } else if (after.equals("p2-2")) page = new MaterialPageSource.Page(
                    List.of(new MaterialPageSource.Entry("p2-3", input(0))), true, null);
            else if (after.equals("p2-3") && failNextRecord) {
                failNextRecord = false;
                var valid = input(0);
                page = new MaterialPageSource.Page(List.of(new MaterialPageSource.Entry("p2-4",
                        new MaterialProductService.ObservationInput(valid.sourceId(), valid.recordId(),
                                valid.sourceVersion(), "", valid.materialMention(), valid.actorMentions()))),
                        true, null);
            } else if (after.equals("p2-3")) page = new MaterialPageSource.Page(List.of(), true, null);
            else { status = 500; page = null; }
            if (after.equals("p2-3") && paused != null) {
                paused.countDown();
                try { if (!resume.await(15, TimeUnit.SECONDS)) status = 503; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); status = 503; }
                paused = null;
            }
            byte[] body = status == 200 ? json.writeValueAsBytes(page) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
    }

    void pauseNextEmptyPage() { paused = new CountDownLatch(1); resume = new CountDownLatch(1); }
    boolean awaitPausedPage() throws InterruptedException { return paused.await(10, TimeUnit.SECONDS); }
    void resumePage() { resume.countDown(); }
    void failNextRecord() { failNextRecord = true; }

    MaterialPageSource connector(String sourceId) {
        return new HttpMaterialPageSource(Map.of(sourceId,
                URI.create("https://localhost:" + server.getAddress().getPort() + "/page").toString()),
                Map.of(CREDENTIAL_REF, TOKEN), client, json);
    }

    private MaterialProductService.ObservationInput input(int index) {
        JsonNode record = records.get(index);
        return new MaterialProductService.ObservationInput("PROJECT-DEV-OBSERVATION",
                record.get("recordId").asText(), record.get("version").asText(),
                record.get("sourceText").asText(), record.get("materialMention").asText(), List.of(
                new MaterialWork.ActorInput("OPERATOR_CANDIDATE", "运营方甲", null),
                new MaterialWork.ActorInput("SUPPLIER_CANDIDATE", "供货方乙", null)));
    }

    @Override public void close() { server.stop(0); }
}
