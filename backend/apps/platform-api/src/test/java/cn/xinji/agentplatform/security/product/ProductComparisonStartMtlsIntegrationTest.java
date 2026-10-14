package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonStartMtlsEngineeringSupport.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * FALLBACK engineering layer: real JDK HTTPS entry -> actual StartController/Application ->
 * actual ProductRetrievalMtls -> client-auth-required HTTPS engineering endpoint -> real file H2
 * StartCommands/attempt/fence -> HTTPS GET. NOT embedded product Servlet security, domain claim,
 * source authorization, approved model, DM8 deployment, browser E2E or product HTTPS acceptance.
 * Input/access/delegation ports are explicit mocks; endpoint attempt and committed closure are
 * labelled engineering fixtures, never described as approved calculation results.
 */
class ProductComparisonStartMtlsIntegrationTest {
    static Tls tls;
    static final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    static final List<Map<String,Object>> evidence=new CopyOnWriteArrayList<>();
    final ProductComparisonInputApplication inputs=mock(ProductComparisonInputApplication.class);
    final ProductComparisonWorkerDelegations delegations=mock(ProductComparisonWorkerDelegations.class);
    final ProductWorkerDelegations authority=mock(ProductWorkerDelegations.class);
    final ProductComparisonMatrixReader results=mock(ProductComparisonMatrixReader.class);
    final ProductTaskDirectory tasks=mock(ProductTaskDirectory.class);
    final ProductConsultationAccess access=mock(ProductConsultationAccess.class);
    final Jwt human=Jwt.withTokenValue("isolated-engineering-principal-not-production-token").header("alg","RS256")
            .issuer("https://engineering.invalid").subject("human").build();
    final ProductComparisonStartCommands.ManagedBinding managed=new ProductComparisonStartCommands.ManagedBinding("worker","engineering-configuration-version");
    final AtomicInteger workerCalls=new AtomicInteger(), executeCalls=new AtomicInteger();
    final CountDownLatch acquired=new CountDownLatch(1), release=new CountDownLatch(1);
    final List<String> peers=new CopyOnWriteArrayList<>();
    Path temporary;
    ProductComparisonStartMtlsEngineeringSupport data;
    ProductComparisonStartCommands store;
    ProductComparisonStartApplication application;
    ProductComparisonStartController controller;
    HttpsServer worker,entry;
    ExecutorService executor;
    HttpClient browser;
    URI workerOrigin,entryOrigin;
    String mode="disconnect";
    String attempt=UUID.randomUUID().toString(), delegation=UUID.randomUUID().toString();
    String base="/v2/consultation/tasks/"+TASK+"/patent-comparisons/"+COMPARISON+"/candidates/"+CANDIDATE;

