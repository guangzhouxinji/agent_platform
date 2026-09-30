package cn.xinji.agentplatform.security.product;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the independent current authorities; restored primary bytes never define them. */
public final class ProductTaskBackupAuthority {
    private record Current(long generation, String grantSha,
                           Map<String, ProductTaskBackupBundle.SourceEpoch> sources) { }

    private final JdbcTemplate primary;
    private final JdbcTemplate authority;
    private final ProductGrantWatermark grants;
    private final ProductSourceWatermark sources;
    private final String issuer;
    private final Clock clock;

    public ProductTaskBackupAuthority(JdbcTemplate primary, JdbcTemplate authority,
            ProductGrantWatermark grants, ProductSourceWatermark sources,
            String issuer, Clock clock) {
        this.primary = primary;
        this.authority = authority;
        this.grants = grants;
        this.sources = sources;
        this.issuer = issuer;
        this.clock = clock;
    }

    public ProductTaskBackupBundle.Snapshot capture(String batchId, String primarySchema,
            String keyId, Instant expiresAt, String retentionRef, long retentionVersion) {
        Current before = current();
        new ProductTaskRecoveryVerifier(primary, grants).verify();
        if (!before.equals(current()))
            throw new IllegalStateException("Backup authority changed during closure check");
        return new ProductTaskBackupBundle.Snapshot(batchId, issuer, primarySchema, keyId,
                before.generation(), before.grantSha(), before.sources(), clock.instant(),
                expiresAt, retentionRef, retentionVersion);
    }

    public void assertUnchanged(ProductTaskBackupBundle.Snapshot snapshot) {
        if (snapshot == null || !issuer.equals(snapshot.issuer()))
            throw new IllegalArgumentException("Exact backup issuer required");
        Current now = current();
        if (now.generation() != snapshot.grantGeneration()
                || !now.grantSha().equals(snapshot.grantHistorySha())
                || !now.sources().equals(snapshot.sourceWatermarks()))
            throw new IllegalStateException("Current authority changed during backup");
        new ProductTaskRecoveryVerifier(primary, grants).verify();
        if (!now.equals(current()))
            throw new IllegalStateException("Current authority changed during closure check");
    }

    private Current current() {
        long generation = grants.currentGeneration();
        String grantSha = authority.queryForObject("SELECT history_sha FROM id2_authority_watermark "
                + "WHERE issuer=? AND generation=? AND state='READY' AND intent_id IS NULL",
                String.class, issuer, generation);
        if (grantSha == null) throw new IllegalStateException("Grant authority unavailable");
        sources.assertAllCurrent();
        Map<String, ProductTaskBackupBundle.SourceEpoch> actual = new LinkedHashMap<>();
        for (String sourceId : primary.queryForList(
                "SELECT source_id FROM con4_source_current ORDER BY source_id", String.class)) {
            ProductSourceWatermark.Current current = sources.current(sourceId);
            String historySha = authority.queryForObject("SELECT history_sha FROM id4_source_watermark "
                    + "WHERE source_id=? AND source_epoch=? AND intent_id IS NULL", String.class,
                    sourceId, current.epoch());
            if (historySha == null) throw new IllegalStateException("Source authority unavailable");
            actual.put(sourceId, new ProductTaskBackupBundle.SourceEpoch(current.epoch(),
                    "ACTIVE".equals(current.status()) ? "READY" : "WITHDRAWN", historySha));
        }
        grants.assertUnchanged(generation);
        sources.assertAllCurrent();
        return new Current(generation, grantSha, Map.copyOf(actual));
    }
}
