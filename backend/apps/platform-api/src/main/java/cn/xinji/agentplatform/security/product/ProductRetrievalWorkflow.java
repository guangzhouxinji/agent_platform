package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Member-confirmed workflow; no source activation, new task grant or model-driven permission changes. */
final class ProductRetrievalWorkflow {
    private final ProductRetrievalWorkspace workspace;
    private final ProductWorkerDelegations delegations;
    private final ProductRetrievalMtls.Transport maintenance;
    private final ProductRetrievalMtls.Transport worker;
    private final JdbcTemplate db;
    private final String workerSubject;
    private final Clock clock;

    ProductRetrievalWorkflow(ProductRetrievalWorkspace workspace, ProductWorkerDelegations delegations,
            ProductRetrievalMtls.Transport maintenance, ProductRetrievalMtls.Transport worker,
            JdbcTemplate db, String workerSubject, Clock clock) {
        if (workerSubject==null || workerSubject.isBlank()) throw new IllegalArgumentException("Formal worker required");
        this.workspace=workspace; this.delegations=delegations; this.maintenance=maintenance;
        this.worker=worker; this.db=db; this.workerSubject=workerSubject; this.clock=clock;
    }

    ProductPatentSearchExecution.Page execute(String taskId, long expectedVersion, Jwt jwt) {
        var task=workspace.permitted(taskId,jwt,ProductAccessPolicy.Action.WRITE);
        workspace.permitted(taskId,jwt,ProductAccessPolicy.Action.READ);
        if (task.version()!=expectedVersion || !"IN_PROGRESS".equals(task.state()))
            throw ProductRetrievalWorkspace.conflict();
        var goal=workspace.goal(taskId,jwt,task);
        if (goal==null || !("QUERY".equals(goal.purpose()) || "SEARCH".equals(goal.purpose())))
            throw ProductRetrievalWorkspace.conflict();
        var scopes=bind(goal,workspace.allowed(task,jwt),clock.instant());
        Instant until=task.expiresAt();
        for (var scope:scopes) until=ProductRetrievalWorkspace.earlier(until,scope.expiresAt());
        // Delegation validates this exact current grant, registered service, human session and independent watermark.
        var grants=db.query("SELECT g.expires_at FROM id2_grant g JOIN id2_current_epoch e "
                + "ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.task_scope=? "
                + "AND g.data_scope=? AND g.resource_type='TASK' AND g.resource_id=? AND g.resource_version=? "
                + "AND g.action_code='WRITE' AND g.purpose='TASK_WORK' AND g.status='ACTIVE' AND g.expires_at>?",
                (rs,n)->rs.getTimestamp(1).toInstant(),jwt.getIssuer().toString(),workerSubject,
                task.organization(),taskId,task.dataScope(),taskId,task.authorizationVersion(),
                Timestamp.from(clock.instant()));
        if (grants.size()!=1) throw new IllegalStateException("Existing worker Task grant required");
        until=ProductRetrievalWorkspace.earlier(until,grants.getFirst()).minusSeconds(1);
        // Also cap by the member's current WRITE grant. No implicit service or member grant is issued.
        var human=db.query("SELECT g.expires_at FROM id2_grant g JOIN id2_current_epoch e "
                + "ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.task_scope=? "
                + "AND g.data_scope=? AND g.resource_type='TASK' AND g.resource_id=? AND g.resource_version=? "
                + "AND g.action_code='WRITE' AND g.purpose='TASK_WORK' AND g.status='ACTIVE' AND g.expires_at>?",
                (rs,n)->rs.getTimestamp(1).toInstant(),jwt.getIssuer().toString(),jwt.getSubject(),
                task.organization(),taskId,task.dataScope(),taskId,task.authorizationVersion(),
                Timestamp.from(clock.instant()));
        if (human.size()!=1) throw new IllegalStateException("Current member Task grant required");
        until=ProductRetrievalWorkspace.earlier(until,human.getFirst().minusSeconds(1));
        String key="workspace:"+goal.inputId()+":"+workerSubject;
        var delegation=delegations.issue(taskId,workerSubject,until,jwt,key+":delegate");
        for (var scope:scopes) if (scope.taskVersion()!=task.version()) {
            maintenance.post("/v2/consultation/source-contracts/scopes/"+segment(scope.sourceId())+"/rebind",
                    Map.of("taskId",taskId,"memberSubject",jwt.getSubject(),"taskVersion",task.version(),
                            "sourceRowVersion",scope.sourceRowVersion(),"expiresAt",scope.expiresAt().toString(),
                            "expectedScopeVersion",scope.scopeVersion(),"previousQueryGrantId",scope.queryGrantId()),
                    "workspace-scope:"+goal.inputId()+":"+scope.scopeId());
        }
        if (!task.equals(workspace.permitted(taskId,jwt,ProductAccessPolicy.Action.WRITE)))
            throw ProductRetrievalWorkspace.conflict();
        bind(goal,workspace.allowed(task,jwt),clock.instant());
        var existing=workspace.result(taskId,goal.inputId(),jwt);
        if (existing!=null) return existing;
        String path="/v2/consultation/tasks/"+segment(taskId)+"/executions";
        var lease=worker.post(path,Map.of("inputId",goal.inputId(),"delegationId",delegation.delegationId()),key+":lease");
        var receipt=worker.post(path+"/"+segment(lease.path("attemptId").asText())+"/patent-search-result",
                Map.of("inputId",goal.inputId(),"delegationId",delegation.delegationId(),
                        "fencingToken",lease.path("fencingToken").asLong()),key+":result");
        var result=workspace.result(taskId,goal.inputId(),jwt);
        if (result==null || !result.resultSetId().equals(receipt.path("resultSetId").asText()))
            throw new IllegalStateException("Actual persisted result receipt required");
        return result;
    }

    static List<ProductRetrievalWorkspace.Source> bind(ProductTaskGoalReader.GoalView goal,
            List<ProductRetrievalWorkspace.Source> available, Instant now) {
        return goal.sourceIds().stream().map(id->{
            var rows=available.stream().filter(s->s.sourceId().equals(id)).toList();
            if (rows.size()!=1) throw new IllegalStateException("Existing exact source grant required");
            var s=rows.getFirst();
            if ((s.taskVersion()!=goal.taskVersion() && s.taskVersion()!=goal.taskVersion()-1)
                    || !s.expiresAt().isAfter(now) || !s.fields().containsAll(goal.requestedFields()))
                throw new IllegalStateException("Source scope cannot widen or revive");
            return s;
        }).toList();
    }

    private static String segment(String value) {
        if (value==null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Exact product resource ID required");
        return value;
    }
}
