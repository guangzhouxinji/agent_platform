package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Authorized original inventory, not a query or a persisted search selection. */
@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-comparisons/documents")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
final class ProductComparisonDocumentCatalogController {
    record Document(String key,String sourceId,String documentId,String documentVersion,String sourceVersion,
                    String rawSha,List<Integer> claimNumbers,String claimsText,String gap) { }
    record Catalog(String taskId,long taskVersion,String goalId,long goalRevision,String inputId,
                   Instant expiresAt,List<Document> documents,Integer nextOffset) { }
    private final JdbcTemplate db;private final ProductTaskDirectory tasks;private final ProductConsultationAccess access;
    private final ProductTaskGoalReader goals;private final ProductComparisonDocuments originals;
    private final Clock clock=Clock.systemUTC();
    ProductComparisonDocumentCatalogController(JdbcTemplate db,ProductTaskDirectory tasks,ProductConsultationAccess access,
            ProductTaskGoalReader goals,ProductSourceAvailability sources,ProductCollectedPatentTitles captured,
            ProductSourceEvidenceFiles files,ProductGrantWatermark watermark,ObjectMapper mapper,
            @Value("${product.identity.issuer}") String issuer) {
        this.db=db;this.tasks=tasks;this.access=access;this.goals=goals;
        this.originals=new ProductComparisonDocuments(sources,captured,files,
                new ProductPatentDocumentAccess(db,watermark,clock,issuer),db,mapper,clock);
    }
    @GetMapping Catalog read(@PathVariable String taskId,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="100") int limit,@AuthenticationPrincipal Jwt jwt) {
        if(offset<0||limit<1||limit>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var task=require(taskId,jwt);var goal=goals.current(taskId,jwt);
        var sourceIds=sourceIds(taskId,task.version(),jwt);
        var before=load(taskId,sourceIds,jwt);var after=load(taskId,sourceIds,jwt);
        if(!before.equals(after)||!task.equals(require(taskId,jwt))||!goal.equals(goals.current(taskId,jwt))
                ||!sourceIds.equals(sourceIds(taskId,task.version(),jwt))) throw changed();
        Instant until=goal.expiresAt().isBefore(task.expiresAt())?goal.expiresAt():task.expiresAt();
        for(var loaded:before)if(loaded.until().isBefore(until))until=loaded.until();
        if(!until.isAfter(clock.instant()))throw changed();
        if(offset>before.size())throw changed();
        int end=(int)Math.min(before.size(),(long)offset+limit);
        return new Catalog(taskId,task.version(),goal.goalId(),goal.goalRevision(),goal.inputId(),until,
                before.subList(offset,end).stream().map(Item::document).toList(),end<before.size()?end:null);
    }
    private record Item(Document document,ProductComparisonDocuments.Authority authority,Instant until) { }
    private List<Item> load(String task,List<String> ids,Jwt jwt) {
        var result=new ArrayList<Item>();var keys=new HashSet<String>();
        for(String id:ids)for(var loaded:originals.catalog(task,id,jwt)) {
            var m=loaded.material();
            if(!keys.add(m.key())||result.size()>=4000)throw changed();
            result.add(new Item(new Document(m.key(),m.sourceId(),m.documentId(),m.documentVersion(),m.sourceVersion(),
                    m.rawSha(),loaded.reference().claimNumbers(),loaded.original().text(),m.gap()),loaded.authority(),loaded.authority().expiresAt()));
        }
        result.sort(Comparator.comparing(x->x.document().key()));return List.copyOf(result);
    }
    private List<String> sourceIds(String task,long version,Jwt jwt) {
        return db.queryForList("SELECT DISTINCT s.source_id FROM con4_task_source_scope s "
                +"JOIN con4_source_field_use fd ON fd.decision_id=s.decision_id AND fd.field_code='CLAIMS' AND fd.use_code='DISPLAY' "
                +"JOIN con4_source_field_use fc ON fc.decision_id=s.decision_id AND fc.field_code='CLAIMS' AND fc.use_code='COMPARISON' "
                +"JOIN id2_grant gd ON gd.resource_id=s.scope_id AND gd.resource_version=s.scope_version AND gd.subject_id=? "
                +"AND gd.issuer=? AND gd.status='ACTIVE' AND gd.action_code='DISPLAY' AND gd.purpose='SOURCE_DISPLAY' "
                +"JOIN id2_grant gc ON gc.resource_id=s.scope_id AND gc.resource_version=s.scope_version AND gc.subject_id=gd.subject_id "
                +"AND gc.issuer=gd.issuer AND gc.status='ACTIVE' AND gc.action_code='COMPARE' AND gc.purpose='COMPARISON' "
                +"WHERE s.task_id=? AND s.task_version=? AND s.status='ACTIVE' AND s.expires_at>? "
                +"AND fd.valid_until>? AND fc.valid_until>? AND gd.expires_at>? AND gc.expires_at>? ORDER BY s.source_id",
                String.class,jwt.getSubject(),jwt.getIssuer().toString(),task,version,Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
    }
    private ProductTaskDirectory.Task require(String id,Jwt jwt) {
        if(jwt==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var task=tasks.find(id).orElseThrow(ProductComparisonDocumentCatalogController::changed);
        var facts=new ProductConsultationAccess.ResourceFacts(task.organization(),id,task.dataScope(),"TASK",id,task.authorizationVersion());
        if(!"IN_PROGRESS".equals(task.state())||!task.expiresAt().isAfter(clock.instant())
                ||!access.permits(jwt,facts,ProductAccessPolicy.Action.READ)
                ||!access.permits(jwt,facts,ProductAccessPolicy.Action.COMPARE))throw changed();
        return task;
    }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) void invalid() { }
    private static ResponseStatusException changed(){return new ResponseStatusException(HttpStatus.CONFLICT,"COMPARISON_DOCUMENT_CONTEXT_CHANGED");}
}
