package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;
import cn.xinji.agentplatform.consultation.patenttask.authorization.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

final class ProductComparisonPreparationLoader {
    record Retention(String ref,long version,String sha) { }
    record Loaded(Prepare command, ProductTaskDirectory.Task task, OriginalClaimText target,
                  ProductComparisonDocuments.Loaded targetPatent, List<ProductComparisonDocuments.Loaded> candidates,
                  List<ProductComparisonStore.DocumentRow> documents, String snapshotJson, String prepareSha,
                  String materialSha, Instant expiresAt, Retention retention, boolean targetReady) { }
    private final JdbcTemplate db;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess access;
    private final ProductComparisonTargetReader targets;
    private final ProductComparisonDocuments documents;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductComparisonPreparationLoader(JdbcTemplate db,ProductTaskDirectory tasks,ProductConsultationAccess access,
            ProductComparisonTargetReader targets,ProductComparisonDocuments documents,ProductComparisonJson json,Clock clock) {
        this.db=db;this.tasks=tasks;this.access=access;this.targets=targets;this.documents=documents;this.json=json;this.clock=clock;
    }
    Loaded load(String taskId,Prepare command,Jwt jwt,boolean historical) {
        validate(command); var task=require(taskId,jwt,ProductAccessPolicy.Action.READ);
        if(!historical && task.version()!=command.expectedTaskVersion()) throw conflict();
        Instant expiry=task.expiresAt();
        if(!historical) {
            var goals=db.query("SELECT g.expires_at,i.expires_at FROM con3_goal_current c "
                    + "JOIN con3_goal_revision g ON g.task_id=c.task_id AND g.goal_id=c.goal_id AND g.revision=c.revision "
                    + "JOIN con3_search_input i ON i.task_id=g.task_id AND i.goal_id=g.goal_id AND i.goal_revision=g.revision "
                    + "WHERE c.task_id=? AND c.goal_id=? AND c.revision=? AND c.task_version=? AND i.input_id=? "
                    + "AND i.task_version=? AND g.status='CONFIRMED'",(rs,row)->min(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant()),
                    taskId,command.goalId(),command.goalRevision(),task.version(),command.inputId(),task.version());
            if(goals.size()!=1 || !goals.getFirst().isAfter(clock.instant())) throw conflict();
            expiry=min(expiry,goals.getFirst());
        }
        OriginalClaimText target; ProductComparisonDocuments.Loaded targetPatent=null;
        if(command.target().messageId()!=null) {
            var message=targets.readUserScheme(taskId,command.target().messageId(),task.version(),jwt);
            target=message.original();expiry=min(expiry,message.expiresAt());
        } else {
            targetPatent=documents.read(taskId,command.target().patent(),jwt,historical);
            target=targetPatent.original();expiry=min(expiry,targetPatent.authority().expiresAt());
        }
        List<ProductComparisonDocuments.Loaded> candidates=new ArrayList<>(); Set<String> keys=new HashSet<>();
        for(var ref:command.candidates()) {
            var candidate=documents.read(taskId,ref,jwt,historical);
            if(!keys.add(candidate.material().key())) throw invalid("DUPLICATE_CANDIDATE_VERSION");
            candidates.add(candidate);expiry=min(expiry,candidate.authority().expiresAt());
        }
        if(candidates.stream().mapToLong(d->d.material().branches().size()).sum()>4000) throw invalid("MATRIX_BUDGET_EXCEEDED");
        var policies=db.query("SELECT retention_ref,retention_version,retention_evidence_sha FROM con3_task WHERE task_id=?",
                (rs,row)->new Retention(rs.getString(1),rs.getLong(2),rs.getString(3)),taskId);
        if(policies.size()!=1 || !expiry.isAfter(clock.instant())) throw conflict();
        expiry=expiry.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Span targetSpan=Span.of(target.evidence(0,target.text().length()));
        Map<String,Object> material=new LinkedHashMap<>();material.put("target",targetSpan);
        material.put("targetPatent",targetPatent==null?null:targetPatent.material());
        material.put("candidates",candidates.stream().map(ProductComparisonDocuments.Loaded::material).toList());
        List<ProductComparisonStore.DocumentRow> rows=new ArrayList<>();
        if(targetPatent!=null) rows.add(ProductComparisonStore.documentRow("TARGET",0,targetPatent,json));
        for(int i=0;i<candidates.size();i++) rows.add(ProductComparisonStore.documentRow("CANDIDATE",i,candidates.get(i),json));
        Map<String,Object> snapshot=new LinkedHashMap<>(); snapshot.put("taskId",taskId); snapshot.put("command",command);
        snapshot.put("taskVersion",task.version());snapshot.put("material",material);snapshot.put("documentRows",rows);
        List<ProductComparisonDocuments.Authority> authorities=new ArrayList<>();
        if(targetPatent!=null) authorities.add(targetPatent.authority());
        candidates.forEach(doc->authorities.add(doc.authority()));snapshot.put("authorityBindings",authorities);
        snapshot.put("retention",policies.getFirst());snapshot.put("expiresAt",expiry);snapshot.put("methodVersion","PATENT_FEATURE_MATRIX_V1");
        String encoded=json.canonical(snapshot);
        return new Loaded(command,task,target,targetPatent,List.copyOf(candidates),List.copyOf(rows),encoded,
                ProductComparisonJson.hash(encoded),json.sha(material),expiry,policies.getFirst(),
                targetPatent==null || targetPatent.material().gap().isEmpty());
    }
    ProductTaskDirectory.Task require(String taskId,Jwt jwt,ProductAccessPolicy.Action action) {
        if(jwt==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"IDENTITY_REQUIRED");
        var task=tasks.find(taskId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"TASK_UNAVAILABLE"));
        if(!"IN_PROGRESS".equals(task.state()) || !task.expiresAt().isAfter(clock.instant())
                || !access.permits(jwt,new ProductConsultationAccess.ResourceFacts(task.organization(),taskId,task.dataScope(),
                "TASK",taskId,task.authorizationVersion()),action)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"TASK_UNAVAILABLE");
        return task;
    }
    private static void validate(Prepare command) {
        if(command==null || command.expectedTaskVersion()<1 || blank(command.goalId()) || command.goalRevision()<1
                || blank(command.inputId()) || command.target()==null || command.candidates()==null
                || command.candidates().isEmpty() || command.candidates().size()>20) throw invalid("PREPARATION_INPUT_INVALID");
        if("DESIGN_ASSISTANCE".equals(command.purpose())) throw invalid("CAPABILITY_UNAVAILABLE");
        if("NOVELTY_ASSISTANCE".equals(command.purpose())) throw invalid("PURPOSE_MATERIALS_REQUIRED");
        if(!Set.of("TECHNICAL_SIMILARITY","CLAIM_ALIGNMENT").contains(command.purpose()==null?"":command.purpose()))
            throw invalid("PURPOSE_INVALID");
        if((command.target().messageId()==null)==(command.target().patent()==null)) throw invalid("EXACT_TARGET_REQUIRED");
        if(command.target().messageId()!=null && blank(command.target().messageId())) throw invalid("EXACT_TARGET_REQUIRED");
        if(command.target().patent()!=null) ProductComparisonDocuments.validate(command.target().patent());
        command.candidates().forEach(ProductComparisonDocuments::validate);
    }
    static Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
    static boolean blank(String s) { return s==null || s.isBlank(); }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"PREPARATION_VERSION_CHANGED"); }
    static ResponseStatusException invalid(String code) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code); }
}
