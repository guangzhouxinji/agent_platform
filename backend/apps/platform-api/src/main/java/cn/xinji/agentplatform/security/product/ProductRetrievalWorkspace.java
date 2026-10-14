package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Read model for the member workface; it cannot create or widen source authority. */
final class ProductRetrievalWorkspace {
    record Source(String sourceId, String scopeId, long scopeVersion, long taskVersion,
                  long sourceEpoch, long sourceRowVersion, String contractVersion, String decisionId,
                  Instant expiresAt, List<String> fields,
                  @com.fasterxml.jackson.annotation.JsonIgnore String queryGrantId) { }
    record View(ProductTaskDirectory.Task task, ProductTaskGoalReader.GoalView goal,
                List<Source> sources, ProductPatentSearchExecution.Page result,
                String retentionRef, long retentionVersion, long selectionRevision,
                List<ProductRetrievalDialogue.Turn> dialogue) { }
    private final JdbcTemplate db;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess access;
    private final ProductTaskGoalReader goals;
    private final ProductSourceAvailability sources;
    private final ProductPatentSearchExecution searches;
    private final ProductRetrievalDialogue dialogue;
    private final Clock clock;
    private final String retentionRef;
    private final long retentionVersion;

    ProductRetrievalWorkspace(JdbcTemplate db, ProductTaskDirectory tasks, ProductConsultationAccess access,
            ProductTaskGoalReader goals, ProductSourceAvailability sources, ProductPatentSearchExecution searches,
            Clock clock, String retentionRef, long retentionVersion, ProductRetrievalDialogue dialogue) {
        this.db=db; this.tasks=tasks; this.access=access; this.goals=goals; this.sources=sources;
        this.searches=searches; this.clock=clock; this.retentionRef=retentionRef;
        this.retentionVersion=retentionVersion;
        this.dialogue=dialogue;
    }

