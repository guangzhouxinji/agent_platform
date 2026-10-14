package cn.xinji.agentplatform.security.product;

import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Current human read coordinates only. No Worker dispatch or authority issuance. */
@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-comparisons/context")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
final class ProductComparisonWorkbenchContextController {
    record Context(String taskId,long taskVersion,String goalId,long goalRevision,String inputId,
                   String schemeMessageId,String schemeText,long previousFeatureRevision,String previousComparisonId,Instant expiresAt) { }
    private final ProductTaskGoalReader goals;private final ProductComparisonTargetReader targets;private final JdbcTemplate db;
    ProductComparisonWorkbenchContextController(ProductTaskGoalReader goals,ProductComparisonTargetReader targets,JdbcTemplate db){this.goals=goals;this.targets=targets;this.db=db;}
    @GetMapping Context current(@PathVariable String taskId,@AuthenticationPrincipal Jwt jwt) {
        var goal=goals.current(taskId,jwt);
        var ids=db.queryForList("SELECT source_message_id FROM con3_goal_revision WHERE task_id=? AND goal_id=? AND revision=?",String.class,taskId,goal.goalId(),goal.goalRevision());
        if(ids.size()!=1)throw conflict();var target=targets.readUserScheme(taskId,ids.getFirst(),goal.taskVersion(),jwt);
        var before=pointers(taskId);var after=goals.current(taskId,jwt);
        var targetAfter=targets.readUserScheme(taskId,ids.getFirst(),goal.taskVersion(),jwt);
        if(!goal.equals(after)||!before.equals(pointers(taskId))||!target.expiresAt().equals(targetAfter.expiresAt())||!target.original().evidence(0,target.original().text().length()).equals(targetAfter.original().evidence(0,targetAfter.original().text().length())))throw conflict();
        return new Context(taskId,goal.taskVersion(),goal.goalId(),goal.goalRevision(),goal.inputId(),ids.getFirst(),target.original().text(),
                Long.parseLong(before.getFirst()),before.get(1).isEmpty()?null:before.get(1),target.expiresAt().isBefore(goal.expiresAt())?target.expiresAt():goal.expiresAt());
    }
    private List<String> pointers(String task) {
        var features=db.queryForList("SELECT revision_number FROM con5_feature_current WHERE task_id=?",Long.class,task);
        var inputs=db.queryForList("SELECT comparison_id FROM con5_comparison_current WHERE task_id=?",String.class,task);
        if(features.size()>1||inputs.size()>1)throw conflict();return List.of(features.isEmpty()?"0":features.getFirst().toString(),inputs.isEmpty()?"":inputs.getFirst());
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"COMPARISON_CONTEXT_CHANGED");}
}
