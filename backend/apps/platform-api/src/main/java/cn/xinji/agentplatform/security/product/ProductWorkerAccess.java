package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the certificate, two current grants, human delegation and source before a worker acts. */
final class ProductWorkerAccess {
    record Authorized(String serviceSubject, String taskId, String inputId, long taskVersion) { }
    private record Facts(String service, String human, String organization, String scope,
                         long resourceVersion, Instant expiresAt) { }
    private record Input(String sourceJson, String fieldsJson, long taskVersion) { }

    private final JdbcTemplate db;
    private final ProductGrantWatermark grants;
    private final ProductSourceAvailability sources;
    private final ProductSourceWatermark sourceWatermark;
    private final ProductTaskDirectory directory;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String issuer;
    private final ProductWorkerSourceGrant sourceGrants;

    ProductWorkerAccess(JdbcTemplate db, ProductGrantWatermark grants,
            ProductSourceAvailability sources, ProductSourceWatermark sourceWatermark,
            ProductTaskDirectory directory, ObjectMapper mapper, Clock clock, String issuer) {
        this.db = db;
        this.grants = grants;
        this.sources = sources;
        this.sourceWatermark = sourceWatermark;
        this.directory = directory;
        this.mapper = mapper;
        this.clock = clock;
        this.issuer = issuer;
        this.sourceGrants = new ProductWorkerSourceGrant(db, issuer);
    }

