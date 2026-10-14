package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Real TLS transport. Neither model nor browser can choose its credentials, host or request path. */
final class ProductRetrievalMtls {
    interface Transport { JsonNode post(String path, Object body, String key); }
    static Transport create(URI origin, Path keys, char[] password, Path trust, char[] trustPassword,
            ObjectMapper mapper) {
        if (!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null
                || origin.getQuery()!=null || origin.getFragment()!=null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath())))
            throw new IllegalArgumentException("Exact HTTPS product origin required");
        try {
            KeyStore client=KeyStore.getInstance("PKCS12"), roots=KeyStore.getInstance("PKCS12");
            try (var input=Files.newInputStream(keys)) { client.load(input,password); }
            try (var input=Files.newInputStream(trust)) { roots.load(input,trustPassword); }
            var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(client,password);
            var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(roots);
            var tls=SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
            var http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
            return (path,body,key)-> {
                if (!path.startsWith("/v2/consultation/") || path.contains("..") || path.contains("?")
                        || key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
                    throw new IllegalArgumentException("Exact consultation command required");
                try {
                    var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(90))
                            .header("Content-Type","application/json").header("Accept","application/json")
                            .header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body))).build();
                    var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode()!=200) throw new ResponseStatusException(
                            HttpStatus.valueOf(response.statusCode()),"Formal execution command rejected");
                    return mapper.readTree(response.body());
                } catch (ResponseStatusException rejected) { throw rejected; }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("Execution interrupted");
                } catch (Exception unavailable) { throw new IllegalStateException("Formal mTLS transport unavailable"); }
            };
        } catch (Exception invalid) { throw new IllegalStateException("Formal mTLS configuration unavailable"); }
        finally { java.util.Arrays.fill(password,'\0'); java.util.Arrays.fill(trustPassword,'\0'); }
    }
}
