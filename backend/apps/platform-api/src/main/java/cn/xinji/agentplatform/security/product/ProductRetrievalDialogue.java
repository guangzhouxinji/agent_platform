package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.application.ConsultationRetrievalModelPort;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Final member dialogue. Model suggestions never mutate goals, grants, search results or selections. */
final class ProductRetrievalDialogue {
    record Input(long expectedTaskVersion, String messageText, String resultSetId) { }
    record Turn(String turnId, String taskId, long taskVersion, String kind, String resultSetId,
                String resultSha, String modelId, String promptVersion, JsonNode output,
                List<ProductPatentSearchCitations.Citation> citations, Instant createdAt, Instant expiresAt) { }
    private final JdbcTemplate db;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess access;
    private final ProductTaskGoalReader goals;
    private final ProductPatentSearchExecution searches;
    private final ProductSourceAvailability sources;
    private final ConsultationRetrievalModelPort model;
    private final TransactionTemplate transactions;
    private final ProductRetentionPolicy retention;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProductModelInvocationFence fence;

    ProductRetrievalDialogue(JdbcTemplate db, ProductTaskDirectory tasks, ProductConsultationAccess access,
            ProductTaskGoalReader goals, ProductPatentSearchExecution searches, ProductSourceAvailability sources,
            ConsultationRetrievalModelPort model, TransactionTemplate transactions,
            ProductRetentionPolicy retention, ObjectMapper mapper, Clock clock) {
        this.db=db; this.tasks=tasks; this.access=access; this.goals=goals; this.searches=searches;
        this.sources=sources; this.model=model; this.transactions=transactions;
        this.retention=retention; this.mapper=mapper; this.clock=clock;
        this.fence=new ProductModelInvocationFence(db,transactions,retention,clock);
    }