    Authorized require(HttpServletRequest request, String taskId, String inputId, String delegationId) {
        String fingerprint = fingerprint(request);
        if (fingerprint == null || blank(taskId) || blank(inputId) || blank(delegationId))
            throw new IllegalStateException("Authenticated worker and exact execution required");
        long generation = grants.currentGeneration();
        var task = directory.find(taskId).orElseThrow(() -> new IllegalStateException("Task missing"));
        Instant now = clock.instant();
        if (!"IN_PROGRESS".equals(task.state()) || !task.expiresAt().isAfter(now))
            throw new IllegalStateException("Task is not executable");
        List<Facts> rows = db.query("SELECT d.service_subject,d.human_subject,d.organization_id,d.data_scope,"
                        + "d.authorization_version,d.expires_at FROM con3_worker_delegation d "
                        + "JOIN id2_workload w ON w.issuer=d.service_issuer AND w.subject_id=d.service_subject "
                        + "JOIN id2_subject s ON s.issuer=w.issuer AND s.subject_id=w.subject_id "
                        + "JOIN id2_subject h ON h.issuer=d.human_issuer AND h.subject_id=d.human_subject "
                        + "JOIN id2_grant sg ON sg.grant_id=d.service_grant_id "
                        + "JOIN id2_current_epoch sc ON sc.current_grant_id=sg.grant_id "
                        + "AND sc.authorization_epoch=sg.grant_generation "
                        + "JOIN id2_grant hg ON hg.grant_id=d.human_grant_id "
                        + "JOIN id2_current_epoch hc ON hc.current_grant_id=hg.grant_id "
                        + "AND hc.authorization_epoch=hg.grant_generation "
                        + "WHERE d.delegation_id=? AND d.task_id=? AND d.human_issuer=? "
                        + "AND d.service_issuer=? AND d.status='ACTIVE' AND d.expires_at>? "
                        + "AND w.certificate_sha=? AND w.organization=? AND w.status='ACTIVE' "
                        + "AND w.workload_kind='AGENT' AND w.expires_at>? AND s.status='ACTIVE' "
                        + "AND s.subject_kind='SERVICE' AND s.password_hash IS NULL "
                        + "AND h.status='ACTIVE' AND h.password_hash IS NOT NULL "
                        + "AND sg.status='ACTIVE' AND hg.status='ACTIVE' "
                        + "AND sg.expires_at>? AND hg.expires_at>? "
                        + "AND sg.issuer=d.service_issuer AND sg.subject_id=d.service_subject "
                        + "AND hg.issuer=d.human_issuer AND hg.subject_id=d.human_subject "
                        + "AND sg.organization=d.organization_id AND hg.organization=d.organization_id "
                        + "AND sg.task_scope=d.task_id AND hg.task_scope=d.task_id "
                        + "AND sg.data_scope=d.data_scope AND hg.data_scope=d.data_scope "
                        + "AND sg.resource_type='TASK' AND hg.resource_type='TASK' "
                        + "AND sg.resource_id=d.task_id AND hg.resource_id=d.task_id "
                        + "AND sg.resource_version=d.authorization_version "
                        + "AND hg.resource_version=d.authorization_version "
                        + "AND sg.action_code='WRITE' AND hg.action_code='WRITE' "
                        + "AND sg.purpose='TASK_WORK' AND hg.purpose='TASK_WORK'",
                (rs, row) -> new Facts(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant()),
                delegationId, taskId, issuer, issuer, Timestamp.from(now), fingerprint,
                task.organization(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows.size() != 1) throw new IllegalStateException("Worker authorization is not current");
        Facts facts = rows.getFirst();
        if (!task.organization().equals(facts.organization()) || !task.dataScope().equals(facts.scope())
                || task.authorizationVersion() != facts.resourceVersion())
            throw new IllegalStateException("Worker task scope changed");
        var service = new ProductAccessPolicy.Actor(issuer, facts.service(),
                ProductAccessPolicy.Kind.SERVICE, facts.organization());
        var human = new ProductAccessPolicy.Actor(issuer, facts.human(),
                ProductAccessPolicy.Kind.HUMAN, facts.organization());
        var resource = new ProductAccessPolicy.Resource("CONSULTATION", "TASK", taskId,
                facts.organization(), facts.resourceVersion());
        var action = ProductAccessPolicy.Action.WRITE;
        var purpose = ProductAccessPolicy.Purpose.TASK_WORK;
        // The adapter has checked each grant's own epoch; the independent generation binds this snapshot.
        var evidence = new ProductAccessPolicy.Evidence(
                new ProductAccessPolicy.CurrentState(generation, 0, true),
                List.of(new ProductAccessPolicy.Grant(service, resource, action, purpose,
                                facts.expiresAt(), generation),
                        new ProductAccessPolicy.Grant(human, resource, action, purpose,
                                facts.expiresAt(), generation)),
                new ProductAccessPolicy.Delegation(human, service, resource, action, purpose,
                        facts.expiresAt(), generation), List.of());
        if (!ProductAccessPolicy.permits(new ProductAccessPolicy.Request(service, resource,
                action, purpose, null, now), evidence))
            throw new IllegalStateException("Worker policy denied");
        List<Input> inputs = db.query("SELECT i.source_scope_json,i.requested_fields_json,i.task_version "
                        + "FROM con3_search_input i JOIN con3_goal_current c ON c.task_id=i.task_id "
                        + "AND c.goal_id=i.goal_id AND c.revision=i.goal_revision "
                        + "AND c.task_version=i.task_version WHERE i.task_id=? AND i.input_id=? "
                        + "AND i.expires_at>?",
                (rs, row) -> new Input(rs.getString(1), rs.getString(2), rs.getLong(3)),
                taskId, inputId, Timestamp.from(now));
        if (inputs.size() != 1 || inputs.getFirst().taskVersion() != task.version())
            throw new IllegalStateException("Confirmed input changed");
        for (String sourceId : parse(inputs.getFirst().sourceJson())) {
            var current = sourceWatermark.current(sourceId);
            if (!"ACTIVE".equals(current.status())) throw new IllegalStateException("Source is withdrawn");
            Integer scoped = db.queryForObject("SELECT COUNT(*) FROM con4_task_source_scope s "
                            + "JOIN con4_task_source_current sc ON sc.scope_id=s.scope_id "
                            + "AND sc.scope_version=s.scope_version AND sc.task_id=s.task_id "
                            + "AND sc.source_id=s.source_id WHERE s.task_id=? AND s.organization_id=? "
                            + "AND s.source_id=? AND s.status='ACTIVE' AND sc.status='ACTIVE' "
                    + "AND s.task_version=? AND s.source_epoch=? AND s.source_row_version=? "
                            + "AND s.contract_version=? AND s.decision_id=? AND s.expires_at>?",
                    Integer.class, taskId, task.organization(), sourceId, task.version(),
                    current.epoch(), current.rowVersion(), current.contractVersion(),
                    current.decisionId(), Timestamp.from(now));
            if (scoped != 1) throw new IllegalStateException("Task source scope is not current");
            sourceGrants.require(facts.human(), task.organization(), taskId, sourceId,
                    task.version(), current.epoch(), current.rowVersion(), now);
            for (String field : parse(inputs.getFirst().fieldsJson())) sources.require(sourceId, field, "QUERY");
            sourceWatermark.assertUnchanged(sourceId, current.epoch());
        }
        grants.assertUnchanged(generation);
        return new Authorized(facts.service(), taskId, inputId, task.version());
    }

    private List<String> parse(String json) {
        try {
            List<String> result = mapper.readValue(json, new TypeReference<List<String>>() { });
            if (result == null || result.isEmpty() || result.stream().anyMatch(ProductWorkerAccess::blank))
                throw new IllegalStateException("Exact source scope required");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Stored source scope invalid", invalid);
        }
    }

    private static String fingerprint(HttpServletRequest request) {
        if (request == null || !request.isSecure()) return null;
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) return null;
        try {
            chain[0].checkValidity();
            List<String> usage = chain[0].getExtendedKeyUsage();
            if (usage == null || !usage.contains("1.3.6.1.5.5.7.3.2")) return null;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].getEncoded()));
        } catch (Exception invalid) { return null; }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
