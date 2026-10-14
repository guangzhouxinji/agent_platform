package cn.xinji.agentplatform.security.product;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit bounded browser runner; registers genuine engineering objects through maintenance HTTPS. */
final class ProductComparisonBrowserEngineering {
    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("approval workspace node script exclusive-output required");
        Path workspace=Path.of(args[1]),output=Path.of(args[4]);
        if(Files.exists(output))throw new IllegalArgumentException("Prior browser evidence must not be overwritten");
        try(var env=new ProductComparisonRealSecurityEnvironment(true)) {
            var user=env.post(env.maintainer,"/v2/identity/subjects",Map.of("loginName","owned-browser-operator"),"browser-user",null,200);
            String subject=user.path("subjectId").asText();
            env.post(env.browser,"/v2/identity/subjects/"+subject+"/activate",Map.of("activation",user.path("oneTimeActivation").asText(),"password",env.password),null,null,200);
            env.post(env.maintainer,"/v2/identity/clients",new ProductClients.Input("owned-security-client","Owned engineering setup",env.origin+"/callback"),"browser-api-client",null,200);
            env.post(env.maintainer,"/v2/identity/clients",new ProductClients.Input("owned-security-browser-client","Owned engineering final workface",env.origin+"/app/consultation"),"browser-workface-client",null,200);
            env.post(env.maintainer,"/v2/identity/keys/rotate",Map.of(),"browser-keys",null,200);
            String setupToken=env.login("owned-browser-operator",env.password);
            var task=env.post(env.maintainer,"/v2/identity/organizations/initial-task",new ProductInitialTaskService.Input(env.organization,
                    "Project-authored CLAIMS browser engineering; not a patent case",subject,"owned-engineering-retention",1,env.until),"browser-task",null,200);
            String taskId=task.path("taskId").asText(),path="/v2/consultation/tasks/"+taskId;
            String scope=env.primary.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",String.class,taskId)+":1";
            for(String action:List.of("WRITE","COMPARE"))env.post(env.maintainer,"/v2/identity/grants",new ProductIdentityController.IssueGrant(
                    new ProductGrants.Scope(env.issuer,subject,env.organization,"CONSULTATION",taskId,scope,"TASK",taskId,1,action,"TASK_WORK"),env.until,0),"browser-"+action,null,200);
            env.post(env.browser,path+"/goals/confirm",new ProductTaskGoalService.Input(1,"Explicitly approved project-authored passages for browser engineering",
                    "COMPARE","owned-engineering",List.of("approved-project-claims"),List.of("CLAIMS"),List.of("selected original passage"),true,"owned-engineering-retention",1),"browser-goal",setupToken,200);
            String workerSha=ProductComparisonRealSecurityEnvironment.sha(ProductComparisonRealSecurityEnvironment.certificate(env.tls.root.resolve("worker.cer")).getEncoded());
            var worker=env.post(env.maintainer,"/v2/identity/workloads",Map.of("certificateSha256",workerSha,"kind","AGENT","expiresAt",env.until),"browser-worker",null,200);
            String workerSubject=worker.path("subjectId").asText();
            env.post(env.maintainer,"/v2/identity/workloads/"+workerSubject+"/comparison-task-grants",new ProductWorkloadController.GrantTask(taskId,env.until,0),"browser-worker-task",null,200);
            var originals=new ProductComparisonApprovedClaimsSource(env,taskId,subject);originals.register(Path.of(args[0]),workspace);
            env.enableStart(workerSubject);env.serveWorkbench(workspace.resolve("frontend/dist-consultation"));
            var browser=new ProcessBuilder(args[2],args[3]).redirectOutput(output.resolveSibling(output.getFileName()+".stdout.log").toFile())
                    .redirectError(output.resolveSibling(output.getFileName()+".stderr.log").toFile()).start();
            // Only stdin contains the temporary operator password. Browser receives no service key/certificate/token.
            var config=env.json.createObjectNode().put("origin",env.origin).put("taskId",taskId).put("loginName","owned-browser-operator")
                    .put("password",env.password).put("output",output.toString()).put("until",env.until.toString());
            try(var stdin=browser.getOutputStream()){stdin.write(env.json.writeValueAsBytes(config));}
            if(!browser.waitFor(180,TimeUnit.SECONDS)){browser.destroyForcibly();throw new IllegalStateException("Bounded browser timeout; original state retained");}
            if(browser.exitValue()!=0)throw new IllegalStateException("Actual browser failed; inspect sanitized output, no reissued business writes");
            var evidence=env.json.readTree(Files.readAllBytes(output));
            // Isolate cancellation from withdrawal: all Source rights remain current for both mTLS calls.
            var accepted=evidence.path("receipt");String commandId=accepted.path("commandId").asText();
            String handoff=path+"/patent-comparisons/"+accepted.path("comparisonId").asText()+"/candidates/"+accepted.path("candidateId").asText()+"/start-commands/"+commandId+"/handoff-receipts";
            var binding=env.primary.queryForMap("SELECT delegation_id,request_sha FROM con5_start_command WHERE command_id=?",commandId);
            var managedClient=java.net.http.HttpClient.newBuilder().sslContext(env.tls.context("worker",true)).build();
            String request=env.json.writeValueAsString(new ProductComparisonHandoffController.Request(accepted.path("inputSha").asText(),
                    (String)binding.get("DELEGATION_ID"),(String)binding.get("REQUEST_SHA")));
            var replay=env.send(managedClient,"POST",handoff,request,"start:"+commandId,null,"application/json");
            if(replay.statusCode()!=200)throw new IllegalStateException("Current mTLS receipt replay failed before cancellation");
            var cancelled=env.post(env.browser,path+"/cancel",Map.of("expectedTaskVersion",2),"browser-independent-cancel",setupToken,200);
            var late=env.send(managedClient,"POST",handoff,request,"start:"+commandId,null,"application/json");
            if(late.statusCode()==200)throw new IllegalStateException("Cancelled task accepted a late mTLS receipt");
            long sourceEpoch=env.primary.queryForObject("SELECT source_epoch FROM con4_source_current WHERE source_id=?",Long.class,originals.source);
            if(sourceEpoch!=originals.admitted.path("sourceEpoch").asLong())throw new IllegalStateException("Source changed during cancellation-only proof");
            var rows=env.primary.queryForList("SELECT receipt_json FROM con5_start_handoff_receipt");
            if(rows.size()!=1)throw new IllegalStateException("Exactly one durable Worker receipt required");
            var result=env.json.createObjectNode();result.put("scope","REAL_FINAL_WORKFACE_BROWSER_IAM_SOURCE_HTTPS_MTLS_FILE_H2_NO_MODEL");
            result.put("taskId",taskId);result.put("workerSubject",workerSubject);result.put("privateRoot",env.root.toString());result.put("retentionUntil",env.retentionUntil.toString());
            result.set("browser",evidence);result.set("sourceDecision",originals.admitted);
            result.set("cancelReceipt",cancelled);result.put("currentMtlsReplayBeforeCancel",replay.statusCode());
            result.put("lateMtlsAfterCancelStatus",late.statusCode());result.put("sourceEpochUnchangedDuringCancellation",true);
            for(String table:List.of("con5_candidate_lease","con5_candidate_attempt","con5_matrix_invocation","con5_matrix_report")){
                int count=env.primary.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);if(count!=0)throw new IllegalStateException("Unexpected computation: "+table);result.put(table,count);
            }
            result.put("durableHandoffReceipts",rows.size());result.put("browserHoldsWorkerCertificate",false);
            Files.writeString(output.resolveSibling(output.getFileName()+".persistence.json"),env.json.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
        }
    }
}
