package cn.xinji.agentplatform.security.product;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Pure native historical fact closure. No historical identity becomes current authorization. */
final class ProductHumanWaitingRecoveryFacts {
    record Verified(Instant taskUntil, String closureSha) { }
    private final JdbcTemplate db;
    private final Clock clock;
    private final ProductComparisonJson json;
    private final ProductRetentionPolicy taskPolicy, policy;
    ProductHumanWaitingRecoveryFacts(JdbcTemplate db, ProductComparisonJson json, Clock clock,
            ProductRetentionPolicy taskPolicy, ProductRetentionPolicy factsPolicy) {
        this.db=db; this.json=json; this.clock=clock; this.taskPolicy=taskPolicy; this.policy=factsPolicy;
    }
    Verified verify(ProductHumanRequestStore.Manifest m) {
        var fingerprint = new java.util.ArrayList<Object>();
        var expected=m.references().facts();
        var tasks=db.query("SELECT organization_id,version,created_at,expires_at,retention_ref,retention_version,retention_evidence_sha "
                + "FROM con3_task WHERE task_id=?", (rs,n)->{
            Instant created=rs.getTimestamp(3).toInstant(), until=rs.getTimestamp(4).toInstant();
            if (!until.isAfter(clock.instant()) || created.isAfter(m.createdAt()) || m.taskVersion()>rs.getLong(2)
                    || !m.organization().equals(rs.getString(1)) || !taskPolicy.reference().equals(rs.getString(5))
                    || taskPolicy.version()!=rs.getLong(6) || !taskPolicy.evidenceSha256().equals(rs.getString(7))
                    || !until.isAfter(created) || until.isAfter(created.plus(taskPolicy.maxAge()))) throw unavailable();
            fingerprint.add(List.of(rs.getString(1),rs.getLong(2),created,until,rs.getString(5),rs.getLong(6),rs.getString(7)));
            return until;
        },m.taskId());
        if(tasks.size()!=1)throw unavailable(); Instant taskUntil=tasks.getFirst();
        var nativeTask=new JdbcProductTaskDirectory(db,clock).find(m.taskId()).orElseThrow(ProductHumanWaitingRecoveryFacts::unavailable);
        fingerprint.add(nativeTask);
        if(!m.dataScope().equals(nativeTask.dataScope()) || !m.organization().equals(nativeTask.organization()))throw unavailable();
        long taskVersion=expected.taskVersion();
        if(!m.taskId().equals(expected.taskId())||m.taskVersion()!=taskVersion)throw unavailable();
        Object[] coordinates={m.taskId(),expected.goal().id(),expected.goal().revision(),expected.input().id()};
        var rows = db.query("SELECT g.goal_id,g.revision,i.task_version,g.organization_id,g.purpose,g.object_text,"
                + "g.scope_json,g.content_sha,g.confirmed_issuer,g.confirmed_subject,g.confirmed_at,g.expires_at,"
                + "g.retention_ref,g.retention_version,g.retention_evidence_sha,i.input_id,i.task_version,i.query_text,"
                + "i.source_scope_json,i.content_sha,i.confirmed_at,i.expires_at,m.message_id,m.actor_issuer,m.actor_subject,"
                + "m.raw_text,m.content_sha,m.received_at,m.expires_at,m.retention_ref,m.retention_version,m.retention_evidence_sha,"
                + "g.features_json,i.requested_fields_json,i.filters_json,r.request_id "
                + "FROM con3_goal_revision g JOIN con3_search_input i ON i.task_id=g.task_id "
                + "AND i.goal_id=g.goal_id AND i.goal_revision=g.revision "
                + "JOIN con3_message m ON m.task_id=g.task_id AND m.message_id=g.source_message_id "
                + "JOIN con3_command_receipt r ON r.task_id=g.task_id "
                + "AND r.operation='GOAL_CONFIRMED' AND r.result_id=g.goal_id AND r.result_version=i.task_version "
                + "AND r.actor_issuer=g.confirmed_issuer AND r.actor_subject=g.confirmed_subject "
                + "AND r.request_sha=g.content_sha AND r.created_at=g.confirmed_at "
                + "WHERE g.task_id=? AND g.status='CONFIRMED'"
                + " AND g.goal_id=? AND g.revision=? AND i.input_id=?", (rs, n) -> {
            Instant confirmed = rs.getTimestamp(11).toInstant();
            Instant goalUntil = rs.getTimestamp(12).toInstant();
            Instant inputUntil = rs.getTimestamp(22).toInstant();
            Instant messageAt = rs.getTimestamp(28).toInstant();
            Instant messageUntil = rs.getTimestamp(29).toInstant();
            Instant at = clock.instant();
            if (!goalUntil.isAfter(at) || !inputUntil.isAfter(at) || !messageUntil.isAfter(at)) throw unavailable();
            var sources = strings(rs.getString(7),true,true);
            var inputSources = strings(rs.getString(19),true,true);
            var features = strings(rs.getString(33),false,false);
            var fields = strings(rs.getString(34),true,true);
            String purpose=rs.getString(5), query=rs.getString(6), message=rs.getString(26);
            if (!List.of("QUERY","SEARCH","COMPARE").contains(purpose) || query==null || query.isBlank()
                    || message==null || message.isBlank() || "COMPARE".equals(purpose) && features.isEmpty()
                    || rs.getString(36)==null || !rs.getString(36).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}")) throw unavailable();
            var filtersNode=json.stored(rs.getString(35),com.fasterxml.jackson.databind.JsonNode.class);
            if (filtersNode==null || !filtersNode.isObject()
                    || !json.canonical(filtersNode).equals(json.canonical(filters(filtersNode).evidence()))) throw unavailable();
            var filters=filters(filtersNode);
            if (List.of("QUERY","SEARCH").contains(purpose)) {
                var scope=ProductPatentSearchFieldScope.from(fields,filters); scope.syntax(query); scope.expansion(query);
            } else {
                new cn.xinji.agentplatform.consultation.patenttask.source.PatentQuerySyntax().parse(query,new java.util.HashSet<>(fields));
                new cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion().expand(query);
            }
            if (rs.getLong(3) != taskVersion || rs.getLong(17) != taskVersion
                    || !m.organization().equals(rs.getString(4)) || !rs.getString(6).equals(rs.getString(18))
                    || !sources.equals(inputSources)
                    || !rs.getString(8).equals(rs.getString(20)) || !rs.getString(8).matches("[a-f0-9]{64}")
                    || !rs.getString(9).equals(rs.getString(24)) || !rs.getString(10).equals(rs.getString(25))
                    || !confirmed.equals(rs.getTimestamp(21).toInstant()) || messageAt.isAfter(confirmed)
                    || !ProductComparisonJson.hash(rs.getString(26)).equals(rs.getString(27))) throw unavailable();
            requirePolicy(rs.getString(13), rs.getLong(14), rs.getString(15), confirmed, goalUntil);
            requirePolicy(rs.getString(30), rs.getLong(31), rs.getString(32), messageAt, messageUntil);
            Instant expiry = List.of(taskUntil, goalUntil, inputUntil, messageUntil).stream().min(Instant::compareTo).orElseThrow();
            if (confirmed.isAfter(m.createdAt()) || confirmed.isAfter(clock.instant()) || !expiry.isAfter(clock.instant())
                    || !inputUntil.equals(goalUntil) || !messageUntil.equals(goalUntil) || !messageAt.equals(confirmed)) throw unavailable();
            // The original Goal command's filter spelling/order was normalized before storage.
            // Its request SHA is cross-linked above, not falsely reconstructed. Fingerprint every
            // native selected column; same-fence typed inventory proves restored byte equality.
            var nativeColumns=new java.util.ArrayList<String>();
            for(int column=1;column<=rs.getMetaData().getColumnCount();column++) nativeColumns.add(rs.getString(column));
            fingerprint.add(nativeColumns);
            return new ProductHumanTaskFacts.Snapshot(m.taskId(), taskVersion, rs.getString(5),
                    new ProductHumanTaskFacts.Reference("GOAL", rs.getString(1), rs.getLong(2), rs.getString(8)),
                    new ProductHumanTaskFacts.Reference("USER_MESSAGE", rs.getString(23), 1, rs.getString(27)),
                    new ProductHumanTaskFacts.Reference("SEARCH_INPUT", rs.getString(16), 1, rs.getString(20)), policy, confirmed, expiry);
        }, coordinates);
        if (rows.size() != 1) throw unavailable();
        if (!rows.getFirst().equals(expected)) throw unavailable();
        if(!clock.instant().isBefore(expected.expiresAt()))throw unavailable();
        return new Verified(taskUntil,json.sha(fingerprint));
    }

