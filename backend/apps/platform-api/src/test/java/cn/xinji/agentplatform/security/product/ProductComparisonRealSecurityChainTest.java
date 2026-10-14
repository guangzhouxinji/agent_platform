package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real HTTPS/mTLS, production Issuer PKCE/JWT/session/grant implementation. No model requests. */
class ProductComparisonRealSecurityChainTest {
    @Test void registeredIdentityExactGrantSessionAndOwnedSourceAreReal() throws Exception {
        try(var env=new ProductComparisonRealSecurityEnvironment()) {
            var member=env.post(env.maintainer,"/v2/identity/subjects",Map.of("loginName","owned-security-user"),"owned-register",null,200);
            String subject=member.path("subjectId").asText();assertFalse(subject.isBlank());
            env.post(env.browser,"/v2/identity/subjects/"+subject+"/activate",Map.of("activation",member.path("oneTimeActivation").asText(),"password",env.password),null,null,200);
            env.post(env.maintainer,"/v2/identity/clients",new ProductClients.Input("owned-security-client","Owned engineering HTTPS client",env.origin+"/callback"),"owned-client",null,200);
            env.post(env.maintainer,"/v2/identity/keys/rotate",Map.of(),"owned-key",null,200);
            String token=env.login("owned-security-user",env.password);
            assertEquals(200,env.send(env.browser,"GET","/v2/identity/me",null,null,token,null).statusCode());
            assertEquals(401,env.send(env.browser,"GET","/v2/identity/me",null,null,"not-a-jwt",null).statusCode());
            var taskInput=new ProductInitialTaskService.Input(env.organization,"Owned engineering Task, not business",subject,"owned-engineering-retention",1,env.until);
            var task=env.post(env.maintainer,"/v2/identity/organizations/initial-task",taskInput,"owned-task",null,200);
            String taskId=task.path("taskId").asText();assertFalse(taskId.isBlank());
            String path="/v2/consultation/tasks/"+taskId;
            assertEquals(200,env.send(env.browser,"GET",path,null,null,token,null).statusCode());
            assertEquals(404,env.send(env.browser,"GET","/v2/consultation/tasks/"+UUID.randomUUID(),null,null,token,null).statusCode());
            var replay=env.post(env.maintainer,"/v2/identity/organizations/initial-task",taskInput,"owned-task",null,200);
            assertEquals(task,replay);
            env.post(env.maintainer,"/v2/identity/organizations/initial-task",new ProductInitialTaskService.Input(env.organization,"Changed",subject,"owned-engineering-retention",1,env.until),"owned-task",null,409);
            String dataScope=env.primary.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",String.class,taskId)+":1";
            for(String action:List.of("WRITE","COMPARE")) {
                var scope=new ProductGrants.Scope(env.issuer,subject,env.organization,"CONSULTATION",taskId,dataScope,"TASK",taskId,1,action,"TASK_WORK");
                env.post(env.maintainer,"/v2/identity/grants",new ProductIdentityController.IssueGrant(scope,env.until,0),"owned-"+action.toLowerCase(),null,200);
            }
            var goal=env.post(env.browser,path+"/goals/confirm",ProductComparisonRealSecurityBoundaries.goalCommand(),"owned-goal",token,200);
            assertEquals(2,goal.path("taskVersion").asLong());
            String workloadSha=ProductComparisonRealSecurityEnvironment.sha(ProductComparisonRealSecurityEnvironment.certificate(env.tls.root.resolve("worker.cer")).getEncoded());
            var workload=env.post(env.maintainer,"/v2/identity/workloads",Map.of("certificateSha256",workloadSha,"kind","AGENT","expiresAt",env.until),"owned-workload",null,200);
            assertFalse(workload.path("subjectId").asText().isBlank());
            String absentInput=UUID.randomUUID().toString();
            assertEquals(409,env.send(env.browser,"GET",path+"/patent-comparisons/"+absentInput,null,null,token,null).statusCode());
            assertEquals(404,env.send(env.browser,"GET","/v2/consultation/tasks/"+UUID.randomUUID()+"/patent-comparisons/"+absentInput,null,null,token,null).statusCode());
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_comparison_input",Integer.class));
            var workerGrant=env.post(env.maintainer,"/v2/identity/workloads/"+workload.path("subjectId").asText()+"/comparison-task-grants",new ProductWorkloadController.GrantTask(taskId,env.until,0),"owned-worker-compare",null,200);
            assertEquals("ACTIVE",workerGrant.path("status").asText());
            var boundaries=new ProductComparisonRealSecurityBoundaries(env);
            var exactTaskInput=boundaries.exactTaskInput(subject,taskId,goal,token);
            var sourceConsumption=new ProductComparisonRealSecuritySource(env,taskId,subject).verifyEnableScopeAndWithdraw(goal,token);
            var sessionBoundaries=boundaries.independentSessionAndOriginalKey(taskId,token,goal);
            // Task-grant revocation below must use a live, independently registered session.
            token=env.login("owned-security-user",env.password);
            env.post(env.maintainer,"/v2/identity/grants/"+task.path("grantId").asText()+"/revoke",Map.of("expectedVersion",1),"owned-revoke",null,200);
            assertEquals(404,env.send(env.browser,"GET",path,null,null,token,null).statusCode());
            env.post(env.browser,"/v2/identity/logout",Map.of(),null,token,200);
            assertEquals(401,env.send(env.browser,"GET","/v2/identity/me",null,null,token,null).statusCode());
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con3_worker_delegation",Integer.class));
            String output=System.getProperty("comparison.security.evidence");
            if(output!=null) {
                var receipt=new LinkedHashMap<String,Object>();
                receipt.put("scope","ACTUAL_HTTPS_PRODUCT_ISSUER_GRANTS_OWNED_TITLE_SOURCE_ENGINEERING_NOT_FULL_START_CHAIN");
                receipt.put("observedAt",java.time.Instant.now());receipt.put("task",task);receipt.put("goal",goal);receipt.put("workload",workload);receipt.put("workloadGrant",workerGrant);
                receipt.put("registeredCurrentEngineeringIdentity",true);receipt.put("browserServiceCertificate",false);
                receipt.put("jwtTamperRejected",true);receipt.put("crossTaskRejected",true);receipt.put("originalKeyReplay",true);receipt.put("changedPayloadRejected",true);
                receipt.put("ownedSourceVerifiedAndEnabled",true);receipt.put("sourceRevokeRejectsOriginalScope",true);receipt.put("taskRevokeReadDenied",true);receipt.put("logoutJwtDenied",true);
                receipt.put("titlePermissionDoesNotImplyClaims",true);
                receipt.put("exactTaskInputNegativeRequests",exactTaskInput);
                receipt.put("titleCurrentConsumption",sourceConsumption);
                receipt.put("independentSessionBoundaries",sessionBoundaries);
                receipt.put("candidateHandoffProven",false);receipt.put("claimInputCreated",false);receipt.put("modelRequests",0);receipt.put("matrixCommitted",false);
                receipt.put("limitations",List.of("Owned TITLE metadata is not an admitted CLAIMS comparison Input","Start/attempt/fence/timeout/cancel/result-current-read chain not demonstrated by this receipt","H2 engineering not DM8, browser acceptance or business authorization","TITLE consumption checks real product current-source guard and projection reader, not HTTP search result or worker handoff"));
                java.nio.file.Files.writeString(java.nio.file.Path.of(output),env.json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt),java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            // No fabricated Input, attempt, COMMITTED result or model budget is inserted.
        }
    }
}