    Turn invoke(String taskId, Jwt jwt, String requestId, String kind, Input input) {
        if (input == null || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,128}")
                || !Set.of("PROPOSAL","EXPLANATION").contains(kind)) throw badRequest();
        if(model==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_EXECUTION_NOT_CONFIGURED");
        var task = dialogueTask(taskId, jwt);
        if (input.expectedTaskVersion() != task.version()) throw conflict();
        boolean proposal = "PROPOSAL".equals(kind);
        if (proposal && (input.messageText() == null || input.messageText().isBlank()
                || input.messageText().getBytes(StandardCharsets.UTF_8).length > 4000
                || input.messageText().contains("\u0000") || input.resultSetId() != null)) throw badRequest();
        if (!proposal && (input.resultSetId() == null || !uuid(input.resultSetId())
                || input.messageText() != null)) throw badRequest();
        var requestPayload=mapper.createObjectNode(); requestPayload.put("kind",kind);
        requestPayload.set("input",mapper.valueToTree(input));
        String requestSha = sha(json(requestPayload));
        var old = db.query("SELECT turn_id,request_sha FROM con3_model_turn WHERE task_id=? AND actor_subject=? AND request_id=?",
                (rs,n) -> Map.entry(rs.getString(1),rs.getString(2)), taskId,jwt.getSubject(),requestId);
        if (!old.isEmpty()) {
            if (!old.getFirst().getValue().equals(requestSha)) throw conflict();
            return read(taskId, old.getFirst().getKey(), jwt);
        }
        ProductPatentSearchExecution.Page page = proposal ? null : currentResult(taskId,input.resultSetId(),jwt);
        Map<String,ProductSourceAvailability.Active> sourceSnapshots = modelSources(page);
        var payload = mapper.createObjectNode(); payload.put("kind",kind);
        payload.put("scope","PUBLIC_NONCONFIDENTIAL_RETRIEVAL_ONLY");
        if (proposal) {
            payload.put("goal",input.messageText());
            // Only the same actor's already-public model proposals may provide clarification context.
            // Never import arbitrary confirmed task text or past source explanations into a new call.
            var context=payload.putArray("previousPublicProposals");
            var previous=db.query(connection -> {
                var statement=connection.prepareStatement("SELECT request_json,output_json FROM con3_model_turn "
                        + "WHERE task_id=? AND actor_subject=? AND kind='PROPOSAL' AND task_version<=? "
                        + "AND expires_at>? ORDER BY created_at DESC");
                statement.setString(1,taskId); statement.setString(2,jwt.getSubject());
                statement.setLong(3,task.version()); statement.setTimestamp(4,Timestamp.from(clock.instant()));
                statement.setMaxRows(8); return statement;
            }, (rs,n) -> Map.entry(tree(rs.getString(1)),tree(rs.getString(2))));
            for (var item:previous) {
                if (context.size()==2) break;
                if (!"PUBLIC_NONCONFIDENTIAL_RETRIEVAL_ONLY".equals(item.getKey().path("scope").asText())
                        || item.getValue().has("failureCode")) continue;
                var frame=context.addObject(); frame.put("goal",item.getKey().path("goal").asText());
                frame.set("proposal",item.getValue());
            }
        }
        else {
            payload.put("goal",goals.current(taskId,jwt).queryObject());
            payload.put("totalCandidates",page.candidateCount()); payload.put("pageSize",page.members().size());
            payload.put("status",page.status()); payload.put("confidence",page.confidenceStatus());
            payload.put("methodVersion",page.methodVersion()); payload.put("confidenceReason",page.confidenceReason());
            var members = payload.putArray("members");
            for (var member : page.members()) {
                var item = members.addObject(); item.put("memberId",member.memberId());
                item.put("documentId",member.documentId()); item.put("documentVersion",member.documentVersion());
                item.set("matchFields",mapper.valueToTree(member.matchFields()));
                item.set("matchedTerms",mapper.valueToTree(member.matchedTerms()));
                item.set("lexicalScore",mapper.valueToTree(member.lexicalScore()));
                item.set("semanticScore",mapper.valueToTree(member.semanticScore()));
                item.put("ordinal",member.ordinal());
                item.set("lexicalRank",mapper.valueToTree(member.lexicalRank()));
                item.set("semanticRank",mapper.valueToTree(member.semanticRank()));
                item.set("fusionScore",mapper.valueToTree(member.fusionScore()));
                item.set("rerankScore",mapper.valueToTree(member.rerankScore()));
                item.set("rerankRank",mapper.valueToTree(member.rerankRank()));
            }
        }
        String minimized = json(payload);
        var claim=fence.reserve(task,jwt.getSubject(),requestId,requestSha,kind,input.resultSetId(),
                page == null ? null : page.resultSha(), () -> {
                    if (!task.equals(dialogueTask(taskId,jwt))
                            || !sourceSnapshots.equals(modelSources(page))) throw conflict();
                });
        if (!claim.dispatch()) return read(taskId,claim.turnId(),jwt);
        String turnId = claim.turnId();
        if (!task.equals(dialogueTask(taskId,jwt))
                || !sourceSnapshots.equals(modelSources(page))) throw conflict();
        var invocation = model.invoke(minimized,turnId);
        Set<String> ids = new HashSet<>(); if (page != null) page.members().forEach(m -> ids.add(m.memberId()));
        JsonNode validated;
        try { validated = ProductRetrievalDialogueOutput.parse(mapper,invocation.content(),kind,ids); }
        catch (ResponseStatusException invalid) {
            if (invalid.getStatusCode().value()!=502) throw invalid;
            validated=mapper.createObjectNode().put("failureCode","MODEL_OUTPUT_VALIDATION_FAILED");
        }
        final JsonNode output=validated;
        // No long transaction is held during the external model call.
        Turn retained=transactions.execute(status -> {
            db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",(rs,n)->rs.getString(1),taskId);
            if (!task.equals(dialogueTask(taskId,jwt))) throw conflict();
            ProductPatentSearchExecution.Page after = proposal ? null : currentResult(taskId,input.resultSetId(),jwt);
            if (page != null && (!page.resultSha().equals(after.resultSha())
                    || !sourceSnapshots.equals(modelSources(after)))) throw conflict();
            Instant now = clock.instant();
            Instant expiry = claim.expiresAt();
            if (!expiry.isAfter(now)) throw notFound();
            db.update("INSERT INTO con3_model_turn(turn_id,task_id,task_version,actor_subject,kind,request_id,request_sha,"
                    + "result_set_id,result_sha,model_id,prompt_version,request_json,raw_output,output_json,created_at,"
                    + "expires_at,retention_ref,retention_version,retention_evidence_sha) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    turnId,taskId,task.version(),jwt.getSubject(),kind,requestId,requestSha,input.resultSetId(),
                    page == null ? null : page.resultSha(),invocation.modelId(),invocation.promptVersion(),minimized,
                    invocation.rawOutput(),json(output),Timestamp.from(now),Timestamp.from(expiry),
                    retention.reference(),retention.version(),retention.evidenceSha256());
            // Fail the transaction on a concurrently changed current permission or source.
            if (!task.equals(dialogueTask(taskId,jwt))
                    || !sourceSnapshots.equals(modelSources(after))) throw conflict();
            return new Turn(turnId,taskId,task.version(),kind,input.resultSetId(),page == null ? null : page.resultSha(),
                    invocation.modelId(),invocation.promptVersion(),output,cited(page,output),now,expiry);
        });
        // Retain the original failed invocation, but never expose it as a usable answer.
        if (output.has("failureCode")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "MODEL_OUTPUT_VALIDATION_FAILED");
        // Return the committed representation (including database timestamp precision), not transient values.
        // The existing reader also rechecks current authority before exposing a successful answer.
        return read(taskId,retained.turnId(),jwt);
    }

