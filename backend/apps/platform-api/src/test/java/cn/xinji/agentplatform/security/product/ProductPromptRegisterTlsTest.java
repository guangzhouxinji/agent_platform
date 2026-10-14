package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** Actual JSSE certificates and production HTTP/security/assembly over the same two Hikari pools. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptRegisterTlsTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    ProductPromptRegisterHttpFixture data;
    SecureServer server;
    HttpClient maintainer,other,noCertificate;
    String otherSha;

    @BeforeAll void certificates()throws Exception{
        tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();
        tls.leaf("other-register-client","CN=Other Owned Registration Client","clientAuth",null);
        otherSha=ProductCapturedArchive.digest(tls.load(tls.root.resolve("other-register-client.p12"))
                .getCertificate("other-register-client").getEncoded());
        maintainer=client("worker");other=client("other-register-client");noCertificate=client(null);
    }
    @AfterAll void eraseCertificates()throws Exception{
        try{for(var client:new HttpClient[]{maintainer,other,noCertificate})if(client!=null)client.close();}
        finally{if(tls!=null)tls.close();}
    }
    @BeforeEach void start()throws Exception{
        data=new ProductPromptRegisterHttpFixture(root.resolve(UUID.randomUUID().toString()),tls);
        server=new SecureServer(root.resolve("server-"+UUID.randomUUID()),data);
    }
    @AfterEach void close()throws Exception{
        try{if(server!=null)server.close();}finally{if(data!=null)data.close();}
    }

    @Test void actualHttpsPostAndOriginalKeyGetUseProductionAssemblyWithoutRenewal()throws Exception{
        var first=send(maintainer,"POST",registration(),"tls-original");assertThat(first.statusCode()).isEqualTo(200);
        JsonNode original=json(first);assertThat(original.path("state").asText()).isEqualTo("READY");
        assertThat(original.path("workspaceId").asText()).isEqualTo(data.f.workspaceId);
        var fields=new java.util.HashSet<String>();original.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(Set.of("requestId","receiptId","workspaceId","state","resultSha","preparedAt","receiptUntil"));
        assertThat(first.body()).doesNotContain("tls-original",data.f.certificateSha,"jdbc:","approvalRef");
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var main=data.f.snapshot(MAIN,"conp_workspace_register_receipt");var authority=data.f.snapshot(AUTHORITY,"idp_workspace_register_request");
        data.f.mainFault.clear();data.f.authorityFault.clear();
        assertThat(json(send(maintainer,"GET",lookup(),"tls-original"))).isEqualTo(original);
        assertThat(json(send(maintainer,"POST",registration(),"tls-original"))).isEqualTo(original);
        assertThat(data.f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(main);
        assertThat(data.f.snapshot(AUTHORITY,"idp_workspace_register_request")).isEqualTo(authority);
        assertNoWrites();assertThat(data.f.count(MAIN,"conp_workspace")).isEqualTo(1);
        assertThat(data.f.count(AUTHORITY,"id2_maintenance_audit")).isZero();
        assertThat(server.peers).hasSize(3).allMatch(peer->peer.secure()&&data.f.certificateSha.equals(peer.certificateSha()));
    }

    @Test void noCertificateAndOtherTrustedClientHandshakeReachBoundIdentityDenial()throws Exception{
        data.f.mainFault.clear();data.f.authorityFault.clear();
        var absent=send(noCertificate,"POST",registration(),"no-certificate");
        var wrong=send(other,"POST",registration(),"wrong-certificate");
        assertThat(absent.statusCode()).isEqualTo(403);assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(absent.sslSession()).isPresent();assertThat(wrong.sslSession()).isPresent();
        assertThat(json(absent).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");
        assertThat(json(wrong).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");
        assertThat(server.peers).hasSize(2);
        assertThat(server.peers.get(0)).isEqualTo(new Peer("POST",true,null));
        assertThat(server.peers.get(1)).isEqualTo(new Peer("POST",true,otherSha));
        assertThat(otherSha).isNotEqualTo(data.f.certificateSha);
        assertThat(data.f.count(MAIN,"conp_workspace")).isZero();
        assertThat(data.f.count(AUTHORITY,"idp_workspace_register_request")).isZero();assertNoWrites();
    }

    @Test void actualHttpsHeadOnBothPathsCannotEnterApplicationOrSettlePending()throws Exception{
        data.f.failSql(AUTHORITY,sql->sql.startsWith("UPDATE idp_workspace SET"));
        assertThat(send(maintainer,"POST",registration(),"head-key").statusCode()).isEqualTo(503);
        assertThat(data.f.state()).isEqualTo("PENDING");
        var receipt=data.f.snapshot(MAIN,"conp_workspace_register_receipt");
        var pending=data.f.snapshot(AUTHORITY,"idp_workspace_register_request");server.peers.clear();
        data.f.mainFault.clear();data.f.authorityFault.clear();
        assertThat(send(maintainer,"HEAD",registration(),"head-key").statusCode()).isEqualTo(405);
        assertThat(send(maintainer,"HEAD",lookup(),"head-key").statusCode()).isEqualTo(405);
        assertThat(server.peers).hasSize(2).allMatch(peer->peer.method().equals("HEAD")&&peer.secure());
        assertThat(data.f.authorityFault.sql).noneMatch(sql->sql.contains("FROM id2_maintainer")
                ||sql.contains("FROM idp_workspace_register_request")||sql.contains("FROM idp_workspace WHERE"));
        assertThat(data.f.mainFault.sql).noneMatch(sql->sql.contains("FROM conp_workspace"));
        assertThat(data.f.state()).isEqualTo("PENDING");
        assertThat(data.f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(receipt);
        assertThat(data.f.snapshot(AUTHORITY,"idp_workspace_register_request")).isEqualTo(pending);assertNoWrites();
    }

    private HttpClient client(String key)throws Exception{return HttpClient.newBuilder().sslContext(tls.context(key,true))
            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();}
    private String registration(){return "/v2/identity/prompt-workspaces/"+data.f.workspaceId+"/register";}
    private String lookup(){return "/v2/identity/prompt-workspaces/"+data.f.workspaceId+"/requests/REGISTER";}
    private HttpResponse<String> send(HttpClient client,String method,String path,String key)throws Exception{
        return client.send(HttpRequest.newBuilder(server.origin().resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Idempotency-Key",key).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response)throws Exception{return data.f.mapper.readTree(response.body());}
    private void assertNoWrites(){
        for(var sql:List.of(data.f.mainFault.sql,data.f.authorityFault.sql))
            assertThat(sql).noneMatch(value->value.startsWith("INSERT")||value.startsWith("UPDATE")||value.startsWith("DELETE"));
    }
    record Peer(String method,boolean secure,String certificateSha) { }

    final class SecureServer implements AutoCloseable {
        final Tomcat tomcat=new Tomcat();final Connector connector;
        final AnnotationConfigWebApplicationContext web;
        final List<Peer> peers=new CopyOnWriteArrayList<>();
        SecureServer(Path directory,ProductPromptRegisterHttpFixture fixture)throws Exception{
            Files.createDirectories(directory.resolve("web"));tomcat.setBaseDir(directory.toString());tomcat.setHostname("localhost");
            connector=new Connector("org.apache.coyote.http11.Http11NioProtocol");connector.setPort(0);connector.setScheme("https");connector.setSecure(true);
            connector.setProperty("address","127.0.0.1");connector.setProperty("SSLEnabled","true");connector.setProperty("maxThreads","10");
            var host=new SSLHostConfig();host.setCertificateVerification("optional");host.setTruststoreFile(tls.root.resolve("trust.p12").toString());
            host.setTruststorePassword(tls.password);host.setTruststoreType("PKCS12");
            var certificate=new SSLHostConfigCertificate(host,SSLHostConfigCertificate.Type.RSA);
            certificate.setCertificateKeystoreFile(tls.root.resolve("server.p12").toString());certificate.setCertificateKeystorePassword(tls.password);
            certificate.setCertificateKeystoreType("PKCS12");certificate.setCertificateKeyAlias("server");host.addCertificate(certificate);
            connector.addSslHostConfig(host);tomcat.getService().addConnector(connector);tomcat.setConnector(connector);
            Context context=tomcat.addContext("",directory.resolve("web").toString());context.setParentClassLoader(getClass().getClassLoader());
            web=fixture.openContext(context.getServletContext());
            try{
                filter(context,"tls-observation",new Filter(){
                    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
                        var actual=(HttpServletRequest)request;Object attribute=actual.getAttribute("jakarta.servlet.request.X509Certificate");
                        String sha=null;
                        if(attribute instanceof X509Certificate[] certificates&&certificates.length>0){
                            try{sha=ProductCapturedArchive.digest(certificates[0].getEncoded());}
                            catch(java.security.cert.CertificateEncodingException malformed){throw new ServletException(malformed);}
                        }
                        // Observation reads actual JSSE attributes and never supplies identity or authorizes a request.
                        peers.add(new Peer(actual.getMethod(),actual.isSecure(),sha));chain.doFilter(request,response);
                    }
                });
                filter(context,"product-transport",web.getBean(ProductIdentityTransportFilter.class));
                filter(context,"product-security",web.getBean(FilterChainProxy.class));
                var servlet=Tomcat.addServlet(context,"product-dispatcher",new DispatcherServlet(web));servlet.setLoadOnStartup(1);
                context.addServletMappingDecoded("/","product-dispatcher");tomcat.start();
                assertThat(connector.getLocalPort()).isPositive();
                assertThat(web.getBean(ProductPromptRegisterController.class)).isNotNull();
                assertThat(web.getBean(ProductPromptRegisterApplication.class)).isNotSameAs(fixture.f.app);
            }catch(Exception|Error failure){
                try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        }
        URI origin(){return URI.create("https://localhost:"+connector.getLocalPort());}
        @Override public void close()throws Exception{try{tomcat.stop();}finally{try{tomcat.destroy();}finally{if(web.isActive())web.close();}}}
        private void filter(Context context,String name,Filter filter){
            var definition=new FilterDef();definition.setFilterName(name);definition.setFilter(filter);context.addFilterDef(definition);
            var mapping=new FilterMap();mapping.setFilterName(name);mapping.addURLPattern("/*");mapping.setDispatcher("REQUEST");context.addFilterMap(mapping);
        }
    }
}