    private List<String> strings(String raw,boolean nonempty,boolean unique) {
        var value=json.stored(raw,String[].class);
        if(value==null)throw unavailable();
        var list=java.util.Arrays.asList(value);
        if(nonempty && list.isEmpty() || list.stream().anyMatch(v->v==null||v.isBlank())
                || unique && list.stream().distinct().count()!=list.size())throw unavailable();
        return List.copyOf(list);
    }
    private ProductPatentSearchFilters filters(com.fasterxml.jackson.databind.JsonNode node) {
        var from=node.get("publicationFrom"); var to=node.get("publicationTo");
        if(from==null||to==null||!from.isTextual()||!to.isTextual())throw unavailable();
        return ProductPatentSearchFilters.validated(from.textValue().isEmpty()?null:java.time.LocalDate.parse(from.textValue()),
                to.textValue().isEmpty()?null:java.time.LocalDate.parse(to.textValue()),
                strings(json.canonical(node.get("ipcPrefixes")),false,true),
                strings(json.canonical(node.get("cpcPrefixes")),false,true));
    }

    private void requirePolicy(String ref,long version,String sha,Instant from,Instant until) {
        if(!policy.reference().equals(ref)||policy.version()!=version||!policy.evidenceSha256().equals(sha)
                ||!until.isAfter(from)||until.isAfter(from.plus(policy.maxAge())))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Recovered human historical facts unavailable");}
}