    Turn read(String taskId, String turnId, Jwt jwt) {
        var task = permitted(taskId,jwt,ProductAccessPolicy.Action.READ);
        var rows = db.query("SELECT task_version,kind,result_set_id,result_sha,model_id,prompt_version,output_json,"
                + "created_at,expires_at,retention_ref,retention_version,retention_evidence_sha FROM con3_model_turn "
                + "WHERE task_id=? AND turn_id=? AND actor_subject=?", (rs,n) -> {
            if (!retention.reference().equals(rs.getString(10)) || retention.version() != rs.getLong(11)
                    || !retention.evidenceSha256().equals(rs.getString(12))
                    || !rs.getTimestamp(9).toInstant().isAfter(clock.instant())) throw notFound();
            return new Turn(turnId,taskId,rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                    rs.getString(5),rs.getString(6),tree(rs.getString(7)),List.of(),
                    rs.getTimestamp(8).toInstant(),rs.getTimestamp(9).toInstant());
        },taskId,turnId,jwt.getSubject());
        if (rows.size()!=1) throw notFound();
        Turn value=rows.getFirst();
        if (value.taskVersion()<1 || value.taskVersion()>task.version()) throw conflict();
        var page = value.resultSetId()==null ? null : searches.read(taskId,value.resultSetId(),20,null,jwt);
        modelSources(page);
        if (page != null && !value.resultSha().equals(page.resultSha())) throw conflict();
        if (!task.equals(permitted(taskId,jwt,ProductAccessPolicy.Action.READ))) throw conflict();
        if (value.output().has("failureCode")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "MODEL_OUTPUT_VALIDATION_FAILED");
        return new Turn(value.turnId(),taskId,value.taskVersion(),value.kind(),value.resultSetId(),value.resultSha(),
                value.modelId(),value.promptVersion(),value.output(),cited(page,value.output()),value.createdAt(),value.expiresAt());
    }

    private ProductPatentSearchExecution.Page currentResult(String taskId,String resultId,Jwt jwt) {
        var goal=goals.current(taskId,jwt);
        Integer count=db.queryForObject("SELECT COUNT(*) FROM con4_result_set r JOIN con4_search_attempt a "
                + "ON a.attempt_id=r.attempt_id WHERE r.result_set_id=? AND a.task_id=? AND a.input_revision=?",
                Integer.class,resultId,taskId,goal.inputId());
        if (count == null || count!=1) throw conflict();
        return searches.read(taskId,resultId,20,null,jwt);
    }

    private Map<String,ProductSourceAvailability.Active> modelSources(ProductPatentSearchExecution.Page page) {
        Map<String,ProductSourceAvailability.Active> result=new TreeMap<>();
        Set<String> fields=new TreeSet<>();
        if (page != null) {
            var stored=db.query("SELECT i.requested_fields_json FROM con4_search_attempt a "
                    + "JOIN con3_search_input i ON i.input_id=a.input_revision AND i.task_id=a.task_id "
                    + "WHERE a.attempt_id=?",(rs,n)->rs.getString(1),page.attemptId());
            if (stored.size()!=1) throw conflict();
            JsonNode requested=tree(stored.getFirst());
            if (!requested.isArray() || requested.isEmpty()) throw conflict();
            requested.forEach(n->fields.add(n.asText()));
        }
        if (page != null) for (var member:page.members()) for (String field:fields) {
            var active=sources.require(member.sourceId(),field,"MODEL");
            if (!active.sourceVersion().equals(member.sourceVersion())) throw conflict();
            result.put(member.sourceId()+":"+field,active);
        }
        return result;
    }

    private List<ProductPatentSearchCitations.Citation> cited(ProductPatentSearchExecution.Page page,JsonNode output) {
        if (page == null) return List.of();
        Set<String> ids=new HashSet<>(); output.path("citations").forEach(n->ids.add(n.asText()));
        var cited=page.citations().stream().filter(c->ids.contains(c.memberId())).toList();
        if (cited.size()!=ids.size()) throw conflict(); return cited;
    }

    private ProductTaskDirectory.Task dialogueTask(String id,Jwt jwt) {
        var task=permitted(id,jwt,ProductAccessPolicy.Action.WRITE);
        if (!task.equals(permitted(id,jwt,ProductAccessPolicy.Action.READ))) throw conflict();
        return task;
    }
    private ProductTaskDirectory.Task permitted(String id,Jwt jwt,ProductAccessPolicy.Action action) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var task=tasks.find(id).orElseThrow(ProductRetrievalDialogue::notFound);
        if (!task.expiresAt().isAfter(clock.instant()) || "USER_CANCELLED".equals(task.state())
                || !access.permits(jwt,new ProductConsultationAccess.ResourceFacts(task.organization(),task.id(),
                task.dataScope(),"TASK",task.id(),task.authorizationVersion()),action)) throw notFound();
        return task;
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw badRequest();}}
    private JsonNode tree(String value) {try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException("Corrupt retained model turn");}}
    private static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static boolean uuid(String v) {try{return UUID.fromString(v).toString().equals(v);}catch(Exception e){return false;}}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT);}
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND);}
    private static ResponseStatusException badRequest(){return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
}
