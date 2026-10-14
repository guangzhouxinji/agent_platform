package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** The current delegating human's READ is separate from both COMPARE and the historical intent. */
final class ProductComparisonWorkerReadAuthority {
    record Read(ProductTaskDirectory.Task task,Instant expiresAt) { }
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ProductTaskDirectory tasks;
    private final Clock clock;
    private final String issuer;
    ProductComparisonWorkerReadAuthority(JdbcTemplate db,ProductGrantWatermark watermark,
            ProductTaskDirectory tasks,Clock clock,String issuer) {
        this.db=db;this.watermark=watermark;this.tasks=tasks;this.clock=clock;this.issuer=issuer;
    }
    Read require(ProductComparisonWorkerTaskAuthority.Authorized actor) {
        if (actor==null || !actor.expiresAt().isAfter(clock.instant())) throw denied();
        watermark.assertUnchanged(actor.grantGeneration());
        var task=tasks.find(actor.taskId()).orElseThrow(ProductComparisonWorkerReadAuthority::denied);
        if (!"IN_PROGRESS".equals(task.state()) || task.version()!=actor.taskVersion()
                || !task.expiresAt().isAfter(clock.instant())) throw denied();
        List<Instant> grants=db.query("SELECT g.expires_at FROM id2_grant g "
                + "JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                + "AND e.issuer=g.issuer AND e.subject_id=g.subject_id AND e.organization=g.organization "
                + "AND e.domain_code=g.domain_code AND e.resource_type=g.resource_type AND e.resource_id=g.resource_id "
                + "AND e.action_code=g.action_code AND e.purpose=g.purpose "
                + "JOIN id2_subject h ON h.issuer=g.issuer AND h.subject_id=g.subject_id "
                + "WHERE g.issuer=? AND g.subject_id=? AND h.status='ACTIVE' AND h.subject_kind='HUMAN' "
                + "AND h.password_hash IS NOT NULL AND g.organization=? AND g.domain_code='CONSULTATION' "
                + "AND g.task_scope=? AND g.data_scope=? AND g.resource_type='TASK' AND g.resource_id=? "
                + "AND g.resource_version=? AND g.action_code='READ' AND g.purpose='TASK_WORK' "
                + "AND g.status='ACTIVE' AND g.expires_at>?",(rs,row)->rs.getTimestamp(1).toInstant(),issuer,
                actor.humanSubject(),task.organization(),task.id(),task.dataScope(),task.id(),task.authorizationVersion(),Timestamp.from(clock.instant()));
        if (grants.size()!=1 || !task.equals(tasks.find(task.id()).orElse(null))) throw denied();
        watermark.assertUnchanged(actor.grantGeneration());
        Instant expiresAt=grants.getFirst();
        if (task.expiresAt().isBefore(expiresAt)) expiresAt=task.expiresAt();
        if (actor.expiresAt().isBefore(expiresAt)) expiresAt=actor.expiresAt();
        if (!expiresAt.isAfter(clock.instant())) throw denied();
        return new Read(task,expiresAt);
    }
    private static IllegalStateException denied() {return new IllegalStateException("Current delegated Task READ required");}
}
