package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Read-only, source-side WAITING closure. Never authorizes restored business activation. */
final class ProductHumanWaitingRecoveryVerifier {
    record Summary(int packages, int scopes, Instant validUntil, String closureSha, boolean businessActivation,
                   Instant executionUntil) { }
    private record Pass(int packages, int scopes, Instant validUntil, String sha, Instant executionUntil) { }
    private final JdbcTemplate main;
    private final JdbcTemplate authority;
    private final ProductComparisonJson json;
    private final Clock clock;
    private final OrdinaryHumanRetention.Policy policy;
    private final ProductRetentionPolicy taskPolicy;
    private final ProductHumanCleanupScope cleanup;
    private final ProductHumanWaitingRecoveryReferences references;
    private static final String SCOPE = "scope_id,organization_id,task_id,generation,state,intent_id,policy_id,policy_version,policy_sha,history_sha,created_at,valid_until";

    ProductHumanWaitingRecoveryVerifier(JdbcTemplate main, JdbcTemplate authority, ObjectMapper mapper,
            Clock clock, OrdinaryHumanRetention.Policy humanPolicy, ProductRetentionPolicy taskPolicy,
            ProductRetentionPolicy factsPolicy, ProductSourceEvidenceFiles files) {
        this.main = Objects.requireNonNull(main); this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock); this.policy = Objects.requireNonNull(humanPolicy);
        this.taskPolicy = Objects.requireNonNull(taskPolicy);
        this.json = new ProductComparisonJson(mapper);
        // The reused scope reader decodes its event row. Guard its mapper before any CLOB access.
        JdbcTemplate scopeReads = new JdbcTemplate(Objects.requireNonNull(main.getDataSource())) {
            @Override public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                return super.query(sql, (rs,n) -> {
                    if (sql.startsWith("SELECT generation,event_json,event_sha,occurred_at,audit_until")) {
                        var at=rs.getTimestamp(4); var end=rs.getTimestamp(5);
                        if(at==null || end==null || at.toInstant().isAfter(clock.instant())
                                || !clock.instant().isBefore(end.toInstant())) throw invalid();
                        var live = main.queryForList("SELECT valid_until FROM con6_human_cleanup_scope WHERE scope_id=?",
                                java.sql.Timestamp.class,args[0]);
                        if(live.size()!=1 || live.getFirst()==null || !clock.instant().isBefore(live.getFirst().toInstant())) throw invalid();
                    }
                    return mapper.mapRow(rs,n);
                },args);
            }
        };
        this.cleanup = new ProductHumanCleanupScope(scopeReads, authority,
                new DataSourceTransactionManager(Objects.requireNonNull(main.getDataSource())), mapper, clock,
                (task, organization, p, operator, now) -> { throw invalid(); });
        this.references = new ProductHumanWaitingRecoveryReferences(main, authority, mapper, clock, taskPolicy, factsPolicy, files);
    }

    Summary verify(Instant backupUntil) {
        Instant start = clock.instant();
        if (backupUntil == null || !backupUntil.isAfter(start) || backupUntil.isAfter(start.plus(Duration.ofDays(30)))) throw invalid();
        Pass before = inspect(backupUntil);
        Pass after = inspect(backupUntil);
        if (!before.equals(after) || !clock.instant().isBefore(after.executionUntil())) throw invalid();
        return new Summary(after.packages(), after.scopes(), after.validUntil(), after.sha(), false,after.executionUntil());
    }

    private Pass inspect(Instant backupUntil) {
        Instant executionUntil=backupUntil;
        // Read every native scalar in every table before decoding any retained JSON.
        var p = rows(main, "SELECT package_id,package_revision,task_id,organization_id,data_scope,task_version,requester_issuer,requester_subject,manifest_sha,created_at,body_until,body_access_until FROM con6_human_package ORDER BY package_id,package_revision");
        var h = rows(main, "SELECT * FROM con6_human_handling ORDER BY package_id,package_revision");
        var e = rows(main, "SELECT * FROM con6_human_event ORDER BY package_id,package_revision,sequence_no");
        var r = rows(main, "SELECT * FROM con6_human_receipt ORDER BY package_id,package_revision,event_sequence");
        var s = rows(main, "SELECT " + SCOPE + " FROM con6_human_cleanup_scope ORDER BY task_id");
        var a = rows(authority, "SELECT " + SCOPE + " FROM id6_human_cleanup_scope ORDER BY task_id");
        var c = rows(main, "SELECT scope_id,generation,event_sha,occurred_at,audit_until FROM con6_human_cleanup_event ORDER BY scope_id,generation");
        for (var row : p) { deadline(row, "created_at", "body_until", backupUntil); deadline(row, "created_at", "body_access_until", backupUntil); if (time(row,"body_access_until").isAfter(time(row,"body_until"))) throw invalid(); }
        for (var row : h) past(time(row, "last_change_at"));
        for (var row : e) deadline(row, "occurred_at", "audit_until", backupUntil);
        for (var row : r) deadline(row, "created_at", "audit_until", backupUntil);
        for (var row : s) deadline(row, "created_at", "valid_until", backupUntil);
        for (var row : a) deadline(row, "created_at", "valid_until", backupUntil);
        for (var row : c) deadline(row, "occurred_at", "audit_until", backupUntil);
        if (!s.equals(a) || p.size()!=h.size() || p.size()!=e.size() || p.size()!=r.size() || s.size()!=c.size()) throw invalid();
        for (var row : p) {
            one(s.stream().filter(x -> Objects.equals(x.get("task_id"),row.get("task_id"))).toList());
            one(matching(p,row)); one(matching(h,row)); one(matching(e,row)); one(matching(r,row));
        }
        var evidence = new ArrayList<Object>();
        evidence.addAll(List.of(p,h,e,r,s,a,c));
        for (var scope : s) {
            var tasks = rows(main, "SELECT task_id,organization_id,expires_at,created_at,retention_ref,retention_version,retention_evidence_sha FROM con3_task WHERE task_id=?", scope.get("task_id"));
            var task = one(tasks);
            if (!Objects.equals(task.get("organization_id"),scope.get("organization_id"))) throw invalid();
            future(time(task,"expires_at"), backupUntil);
            past(time(task,"created_at"));
            if (!time(task,"expires_at").isAfter(time(task,"created_at"))
                    || time(task,"expires_at").isAfter(time(task,"created_at").plus(taskPolicy.maxAge()))
                    || !taskPolicy.reference().equals(task.get("retention_ref"))
                    || taskPolicy.version()!=number(task,"retention_version")
                    || !taskPolicy.evidenceSha256().equals(task.get("retention_evidence_sha"))) throw invalid();
            if (time(scope,"valid_until").isAfter(time(task,"expires_at"))
                    || time(scope,"created_at").isBefore(time(task,"created_at"))) throw invalid();
            one(c.stream().filter(x -> Objects.equals(x.get("scope_id"),scope.get("scope_id"))).toList());
            evidence.add(task);
        }
        // All seven-table and Task deadlines have now passed before current() parses event JSON.
        var expectedPolicy = new ProductHumanCleanupScope.Policy(policy.reference(),policy.version(),policy.evidenceSha256());
        var scopes = new LinkedHashMap<String, ProductHumanCleanupScope.Snapshot>();
        for (var row : s) {
            String task = string(row,"task_id");
            deadline(row,"created_at","valid_until",backupUntil);
            if (scopes.put(task, cleanup.current(task,expectedPolicy)) != null) throw invalid();
        }
        for (var row : p) {
            var handling = one(matching(h,row)); var event = one(matching(e,row)); var receipt = one(matching(r,row));
            if (number(handling,"processing_version")!=1 || !"WAITING".equals(handling.get("state"))
                    || number(event,"sequence_no")!=1 || number(event,"before_version")!=0 || number(event,"after_version")!=1
                    || !"REQUESTED".equals(event.get("operation")) || number(receipt,"event_sequence")!=1
                    || !"REQUEST".equals(receipt.get("command_code"))) throw invalid();
            var bodies = main.query("SELECT body_access_until,body_until,manifest_json FROM con6_human_package WHERE package_id=? AND package_revision=?", (rs,n) -> {
                var access=rs.getTimestamp(1); var body=rs.getTimestamp(2);
                if(access==null || body==null || !access.toInstant().equals(time(row,"body_access_until"))
                        || !body.toInstant().equals(time(row,"body_until"))) throw invalid();
                future(access.toInstant(),backupUntil); future(body.toInstant(),backupUntil);
                return json.stored(rs.getString(3),ProductHumanRequestStore.Manifest.class);
            },row.get("package_id"),row.get("package_revision"));
            if (bodies.size()!=1) throw invalid();
            var m = bodies.getFirst();
            if (m==null || !policy.equals(m.policy()) || !Objects.equals(scopes.get(m.taskId()),m.references().cleanup())
                    || m.requester().kind()!=cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling.ActorKind.HUMAN
                    || !m.packageId().equals(row.get("package_id")) || m.packageRevision()!=number(row,"package_revision")
                    || !m.taskId().equals(row.get("task_id")) || !m.organization().equals(row.get("organization_id"))
                    || !m.dataScope().equals(row.get("data_scope")) || m.taskVersion()!=number(row,"task_version")
                    || !m.requester().issuer().equals(row.get("requester_issuer")) || !m.requester().subject().equals(row.get("requester_subject"))
                    || !m.createdAt().equals(time(row,"created_at")) || !json.sha(m).equals(row.get("manifest_sha"))) throw invalid();
            var subjects = main.queryForList("SELECT subject_kind FROM id2_subject WHERE issuer=? AND subject_id=?",String.class,m.requester().issuer(),m.requester().subject());
            if (subjects.size()!=1 || !"HUMAN".equals(subjects.getFirst())) throw invalid();
            var key = ProductHumanRequestCommands.key(m.taskId(),m.requester(),string(receipt,"request_id"),ProductHumanRequestCommands.fromManifest(m),json);
            var retention = new OrdinaryHumanRetention(policy);
            retention.requireReadable(clock.instant(),m.createdAt(),null,m.bounds());
            retention.requireReceiptCompatibility(m.createdAt(),m.bounds());
            Instant audit = retention.auditUntil(m.createdAt(),m.bounds());
            if (!retention.bodyUntil(m.createdAt(),null,m.bounds()).equals(time(row,"body_until"))
                    || !ProductHumanRequestStore.bodyAccessUntil(m).equals(time(row,"body_access_until"))
                    || !m.createdAt().equals(time(handling,"last_change_at"))
                    || !m.createdAt().equals(time(event,"occurred_at")) || !m.createdAt().equals(time(receipt,"created_at"))
                    || !audit.equals(time(event,"audit_until")) || !audit.equals(time(receipt,"audit_until"))
                    || !m.taskId().equals(receipt.get("task_id"))) throw invalid();
            for (var nativeRow : List.of(event,receipt)) {
                if (!m.requester().issuer().equals(nativeRow.get("actor_issuer")) || !m.requester().subject().equals(nativeRow.get("actor_subject"))
                        || !key.requestSha().equals(nativeRow.get("request_sha"))) throw invalid();
            }
            if (!json.sha(m).equals(event.get("manifest_sha"))) throw invalid();
            var verifiedReferences = references.verify(m);
            future(verifiedReferences.referencedSaveUntil(),backupUntil);
            if(verifiedReferences.executionUntil().isBefore(executionUntil))executionUntil=verifiedReferences.executionUntil();
            var refs = verifiedReferences.bounds();
            var originalBounds = m.bounds();
            if (originalBounds.taskWriteUntil().isAfter(refs.taskWriteUntil())
                    || originalBounds.taskSaveUntil().isAfter(refs.taskSaveUntil())
                    || originalBounds.sourceUseUntil().isAfter(refs.sourceUseUntil())
                    || originalBounds.sourceSaveUntil().isAfter(refs.sourceSaveUntil())
                    || originalBounds.auditSaveUntil().isAfter(refs.auditSaveUntil())
                    || originalBounds.backupSaveUntil().isAfter(refs.backupSaveUntil())) throw invalid();
            Instant referenceUntil = java.util.stream.Stream.of(refs.taskWriteUntil(),refs.taskSaveUntil(),refs.sourceUseUntil(),
                    refs.sourceSaveUntil(),refs.auditSaveUntil(),refs.backupSaveUntil()).min(Instant::compareTo).orElseThrow();
            future(referenceUntil,backupUntil);
            Instant permitted = retention.backupUntil(clock.instant(),List.of(time(row,"body_until"),audit,referenceUntil),m.bounds());
            if (backupUntil.isAfter(permitted)) throw invalid();
            evidence.add(List.of(m,refs,verifiedReferences.closureSha(),verifiedReferences.referencedSaveUntil()));
        }
        for (var scope : scopes.values()) cleanup.assertUnchanged(scope);
        if (!clock.instant().isBefore(executionUntil)) throw invalid();
        return new Pass(p.size(),s.size(),backupUntil,json.sha(evidence),executionUntil);
    }

    private void deadline(Map<String,Object> row,String created,String until,Instant backup) {
        Instant at=time(row,created), end=time(row,until); past(at);
        if (!end.isAfter(at)) throw invalid(); future(end,backup);
    }
    private void past(Instant at) { if (at.isAfter(clock.instant())) throw invalid(); }
    private void future(Instant until,Instant backup) { if (!clock.instant().isBefore(until) || backup.isAfter(until)) throw invalid(); }
    private static List<Map<String,Object>> matching(List<Map<String,Object>> rows,Map<String,Object> p) {
        return rows.stream().filter(x -> Objects.equals(x.get("package_id"),p.get("package_id")) && number(x,"package_revision")==number(p,"package_revision")).toList();
    }
    private static Map<String,Object> one(List<Map<String,Object>> values) { if(values.size()!=1) throw invalid(); return values.getFirst(); }
    private static String string(Map<String,Object> row,String key) { if (!(row.get(key) instanceof String value) || value.isBlank()) throw invalid(); return value; }
    private static long number(Map<String,Object> row,String key) { if (!(row.get(key) instanceof Number value)) throw invalid(); return value.longValue(); }
    private static Instant time(Map<String,Object> row,String key) { if (!(row.get(key) instanceof Instant value)) throw invalid(); return value; }
    private static List<Map<String,Object>> rows(JdbcTemplate db,String sql,Object... args) {
        return db.query(sql,(rs,n)-> { Map<String,Object> row=new LinkedHashMap<>(); var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++) { Object v=rs.getObject(i); if(v instanceof java.sql.Timestamp t) v=t.toInstant();
                row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT),v); } return row; },args);
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Human WAITING source recovery closure invalid"); }
}
