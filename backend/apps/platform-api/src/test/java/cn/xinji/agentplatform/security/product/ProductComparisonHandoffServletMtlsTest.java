package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonStartMtlsEngineeringSupport.*;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual HTTPS/mTLS and persisted receipt. Issuer/source ports remain declared doubles, not a formal security chain. */
class ProductComparisonHandoffServletMtlsTest {
    ProductComparisonStartServletMtlsTest h;
    @BeforeAll static void certificates() throws Exception {ProductComparisonStartServletMtlsTest.certificates();}
    @AfterAll static void certificatesClosed() throws Exception {ProductComparisonStartServletMtlsTest.discardCertificates();}
    @BeforeEach void setup() throws Exception {
        h=new ProductComparisonStartServletMtlsTest();h.setup();h.entry.close();h.entry=null;h.worker.close();h.worker=null;
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!Files.isDirectory(root.resolve("db/dm8")))root=root.getParent();
        String sql=Files.readString(root.resolve("db/dm8/V105__comparison_start_handoff_receipt.sql")).replaceAll("(?m)^\\s*--[^\\r\\n]*","");
        for(String statement:sql.split(";"))if(!statement.isBlank())h.data.db.execute(statement);
    }
    @AfterEach void cleanup() throws Exception {if(h!=null)h.cleanup();}
    void wire(boolean loseResponse) throws Exception {
        var json=new ProductComparisonJson(ProductComparisonStartServletMtlsTest.mapper);
        var handoffs=new ProductComparisonHandoffStore(h.data.db,h.data.tx,h.store,json,Clock.fixed(NOW,ZoneOffset.UTC));
        var materials=mock(ProductComparisonWorkerMaterials.class);var loaded=materialFixture();
        when(materials.consume(any(),eq(TASK),eq(COMPARISON),eq(CANDIDATE),eq(SHA),anyString())).thenAnswer(call->{h.assertContainerCertificate(call.getArgument(0));return loaded;});
        h.worker=h.new ServletServer("layered-worker",true,new ProductComparisonHandoffController(h.store,materials,handoffs,json));
        var tls=ProductComparisonStartServletMtlsTest.tls;
        var actual=ProductRetrievalMtls.create(h.worker.origin(),tls.root.resolve("worker.p12"),tls.password.toCharArray(),tls.root.resolve("trust.p12"),tls.password.toCharArray(),ProductComparisonStartServletMtlsTest.mapper);
        ProductRetrievalMtls.Transport transport=(path,body,key)->{var response=actual.post(path,body,key);if(loseResponse)throw new IllegalStateException("actual response lost after server committed");return response;};
        var app=new ProductComparisonStartApplication(h.store,h.inputs,h.delegations,h.authority,h.results,h.tasks,h.access,h.data.db,transport,
                new ProductComparisonStartCommands.ManagedBinding("worker","HANDOFF_ONLY:managed-handoff-v1"),Duration.ofSeconds(60),Clock.fixed(NOW,ZoneOffset.UTC),ProductComparisonStartServletMtlsTest.mapper,handoffs);
        h.entry=h.new ServletServer("layered-human",false,new ProductComparisonStartController(app,json));
    }
    /** Complete value fixture for this declared port-double transport test, not a source admission. */
    ProductComparisonWorkerMaterials.Loaded materialFixture() throws Exception {
        var mapper=ProductComparisonStartServletMtlsTest.mapper;
        byte[] bytes=mapper.writeValueAsBytes(Map.of("claimsText","1. Owned transport unit passage."));
        String digest=ProductCapturedArchive.digest(bytes),version="RAW_SHA256:"+digest;
        var original=new ProductPatentClaimOriginal("unit-original.json",bytes,digest,version,"claimsText",mapper);
        var ref=new ProductComparisonContracts.DocumentRef("unit-source","unit-record",version,java.util.List.of(1),null,null,null,null);
        var proof=new ProductComparisonContracts.MaterialDocument("unit-key","unit-source","unit-record",version,"v1",
                digest,"unit-original.json","/claimsText",java.util.List.of(),null);
        var document=new ProductComparisonDocuments.Loaded(ref,original,proof,new ProductComparisonDocuments.Authority(null,null,null,UNTIL));
        return new ProductComparisonWorkerMaterials.Loaded(h.inputs.consume(TASK,COMPARISON,h.human),original,null,document,
                new ProductComparisonWorkerTaskAuthority.Authorized("worker","human",TASK,5,1,UNTIL),UNTIL);
    }
    void evidence(String scenario,Object receipt) throws Exception {
        String run=System.getProperty("surefire.reportNameSuffix","focused").replaceAll("[^A-Za-z0-9._-]","_");
        Path output=Path.of("target/handoff-layering-evidence",run,scenario+"-"+java.util.UUID.randomUUID());Files.createDirectories(output);
        var raw=new java.util.LinkedHashMap<String,Object>();raw.put("scenario",scenario);raw.put("observedAt",Instant.now().toString());raw.put("effectiveEngineeringClock",NOW.toString());
        raw.put("taskId","PC-COMPARISON-ADMISSION-LAYERS-01");raw.put("transport","REAL_TOMCAT_HTTPS_MTLS_PRODUCT_RECEIPT_CONTROLLER");raw.put("tls",java.util.List.copyOf(h.tlsRequests));
        raw.put("currentHumanReceipt",receipt);raw.put("persistentReceiptJson",h.data.db.queryForList("SELECT receipt_json FROM con5_start_handoff_receipt",String.class));
        raw.put("candidateAttempts",h.data.db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Integer.class));raw.put("modelInvocations",h.data.db.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation",Integer.class));
        raw.put("workerServletRequests",h.workerServletRequests.get());raw.put("identityAndSourcePorts","DECLARED_ENGINEERING_DOUBLES_NOT_REAL_ISSUER_SOURCE");raw.put("prepareAndConfirmationExecuted",false);
        raw.put("formalSecurityChainAccepted",false);raw.put("matrixCalculated",false);
        Files.writeString(output.resolve("evidence.json"),ProductComparisonStartServletMtlsTest.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw),StandardOpenOption.CREATE_NEW);
    }
    @Test void actualWorkerReceivesNoComputationAndOriginalGetProvesReceipt() throws Exception {
        wire(false);var receipt=h.json(h.post(5,SHA));assertEquals("ADMISSION_REQUIRED",receipt.path("status").asText());assertTrue(receipt.path("attemptId").isNull());
        assertEquals(receipt,h.json(h.get()));assertEquals(receipt,h.json(h.post(5,SHA)));assertEquals(409,h.post(6,SHA).statusCode());
        assertEquals(1,h.workerServletRequests.get());assertEquals(0,h.claimCalls.get());assertEquals(0,h.executeCalls.get());
        assertEquals(0,h.data.db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Integer.class));assertEquals(0,h.data.db.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation",Integer.class));
        assertEquals(1,h.data.db.queryForObject("SELECT COUNT(*) FROM con5_start_handoff_receipt",Integer.class));assertEquals("QUEUED",h.data.db.queryForObject("SELECT status FROM con5_comparison_candidate",String.class));
        assertTrue(h.tlsRequests.size()>0);assertTrue(h.tlsRequests.stream().allMatch(e->e.get("peer").equals("CN=Engineering Comparison Worker")));evidence("received-and-replayed",receipt);
    }
    @Test void lostActualResponseReconcilesPersistedReceiptWithoutResending() throws Exception {
        wire(true);var receipt=h.json(h.post(5,SHA));assertEquals("ADMISSION_REQUIRED",receipt.path("status").asText());
        assertEquals(receipt,h.json(h.get()));assertEquals(receipt,h.json(h.post(5,SHA)));assertEquals(1,h.workerServletRequests.get());evidence("response-lost-read-only-recovery",receipt);
    }
    @Test void cancelAfterReceiptHasPriorityAndCannotClaim() throws Exception {
        wire(false);assertEquals("ADMISSION_REQUIRED",h.json(h.post(5,SHA)).path("status").asText());h.data.db.update("UPDATE con3_task SET cancel_epoch=cancel_epoch+1");
        assertEquals("CANCELLED",h.json(h.get()).path("status").asText());assertEquals(1,h.workerServletRequests.get());assertEquals(0,h.data.db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Integer.class));evidence("cancellation-priority",h.json(h.get()));
    }
}
