package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Scope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Independently maintained source-use permission. Business snapshots never grant access to originals. */
public final class CaseSourceAuthority {
    public record Provenance(String publisher,String url,Instant capturedAt,String publicationVersion,String rightsBasis,String purpose,String artifactSha256,long artifactBytes,String textSha256,String manifestSha256){
        public Provenance{
            java.net.URI uri;try{uri=java.net.URI.create(url);}catch(Exception e){throw new IllegalArgumentException("INVALID_PUBLIC_SOURCE_PROVENANCE");}
            if(publisher==null||publisher.isBlank()||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||capturedAt==null||publicationVersion==null||publicationVersion.isBlank()||rightsBasis==null||rightsBasis.isBlank()||purpose==null||purpose.isBlank()||artifactBytes<1||java.util.stream.Stream.of(artifactSha256,textSha256,manifestSha256).anyMatch(h->h==null||!h.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("INVALID_PUBLIC_SOURCE_PROVENANCE");
        }
    }
    public record Use(Scope scope, Source source, Set<String> allowedUses, Instant validUntil,
                      String evidenceRef, String evidenceSha256,Provenance provenance) {
        public Use(Scope scope,Source source,Set<String> allowedUses,Instant validUntil,String evidenceRef,String evidenceSha256){this(scope,source,allowedUses,validUntil,evidenceRef,evidenceSha256,null);}
        public Use {
            Objects.requireNonNull(scope); Objects.requireNonNull(source); allowedUses = Set.copyOf(allowedUses);
            if (allowedUses.isEmpty() || !Set.of("READ", "SAVE", "ANALYZE", "MODEL").containsAll(allowedUses)
                    || validUntil == null || evidenceRef == null || evidenceRef.isBlank()
                    || evidenceSha256 == null || !evidenceSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("EXPLICIT_SOURCE_USE_EVIDENCE_REQUIRED");
        }
    }
    public record SourceReceipt(String sourceKey, long version, String state) { }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final CaseWorkCodec codec;
    private final Clock clock;
    public CaseSourceAuthority(JdbcTemplate db, TransactionTemplate tx, CaseWorkCodec codec, Clock clock) {
        this.db = db; this.tx = tx; this.codec = codec; this.clock = clock;
    }
    public long generation() {
        var rows = db.query("SELECT generation,state FROM adj2_source_generation WHERE singleton_key=1",
                (rs, row) -> { if (!"READY".equals(rs.getString(2))) throw new IllegalStateException("SOURCE_AUTHORITY_PENDING"); return rs.getLong(1); });
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_AUTHORITY_UNAVAILABLE"); return rows.getFirst();
    }
    public void assertUnchanged(long generation) {
        if (generation() != generation) throw new IllegalStateException("SOURCE_AUTHORITY_CHANGED");
    }
    public void require(Scope scope, List<Source> sources, String use) {
        long before = generation();
        for (var source : sources.stream().distinct().toList()) {
            String key = key(scope, source);
            var rows = db.query("SELECT payload,payload_sha256,state FROM adj2_source_use WHERE source_key=? AND case_id=? AND organization=? AND data_scope=?",
                    (rs, row) -> {
                        String payload = rs.getString(1); CaseWorkCodec.hash(payload, rs.getString(2));
                        if (!"ACTIVE".equals(rs.getString(3))) throw new SecurityException("SOURCE_USE_NOT_CURRENT");
                        return codec.decode(payload, Use.class);
                    }, key, scope.caseId(), scope.organization(), scope.dataScope());
            if (rows.size() != 1 || !rows.getFirst().scope().equals(scope) || !rows.getFirst().source().equals(source)
                    || !rows.getFirst().allowedUses().contains(use) || !rows.getFirst().validUntil().isAfter(clock.instant()))
                throw new SecurityException("EXACT_SOURCE_USE_REQUIRED");
        }
        assertUnchanged(before);
    }
    /** Narrow read-only deadline port for derived records; never changes source rights or generations. */
    public Instant permittedUntil(Scope scope,List<Source> sources,String use) {
        if(sources==null||sources.isEmpty())throw new SecurityException("EXACT_SOURCE_USE_REQUIRED");
        long before=generation();Instant deadline=Instant.MAX;
        for(var source:sources.stream().distinct().toList()){
            var rows=db.query("SELECT payload,payload_sha256,state FROM adj2_source_use WHERE source_key=? AND case_id=? AND organization=? AND data_scope=?",(rs,row)->{
                String payload=rs.getString(1);CaseWorkCodec.hash(payload,rs.getString(2));if(!"ACTIVE".equals(rs.getString(3)))throw new SecurityException("SOURCE_USE_NOT_CURRENT");return codec.decode(payload,Use.class);
            },key(scope,source),scope.caseId(),scope.organization(),scope.dataScope());
            if(rows.size()!=1||!rows.getFirst().scope().equals(scope)||!rows.getFirst().source().equals(source)||!rows.getFirst().allowedUses().contains(use)||!rows.getFirst().validUntil().isAfter(clock.instant()))throw new SecurityException("EXACT_SOURCE_USE_REQUIRED");
            if(rows.getFirst().validUntil().isBefore(deadline))deadline=rows.getFirst().validUntil();
        }
        assertUnchanged(before);return deadline;
    }
    public SourceReceipt register(Use use, String maintainer) {
        if(use.source().kind()==InputKind.PUBLIC){var p=use.provenance();if(p==null)throw new IllegalArgumentException("PUBLIC_SOURCE_PROVENANCE_REQUIRED");if(!p.textSha256().equals(use.source().sha256())||!p.manifestSha256().equals(use.evidenceSha256())||p.capturedAt().isAfter(clock.instant().plusSeconds(60)))throw new IllegalArgumentException("PUBLIC_SOURCE_PROVENANCE_MISMATCH");}

        if (maintainer == null || maintainer.isBlank() || !use.validUntil().isAfter(clock.instant()))
            throw new IllegalArgumentException("LIVE_MAINTAINED_SOURCE_USE_REQUIRED");
        return tx.execute(status -> {
            long generation = lock(); String key = key(use.scope(), use.source());
            if (db.queryForObject("SELECT COUNT(*) FROM adj2_source_use WHERE source_key=?", Integer.class, key) != 0)
                throw new IllegalArgumentException("SOURCE_USE_ALREADY_REGISTERED");
            String payload = codec.encode(use);
            db.update("INSERT INTO adj2_source_use(source_key,case_id,organization,data_scope,payload,payload_sha256,state,version,maintainer_ref) VALUES(?,?,?,?,?,?,'ACTIVE',1,?)",
                    key, use.scope().caseId(), use.scope().organization(), use.scope().dataScope(), payload, sha256(payload), maintainer);
            event(key, 1, "REGISTER", maintainer); complete(generation);
            return new SourceReceipt(key, 1, "ACTIVE");
        });
    }
    public Scope scope(String key) {
        var rows=db.query("SELECT case_id,organization,data_scope FROM adj2_source_use WHERE source_key=?",(rs,row)->new Scope(rs.getString(1),rs.getString(2),rs.getString(3)),key);
        if(rows.size()!=1)throw new IllegalArgumentException("SOURCE_USE_NOT_FOUND");return rows.getFirst();
    }
    public SourceReceipt revoke(String key, long expectedVersion, String maintainer) {
        if (key == null || !key.matches("[a-f0-9]{64}") || expectedVersion < 1 || maintainer == null || maintainer.isBlank())
            throw new IllegalArgumentException("SOURCE_REVOCATION_INPUT_REQUIRED");
        return tx.execute(status -> {
            long generation = lock();
            int changed = db.update("UPDATE adj2_source_use SET state='REVOKED',version=version+1,maintainer_ref=? WHERE source_key=? AND version=? AND state='ACTIVE'",
                    maintainer, key, expectedVersion);
            if (changed != 1) throw new IllegalArgumentException("SOURCE_USE_VERSION_CONFLICT");
            event(key, expectedVersion + 1, "REVOKE", maintainer); complete(generation);
            return new SourceReceipt(key, expectedVersion + 1, "REVOKED");
        });
    }
    public String key(Scope scope, Source source) { return sha256(codec.encode(List.of(scope.caseId(), scope.organization(), scope.dataScope(), source))); }
    private long lock() {
        Long value = db.queryForObject("SELECT generation FROM adj2_source_generation WHERE singleton_key=1 AND state='READY' FOR UPDATE", Long.class);
        if (value == null) throw new IllegalStateException("SOURCE_AUTHORITY_UNAVAILABLE");
        if (db.update("UPDATE adj2_source_generation SET state='PENDING' WHERE singleton_key=1 AND generation=? AND state='READY'", value) != 1)
            throw new IllegalStateException("SOURCE_AUTHORITY_CONFLICT");
        return value;
    }
    private void complete(long generation) {
        if (db.update("UPDATE adj2_source_generation SET generation=generation+1,state='READY' WHERE singleton_key=1 AND generation=? AND state='PENDING'", generation) != 1)
            throw new IllegalStateException("SOURCE_AUTHORITY_CONFLICT");
    }
    private void event(String key, long version, String action, String actor) {
        db.update("INSERT INTO adj2_source_event(event_id,source_key,version,action_code,maintainer_ref,occurred_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), key, version, action, actor, Timestamp.from(clock.instant()));
    }
}