    @BeforeAll static void certificates() throws Exception {tls=new Tls();}
    @AfterAll static void discardCertificates() throws Exception {if(tls!=null)tls.close();}
    @BeforeEach void setup() throws Exception {
        temporary=Files.createTempDirectory("comparison-start-mtls-data-");
        data=new ProductComparisonStartMtlsEngineeringSupport(temporary.resolve("persistent"));store=data.store();
        var envelope=mock(ProductComparisonInputs.Envelope.class);var view=mock(ProductComparisonInputs.View.class);
        when(view.input()).thenReturn(envelope);when(view.inputSha()).thenReturn(SHA);
        when(envelope.expiresAt()).thenReturn(NOW.plusSeconds(600));when(envelope.candidates()).thenReturn(List.of(new ProductComparisonInputs.Candidate(CANDIDATE,0)));
        when(inputs.consume(TASK,COMPARISON,human)).thenReturn(view);when(inputs.read(TASK,COMPARISON,human)).thenReturn(view);
        when(tasks.find(TASK)).thenReturn(Optional.of(new ProductTaskDirectory.Task(TASK,"engineering-org","https://engineering.invalid","human","IN_PROGRESS",5,17,"engineering-scope",1,NOW.plusSeconds(900))));
        when(access.permits(eq(human),any(),any())).thenReturn(true);
        when(results.results(TASK,COMPARISON,human)).thenReturn(new ProductComparisonMatrixReader.Results(TASK,COMPARISON,SHA,"IN_PROGRESS",List.of(),List.of()));
        when(delegations.issue(eq(TASK),eq(COMPARISON),any(),eq(human),anyString())).thenAnswer(call->{
            data.delegation(delegation);return new ProductComparisonWorkerDelegations.Receipt(delegation,1,"ACTIVE",COMPARISON,SHA,CANDIDATE,UNTIL);
        });
        executor=Executors.newCachedThreadPool();worker=server(true);workerOrigin=origin(worker);
        worker.createContext("/v2/consultation/",this::workerRequest);worker.start();
        rebuild();entry=server(false);entryOrigin=origin(entry);entry.createContext(base+"/start",this::humanRequest);entry.start();
        browser=HttpClient.newBuilder().sslContext(tls.context(false,true)).connectTimeout(Duration.ofSeconds(5)).build();
    }
    void rebuild(){
        var transport=ProductRetrievalMtls.create(workerOrigin,tls.root.resolve("worker.p12"),tls.password.toCharArray(),tls.root.resolve("trust.p12"),tls.password.toCharArray(),mapper);
        application=new ProductComparisonStartApplication(store,inputs,delegations,authority,results,tasks,access,data.db,transport,managed,Duration.ofSeconds(60),Clock.fixed(NOW,ZoneOffset.UTC),mapper);
        controller=new ProductComparisonStartController(application,new ProductComparisonJson(mapper));
    }
    HttpsServer server(boolean clientAuth) throws Exception {
        var s=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        s.setHttpsConfigurator(new HttpsConfigurator(tls.context("server",true)){
            @Override public void configure(HttpsParameters parameters){var p=getSSLContext().getDefaultSSLParameters();p.setNeedClientAuth(clientAuth);parameters.setSSLParameters(p);}
        });s.setExecutor(executor);return s;
    }
    URI origin(HttpsServer server){return URI.create("https://localhost:"+server.getAddress().getPort());}
    void humanRequest(HttpExchange exchange) throws IOException {
        // Engineering HTTP adapter only; no fabricated Servlet certificate attributes.
        try {
            if(!"Bearer isolated-human".equals(exchange.getRequestHeaders().getFirst("Authorization"))){reply(exchange,401,Map.of("code","ENGINEERING_AUTH_REQUIRED"));return;}
            if("POST".equals(exchange.getRequestMethod()))reply(exchange,200,controller.start(TASK,COMPARISON,CANDIDATE,exchange.getRequestHeaders().getFirst("Idempotency-Key"),new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),human));
            else if("GET".equals(exchange.getRequestMethod()))reply(exchange,200,controller.read(TASK,COMPARISON,CANDIDATE,"human-key",human));
            else reply(exchange,405,Map.of("code","METHOD_NOT_ALLOWED"));
        } catch(ResponseStatusException denied){reply(exchange,denied.getStatusCode().value(),Map.of("code","CURRENT_ACCESS_DENIED"));}
        catch(IllegalArgumentException invalid){reply(exchange,400,Map.of("code","INVALID_COMPARISON_START"));}
        catch(IllegalStateException conflict){reply(exchange,409,Map.of("code","COMPARISON_START_UNAVAILABLE"));}
        catch(Exception unexpected){reply(exchange,500,Map.of("code","ENGINEERING_ADAPTER_FAILURE"));}
        finally{exchange.close();}
    }
    void workerRequest(HttpExchange exchange) throws IOException {
        try {
            var session=((HttpsExchange)exchange).getSSLSession();peers.add(session.getPeerPrincipal().getName());
            workerCalls.incrementAndGet();
            if(exchange.getRequestURI().getPath().endsWith("/patent-comparison-result")){executeCalls.incrementAndGet();reply(exchange,403,Map.of("code","NO_MODEL_APPROVAL"));return;}
            JsonNode body=mapper.readTree(exchange.getRequestBody());
            assertEquals(SHA,body.path("inputSha").asText());assertEquals(delegation,body.path("delegationId").asText());
            String request=exchange.getRequestHeaders().getFirst("Idempotency-Key");assertTrue(request.startsWith("start:"));
            data.attempt(attempt,delegation,request);acquired.countDown();
            if("delay".equals(mode)||"cancel".equals(mode))release.await(100,TimeUnit.SECONDS);
            if("claim".equals(mode)||"cancel".equals(mode))reply(exchange,200,new ProductComparisonCandidateExecution.Lease(attempt,1,UNTIL));
            // disconnect/delay: persisted request outcome, deliberately no response headers.
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception failure){reply(exchange,500,Map.of("code","ENGINEERING_ENDPOINT_FAILURE"));}
        finally{exchange.close();}
    }
    void reply(HttpExchange exchange,int code,Object object) throws IOException {
        byte[] bytes=mapper.writeValueAsBytes(object);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
    }
    HttpResponse<String> post(long version,String sha) throws Exception {
        return browser.send(HttpRequest.newBuilder(entryOrigin.resolve(base+"/start")).timeout(Duration.ofSeconds(110))
                .header("Authorization","Bearer isolated-human").header("Idempotency-Key","human-key").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(new ProductComparisonStartApplication.Start(version,sha)))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get() throws Exception {return browser.send(HttpRequest.newBuilder(entryOrigin.resolve(base+"/start?requestId=human-key")).header("Authorization","Bearer isolated-human").GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> response) throws Exception {assertEquals(200,response.statusCode(),response.body());return mapper.readTree(response.body());}
    String status(){return data.db.queryForObject("SELECT status FROM con5_start_command",String.class);}
    String command(){return data.db.queryForObject("SELECT command_id FROM con5_start_command",String.class);}
    void event(String scenario,Object... values){var e=new LinkedHashMap<String,Object>();e.put("scenario",scenario);e.put("layer","JDK_HTTPS_ENGINEERING_ADAPTER_REAL_PRODUCT_START_TRANSPORT_FILE_H2");e.put("workerHttpRequests",workerCalls.get());e.put("modelCalls",0);e.put("peerPrincipals",List.copyOf(peers));for(int i=0;i<values.length;i+=2)e.put(values[i].toString(),values[i+1]);evidence.add(e);}
    @AfterEach void cleanup() throws Exception {
        release.countDown();if(entry!=null)entry.stop(0);if(worker!=null)worker.stop(0);
        if(executor!=null){executor.shutdownNow();executor.awaitTermination(10,TimeUnit.SECONDS);}if(temporary!=null)erase(temporary);
    }

    @Test void requiredClientCertificateAndUntrustedServerFailBeforeHttpEntry() throws Exception {
        for(boolean trust:List.of(true,false)){
            var unauthenticated=HttpClient.newBuilder().sslContext(tls.context(false,trust)).connectTimeout(Duration.ofSeconds(5)).build();
            assertThrows(IOException.class,()->unauthenticated.send(HttpRequest.newBuilder(workerOrigin.resolve(base+"/executions")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()));
        }
        assertEquals(0,workerCalls.get());
        var untrusted=ProductRetrievalMtls.create(workerOrigin,tls.root.resolve("worker.p12"),tls.password.toCharArray(),tls.root.resolve("no-trust.p12"),tls.password.toCharArray(),mapper);
        assertThrows(IllegalStateException.class,()->untrusted.post(base+"/executions",Map.of(),"engineering-untrusted"));
        assertEquals(0,workerCalls.get());
        event("TLS_CLIENT_AUTH_NO_BROWSER_SERVICE_CERT","rejectedBeforeHandler",true);
    }
    @Test void disconnectOriginalKeyReconcilesPersistedAttemptWithoutResendAndConflict() throws Exception {
        var receipt=json(post(5,SHA));assertEquals("RECONCILE_REQUIRED",receipt.path("status").asText());assertEquals(attempt,receipt.path("attemptId").asText());
        assertEquals(1,workerCalls.get());assertEquals(0,executeCalls.get());assertFalse(peers.isEmpty());
        assertEquals(receipt,json(post(5,SHA)));assertEquals(409,post(6,SHA).statusCode());assertEquals(409,post(5,REQUEST).statusCode());
        // Close/reopen file H2 and reconstruct actual product commands/application: no memory receipt cache.
        store=data.store();rebuild();assertEquals(receipt,json(get()));
        assertEquals(1,workerCalls.get());assertEquals(1L,data.db.queryForObject("SELECT fencing_token FROM con5_start_command",Long.class));
        assertEquals(1L,data.db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Long.class));
        event("DISCONNECT_ORIGINAL_KEY_FILE_STORE_REOPEN","receipt",receipt,"changedPayloadHttp",409,"fence",1,"executeRequests",executeCalls.get());
    }
    @Test void currentAccessAndSourceWithdrawalAtExplicitMockPortsCannotResend() throws Exception {
        json(post(5,SHA));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(authority).requireComparisonActor(TASK,human);
        when(access.permits(eq(human),any(),any())).thenReturn(false);
        assertEquals(404,post(5,SHA).statusCode());assertEquals(404,get().statusCode());
        when(access.permits(eq(human),any(),any())).thenReturn(true);
        when(inputs.read(TASK,COMPARISON,human)).thenThrow(new IllegalStateException("engineering source withdrawal port"));
        assertEquals(409,get().statusCode());assertEquals(1,workerCalls.get());
        event("WITHDRAWAL_APPLICATION_PORT_BOUNDARY_ONLY","realProductAuthorization",false,"readDenied",true);
    }
    @Test void cancellationBeforeLateClaimRejectsAttemptBindingAndReturnsOnlyCoordinates() throws Exception {
        mode="cancel";var future=executor.submit(()->post(5,SHA));assertTrue(acquired.await(15,TimeUnit.SECONDS));
        data.db.update("UPDATE con3_task SET cancel_epoch=1,state='CANCELLED'");release.countDown();
        var receipt=json(future.get(20,TimeUnit.SECONDS));assertEquals("CANCELLED",receipt.path("status").asText());assertTrue(receipt.path("attemptId").isNull());
        assertThrows(IllegalStateException.class,()->store.bindAttempt(command(),new ProductComparisonStartCommands.AttemptBinding(attempt,1,UNTIL)));
        assertThrows(IllegalStateException.class,()->store.commitFromExactReceipt(command(),"report",SHA));
        assertEquals(receipt,json(get()));assertEquals(1,workerCalls.get());assertEquals(0,executeCalls.get());
        verify(inputs,never()).read(TASK,COMPARISON,human);
        event("CANCEL_LATE_CLAIM_REJECTED","receipt",receipt,"lateResultStoreCommitRejected",true,"cancellationMechanism","engineering SQL epoch change, not product cancel API");
    }
    @Test void actualProductNinetySecondTimeoutReconcilesWithoutNewKeyOrRetry() throws Exception {
        mode="delay";long start=System.nanoTime();var receipt=json(post(5,SHA));long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);release.countDown();
        assertTrue(millis>=89000,"Must exercise unchanged real product 90-second timeout");
        assertEquals("RECONCILE_REQUIRED",receipt.path("status").asText());assertEquals(attempt,receipt.path("attemptId").asText());
        assertEquals(receipt,json(post(5,SHA)));assertEquals(receipt,json(get()));assertEquals(1,workerCalls.get());
        event("REAL_TRANSPORT_90_SECOND_TIMEOUT","elapsedMillis",millis,"receipt",receipt,"workerClaimFixturePersisted",true);
    }
    @Test void committedEngineeringStorageFixtureRestoresByGetWithoutWorkerOrModel() throws Exception {
        json(post(5,SHA));String sha=data.seedClosure(attempt,managed.configurationVersion());
        assertEquals("COMMITTED",store.commitFromExactReceipt(command(),"report",sha).status());
        store=data.store();rebuild();var receipt=json(get());assertEquals("COMMITTED",receipt.path("status").asText());assertEquals(attempt,receipt.path("attemptId").asText());
        assertEquals(receipt,json(get()));assertEquals(1,workerCalls.get());verify(results,never()).report(anyString(),anyString(),anyString(),any());
        assertEquals(1L,data.db.queryForObject("SELECT COUNT(*) FROM con5_matrix_receipt",Long.class));
        event("COMMITTED_STORAGE_FIXTURE_GET_ONLY","receipt",receipt,"reportSha",sha,"seededClosureNotModelResult",true,"normalCalculationPassed",false);
    }
    @Test void validMtlsClaimThenModelApprovalDenialLeavesOriginalAttemptForGet() throws Exception {
        mode="claim";var receipt=json(post(5,SHA));assertEquals("RECONCILE_REQUIRED",receipt.path("status").asText());assertEquals(attempt,receipt.path("attemptId").asText());
        assertEquals(2,workerCalls.get());assertEquals(1,executeCalls.get());assertEquals(receipt,json(get()));assertEquals(receipt,json(post(5,SHA)));assertEquals(2,workerCalls.get());
        event("MTLS_CLAIM_THEN_ENGINEERING_EXECUTE_DENIAL","receipt",receipt,"executeHttpStatus",403,"formalModelAdmissionExercised",false);
    }

    /** Isolated no-Maven launcher; JUnit assertions/lifecycle execute; emits honest focused XML + raw JSON. */
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);int failures=0;var rows=new ArrayList<Map<String,Object>>();
        certificates();try {
            for(var method:Arrays.stream(ProductComparisonStartMtlsIntegrationTest.class.getDeclaredMethods()).filter(m->m.isAnnotationPresent(Test.class)).sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()){
                var test=new ProductComparisonStartMtlsIntegrationTest();long start=System.nanoTime();Throwable failure=null;
                try{test.setup();method.invoke(test);}catch(Throwable thrown){failure=thrown instanceof java.lang.reflect.InvocationTargetException?thrown.getCause():thrown;failures++;failure.printStackTrace(System.out);}
                finally{try{test.cleanup();}catch(Throwable cleanup){if(failure==null){failure=cleanup;failures++;}cleanup.printStackTrace(System.out);}}
                var row=new LinkedHashMap<String,Object>();row.put("test",method.getName());row.put("passed",failure==null);row.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));if(failure!=null)row.put("failure",failure.toString());rows.add(row);System.out.println("FOCUSED "+method.getName()+" "+(failure==null?"PASS":"FAIL"));
            }
        } finally{discardCertificates();}
        var result=Map.of("task","VER-CON-USER-START-MTLS-01","tests",rows.size(),"failures",failures,"skipped",0,"runner","isolated reflection launcher executing JUnit Test/BeforeEach/AfterEach assertions; not Maven Surefire","results",rows,"events",evidence,"productHttpsFullChainCompleted",false,"approvedModelCalls",0);
        Files.writeString(output.resolve("focused-result.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"ProductComparisonStartMtlsIntegrationTest\" tests=\""+rows.size()+"\" failures=\""+failures+"\" errors=\"0\" skipped=\"0\">");
        for(var row:rows){xml.append("<testcase name=\"").append(row.get("test")).append("\" classname=\"cn.xinji.agentplatform.security.product.ProductComparisonStartMtlsIntegrationTest\" time=\"").append(((Long)row.get("elapsedMillis"))/1000.0).append("\">");if(!(Boolean)row.get("passed"))xml.append("<failure message=\"see focused log\"/>");xml.append("</testcase>");}xml.append("</testsuite>");Files.writeString(output.resolve("focused-junit.xml"),xml);
        if(failures!=0)System.exit(1);
    }
}
