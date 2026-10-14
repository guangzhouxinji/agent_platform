package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Negative requests use real confirmed goals/inputs, never a fabricated comparison Input. */
final class ProductComparisonRealSecurityBoundaries {
    private final ProductComparisonRealSecurityEnvironment env;
    ProductComparisonRealSecurityBoundaries(ProductComparisonRealSecurityEnvironment env) { this.env=env; }

    Map<String,Object> exactTaskInput(String subject,String task,JsonNode goal,String token) throws Exception {
        long organizationVersion=env.primary.queryForObject("SELECT version FROM con3_organization WHERE organization_id=?",Long.class,env.organization);
        env.post(env.maintainer,"/v2/identity/grants",new ProductIdentityController.IssueGrant(
                new ProductGrants.Scope(env.issuer,subject,env.organization,"CONSULTATION",env.organization,
                        env.organization+":"+organizationVersion,"ORGANIZATION",env.organization,organizationVersion,
                        "CREATE","TASK_WORK"),env.until,0),"owned-create-task",null,200);
        var other=env.post(env.browser,"/v2/consultation/tasks",
                new ProductTaskCreationService.Input(env.organization,env.until,"owned-engineering-retention",1),
                "other-owned-task",token,200);
        String otherTask=other.path("taskId").asText();assertNotEquals(task,otherTask);
        String scope=env.primary.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",String.class,otherTask)+":1";
        for(String action:List.of("WRITE","COMPARE"))
            env.post(env.maintainer,"/v2/identity/grants",new ProductIdentityController.IssueGrant(
                    new ProductGrants.Scope(env.issuer,subject,env.organization,"CONSULTATION",otherTask,scope,
                            "TASK",otherTask,1,action,"TASK_WORK"),env.until,0),"other-"+action,null,200);
        var otherGoal=env.post(env.browser,"/v2/consultation/tasks/"+otherTask+"/goals/confirm",
                goalCommand(),"other-goal",token,200);
        assertNotEquals(goal.path("inputId").asText(),otherGoal.path("inputId").asText());
        String prepare="/v2/consultation/tasks/"+task+"/patent-comparisons/prepare";
        var candidates=List.of(new ProductComparisonContracts.DocumentRef("owned-security-material",
                "owned-record","owned-record-v1",List.of(1),null,null,null,null));
        var observations=new LinkedHashMap<String,Object>();
        // Both Tasks exist and both have exact live READ/WRITE/COMPARE grants. Permission absence
        // and unavailable CLAIMS are not allowed to mask the Goal/Input binding rejection.
        for(int boundary=0;boundary<3;boundary++) {
            var command=new ProductComparisonContracts.Prepare(goal.path("taskVersion").asLong(),
                    boundary==1?otherGoal.path("goalId").asText():goal.path("goalId").asText(),1,
                    boundary<2?otherGoal.path("inputId").asText():goal.path("inputId").asText(),
                    "CLAIM_ALIGNMENT",new ProductComparisonContracts.Target(
                            boundary==2?otherGoal.path("messageId").asText():goal.path("messageId").asText(),null),candidates);
            var denied=env.post(env.browser,prepare,command,"exact-binding-"+boundary,token,boundary==2?404:409);
            assertEquals(boundary==2?"COMPARISON_REQUEST_REJECTED":"PREPARATION_VERSION_CHANGED",denied.path("code").asText());
            observations.put(List.of("foreignInputWithOwnGoal","foreignGoalAndInput","foreignTargetMessage").get(boundary),denied);
        }
        var stale=new ProductComparisonContracts.Prepare(1,goal.path("goalId").asText(),1,
                goal.path("inputId").asText(),"CLAIM_ALIGNMENT",
                new ProductComparisonContracts.Target(goal.path("messageId").asText(),null),candidates);
        var denied=env.post(env.browser,prepare,stale,"stale-task-version",token,409);
        assertEquals("PREPARATION_VERSION_CHANGED",denied.path("code").asText());
        observations.put("staleTaskVersion",denied);observations.put("otherTask",other);observations.put("otherGoal",otherGoal);
        assertNoComparisonWrites();return observations;
    }

    Map<String,Object> independentSessionAndOriginalKey(String task,String token,JsonNode goal) throws Exception {
        String replacement=env.login("owned-security-user",env.password);
        assertNotEquals(token,replacement);
        var originalSelf=env.json.readTree(env.send(env.browser,"GET","/v2/identity/me",null,null,token,null).body());
        var replacementSelf=env.json.readTree(env.send(env.browser,"GET","/v2/identity/me",null,null,replacement,null).body());
        assertNotEquals(originalSelf.path("sessionId").asText(),replacementSelf.path("sessionId").asText());
        String path="/v2/consultation/tasks/"+task;
        env.post(env.browser,"/v2/identity/logout",Map.of(),null,token,200);
        var replay=env.send(env.browser,"POST",path+"/goals/confirm",env.json.writeValueAsString(goalCommand()),
                "owned-goal",token,"application/json");
        assertEquals(401,replay.statusCode());
        assertEquals(401,env.send(env.browser,"GET",path,null,null,token,null).statusCode());
        assertEquals(401,env.send(env.browser,"GET",path+"/patent-comparisons/"+goal.path("inputId").asText(),null,null,token,null).statusCode());
        assertEquals(200,env.send(env.browser,"GET",path,null,null,replacement,null).statusCode());
        assertEquals(goal,env.post(env.browser,path+"/goals/confirm",goalCommand(),"owned-goal",replacement,200));
        assertEquals(2L,env.primary.queryForObject("SELECT version FROM con3_task WHERE task_id=?",Long.class,task));
        assertEquals(1,env.primary.queryForObject("SELECT COUNT(*) FROM con3_goal_revision WHERE task_id=?",Integer.class,task));
        assertNoComparisonWrites();
        return Map.of("revokedOriginalKeyWriteStatus",replay.statusCode(),"revokedTaskReadStatus",401,
                "revokedComparisonReadStatus",401,"separateLiveSessionReadStatus",200,
                "separateLiveSessionOriginalKeyGoalReplayUnchanged",true,"distinctRegisteredSessions",true);
    }

    static ProductTaskGoalService.Input goalCommand() {
        return new ProductTaskGoalService.Input(1,"Owned scheme for source-control engineering, not a patent decision",
                "COMPARE","owned-engineering",List.of("owned-security-material"),List.of("TITLE"),
                List.of("owned feature"),true,"owned-engineering-retention",1);
    }
    void assertNoComparisonWrites() {
        for(String table:List.of("con5_preparation","con5_comparison_input","con3_worker_delegation"))
            assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table);
    }
}
