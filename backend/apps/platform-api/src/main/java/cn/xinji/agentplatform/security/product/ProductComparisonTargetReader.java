package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Internal prepare adapter: caller explicitly selects a saved user scheme, not a search query. */
final class ProductComparisonTargetReader {
    record Target(String taskId, long taskVersion, String messageId, Instant expiresAt,
                  ProductTechnicalSchemeOriginal original) { }
    private record Message(String text, String sha, Instant expiresAt) { }
    private final JdbcTemplate db;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess access;
    private final Clock clock;
    private final int maximumBytes;
    ProductComparisonTargetReader(JdbcTemplate db, ProductTaskDirectory tasks, ProductConsultationAccess access,
            Clock clock, int maximumBytes) {
        if (maximumBytes<1) throw new IllegalArgumentException("Configured goal byte budget required");
        this.db=db; this.tasks=tasks; this.access=access; this.clock=clock; this.maximumBytes=maximumBytes;
    }
    Target readUserScheme(String taskId, String messageId, long expectedTaskVersion, Jwt jwt) {
        if (jwt==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (taskId==null || messageId==null || !taskId.matches("[0-9a-fA-F-]{36}")
                || !messageId.matches("[0-9a-fA-F-]{36}") || expectedTaskVersion<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var task=require(taskId,jwt);
        if (task.version()!=expectedTaskVersion) throw new ResponseStatusException(HttpStatus.CONFLICT);
        Message message=message(taskId,messageId);
        var original=new ProductTechnicalSchemeOriginal(taskId,messageId,message.text(),message.sha(),maximumBytes);
        if (!message.equals(message(taskId,messageId)) || !task.equals(require(taskId,jwt)))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Target(taskId,task.version(),messageId,
                message.expiresAt().isBefore(task.expiresAt())?message.expiresAt():task.expiresAt(),original);
    }
    Target readDelegatedUserScheme(String taskId,String messageId,long expectedTaskVersion,
            ProductComparisonWorkerTaskAuthority.Authorized actor,ProductComparisonWorkerReadAuthority reads) {
        if (actor==null || !taskId.equals(actor.taskId()) || expectedTaskVersion!=actor.taskVersion()) throw missing();
        var before=reads.require(actor);Message message=message(taskId,messageId);
        var original=new ProductTechnicalSchemeOriginal(taskId,messageId,message.text(),message.sha(),maximumBytes);
        if (!message.equals(message(taskId,messageId)) || !before.equals(reads.require(actor))) throw missing();
        return new Target(taskId,expectedTaskVersion,messageId,
                message.expiresAt().isBefore(before.expiresAt())?message.expiresAt():before.expiresAt(),original);
    }
    private Message message(String taskId,String messageId) {
        var rows=db.query("SELECT raw_text,content_sha,expires_at FROM con3_message "
                + "WHERE task_id=? AND message_id=? AND expires_at>?",
                (rs,row)->new Message(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant()),
                taskId,messageId,Timestamp.from(clock.instant()));
        if (rows.size()!=1) throw missing();
        return rows.getFirst();
    }
    private ProductTaskDirectory.Task require(String taskId,Jwt jwt) {
        var task=tasks.find(taskId).orElseThrow(ProductComparisonTargetReader::missing);
        if (!"IN_PROGRESS".equals(task.state()) || !task.expiresAt().isAfter(clock.instant())
                || !access.permits(jwt,new ProductConsultationAccess.ResourceFacts(task.organization(),taskId,
                task.dataScope(),"TASK",taskId,task.authorizationVersion()),ProductAccessPolicy.Action.READ)) throw missing();
        return task;
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
