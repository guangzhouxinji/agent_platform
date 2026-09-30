package cn.xinji.agentplatform.enforcement.procedure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;

final class ProcedureHttpsLogin {
    private ProcedureHttpsLogin() { }
    static String token(SSLContext trust, ObjectMapper json, String base,
                        String username, String password) throws Exception {
        var client = HttpClient.newBuilder().sslContext(trust).cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var login = send(client, "GET", base + "/login", null);
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(csrf.find()).isTrue();
        assertThat(send(client, "POST", base + "/login",
                "username=" + username + "&password=" + password + "&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8)).statusCode()).isEqualTo(302);
        String verifier = "enf-procedure-second-verifier-12345678901234567890";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorize = send(client, "GET", base + "/oauth2/authorize?response_type=code"
                + "&client_id=enf-procedure-client&redirect_uri="
                + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                + "&scope=openid&state=review&code_challenge=" + challenge + "&code_challenge_method=S256", null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String code = java.net.URLDecoder.decode(URI.create(authorize.headers().firstValue("Location")
                .orElseThrow()).getRawQuery().split("code=")[1].split("&")[0], StandardCharsets.UTF_8);
        var issued = send(client, "POST", base + "/oauth2/token",
                "grant_type=authorization_code&client_id=enf-procedure-client&redirect_uri="
                        + URLEncoder.encode("https://localhost/callback", StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier);
        assertThat(issued.statusCode()).isEqualTo(200);
        return json.readTree(issued.body()).path("access_token").asText();
    }
    private static HttpResponse<String> send(HttpClient client, String method, String url,
                                              String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (body != null) builder.header("Content-Type", "application/x-www-form-urlencoded");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
