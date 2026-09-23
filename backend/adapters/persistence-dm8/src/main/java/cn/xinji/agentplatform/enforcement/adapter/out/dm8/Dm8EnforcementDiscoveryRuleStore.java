package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceStoreSupport.*;
import static cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class Dm8EnforcementDiscoveryRuleStore implements Store {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Dm8EnforcementDiscoveryRuleStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override public List<RuleVersion> list(String organization, RuleFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE v.organization_ref=?");
        List<Object> values = new ArrayList<>(); values.add(organization);
        if (present(filter.status())) { where.append(" AND v.status=?"); values.add(filter.status()); }
        if (present(filter.category())) { where.append(" AND v.category=?"); values.add(filter.category()); }
        if (present(filter.name())) { where.append(" AND v.rule_name LIKE ?"); values.add("%" + filter.name().trim() + "%"); }
        if (present(filter.sourceId())) { where.append(" AND EXISTS (SELECT 1 FROM enf_discovery_rule_source s WHERE s.rule_id=v.rule_id AND s.rule_version=v.rule_version AND s.organization_ref=v.organization_ref AND s.source_id=?)"); values.add(filter.sourceId()); }
        where.append(" ORDER BY v.updated_at DESC");
        return query(where.toString(), values.toArray());
    }

    @Override public Optional<RuleVersion> find(String organization, String ruleId, Integer version) {
        String suffix = version == null
                ? " WHERE v.organization_ref=? AND v.rule_id=? ORDER BY v.rule_version DESC"
                : " WHERE v.organization_ref=? AND v.rule_id=? AND v.rule_version=?";
        List<RuleVersion> rows = version == null ? query(suffix, organization, ruleId)
                : query(suffix, organization, ruleId, version);
        return rows.stream().findFirst();
    }

    @Override public RuleVersion create(RuleVersion version, String actor, Instant now) {
        return transaction(connection -> {
            execute(connection, "INSERT INTO enf_discovery_rule (rule_id,organization_ref,next_version,created_by,created_at) VALUES (?,?,?,?,?)",
                    version.ruleId(), version.organizationRef(), 2, actor, time(now));
            insertVersion(connection, version);
            lifecycle(connection, version, null, "DRAFT", actor, now);
            audit(connection, version, "CREATED", actor, now, Map.of("revision", 1));
            return version;
        }, "RULE_ALREADY_EXISTS");
    }

    @Override public RuleVersion update(RuleVersion version, int expectedRevision, String actor, Instant now) {
        return transaction(connection -> {
            int changed = execute(connection, "UPDATE enf_discovery_rule_version SET revision_no=?,rule_name=?,category=?,rule_description=?,condition_json=?,content_sha256=?,updated_by=?,updated_at=? WHERE rule_id=? AND rule_version=? AND organization_ref=? AND status='DRAFT' AND revision_no=?",
                    version.revision(), version.ruleName(), version.category(), version.description(), json(version.condition()),
                    version.contentDigest(), actor, time(now), version.ruleId(), version.ruleVersion(),
                    version.organizationRef(), expectedRevision);
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedRevision");
            replaceSources(connection, version);
            audit(connection, version, "UPDATED", actor, now, Map.of("revision", version.revision()));
            return read(connection, version.organizationRef(), version.ruleId(), version.ruleVersion()).orElseThrow();
        }, null);
    }

    @Override public int nextVersion(String organization, String ruleId) {
        return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT next_version FROM enf_discovery_rule WHERE organization_ref=? AND rule_id=? FOR UPDATE")) {
                bind(statement, organization, ruleId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw failure("RULE_NOT_FOUND", "ruleId");
                    return result.getInt(1);
                }
            }
        }, null);
    }

    @Override public RuleVersion createVersion(RuleVersion version, String actor, Instant now, int copiedFromVersion) {
        return transaction(connection -> {
            int changed = execute(connection, "UPDATE enf_discovery_rule SET next_version=next_version+1 WHERE rule_id=? AND organization_ref=? AND next_version=?",
                    version.ruleId(), version.organizationRef(), version.ruleVersion());
            if (changed != 1) throw failure("VERSION_CONFLICT", "ruleVersion");
            insertVersion(connection, version);
            lifecycle(connection, version, null, "DRAFT", actor, now);
            audit(connection, version, "COPIED", actor, now, Map.of("copiedFromVersion", copiedFromVersion));
            return version;
        }, null);
    }

    @Override public RuleVersion transition(String organization, String ruleId, int version, int expectedRevision,
                                            String from, String to, String actor, Instant now) {
        return transaction(connection -> {
            int changed = execute(connection, "UPDATE enf_discovery_rule_version SET status=?,revision_no=revision_no+1,updated_by=?,updated_at=?,published_by=CASE WHEN ?='PUBLISHED' THEN ? ELSE published_by END,published_at=CASE WHEN ?='PUBLISHED' THEN ? ELSE published_at END WHERE rule_id=? AND rule_version=? AND organization_ref=? AND status=? AND revision_no=?",
                    to, actor, time(now), to, actor, to, time(now), ruleId, version, organization, from, expectedRevision);
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedRevision");
            RuleVersion result = read(connection, organization, ruleId, version).orElseThrow();
            lifecycle(connection, result, from, to, actor, now);
            audit(connection, result, to, actor, now, Map.of("from", from, "to", to));
            return result;
        }, null);
    }

    @Override public Optional<SourceState> source(String organization, String sourceId) {
        String sql = "SELECT source_id,enabled,health_status FROM enf_source_configuration WHERE organization_ref=? AND source_id=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, sourceId); try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new SourceState(result.getString(1), result.getInt(2) == 1, result.getString(3))) : Optional.empty();
            }
        } catch (Exception problem) { throw unavailable(problem); }
    }

    @Override public DryRun saveRun(DryRun run, Map<String, Object> payload, String actor, Instant now) {
        return transaction(connection -> {
            execute(connection, "INSERT INTO enf_discovery_rule_dry_run (run_id,rule_id,rule_version,rule_revision,organization_ref,content_sha256,sample_id,sample_version,sample_origin,input_snapshot_json,input_sha256,result_status,matched,unhandled_reason,executed_by,executed_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.runId(), run.ruleId(), run.ruleVersion(), run.ruleRevision(), run.organizationRef(),
                    run.contentDigest(), run.sampleId(), run.sampleVersion(), run.sampleOrigin(), json(payload),
                    run.inputDigest(), run.resultStatus(), run.matched() ? 1 : 0, run.unhandledReason(), actor,
                    time(now), run.requestId());
            int sequence = 0;
            for (ConditionResult result : run.conditionResults()) {
                execute(connection, "INSERT INTO enf_discovery_rule_condition_result (result_id,run_id,sequence_no,node_id,operator_name,field_path,matched,reason_code,actual_value_json) VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), run.runId(), ++sequence, result.nodeId(), result.operator(),
                        result.fieldPath(), result.matched() ? 1 : 0, result.reasonCode(), jsonNullable(result.actualValue()));
            }
            audit(connection, read(connection, run.organizationRef(), run.ruleId(), run.ruleVersion()).orElseThrow(),
                    "DRY_RUN", actor, now, Map.of("runId", run.runId(), "resultStatus", run.resultStatus()));
            return run;
        }, null);
    }

    @Override public boolean hasSuccessfulRun(String organization, String ruleId, int version, int revision, String digest) {
        String sql = "SELECT 1 FROM enf_discovery_rule_dry_run WHERE organization_ref=? AND rule_id=? AND rule_version=? AND rule_revision=? AND content_sha256=? AND result_status='SUCCEEDED'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, ruleId, version, revision, digest);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        } catch (Exception problem) { throw unavailable(problem); }
    }

    @Override public List<RuleVersion> versions(String organization, String ruleId) {
        return query(" WHERE v.organization_ref=? AND v.rule_id=? ORDER BY v.rule_version DESC", organization, ruleId);
    }

    @Override public List<DryRun> runs(String organization, String ruleId, int version) {
        String sql = "SELECT run_id,rule_id,organization_ref,rule_version,rule_revision,content_sha256,sample_id,sample_version,sample_origin,input_sha256,result_status,matched,unhandled_reason,executed_by,executed_at,request_id FROM enf_discovery_rule_dry_run WHERE organization_ref=? AND rule_id=? AND rule_version=? ORDER BY executed_at DESC";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, ruleId, version); try (ResultSet result = statement.executeQuery()) {
                List<DryRun> rows = new ArrayList<>();
                while (result.next()) rows.add(mapRun(connection, result));
                return List.copyOf(rows);
            }
        } catch (Exception problem) { throw unavailable(problem); }
    }

    private List<RuleVersion> query(String suffix, Object... values) {
        try (Connection connection = dataSource.getConnection()) { return query(connection, suffix, values); }
        catch (Exception problem) { throw unavailable(problem); }
    }
    private List<RuleVersion> query(Connection connection, String suffix, Object... values) throws Exception {
        String sql = "SELECT v.rule_id,v.organization_ref,v.rule_version,v.revision_no,v.status,v.rule_name,v.category,v.rule_description,v.engine_type,v.usage_scope,v.condition_json,v.content_sha256,v.created_by,v.created_at,v.updated_by,v.updated_at,v.published_by,v.published_at FROM enf_discovery_rule_version v" + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values); try (ResultSet result = statement.executeQuery()) {
                List<RuleVersion> rows = new ArrayList<>(); while (result.next()) rows.add(mapRule(connection, result));
                return List.copyOf(rows);
            }
        }
    }
    private Optional<RuleVersion> read(Connection connection, String organization, String ruleId, int version) throws Exception {
        return query(connection, " WHERE v.organization_ref=? AND v.rule_id=? AND v.rule_version=?", organization, ruleId, version).stream().findFirst();
    }
    private RuleVersion mapRule(Connection connection, ResultSet result) throws Exception {
        String ruleId = result.getString(1); int version = result.getInt(3); String organization = result.getString(2);
        return new RuleVersion(ruleId, organization, version, result.getInt(4), result.getString(5), result.getString(6),
                result.getString(7), result.getString(8), result.getString(9), result.getString(10),
                sources(connection, organization, ruleId, version), mapper.readValue(result.getString(11), Condition.class),
                result.getString(12), result.getString(13), instant(result.getTimestamp(14)), result.getString(15),
                instant(result.getTimestamp(16)), result.getString(17), instant(result.getTimestamp(18)));
    }
    private List<String> sources(Connection connection, String organization, String ruleId, int version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT source_id FROM enf_discovery_rule_source WHERE organization_ref=? AND rule_id=? AND rule_version=? ORDER BY source_id")) {
            bind(statement, organization, ruleId, version); try (ResultSet result = statement.executeQuery()) {
                List<String> rows = new ArrayList<>(); while (result.next()) rows.add(result.getString(1)); return List.copyOf(rows);
            }
        }
    }
    private DryRun mapRun(Connection connection, ResultSet result) throws Exception {
        String runId = result.getString(1);
        return new DryRun(runId, result.getString(2), result.getString(3), result.getInt(4), result.getInt(5),
                result.getString(6), result.getString(7), result.getString(8), result.getString(9), result.getString(10),
                result.getString(11), result.getInt(12) == 1, result.getString(13), result.getString(14),
                instant(result.getTimestamp(15)), result.getString(16), results(connection, runId));
    }
    private List<ConditionResult> results(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT node_id,operator_name,field_path,matched,reason_code,actual_value_json FROM enf_discovery_rule_condition_result WHERE run_id=? ORDER BY sequence_no")) {
            bind(statement, runId); try (ResultSet result = statement.executeQuery()) {
                List<ConditionResult> rows = new ArrayList<>();
                while (result.next()) rows.add(new ConditionResult(result.getString(1), result.getString(2), result.getString(3),
                        result.getInt(4) == 1, result.getString(5), result.getString(6) == null ? null : mapper.readValue(result.getString(6), Object.class)));
                return List.copyOf(rows);
            }
        }
    }
    private void insertVersion(Connection connection, RuleVersion value) throws Exception {
        execute(connection, "INSERT INTO enf_discovery_rule_version (rule_id,organization_ref,rule_version,revision_no,status,rule_name,category,rule_description,engine_type,usage_scope,condition_json,content_sha256,created_by,created_at,updated_by,updated_at,published_by,published_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.ruleId(), value.organizationRef(), value.ruleVersion(), value.revision(), value.status(), value.ruleName(),
                value.category(), value.description(), value.engineType(), value.usageScope(), json(value.condition()),
                value.contentDigest(), value.createdBy(), time(value.createdAt()), value.updatedBy(), time(value.updatedAt()), null, null);
        replaceSources(connection, value);
    }
    private void replaceSources(Connection connection, RuleVersion value) throws Exception {
        execute(connection, "DELETE FROM enf_discovery_rule_source WHERE rule_id=? AND rule_version=? AND organization_ref=?",
                value.ruleId(), value.ruleVersion(), value.organizationRef());
        for (String source : value.sourceIds()) execute(connection, "INSERT INTO enf_discovery_rule_source (rule_id,rule_version,source_id,organization_ref) VALUES (?,?,?,?)",
                value.ruleId(), value.ruleVersion(), source, value.organizationRef());
        execute(connection, "DELETE FROM enf_discovery_rule_condition WHERE rule_id=? AND rule_version=? AND organization_ref=?",
                value.ruleId(), value.ruleVersion(), value.organizationRef());
        insertCondition(connection, value, value.condition(), null, 1, 1);
    }
    private void insertCondition(Connection connection, RuleVersion rule, Condition condition,
                                 String parent, int sequence, int depth) throws Exception {
        execute(connection, "INSERT INTO enf_discovery_rule_condition (rule_id,rule_version,organization_ref,node_id,parent_node_id,sequence_no,depth_no,operator_name,field_path,comparison_value_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
                rule.ruleId(), rule.ruleVersion(), rule.organizationRef(), condition.nodeId(), parent, sequence, depth,
                condition.operator(), condition.fieldPath(), jsonNullable(condition.value()));
        for (int index = 0; index < condition.children().size(); index++) {
            insertCondition(connection, rule, condition.children().get(index), condition.nodeId(), index + 1, depth + 1);
        }
    }
    private void lifecycle(Connection connection, RuleVersion value, String from, String to, String actor, Instant now) throws Exception {
        execute(connection, "INSERT INTO enf_discovery_rule_lifecycle (event_id,rule_id,rule_version,organization_ref,from_status,to_status,actor_ref,occurred_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), value.ruleId(), value.ruleVersion(), value.organizationRef(), from, to, actor, time(now));
    }
    private void audit(Connection connection, RuleVersion value, String action, String actor, Instant now, Object details) throws Exception {
        execute(connection, "INSERT INTO enf_discovery_rule_audit (audit_id,rule_id,rule_version,organization_ref,action_type,actor_ref,occurred_at,detail_json) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), value.ruleId(), value.ruleVersion(), value.organizationRef(), action, actor, time(now), json(details));
    }
    private <T> T transaction(SqlWork<T> work, String constraintCode) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false); try { T value = work.run(connection); connection.commit(); return value; }
            catch (Exception problem) { connection.rollback(); if (constraintCode != null && isConstraint(problem)) throw failure(constraintCode, "ruleId"); throw problem; }
        } catch (RuleException problem) { throw problem; }
        catch (Exception problem) { throw unavailable(problem); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception problem) { throw unavailable(problem); } }
    private String jsonNullable(Object value) { return value == null ? null : json(value); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static RuntimeException unavailable(Exception problem) { return failure("RULE_STORE_UNAVAILABLE", null); }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws Exception; }
}
