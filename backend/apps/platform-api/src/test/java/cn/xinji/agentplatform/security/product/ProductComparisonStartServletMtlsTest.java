package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonStartMtlsEngineeringSupport.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.apache.tomcat.util.net.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;

/**
 * Real Tomcat HTTPS -> DispatcherServlet -> actual product StartController/Application -> actual
 * managed ProductRetrievalMtls -> Tomcat REQUIRE client-auth -> DispatcherServlet -> actual product
 * MatrixController mappings -> real file-H2 StartCommands -> mapped HTTPS GET.
 * Engineering JWT filter and input/access/delegation ports, domain claim/execute ports are explicit
 * fixtures/mocks. No request certificate attribute is set by the test. No model calls/approval.
 * This proves Servlet mappings/TLS handoff, NOT final source/policy/domain/model product acceptance.
 */
class ProductComparisonStartServletMtlsTest {
    static Tls tls;
    static final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    static final List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
    final ProductComparisonInputApplication inputs=mock(ProductComparisonInputApplication.class);
    final ProductComparisonWorkerDelegations delegations=mock(ProductComparisonWorkerDelegations.class);
    final ProductWorkerDelegations authority=mock(ProductWorkerDelegations.class);
    final ProductComparisonMatrixReader results=mock(ProductComparisonMatrixReader.class);
    final ProductTaskDirectory tasks=mock(ProductTaskDirectory.class);
    final ProductConsultationAccess access=mock(ProductConsultationAccess.class);
    final ProductComparisonCandidateExecution leases=mock(ProductComparisonCandidateExecution.class);
    final ProductComparisonMatrixExecution execution=mock(ProductComparisonMatrixExecution.class);
    final Jwt human=Jwt.withTokenValue("isolated-servlet-engineering").header("alg","RS256").issuer("https://engineering.invalid").subject("human").build();
    final ProductComparisonStartCommands.ManagedBinding managed=new ProductComparisonStartCommands.ManagedBinding("worker","engineering-configuration-version");
    final AtomicInteger humanServletRequests=new AtomicInteger(),workerServletRequests=new AtomicInteger(),claimCalls=new AtomicInteger(),executeCalls=new AtomicInteger();
    final List<Map<String,Object>> tlsRequests=new CopyOnWriteArrayList<>();
    final CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);
    String task=TASK, comparison=COMPARISON, candidate=CANDIDATE, attempt=UUID.randomUUID().toString(),delegation=UUID.randomUUID().toString();
    String base="/v2/consultation/tasks/"+TASK+"/patent-comparisons/"+COMPARISON+"/candidates/"+CANDIDATE;
    Path temporary;
    ProductComparisonStartMtlsEngineeringSupport data;
    ProductComparisonStartCommands store;
    ServletServer worker,entry;
    ProductComparisonStartController startController;
    HttpClient browser;
    ExecutorService executor;
    boolean delayClaim;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Profile("comparison-start-servlet-engineering") @EnableWebMvc
    static class Mvc implements WebMvcConfigurer {
        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers){resolvers.add(new AuthenticationPrincipalArgumentResolver());}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));converters.add(new MappingJackson2HttpMessageConverter(mapper));}
    }
    @BeforeAll static void certificates() throws Exception{tls=new Tls();}
    @AfterAll static void discardCertificates() throws Exception{if(tls!=null)tls.close();}
    @BeforeEach void setup() throws Exception {
        temporary=Files.createTempDirectory("comparison-start-servlet-mtls-");data=new ProductComparisonStartMtlsEngineeringSupport(temporary.resolve("persistent"));store=data.store();executor=Executors.newCachedThreadPool();
        var envelope=mock(ProductComparisonInputs.Envelope.class);var view=mock(ProductComparisonInputs.View.class);
        when(view.input()).thenReturn(envelope);when(view.inputSha()).thenReturn(SHA);when(envelope.expiresAt()).thenReturn(NOW.plusSeconds(600));when(envelope.candidates()).thenReturn(List.of(new ProductComparisonInputs.Candidate(CANDIDATE,0)));
        when(inputs.consume(TASK,COMPARISON,human)).thenReturn(view);when(inputs.read(TASK,COMPARISON,human)).thenReturn(view);
        when(tasks.find(TASK)).thenReturn(Optional.of(new ProductTaskDirectory.Task(TASK,"engineering-org","https://engineering.invalid","human","IN_PROGRESS",5,17,"engineering-scope",1,NOW.plusSeconds(900))));
        when(access.permits(eq(human),any(),any())).thenReturn(true);
        when(results.results(TASK,COMPARISON,human)).thenReturn(new ProductComparisonMatrixReader.Results(TASK,COMPARISON,SHA,"IN_PROGRESS",List.of(),List.of()));
        when(delegations.issue(eq(TASK),eq(COMPARISON),any(),eq(human),anyString())).thenAnswer(call->{data.delegation(delegation);return new ProductComparisonWorkerDelegations.Receipt(delegation,1,"ACTIVE",COMPARISON,SHA,CANDIDATE,UNTIL);});
        doAnswer(call->{assertContainerCertificate(call.getArgument(0));return null;}).when(execution).authorizeClaim(any(),any(),any());
        when(leases.acquire(any(),any(),anyString(),any())).thenAnswer(call->{
            HttpServletRequest request=call.getArgument(0);assertContainerCertificate(request);claimCalls.incrementAndGet();
            var scope=(ProductComparisonCandidateExecution.Scope)call.getArgument(1);assertEquals(TASK,scope.task());assertEquals(COMPARISON,scope.comparison());assertEquals(CANDIDATE,scope.candidate());assertEquals(SHA,scope.inputSha());assertEquals(delegation,scope.delegation());
            String key=call.getArgument(2);assertTrue(key.startsWith("start:"));data.attempt(attempt,delegation,key);acquired.countDown();if(delayClaim)assertTrue(release.await(20,TimeUnit.SECONDS));
            return new ProductComparisonCandidateExecution.Lease(attempt,1,UNTIL);
        });
        when(execution.execute(any(),any(),any())).thenAnswer(call->{assertContainerCertificate(call.getArgument(0));executeCalls.incrementAndGet();throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"NO_APPROVED_MODEL_ENGINEERING_PORT");});
        worker=new ServletServer("worker",true,new ProductComparisonMatrixController(leases,execution,results,new ProductComparisonJson(mapper)));
        var transport=ProductRetrievalMtls.create(worker.origin(),tls.root.resolve("worker.p12"),tls.password.toCharArray(),tls.root.resolve("trust.p12"),tls.password.toCharArray(),mapper);
        var app=new ProductComparisonStartApplication(store,inputs,delegations,authority,results,tasks,access,data.db,transport,managed,Duration.ofSeconds(60),Clock.fixed(NOW,ZoneOffset.UTC),mapper);
        startController=new ProductComparisonStartController(app,new ProductComparisonJson(mapper));entry=new ServletServer("human",false,startController);
        browser=HttpClient.newBuilder().sslContext(tls.context(false,true)).connectTimeout(Duration.ofSeconds(5)).build();
    }
    void assertContainerCertificate(HttpServletRequest request){
        assertTrue(request.isSecure());assertEquals("https",request.getScheme());
        Object attribute=request.getAttribute("jakarta.servlet.request.X509Certificate");assertInstanceOf(X509Certificate[].class,attribute);
        var certs=(X509Certificate[])attribute;assertTrue(certs.length>0);assertEquals("CN=Engineering Comparison Worker",certs[0].getSubjectX500Principal().getName());
    }
    final class ServletServer implements AutoCloseable {
        final Tomcat tomcat=new Tomcat();final Connector connector;final AnnotationConfigWebApplicationContext web=new AnnotationConfigWebApplicationContext();
        final boolean clientAuth;
        ServletServer(String role,boolean clientAuth,Object actualController) throws Exception {
            this.clientAuth=clientAuth;Path directory=temporary.resolve(role);Files.createDirectories(directory.resolve("web"));tomcat.setBaseDir(directory.toString());tomcat.setHostname("localhost");
            connector=new Connector("org.apache.coyote.http11.Http11NioProtocol");connector.setPort(0);connector.setScheme("https");connector.setSecure(true);connector.setProperty("address","127.0.0.1");connector.setProperty("SSLEnabled","true");connector.setProperty("maxThreads","10");
            var host=new SSLHostConfig();host.setCertificateVerification(clientAuth?"required":"none");host.setTruststoreFile(tls.root.resolve("trust.p12").toString());host.setTruststorePassword(tls.password);host.setTruststoreType("PKCS12");
            var certificate=new SSLHostConfigCertificate(host,SSLHostConfigCertificate.Type.RSA);certificate.setCertificateKeystoreFile(tls.root.resolve("server.p12").toString());certificate.setCertificateKeystorePassword(tls.password);certificate.setCertificateKeystoreType("PKCS12");certificate.setCertificateKeyAlias("server");host.addCertificate(certificate);connector.addSslHostConfig(host);tomcat.getService().addConnector(connector);tomcat.setConnector(connector);
            Context context=tomcat.addContext("",directory.resolve("web").toString());context.setParentClassLoader(getClass().getClassLoader());
            FilterDef filter=new FilterDef();filter.setFilterName("engineering-boundary");filter.setFilter(new Filter(){
                @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
                    var req=(HttpServletRequest)request;var res=(HttpServletResponse)response;
                    assertTrue(req.isSecure());
                    if(clientAuth){
                        workerServletRequests.incrementAndGet();assertContainerCertificate(req);var cert=((X509Certificate[])req.getAttribute("jakarta.servlet.request.X509Certificate"))[0];
                        tlsRequests.add(Map.of("path",req.getRequestURI(),"dispatcher",req.getDispatcherType().name(),"certificateOrigin","Tomcat JSSE TLS handshake","peer",cert.getSubjectX500Principal().getName(),"issuer",cert.getIssuerX500Principal().getName(),"serial",cert.getSerialNumber().toString(16),"scheme",req.getScheme(),"cipher",String.valueOf(req.getAttribute("jakarta.servlet.request.cipher_suite"))));chain.doFilter(request,response);
                    } else {
                        humanServletRequests.incrementAndGet();assertNull(req.getAttribute("jakarta.servlet.request.X509Certificate"),"Human client must not carry service private key/certificate");
                        if(!"Bearer isolated-human".equals(req.getHeader("Authorization"))){res.sendError(401);return;}
                        var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(new JwtAuthenticationToken(human));SecurityContextHolder.setContext(security);
                        try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
                    }
                }
            });context.addFilterDef(filter);FilterMap map=new FilterMap();map.setFilterName(filter.getFilterName());map.addURLPattern("/*");map.setDispatcher("REQUEST");context.addFilterMap(map);
            web.getEnvironment().setActiveProfiles("comparison-start-servlet-engineering");
            web.register(Mvc.class);web.addBeanFactoryPostProcessor(factory->factory.registerSingleton("actualProductController",actualController));
            var servlet=Tomcat.addServlet(context,"product-dispatcher",new DispatcherServlet(web));servlet.setLoadOnStartup(1);context.addServletMappingDecoded("/","product-dispatcher");tomcat.start();
            assertTrue(connector.getLocalPort()>0);
            assertTrue(web.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class).getHandlerMethods().values().stream().anyMatch(m->m.getBeanType().equals(actualController.getClass())),"Actual product controller must be discovered by DispatcherServlet");
        }
        URI origin(){return URI.create("https://localhost:"+connector.getLocalPort());}
        public void close() throws Exception {tomcat.stop();tomcat.destroy();if(web.isActive())web.close();}
    }
    HttpResponse<String> post(long version,String sha) throws Exception {return browser.send(HttpRequest.newBuilder(entry.origin().resolve(base+"/start-commands")).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer isolated-human").header("Content-Type","application/json").header("Idempotency-Key","human-key").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(new ProductComparisonStartApplication.Start(version,sha)))).build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> get() throws Exception {return browser.send(HttpRequest.newBuilder(entry.origin().resolve(base+"/start-commands?requestId=human-key")).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer isolated-human").GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> response) throws Exception {assertEquals(200,response.statusCode(),response.body());return mapper.readTree(response.body());}
    String command(){return data.db.queryForObject("SELECT command_id FROM con5_start_command",String.class);}
    void event(String scenario,Object... extra){var e=new LinkedHashMap<String,Object>();e.put("scenario",scenario);e.put("layer","REAL_TOMCAT_HTTPS_DISPATCHER_SERVLET_PRODUCT_CONTROLLER_MTLS_FILE_H2");e.put("humanServletRequests",humanServletRequests.get());e.put("workerServletRequests",workerServletRequests.get());e.put("mappedProductClaimCalls",claimCalls.get());e.put("mappedProductExecuteCalls",executeCalls.get());e.put("modelCalls",0);e.put("tlsRequests",List.copyOf(tlsRequests));e.put("formalIssuerAndPolicyVerified",false);e.put("domainClaimAndExecutePortsMocked",true);for(int i=0;i<extra.length;i+=2)e.put(extra[i].toString(),extra[i+1]);events.add(e);}
    @AfterEach void cleanup() throws Exception {release.countDown();if(entry!=null)entry.close();if(worker!=null)worker.close();if(executor!=null){executor.shutdownNow();executor.awaitTermination(10,TimeUnit.SECONDS);}if(temporary!=null)erase(temporary);}

    @Test void actualServletStartWorkerMappingsAndGetPreserveOriginalKeyAttemptFence() throws Exception {
        var receipt=json(post(5,SHA));assertEquals("RECONCILE_REQUIRED",receipt.path("status").asText());assertEquals(attempt,receipt.path("attemptId").asText());assertEquals(1,claimCalls.get());assertEquals(1,executeCalls.get());assertEquals(2,workerServletRequests.get());
        assertEquals(receipt,json(get()));assertEquals(receipt,json(post(5,SHA)));assertEquals(409,post(6,SHA).statusCode());assertEquals(409,post(5,REQUEST).statusCode());assertEquals(2,workerServletRequests.get());
        assertEquals(1L,data.db.queryForObject("SELECT fencing_token FROM con5_start_command",Long.class));assertEquals(1L,data.db.queryForObject("SELECT COUNT(*) FROM con5_start_command",Long.class));
        event("SERVLET_ACTUAL_START_AND_WORKER_MAPPINGS_GET_ORIGINAL_KEY","receipt",receipt,"changedPayloadHttp",409,"executeEngineeringPortHttp",403,"humanHasServiceCertificate",false);
    }
    @Test void tomcatRequiresRealClientCertificateBeforeProductWorkerServlet() throws Exception {
        var request=HttpRequest.newBuilder(worker.origin().resolve(base+"/executions")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
        assertThrows(IOException.class,()->browser.send(request,HttpResponse.BodyHandlers.ofString()));
        var noTrust=HttpClient.newBuilder().sslContext(tls.context(true,false)).connectTimeout(Duration.ofSeconds(5)).build();assertThrows(IOException.class,()->noTrust.send(request,HttpResponse.BodyHandlers.ofString()));
        assertEquals(0,workerServletRequests.get());assertEquals(0,claimCalls.get());assertEquals(0L,data.db.queryForObject("SELECT COUNT(*) FROM con5_start_command",Long.class));
        var unauthenticated=browser.send(HttpRequest.newBuilder(entry.origin().resolve(base+"/start-commands?requestId=human-key")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,unauthenticated.statusCode());
        event("TOMCAT_MTLS_NO_CERT_OR_NO_TRUST_REJECTED_BEFORE_DISPATCHER","clientAuthRequired",true,"engineeringHumanBearerDeniedHttp",401);
    }
    @Test void servletCancellationBeforeLateMappedClaimDeniesStoreBindingAndCurrentRead() throws Exception {
        delayClaim=true;var future=executor.submit(()->post(5,SHA));assertTrue(acquired.await(15,TimeUnit.SECONDS));data.db.update("UPDATE con3_task SET cancel_epoch=1,state='CANCELLED'");release.countDown();
        var receipt=json(future.get(20,TimeUnit.SECONDS));assertEquals("CANCELLED",receipt.path("status").asText());assertTrue(receipt.path("attemptId").isNull());assertEquals(1,claimCalls.get());assertEquals(0,executeCalls.get());
        assertThrows(IllegalStateException.class,()->store.bindAttempt(command(),new ProductComparisonStartCommands.AttemptBinding(attempt,1,UNTIL)));assertThrows(IllegalStateException.class,()->store.commitFromExactReceipt(command(),"report",SHA));
        assertEquals(receipt,json(get()));when(access.permits(eq(human),any(),any())).thenReturn(false);assertEquals(404,get().statusCode());assertEquals(1,workerServletRequests.get());verify(inputs,never()).read(TASK,COMPARISON,human);
        event("SERVLET_CANCEL_LATE_WORKER_CLAIM_GET_CURRENT_READ","receipt",receipt,"lateStoreCommitRejected",true,"cancellationTrigger","engineering SQL; product cancel API not covered","currentReadPortDeniedHttp",404);
    }
    @Test void servletCommittedStorageFixtureGetIsReadOnlyAndSourcePortDenialIsMapped() throws Exception {
        json(post(5,SHA));String sha=data.seedClosure(attempt,managed.configurationVersion());assertEquals("COMMITTED",store.commitFromExactReceipt(command(),"report",sha).status());
        var reopened=data.store();assertEquals("COMMITTED",reopened.lookupOriginalKey(TASK,new ProductComparisonStartCommands.Actor("https://engineering.invalid","human"),"human-key").orElseThrow().status());
        var receipt=json(get());assertEquals("COMMITTED",receipt.path("status").asText());assertEquals(receipt,json(get()));assertEquals(2,workerServletRequests.get());
        when(inputs.read(TASK,COMPARISON,human)).thenThrow(new IllegalStateException("engineering source withdrawal port"));assertEquals(409,get().statusCode());assertEquals(2,workerServletRequests.get());
        event("SERVLET_COMMITTED_STORAGE_FIXTURE_GET_SOURCE_PORT_DENIAL","receipt",receipt,"seededClosureNotModelResult",true,"sourcePortDeniedHttp",409,"normalCalculationPassed",false);
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);int failures=0;var rows=new ArrayList<Map<String,Object>>();certificates();
        try{for(var method:Arrays.stream(ProductComparisonStartServletMtlsTest.class.getDeclaredMethods()).filter(m->m.isAnnotationPresent(Test.class)).sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()){
            var test=new ProductComparisonStartServletMtlsTest();long start=System.nanoTime();Throwable failure=null;
            try{test.setup();method.invoke(test);}catch(Throwable thrown){failure=thrown instanceof java.lang.reflect.InvocationTargetException?thrown.getCause():thrown;failures++;failure.printStackTrace(System.out);}finally{try{test.cleanup();}catch(Throwable cleanup){if(failure==null){failure=cleanup;failures++;}cleanup.printStackTrace(System.out);}}
            var row=new LinkedHashMap<String,Object>();row.put("test",method.getName());row.put("passed",failure==null);row.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));if(failure!=null)row.put("failure",failure.toString());rows.add(row);System.out.println("SERVLET_FOCUSED "+method.getName()+" "+(failure==null?"PASS":"FAIL"));
        }}finally{discardCertificates();}
        var result=Map.of("task","VER-CON-USER-START-MTLS-01","tests",rows.size(),"failures",failures,"skipped",0,"runner","isolated reflection launcher executing JUnit lifecycle/assertions; not Surefire","results",rows,"events",events,"productHttpsFullChainCompleted",false,"servletHttpsProductControllerMappingsVerified",failures==0,"approvedModelCalls",0);
        Files.writeString(output.resolve("focused-result.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"ProductComparisonStartServletMtlsTest\" tests=\""+rows.size()+"\" failures=\""+failures+"\" errors=\"0\" skipped=\"0\">");for(var row:rows){xml.append("<testcase name=\"").append(row.get("test")).append("\" classname=\"cn.xinji.agentplatform.security.product.ProductComparisonStartServletMtlsTest\" time=\"").append(((Long)row.get("elapsedMillis"))/1000.0).append("\">");if(!(Boolean)row.get("passed"))xml.append("<failure message=\"see focused log\"/>");xml.append("</testcase>");}xml.append("</testsuite>");Files.writeString(output.resolve("focused-junit.xml"),xml);if(failures!=0)System.exit(1);
    }
}
