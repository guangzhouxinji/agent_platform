package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit licensed run only. Real product endpoints, registered principals, HTTPS/mTLS and durable receipt. */
@EnabledIfSystemProperty(named="comparison.claims.approval", matches=".+")
class ProductComparisonApprovedClaimsChainTest {
    @Test void approvedInputRealHandoffAndOriginalKeyRecoveryWithoutCalculation() throws Exception {
        try(var env=new ProductComparisonRealSecurityEnvironment(true)) {
            String source="approved-project-claims";
            var member=env.post(env.maintainer,"/v2/identity/subjects",Map.of("loginName","owned-security-user"),"claims-user",null,200);
            String subject=member.path("subjectId").asText();
            env.post(env.browser,"/v2/identity/subjects/"+subject+"/activate",Map.of("activation",member.path("oneTimeActivation").asText(),"password",env.password),null,null,200);
            env.post(env.maintainer,"/v2/identity/clients",new ProductClients.Input("owned-security-client","Owned engineering HTTPS client",env.origin+"/callback"),"claims-client",null,200);
            env.post(env.maintainer,"/v2/identity/keys/rotate",Map.of(),"claims-key",null,200);
            String token=env.login("owned-security-user",env.password);
            var task=env.post(env.maintainer,"/v2/identity/organizations/initial-task",
                    new ProductInitialTaskService.Input(env.organization,"Approved project-authored input engineering, not a formal case",subject,"owned-engineering-retention",1,env.until),"claims-task",null,200);
            String taskId=task.path("taskId").asText(),path="/v2/consultation/tasks/"+taskId;
            String dataScope=env.primary.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",String.class,taskId)+":1";
            for(String action:List.of("WRITE","COMPARE")) {
                var scope=new ProductGrants.Scope(env.issuer,subject,env.organization,"CONSULTATION",taskId,dataScope,"TASK",taskId,1,action,"TASK_WORK");
                env.post(env.maintainer,"/v2/identity/grants",new ProductIdentityController.IssueGrant(scope,env.until,0),"claims-"+action.toLowerCase(),null,200);
            }
            var goal=env.post(env.browser,path+"/goals/confirm",new ProductTaskGoalService.Input(1,
                    "Explicit engineering comparison of licensed project-authored claim passages", "COMPARE","owned-engineering",
                    List.of(source),List.of("CLAIMS"),List.of("selected source passage"),true,"owned-engineering-retention",1),"claims-goal",token,200);
            String workloadSha=ProductComparisonRealSecurityEnvironment.sha(ProductComparisonRealSecurityEnvironment.certificate(env.tls.root.resolve("worker.cer")).getEncoded());
            var worker=env.post(env.maintainer,"/v2/identity/workloads",Map.of("certificateSha256",workloadSha,"kind","AGENT","expiresAt",env.until),"claims-worker",null,200);
            String workerSubject=worker.path("subjectId").asText();
            env.post(env.maintainer,"/v2/identity/workloads/"+workerSubject+"/comparison-task-grants",
                    new ProductWorkloadController.GrantTask(taskId,env.until,0),"claims-worker-grant",null,200);
            var materials=new ProductComparisonApprovedClaimsSource(env,taskId,subject);
            materials.register(Path.of(System.getProperty("comparison.claims.approval")),Path.of(System.getProperty("comparison.claims.workspace")));
            env.enableStart(workerSubject);
            // Direct workbench consumes server-produced references, without QUERY or retrieval Selection.
            var workface=env.send(env.browser,"GET",path+"/retrieval-workspace",null,null,token,null);
            assertEquals(200,workface.statusCode(),workface.body());
            assertEquals(taskId,env.json.readTree(workface.body()).path("task").path("id").asText());
            assertEquals(503,env.send(env.browser,"POST",path+"/retrievals/execute","{\"expectedTaskVersion\":2}","no-retrieval",token,"application/json").statusCode());
            String catalogPath=path+"/patent-comparisons/documents";
            var catalogResponse=env.send(env.browser,"GET",catalogPath,null,null,token,null);
            assertEquals(200,catalogResponse.statusCode(),catalogResponse.body());
            var catalog=env.json.readTree(catalogResponse.body());
            assertEquals(taskId,catalog.path("taskId").asText());assertEquals(2,catalog.path("taskVersion").asLong());
            assertEquals(goal.path("inputId").asText(),catalog.path("inputId").asText());
            assertEquals(2,catalog.path("documents").size());
            var firstPage=env.json.readTree(env.send(env.browser,"GET",catalogPath+"?offset=0&limit=1",null,null,token,null).body());
            assertEquals(1,firstPage.path("documents").size());assertEquals(1,firstPage.path("nextOffset").asInt());
            var secondPage=env.json.readTree(env.send(env.browser,"GET",catalogPath+"?offset=1&limit=1",null,null,token,null).body());
            assertTrue(secondPage.path("nextOffset").isNull());
            assertNotEquals(firstPage.path("documents").get(0).path("key").asText(),secondPage.path("documents").get(0).path("key").asText());
            assertEquals(400,env.send(env.browser,"GET",catalogPath+"?offset=-1",null,null,token,null).statusCode());
            assertNotEquals(200,env.send(env.browser,"GET",catalogPath.replace(taskId,UUID.randomUUID().toString()),null,null,token,null).statusCode());
            assertEquals(401,env.send(env.browser,"GET",catalogPath,null,null,null,null).statusCode());
            var directRefs=new HashMap<String,ProductComparisonContracts.DocumentRef>();
            for(var doc:catalog.path("documents")) {
                assertFalse(doc.path("claimsText").asText().isBlank());assertEquals(List.of(1),env.json.convertValue(doc.path("claimNumbers"),new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>(){}));
                assertEquals(ProductComparisonJson.hash(doc.path("sourceId").asText()+"\n"+doc.path("documentId").asText()+"\n"+doc.path("documentVersion").asText()),doc.path("key").asText());
                directRefs.put(doc.path("documentId").asText(),new ProductComparisonContracts.DocumentRef(doc.path("sourceId").asText(),doc.path("documentId").asText(),doc.path("documentVersion").asText(),List.of(1),null,null,null,null));
            }
            var prepareCommand=new ProductComparisonContracts.Prepare(2,goal.path("goalId").asText(),1,goal.path("inputId").asText(),"CLAIM_ALIGNMENT",
                    new ProductComparisonContracts.Target(null,directRefs.get("P1")),List.of(directRefs.get("P2")));
            var preparation=env.post(env.browser,path+"/patent-comparisons/prepare",prepareCommand,"claims-prepare",token,200);
            assertTrue(preparation.path("targetReady").asBoolean());assertFalse(preparation.path("targetText").asText().isBlank());
            assertEquals(preparation,env.post(env.browser,path+"/patent-comparisons/prepare",prepareCommand,"claims-prepare",token,200));
            String target=preparation.path("targetText").asText();
            var confirm=new ProductComparisonContracts.Confirm(2,preparation.path("preparationId").asText(),preparation.path("prepareSha").asText(),0,true,
                    "Explicit engineering operator selection of the displayed full target passage; not professional review",
                    List.of(new ProductComparisonContracts.FeatureInput("F1",3,target.length(),target.substring(3),null)));
            var feature=env.post(env.browser,path+"/patent-comparisons/features/confirm",confirm,"claims-features",token,200);
            var create=new ProductComparisonInputs.Create(2,feature.path("revision").path("revisionId").asText(),feature.path("revision").path("revisionNumber").asLong(),
                    feature.path("packageSha").asText(),null,"PATENT_FEATURE_MATRIX_V1");
            var input=env.post(env.browser,path+"/patent-comparisons",create,"claims-input",token,200);
            String comparison=input.path("comparisonId").asText(),inputSha=input.path("inputSha").asText(),candidate=input.path("candidates").get(0).path("candidateId").asText();
            assertFalse(comparison.isBlank());assertFalse(inputSha.isBlank());
            String endpoint=path+"/patent-comparisons/"+comparison+"/candidates/"+candidate+"/start-commands";
            var command=new ProductComparisonStartApplication.Start(2,inputSha);
            var receipt=env.post(env.browser,endpoint,command,"claims-start",token,200);
            assertEquals("ADMISSION_REQUIRED",receipt.path("status").asText());assertTrue(receipt.path("attemptId").isNull());
            assertEquals(receipt,env.post(env.browser,endpoint,command,"claims-start",token,200));
            assertEquals(receipt,env.json.readTree(env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).body()));
            // A lost HTTP response is reconciled by the original GET; no new command, lease or dispatch.
            assertEquals(receipt,env.json.readTree(env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).body()));
            env.post(env.browser,endpoint,new ProductComparisonStartApplication.Start(2,"0".repeat(64)),"claims-start",token,409);
            assertNotEquals(200,env.send(env.browser,"GET",endpoint.replace(taskId,UUID.randomUUID().toString())+"?requestId=claims-start",null,null,token,null).statusCode());
            assertEquals(1,env.primary.queryForObject("SELECT COUNT(*) FROM con5_start_handoff_receipt",Integer.class));
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Integer.class));
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_matrix_report",Integer.class));
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation",Integer.class));
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_candidate_lease",Integer.class));
            env.restart();
            assertEquals(receipt,env.json.readTree(env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).body()));
            String replacement=env.login("owned-security-user",env.password);
            env.post(env.browser,"/v2/identity/logout",Map.of(),null,token,200);
            assertEquals(401,env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).statusCode());
            token=replacement;
            assertEquals(receipt,env.json.readTree(env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).body()));
            env.post(env.maintainer,"/v2/consultation/source-contracts/"+source+"/withdraw-product-use",
                    Map.of("expectedEpoch",materials.admitted.path("sourceEpoch").asLong()),"claims-withdraw",null,200);
            assertNotEquals(200,env.send(env.browser,"GET",path+"/patent-comparisons/"+comparison,null,null,token,null).statusCode());
            assertNotEquals(200,env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).statusCode());
            assertNotEquals(200,env.send(env.browser,"GET",catalogPath,null,null,token,null).statusCode());
            var cancel=env.post(env.browser,path+"/cancel",Map.of("expectedTaskVersion",2),"claims-cancel",token,200);
            var cancelled=env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null);
            assertEquals(200,cancelled.statusCode());assertEquals("CANCELLED",env.json.readTree(cancelled.body()).path("status").asText());

            String commandId=receipt.path("commandId").asText();
            var bound=env.primary.queryForMap("SELECT delegation_id,request_sha FROM con5_start_command WHERE command_id=?",commandId);
            var actualWorker=java.net.http.HttpClient.newBuilder().sslContext(env.tls.context("worker",true)).build();
            var late=new ProductComparisonHandoffController.Request(inputSha,(String)bound.get("DELEGATION_ID"),(String)bound.get("REQUEST_SHA"));
            assertNotEquals(200,env.send(actualWorker,"POST",endpoint+"/"+commandId+"/handoff-receipts",env.json.writeValueAsString(late),"start:"+commandId,null,"application/json").statusCode());
            assertEquals(1,env.primary.queryForObject("SELECT COUNT(*) FROM con5_start_handoff_receipt",Integer.class));
            String readGrant=env.primary.queryForObject("SELECT grant_id FROM id2_grant WHERE subject_id=? AND resource_id=? AND action_code='READ' AND status='ACTIVE'",String.class,subject,taskId);
            long readVersion=env.primary.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,readGrant);
            env.post(env.maintainer,"/v2/identity/grants/"+readGrant+"/revoke",Map.of("expectedVersion",readVersion),"claims-read-revoke",null,200);
            assertNotEquals(200,env.send(env.browser,"GET",endpoint+"?requestId=claims-start",null,null,token,null).statusCode());
            var out=env.json.createObjectNode();out.put("layer","REAL_ISSUER_SOURCE_HTTPS_MTLS_HANDOFF_NO_MODEL");
            out.put("sourceDecisionRegistered",true);out.put("taskId",taskId);out.put("comparisonId",comparison);out.put("inputSha",inputSha);
            out.put("candidateId",candidate);out.put("isolatedPersistentRoot",env.root.toString());out.put("serviceReceived",true);out.put("receiptReadAfterServiceRestart",true);out.put("privateRetentionUntil",env.retentionUntil.toString());
            out.put("calculationAttempts",0);out.put("modelCalls",0);out.put("matrixSubmissions",0);out.put("browserAcceptance",false);out.put("directCatalogPrepareConsumed",true);out.set("directCatalog",catalog);
            out.set("sourceReceipt",materials.admitted);out.set("originalKeyReceipt",receipt);out.set("cancelReceipt",cancel);
            out.set("mapping",env.json.valueToTree(materials.mappings));
            out.put("lostResponseCoverage","POST response obtained then deliberately discarded by consumer; original GET used, not network-level timeout injection");
            String output=System.getProperty("comparison.claims.evidence");
            if(output!=null)Files.writeString(Path.of(output),env.json.writerWithDefaultPrettyPrinter().writeValueAsString(out),StandardOpenOption.CREATE_NEW);
        }
    }
}

