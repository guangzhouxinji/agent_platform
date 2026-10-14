package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only SQL binding checks; caller owns the task/command lock and current-state transaction. */
final class ProductComparisonStartHandoffGuard {
    private ProductComparisonStartHandoffGuard() { }

    static void require(JdbcTemplate db, ProductComparisonStartCommands.Command c, Instant now) {
        requireDelegation(db, c, c.delegationId());
        if (c.attemptId() != null) {
            if (c.leaseUntil() == null || !c.leaseUntil().isAfter(now)) throw conflict();
            Long attempts = db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt a JOIN con5_candidate_lease l "
                    + "ON l.comparison_id=a.comparison_id AND l.candidate_id=a.candidate_id AND l.attempt_id=a.attempt_id "
                    + "AND l.fencing_token=a.fencing_token AND l.lease_until=a.lease_until WHERE a.attempt_id=? AND a.task_id=? "
                    + "AND a.comparison_id=? AND a.candidate_id=? AND a.input_sha=? AND a.task_version=? AND a.cancel_epoch=? "
                    + "AND a.worker_subject=? AND a.delegation_id=? AND a.fencing_token=? AND a.lease_until=? "
                    + "AND a.status='ACTIVE' AND l.status='ACTIVE'", Long.class, c.attemptId(), c.scope().task(), c.scope().comparison(),
                    c.scope().candidate(), c.scope().inputSha(), c.scope().taskVersion(), c.scope().cancelEpoch(),
                    c.managed().serviceSubject(), c.delegationId(), c.fencingToken(), Timestamp.from(c.leaseUntil()));
            if (attempts == null || attempts != 1) throw conflict();
        }
    }

    static void requireDelegation(JdbcTemplate db, ProductComparisonStartCommands.Command c, String delegationId) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM con5_comparison_delegation b JOIN con3_worker_delegation d ON d.delegation_id=b.delegation_id "
                + "JOIN con3_task t ON t.task_id=b.task_id "
                + "WHERE b.delegation_id=? AND b.task_id=? AND b.comparison_id=? AND b.candidate_id=? AND b.input_sha=? AND b.service_subject=? "
                + "AND d.task_id=b.task_id AND d.human_issuer=? AND d.human_subject=? AND d.service_subject=b.service_subject "
                + "AND d.authorization_version=t.authorization_version AND d.status='ACTIVE' AND b.expires_at>=? AND d.expires_at>=?",
                Long.class, delegationId, c.scope().task(), c.scope().comparison(), c.scope().candidate(), c.scope().inputSha(),
                c.managed().serviceSubject(), c.actor().issuer(), c.actor().subject(),
                Timestamp.from(c.expiresAt()), Timestamp.from(c.expiresAt()));
        if (count == null || count != 1) throw conflict();
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("COMPARISON_START_BINDING_OR_STATE_CONFLICT");
    }
}
