package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** A separately approved physical retention window, distinct from business-use expiry. */
final class ProductTaskBackupRetention {
    record Decision(long version, String responsibleParty, List<String> organizations,
                    Duration packageMaxAge, Instant historicalAuditUntil,
                    Map<String, String> retainedEvidence, boolean authorizationHistory,
                    boolean sourceHistory) { }

    private static final List<String> RETENTION_TABLES = List.of("con3_task", "con4_source_contract",
            "con4_source_product_decision", "con3_message", "con3_goal_revision");
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    private final Clock clock;

    ProductTaskBackupRetention(ProductSourceEvidenceFiles files, ObjectMapper mapper, Clock clock) {
        this.files = files;
        this.mapper = mapper;
        this.clock = clock;
    }

    void require(JdbcTemplate db, String ref, String sha, long version, Instant packageExpiresAt) {
        Decision decision;
        try { decision = mapper.readValue(files.verified(ref, sha), Decision.class); }
        catch (Exception invalid) { throw new IllegalStateException("Backup retention decision is invalid", invalid); }
        Instant now = clock.instant();
        if (decision.version() != version || blank(decision.responsibleParty())
                || decision.packageMaxAge() == null || decision.packageMaxAge().isNegative()
                || decision.packageMaxAge().isZero() || decision.historicalAuditUntil() == null
                || decision.organizations() == null || decision.organizations().isEmpty()
                || decision.organizations().stream().anyMatch(ProductTaskBackupRetention::blank)
                || decision.retainedEvidence() == null || !decision.authorizationHistory()
                || !decision.sourceHistory() || packageExpiresAt == null
                || !packageExpiresAt.isAfter(now)
                || packageExpiresAt.isAfter(now.plus(decision.packageMaxAge()))
                || packageExpiresAt.isAfter(decision.historicalAuditUntil()))
            throw new IllegalStateException("Backup package exceeds approved physical retention");
        Set<String> organizations = new HashSet<>(db.queryForList(
                "SELECT organization_id FROM con3_organization", String.class));
        organizations.addAll(db.queryForList(
                "SELECT organization_id FROM con4_source_product_decision", String.class));
        if (!organizations.equals(new HashSet<>(decision.organizations())))
            throw new IllegalStateException("Backup organization coverage changed");
        Map<String, String> actual = new HashMap<>();
        for (String table : RETENTION_TABLES) {
            db.query("SELECT retention_ref,retention_evidence_sha FROM " + table,
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                        String previous = actual.putIfAbsent(row.getString(1), row.getString(2));
                        if (previous != null && !previous.equalsIgnoreCase(row.getString(2)))
                            throw new IllegalStateException("Conflicting object retention versions");
                    });
        }
        ProductModelTurnRetentionReferences.collect(db, actual);
        if (!actual.keySet().equals(decision.retainedEvidence().keySet()))
            throw new IllegalStateException("Backup object retention coverage changed");
        for (var entry : actual.entrySet()) {
            String expected = decision.retainedEvidence().get(entry.getKey());
            if (expected == null || !expected.matches("[0-9a-fA-F]{64}")
                    || !expected.equalsIgnoreCase(entry.getValue()))
                throw new IllegalStateException("Backup object retention digest changed");
            files.verified(entry.getKey(), expected);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