    ProductTaskDirectory.Task permitted(String id, Jwt jwt, ProductAccessPolicy.Action action) {
        if (jwt==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var task=tasks.find(id).orElseThrow(ProductRetrievalWorkspace::notFound);
        if (!task.expiresAt().isAfter(clock.instant()) || !access.permits(jwt,
                new ProductConsultationAccess.ResourceFacts(task.organization(),id,task.dataScope(),
                        "TASK",id,task.authorizationVersion()),action)) throw notFound();
        return task;
    }

    void requireResultDependency(String taskId, String resultSetId, Jwt jwt) {
        permitted(taskId,jwt,ProductAccessPolicy.Action.READ);
        searches.read(taskId,resultSetId,20,null,jwt);
    }

    View read(String id, Jwt jwt) {
        var task=permitted(id,jwt,ProductAccessPolicy.Action.READ);
        var goal=goal(id,jwt,task);
        var allowed=allowed(task,jwt);
        var result=goal==null ? null : result(id,goal.inputId(),jwt);
        if (!task.equals(permitted(id,jwt,ProductAccessPolicy.Action.READ))) throw conflict();
        Long revision=db.queryForObject("SELECT COALESCE(MAX(revision),0) FROM con4_selection_revision "
                + "WHERE task_id=?",Long.class,id);
        var turns=db.query(connection->{
            var statement=connection.prepareStatement("SELECT turn_id FROM con3_model_turn WHERE task_id=? "
                    + "AND actor_subject=? AND task_version=? AND expires_at>? ORDER BY created_at DESC");
            statement.setString(1,id); statement.setString(2,jwt.getSubject()); statement.setLong(3,task.version());
            statement.setTimestamp(4,Timestamp.from(clock.instant())); statement.setMaxRows(3); return statement;
        },(rs,n)->rs.getString(1)).stream().map(turn->dialogue.read(id,turn,jwt)).toList();
        if (!task.equals(permitted(id,jwt,ProductAccessPolicy.Action.READ))) throw conflict();
        return new View(task,goal,allowed,result,retentionRef,retentionVersion,revision==null?0:revision,turns);
    }

    ProductTaskGoalReader.GoalView goal(String id, Jwt jwt, ProductTaskDirectory.Task task) {
        return "IN_PROGRESS".equals(task.state()) ? goals.current(id,jwt) : null;
    }

    List<Source> allowed(ProductTaskDirectory.Task task, Jwt jwt) {
        // SOURCE_QUERY remains independent of Task READ/WRITE. A grant from another issuer/member is never reused.
        Instant now=clock.instant();
        var rows=db.query("SELECT s.source_id,s.scope_id,s.scope_version,s.task_version,s.source_epoch,"
                + "s.source_row_version,s.contract_version,s.decision_id,s.expires_at,g.expires_at,g.grant_id "
                + "FROM con4_task_source_current c JOIN con4_task_source_scope s ON s.scope_id=c.scope_id "
                + "AND s.scope_version=c.scope_version JOIN id2_grant g ON g.resource_id=s.scope_id "
                + "AND g.resource_version=s.scope_version AND g.data_scope=s.scope_id || ':' || s.scope_version "
                + "JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id "
                + "AND e.authorization_epoch=g.grant_generation "
                + "WHERE c.task_id=? AND s.organization_id=? AND c.status='ACTIVE' AND s.status='ACTIVE' "
                + "AND s.expires_at>? AND g.issuer=? AND g.subject_id=? AND g.organization=? "
                + "AND g.domain_code='CONSULTATION' AND g.task_scope=? AND g.resource_type='SOURCE_SCOPE' "
                + "AND g.action_code='QUERY' AND g.purpose='SOURCE_QUERY' AND g.status='ACTIVE' AND g.expires_at>?",
                (rs,n)->new Source(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),
                        rs.getLong(6),rs.getString(7),rs.getString(8),
                        earlier(rs.getTimestamp(9).toInstant(),rs.getTimestamp(10).toInstant()),List.of(),rs.getString(11)),
                task.id(),task.organization(),Timestamp.from(now),jwt.getIssuer().toString(),jwt.getSubject(),
                task.organization(),task.id(),Timestamp.from(now));
        return rows.stream().filter(s->s.taskVersion()==task.version() || s.taskVersion()==task.version()-1)
                .map(s->withFields(s,now)).filter(s->!s.fields().isEmpty()).toList();
    }

    private Source withFields(Source s, Instant now) {
        var fields=db.query("SELECT field_code FROM con4_source_field_use WHERE decision_id=? "
                + "AND use_code='QUERY' AND valid_until>? AND field_code IN ('TITLE','CLAIMS') ORDER BY field_code",
                (rs,n)->rs.getString(1),s.decisionId(),Timestamp.from(now));
        if (!fields.isEmpty()) {
            var current=sources.requireFields(s.sourceId(),Set.copyOf(fields),"QUERY");
            if (current.epoch()!=s.sourceEpoch() || current.rowVersion()!=s.sourceRowVersion()
                    || !current.contractVersion().equals(s.contractVersion())
                    || !current.decisionId().equals(s.decisionId())) throw conflict();
        }
        return new Source(s.sourceId(),s.scopeId(),s.scopeVersion(),s.taskVersion(),s.sourceEpoch(),
                s.sourceRowVersion(),s.contractVersion(),s.decisionId(),s.expiresAt(),fields,s.queryGrantId());
    }

    ProductPatentSearchExecution.Page result(String task, String input, Jwt jwt) {
        var ids=db.query("SELECT r.result_set_id FROM con4_result_set r JOIN con4_search_attempt a "
                + "ON a.attempt_id=r.attempt_id WHERE a.task_id=? AND a.input_revision=? "
                + "AND a.expires_at>? ORDER BY a.created_at DESC",(rs,n)->rs.getString(1),task,input,
                Timestamp.from(clock.instant()));
        return ids.isEmpty() ? null : searches.read(task,ids.getFirst(),20,null,jwt);
    }

    static Instant earlier(Instant a,Instant b) { return a.isBefore(b)?a:b; }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
