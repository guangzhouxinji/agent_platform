package cn.xinji.agentplatform.enforcement.conduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.*;

final class ConductIndependentHttpHarness {
    private ConductIndependentHttpHarness() { }

    static HttpClient client() throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return HttpClient.newBuilder().sslContext(tls).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static Process second(String[] settings, int port, Path log) throws Exception {
        Path jar = Path.of("backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar")
                .toAbsolutePath();
        Path classes = Path.of("backend/apps/platform-api/target/test-classes").toAbsolutePath();
        assertTrue(Files.exists(jar), "Bundle the product jar before independent JVM test");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Dloader.path=" + classes);
        command.add("-Dloader.main=" + ConductIndependentHttpNode.class.getName());
        command.add("-cp");
        command.add(jar.toString());
        command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        for (String setting : settings) {
            if (setting.startsWith("server.port=") || setting.startsWith("spring.datasource.password=") ||
                    setting.startsWith("product.identity.authority.password=")) continue;
            command.add("--" + setting);
        }
        command.add("--server.port=" + port);
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", value(settings, "spring.datasource.password="));
        builder.environment().put("PRODUCT_IDENTITY_AUTHORITY_PASSWORD",
                value(settings, "product.identity.authority.password="));
        return builder.start();
    }

    static void ready(HttpClient http, String base, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try { if (get(http, base + "/login", null).statusCode() == 200) return; }
            catch (Exception unavailable) { Thread.sleep(200); }
        }
        throw new IllegalStateException("Second Conduct HTTPS JVM unavailable: " +
                (Files.exists(log) ? Files.readString(log).lines()
                        .filter(line -> line.contains("ERROR") || line.contains("Exception"))
                        .map(line -> line.replaceAll("(?i)(password|secret)=[^\\s,]+", "$1=[REDACTED]"))
                        .limit(8).reduce((a, b) -> a + "\n" + b).orElse("no error line") : "missing log"));
    }

    static String login(HttpClient http, String base) throws Exception {
        var page = get(http, base + "/login", null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        assertTrue(csrf.find());
        assertEquals(302, form(http, base + "/login", "username=enf-conduct-operator&password="
                + "unique-conduct-password-1&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8))
                .statusCode());
        String verifier = "enf-conduct-pkce-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8);
        var authorize = get(http, base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-conduct-client&redirect_uri=" + redirect
                + "&scope=openid&state=conduct&code_challenge=" + challenge
                + "&code_challenge_method=S256", null);
        assertEquals(302, authorize.statusCode());
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location")
                .orElseThrow()).getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var token = form(http, base + "/oauth2/token", "grant_type=authorization_code"
                + "&client_id=enf-conduct-client&redirect_uri=" + redirect
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&code_verifier=" + verifier);
        assertEquals(200, token.statusCode(), token.body());
        return new ObjectMapper().readTree(token.body()).path("access_token").asText();
    }

    static HttpResponse<String> get(HttpClient http, String url, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient http, String url, String token,
                                     String body, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    private static HttpResponse<String> form(HttpClient http, String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String value(String[] settings, String prefix) {
        for (String setting : settings) if (setting.startsWith(prefix)) return setting.substring(prefix.length());
        throw new IllegalArgumentException("Missing isolated Conduct setting");
    }
}
