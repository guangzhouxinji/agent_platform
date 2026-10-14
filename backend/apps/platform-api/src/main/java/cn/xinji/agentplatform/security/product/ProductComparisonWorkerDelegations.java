package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonPreparationLoader.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Explicit input/candidate intent. This is not a source right, execution lease or result. */
final class ProductComparisonWorkerDelegations {
    record Issue(String serviceSubject, String candidateId, String inputSha, Instant expiresAt) { }
    record Receipt(String delegationId, long version, String status, String comparisonId,
                   String inputSha, String candidateId, Instant expiresAt) { }
    private record Binding(String taskId, String comparisonId, String candidateId, String inputSha,
                           String bindingSha, String serviceSubject, Instant createdAt, Instant expiresAt) { }
    private final JdbcTemplate db;
    private final ProductWorkerDelegations delegations;
    private final ProductComparisonInputApplication comparisons;
    private final ProductComparisonInputStore inputs;
    private final ProductComparisonJson json;
    private final Clock clock;

    ProductComparisonWorkerDelegations(JdbcTemplate db, ProductWorkerDelegations delegations,
            ProductComparisonInputApplication comparisons, ProductComparisonInputStore inputs,
            ProductComparisonJson json, Clock clock) {
        this.db=db;this.delegations=delegations;this.comparisons=comparisons;
        this.inputs=inputs;this.json=json;this.clock=clock;
    }
    Receipt issue(String task, String comparison, Issue command, Jwt human, String key) {
        if (command==null || blank(command.serviceSubject()) || blank(command.candidateId())
                || command.inputSha()==null || !command.inputSha().matches("[a-f0-9]{64}")
                || command.expiresAt()==null || human==null) throw invalid("INVALID_COMPARISON_DELEGATION");
        var view=comparisons.consume(task,comparison,human);
        Instant until=command.expiresAt().truncatedTo(ChronoUnit.MILLIS);
        if (!view.inputSha().equals(command.inputSha()) || !until.isAfter(clock.instant())
                || until.isAfter(view.input().expiresAt())
                || view.input().candidates().stream().noneMatch(c->c.candidateId().equals(command.candidateId()))) throw conflict();
        String digest=bindingSha(task,comparison,command.candidateId(),command.inputSha(),command.serviceSubject(),until);
        var result=delegations.issueBoundComparison(task,command.serviceSubject(),until,human,key,digest,(receipt,created)->{
            // Called under the Task lock and the primary transaction, while authority is PENDING.
            // No fabricated human JWT and no protected original read is performed in that window.
            requireCurrent(view);
            if (created) db.update("INSERT INTO con5_comparison_delegation VALUES(?,?,?,?,?,?,?,?,?)",
                    receipt.delegationId(),task,comparison,command.candidateId(),command.inputSha(),digest,
                    command.serviceSubject(),Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)),Timestamp.from(until));
            var bound=binding(task,comparison,receipt.delegationId());
            if (!bound.bindingSha().equals(digest)) throw conflict();
            requireReceipt(receipt.delegationId(),bound);
        });
        // A returned intent never makes a stale comparison executable. Recheck the human product chain.
        if (!view.equals(comparisons.consume(task,comparison,human))) throw conflict();
        return receipt(result,binding(task,comparison,result.delegationId()));
    }
    Receipt read(String task,String comparison,String id,Jwt human) {
        var view=comparisons.read(task,comparison,human);
        var bound=binding(task,comparison,id);requireReceipt(id,bound);
        if (!view.inputSha().equals(bound.inputSha())) throw conflict();
        var states=db.query("SELECT version,status FROM con3_worker_delegation WHERE delegation_id=? AND task_id=?",
                (rs,row)->new ProductWorkerDelegations.Receipt(id,rs.getLong(1),rs.getString(2)),id,task);
        if (states.size()!=1) throw conflict();
        return receipt(states.getFirst(),bound);
    }
    Receipt revoke(String task,String comparison,String id,long version,Jwt human,String key) {
        delegations.requireComparisonActor(task,human);
        var bound=binding(task,comparison,id);requireReceipt(id,bound);
        // Revocation is permitted for a stale input; it reads coordinates, not old Goal or source content.
        return receipt(delegations.revokeComparison(task,id,version,human,key),bound);
    }
    /** Current intent coordinates only. Caller must separately authenticate the service and consume source rights. */
    Receipt currentWorkerIntent(String task,String comparison,String candidate,String inputSha,String id,String service,long taskVersion) {
        if (blank(task) || blank(comparison) || blank(candidate) || blank(id) || blank(service)
                || inputSha==null || !inputSha.matches("[a-f0-9]{64}") || taskVersion<1) throw conflict();
        var bound=binding(task,comparison,id);requireReceipt(id,bound);
        if (!bound.candidateId().equals(candidate) || !bound.inputSha().equals(inputSha)
                || !bound.serviceSubject().equals(service) || !bound.expiresAt().isAfter(clock.instant())) throw conflict();
        var view=inputs.read(task,comparison);requireCurrent(view);
        if (!view.inputSha().equals(inputSha) || view.input().taskVersion()!=taskVersion
                || view.input().candidates().stream().noneMatch(c->c.candidateId().equals(candidate))) throw conflict();
        var states=db.query("SELECT version,status FROM con3_worker_delegation WHERE delegation_id=? AND task_id=? "
                + "AND service_subject=? AND status='ACTIVE' AND expires_at>?",
                (rs,row)->new ProductWorkerDelegations.Receipt(id,rs.getLong(1),rs.getString(2)),id,task,service,Timestamp.from(clock.instant()));
        if (states.size()!=1) throw conflict();
        var after=binding(task,comparison,id);requireReceipt(id,after);requireCurrent(view);
        if (!bound.equals(after)) throw conflict();
        return receipt(states.getFirst(),bound);
    }
    private void requireCurrent(ProductComparisonInputs.View expected) {
        var e=expected.input();
        if (!e.comparisonId().equals(inputs.current(e.taskId())) || !expected.equals(inputs.read(e.taskId(),e.comparisonId()))
                || !e.expiresAt().isAfter(clock.instant())) throw conflict();
        var featureRevision=new ProductComparisonStore(db,json).revision(e.taskId(),e.command().featureRevisionId());
        if (!featureRevision.equals(e.confirmedFeatures()) || !featureRevision.revision().expiresAt().isAfter(clock.instant())) throw conflict();
        Integer feature=db.queryForObject("SELECT COUNT(*) FROM con5_feature_current WHERE task_id=? AND revision_id=? AND revision_number=?",
                Integer.class,e.taskId(),e.command().featureRevisionId(),e.command().featureRevisionNumber());
        JsonNode command=json.stored(e.currentPreparationSnapshot(),JsonNode.class).path("command");
        Integer goal=db.queryForObject("SELECT COUNT(*) FROM con3_goal_current g JOIN con3_search_input i ON i.task_id=g.task_id "
                + "AND i.goal_id=g.goal_id AND i.goal_revision=g.revision AND i.task_version=g.task_version "
                + "JOIN con3_goal_revision r ON r.task_id=g.task_id AND r.goal_id=g.goal_id AND r.revision=g.revision "
                + "WHERE g.task_id=? AND g.goal_id=? AND g.revision=? AND i.input_id=? AND i.task_version=? AND i.expires_at>? "
                + "AND r.expires_at>? AND r.status='CONFIRMED'",
                Integer.class,e.taskId(),command.path("goalId").asText(),command.path("goalRevision").asLong(),
                command.path("inputId").asText(),e.taskVersion(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        if (feature==null || feature!=1 || goal==null || goal!=1) throw conflict();
    }
    private Binding binding(String task,String comparison,String id) {
        List<Binding> rows=db.query("SELECT task_id,comparison_id,candidate_id,input_sha,binding_sha,service_subject,created_at,expires_at "
                + "FROM con5_comparison_delegation WHERE delegation_id=? AND task_id=? AND comparison_id=?",
                (rs,row)->new Binding(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                        rs.getTimestamp(7).toInstant(),rs.getTimestamp(8).toInstant()),id,task,comparison);
        if (rows.size()!=1) throw conflict();var b=rows.getFirst();
        if (!b.bindingSha().equals(bindingSha(b.taskId(),b.comparisonId(),b.candidateId(),b.inputSha(),b.serviceSubject(),b.expiresAt())))
            throw ProductComparisonInputStore.corrupt();
        return b;
    }
    private void requireReceipt(String id,Binding binding) {
        var receipts=db.query("SELECT d.human_subject,d.expires_at,r.request_sha,d.service_subject,h.action_code,s.action_code "
                + "FROM con3_worker_delegation d JOIN con3_worker_delegation_receipt r ON r.delegation_id=d.delegation_id AND r.operation='ISSUE' "
                + "JOIN id2_grant h ON h.grant_id=d.human_grant_id JOIN id2_grant s ON s.grant_id=d.service_grant_id "
                + "WHERE d.delegation_id=? AND d.task_id=? AND h.purpose='TASK_WORK' AND s.purpose='TASK_WORK'",
                (rs,row)->binding.expiresAt().equals(rs.getTimestamp(2).toInstant()) && binding.serviceSubject().equals(rs.getString(4))
                        && "COMPARE".equals(rs.getString(5)) && "COMPARE".equals(rs.getString(6))
                        && rs.getString(3).equals(ProductWorkerDelegations.comparisonDigest(rs.getString(1),binding.taskId(),
                                binding.serviceSubject(),binding.expiresAt(),binding.bindingSha())),id,binding.taskId());
        if (receipts.size()!=1 || !receipts.getFirst()) throw ProductComparisonInputStore.corrupt();
    }
    private String bindingSha(String task,String comparison,String candidate,String inputSha,String service,Instant until) {
        return json.sha(Map.of("taskId",task,"comparisonId",comparison,"candidateId",candidate,
                "inputSha",inputSha,"serviceSubject",service,"expiresAt",until,"action","COMPARE"));
    }
    private static Receipt receipt(ProductWorkerDelegations.Receipt result,Binding b) {
        return new Receipt(result.delegationId(),result.version(),result.status(),b.comparisonId(),b.inputSha(),b.candidateId(),b.expiresAt());
    }
    private static boolean blank(String value) {return value==null || value.isBlank();}
}
