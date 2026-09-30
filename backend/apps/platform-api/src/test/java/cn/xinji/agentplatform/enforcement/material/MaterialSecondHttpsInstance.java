package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class MaterialSecondHttpsInstance implements AutoCloseable {
    private final ConfigurableApplicationContext context;

    MaterialSecondHttpsInstance(Path tlsRoot) {
        context = new SpringApplicationBuilder(EnforcementMaterialHttpsTest.IsolatedEnforcement.class)
                .profiles("identity-http")
                .properties(
                        "spring.config.name=enf-isolated-http",
                        "server.port=0",
                        "server.ssl.key-store=" + tlsRoot.resolve("server.p12").toUri(),
                        "server.ssl.key-store-password=enf-local-https-test",
                        "server.ssl.key-store-type=PKCS12",
                        "spring.datasource.url=jdbc:h2:mem:enf-https;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.sql.init.mode=never",
                        "product.identity.enabled=true",
                        "enforcement.v2.enabled=true",
                        "product.identity.issuer=https://enf-http.example.org",
                        "product.identity.audience=https://enf-api.example.org",
                        "product.identity.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "product.identity.authority.jdbc-url=jdbc:h2:mem:enf-https-authority;DB_CLOSE_DELAY=-1",
                        "product.identity.authority.schema=PUBLIC",
                        "product.identity.authority.username=sa",
                        "product.identity.token-verification-window=PT5M",
                        "product.identity.activation-lifetime=PT30M",
                        "product.identity.minimum-password-length=12",
                        "product.consultation.retention.ref=isolated-retention-input",
                        "product.consultation.retention.version=1",
                        "product.consultation.retention.evidence-sha256=" + "a".repeat(64),
                        "product.consultation.retention.max-age=PT2H",
                        "product.consultation.goal-retention.ref=isolated-goal-retention",
                        "product.consultation.goal-retention.version=1",
                        "product.consultation.goal-retention.evidence-sha256=" + "a".repeat(64),
                        "product.consultation.goal-retention.max-age=PT1H",
                        "product.consultation.goal-retention.max-bytes=16384",
                        "enforcement.v2.file-root=" + tlsRoot.resolve("files"))
                .run();
    }

    String baseUrl() {
        return "https://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    void assertPreviewWithdrawal(String firstBase, HttpClient client, String materialId, String token,
                                 byte[] original, Runnable revoke) throws Exception {
        MaterialBrowserE2e.run(firstBase, materialId, () -> checkPreviewWithdrawal(client, materialId,
                token, original, revoke));
    }

    private void checkPreviewWithdrawal(HttpClient client, String materialId, String token,
                                        byte[] original, Runnable revoke) throws Exception {
        URI uri = URI.create(baseUrl() + "/v2/enforcement/materials/" + materialId + "/preview");
        HttpRequest request = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).GET().build();
        var allowed = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        assertThat(allowed.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(original);
        revoke.run();
        var denied = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain("虚构");
    }

    @Override public void close() { context.close(); }
}
