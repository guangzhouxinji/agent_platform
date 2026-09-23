package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceStoreSupport.*;
import static cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.Condition;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.ConditionResult;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.RuleVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

public final class Dm8EnforcementClueDiscoveryStore implements Store {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    public Dm8EnforcementClueDiscoveryStore(DataSource dataSource, ObjectMapper mapper) { this.dataSource = dataSource; this.mapper = mapper; }

    @Override public DiscoveryTask begin(String organization, String actor, String key, String digest,
                                         String requestId, DiscoveryCommand command, Instant now) {
        return tx(connection -> {
            Optional<DiscoveryTask> replay = taskByKey(connection, organization, actor, key);
            if (replay.isPresent()) {
                if (!replay.get().inputDigest().equals(digest)) throw failure("IDEMPOTENCY_CONFLICT", "idempotencyKey");
                return replay.get();
            }
            String taskId = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO enf_clue_discovery_task (task_id,organization_ref,requester_ref,idempotency_key,input_sha256,status,processed_count,matched_count,lead_count,failed_count,request_id,created_at,started_at,completed_at) VALUES (?,?,?,?,?,'running',0,0,0,0,?,?,?,NULL)",
                    taskId, organization, actor, key, digest, requestId, time(now), time(now));
            int sequence = 0;
            int snapshotSequence = 0;
            for (String syncId : command.syncTaskIds()) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT source_id,configuration_version,status FROM enf_source_sync_task WHERE task_id=? AND organization_ref=?")) {
                    bind(statement, syncId, organization); try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw failure("SOURCE_SYNC_TASK_NOT_FOUND", "syncTaskIds");
                        if (!List.of("succeeded", "partial_failed").contains(result.getString(3))) throw failure("SOURCE_SYNC_TASK_NOT_READY", "syncTaskIds");
                        execute(connection, "INSERT INTO enf_clue_discovery_input_sync (task_id,sequence_no,source_sync_task_id,source_id,source_configuration_version) VALUES (?,?,?,?,?)",
                                taskId, ++sequence, syncId, result.getString(1), result.getInt(2));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_id,content_sha256 FROM enf_source_record_snapshot WHERE task_id=? AND organization_ref=? ORDER BY fetched_at,snapshot_id")) {
                    bind(statement, syncId, organization); try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) execute(connection, "INSERT INTO enf_clue_discovery_input_snapshot (task_id,sequence_no,snapshot_id,content_sha256) VALUES (?,?,?,?)",
                                taskId, ++snapshotSequence, result.getString(1), result.getString(2));
                    }
                }
            }
            sequence = 0;
            for (RuleRef ref : command.ruleVersions()) {
                RuleVersion rule = readRule(connection, organization, ref.ruleId(), ref.ruleVersion())
                        .orElseThrow(() -> failure("RULE_NOT_FOUND", "ruleVersions"));
                if (!"PUBLISHED".equals(rule.status())) throw failure("RULE_NOT_PUBLISHED", "ruleVersions");
                execute(connection, "INSERT INTO enf_clue_discovery_input_rule (task_id,sequence_no,rule_id,rule_version,rule_revision,content_sha256) VALUES (?,?,?,?,?,?)",
                        taskId, ++sequence, rule.ruleId(), rule.ruleVersion(), rule.revision(), rule.contentDigest());
            }
            audit(connection, organization, "TASK", taskId, "STARTED", actor, requestId, now, Map.of("inputSha256", digest));
            return new DiscoveryTask(taskId, organization, actor, "queued", 0, 0, 0, 0, digest,
                    requestId, now, now, null, List.of(), List.of());
        });
    }

    @Override public PreparedInput prepare(String organization, String taskId, DiscoveryCommand command) {
        try (Connection connection = dataSource.getConnection()) {
            List<SourceSnapshot> snapshots = new ArrayList<>();
            List<TaskItem> upstream = new ArrayList<>();
            int sequence = 0;
            try (PreparedStatement statement = connection.prepareStatement("SELECT s.snapshot_id,s.source_id,s.source_record_id,s.source_version,s.content_sha256,s.withdrawal_status,s.payload_json,i.content_sha256 FROM enf_clue_discovery_input_snapshot i JOIN enf_clue_discovery_task t ON t.task_id=i.task_id JOIN enf_source_record_snapshot s ON s.snapshot_id=i.snapshot_id WHERE i.task_id=? AND t.organization_ref=? ORDER BY i.sequence_no")) {
                bind(statement, taskId, organization); try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (!result.getString(5).equals(result.getString(8))) throw failure("FROZEN_INPUT_CHANGED", "snapshot");
                        snapshots.add(new SourceSnapshot(result.getString(1), result.getString(2),
                                result.getString(3), result.getString(4), result.getString(5), result.getString(6),
                                mapper.readValue(result.getString(7), new TypeReference<Map<String, Object>>() {})));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT f.source_record_ref,f.error_code FROM enf_clue_discovery_input_sync i JOIN enf_clue_discovery_task d ON d.task_id=i.task_id JOIN enf_source_sync_failure f ON f.task_id=i.source_sync_task_id WHERE i.task_id=? AND d.organization_ref=? ORDER BY i.sequence_no,f.sequence_no")) {
                bind(statement, taskId, organization); try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) upstream.add(new TaskItem(++sequence, null, null, result.getString(1),
                            null, null, null, "FAILED", "UPSTREAM_" + result.getString(2), true, null));
                }
            }
            List<RuleVersion> rules = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT i.rule_id,i.rule_version,i.rule_revision,i.content_sha256 FROM enf_clue_discovery_input_rule i JOIN enf_clue_discovery_task t ON t.task_id=i.task_id WHERE i.task_id=? AND t.organization_ref=? ORDER BY i.sequence_no")) {
                bind(statement, taskId, organization); try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        RuleVersion rule = readRule(connection, organization, result.getString(1), result.getInt(2))
                                .orElseThrow(() -> failure("FROZEN_INPUT_CHANGED", "ruleVersions"));
                        if (rule.revision() != result.getInt(3) || !rule.contentDigest().equals(result.getString(4))) {
                            throw failure("FROZEN_INPUT_CHANGED", "ruleVersions");
                        }
                        rules.add(rule);
                    }
                }
            }
            return new PreparedInput(snapshots, rules, upstream);
        } catch (DiscoveryException problem) { throw problem; }
        catch (Exception problem) { throw unavailable(); }
    }

    @Override public Lead upsertLead(String organization, String actor, String requestId, SourceSnapshot snapshot,
                                     String excerpt, List<RuleMatch> matches, Instant now) {
        return tx(connection -> {
            Lead lead = findLeadBySource(connection, organization, snapshot.sourceId(), snapshot.sourceRecordId(), snapshot.sourceVersion()).orElse(null);
            if (lead == null) {
                String leadId = UUID.randomUUID().toString();
                execute(connection, "INSERT INTO enf_discovered_lead (lead_id,organization_ref,source_id,source_record_id,source_version,snapshot_id,snapshot_sha256,source_availability,source_excerpt,suspected_types_json,processing_status,review_required,usage_scope,conclusion_scope,first_found_at,version_no,created_by,created_at,updated_by,updated_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING_VERIFICATION',0,'DEVELOPMENT_ONLY','NOT_AN_INFRINGEMENT_FINDING',?,1,?,?,?,?,?)",
                        leadId, organization, snapshot.sourceId(), snapshot.sourceRecordId(), snapshot.sourceVersion(),
                        snapshot.snapshotId(), snapshot.contentDigest(), "AVAILABLE", excerpt, "[]", time(now),
                        actor, time(now), actor, time(now), requestId);
                lead = readLead(connection, organization, leadId).orElseThrow();
                history(connection, lead, null, "PENDING_VERIFICATION", null, actor, requestId, now);
                audit(connection, organization, "LEAD", leadId, "DISCOVERED", actor, requestId, now,
                        Map.of("snapshotId", snapshot.snapshotId()));
            }
            for (RuleMatch match : matches) insertMatch(connection, lead.leadId(), match, now);
            List<String> types = leadTypes(connection, lead.leadId());
            execute(connection, "UPDATE enf_discovered_lead SET suspected_types_json=?,updated_by=?,updated_at=? WHERE lead_id=? AND organization_ref=?",
                    json(types), actor, time(now), lead.leadId(), organization);
            return readLead(connection, organization, lead.leadId()).orElseThrow();
        });
    }

    @Override public void propagateWithdrawal(String organization, SourceSnapshot snapshot, String actor, String requestId, Instant now) {
        tx(connection -> {
            execute(connection, "UPDATE enf_discovered_lead SET source_availability='WITHDRAWN',review_required=1,version_no=version_no+1,updated_by=?,updated_at=? WHERE organization_ref=? AND source_id=? AND source_record_id=? AND source_availability='AVAILABLE'",
                    actor, time(now), organization, snapshot.sourceId(), snapshot.sourceRecordId());
            audit(connection, organization, "SOURCE_RECORD", snapshot.sourceRecordId(), "WITHDRAWAL_PROPAGATED", actor,
                    requestId, now, Map.of("snapshotId", snapshot.snapshotId()));
            return null;
        });
    }

    @Override public DiscoveryTask complete(String taskId, String organization, List<TaskItem> items, List<String> leadIds, Instant now) {
        return tx(connection -> {
            for (TaskItem item : items) execute(connection, "INSERT INTO enf_clue_discovery_item (item_id,task_id,sequence_no,snapshot_id,source_id,source_record_id,source_version,rule_id,rule_version,item_status,reason_code,retryable,lead_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), taskId, item.sequence(), item.snapshotId(), item.sourceId(),
                    item.sourceRecordId(), item.sourceVersion(), item.ruleId(), item.ruleVersion(), item.status(),
                    item.reasonCode(), item.retryable() ? 1 : 0, item.leadId(), time(now));
            for (String leadId : leadIds) {
                try { execute(connection, "INSERT INTO enf_clue_discovery_task_lead (task_id,lead_id,linked_at) VALUES (?,?,?)", taskId, leadId, time(now)); }
                catch (Exception problem) { if (!isConstraint(problem)) throw problem; }
            }
            int failed = (int) items.stream().filter(item -> "FAILED".equals(item.status())).count();
            int matched = (int) items.stream().filter(item -> "MATCHED".equals(item.status())).count();
            int processed = (int) items.stream().map(item -> item.snapshotId() == null ? "failure:" + item.sequence() : item.snapshotId()).distinct().count();
            boolean anySuccess = items.stream().anyMatch(item -> !"FAILED".equals(item.status()));
            String status = failed == 0 ? "succeeded" : anySuccess ? "partial_failed" : "failed";
            execute(connection, "UPDATE enf_clue_discovery_task SET status=?,processed_count=?,matched_count=?,lead_count=?,failed_count=?,completed_at=? WHERE task_id=? AND organization_ref=? AND status='running'",
                    status, processed, matched, leadIds.size(), failed, time(now), taskId, organization);
            return readTask(connection, organization, taskId).orElseThrow();
        });
    }

    @Override public Optional<DiscoveryTask> task(String organization, String taskId) {
        try (Connection connection = dataSource.getConnection()) { return readTask(connection, organization, taskId); }
        catch (Exception problem) { throw unavailable(); }
    }
    @Override public List<DiscoveryTask> tasks(String organization) {
        String sql = "SELECT task_id FROM enf_clue_discovery_task WHERE organization_ref=? ORDER BY created_at DESC";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization); try (ResultSet result = statement.executeQuery()) {
                List<DiscoveryTask> rows = new ArrayList<>(); while (result.next()) rows.add(readTask(connection, organization, result.getString(1)).orElseThrow()); return List.copyOf(rows);
            }
        } catch (Exception problem) { throw unavailable(); }
    }

    @Override public LeadPage leads(String organization, LeadFilter filter) {
        int page = Math.max(1, filter.page()); int size = Math.min(100, Math.max(1, filter.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE organization_ref=?"); List<Object> values = new ArrayList<>(); values.add(organization);
        if (present(filter.status())) { where.append(" AND processing_status=?"); values.add(filter.status()); }
        if (present(filter.sourceId())) { where.append(" AND source_id=?"); values.add(filter.sourceId()); }
        if (present(filter.keyword())) { where.append(" AND (source_record_id LIKE ? OR source_excerpt LIKE ?)"); values.add("%" + filter.keyword() + "%"); values.add("%" + filter.keyword() + "%"); }
        List<Lead> all = queryLeads(where + " ORDER BY updated_at DESC", values.toArray());
        if (present(filter.suspectedType())) all = all.stream().filter(lead -> lead.suspectedTypes().contains(filter.suspectedType())).toList();
        int from = Math.min(all.size(), (page - 1) * size); int to = Math.min(all.size(), from + size);
        return new LeadPage(all.subList(from, to), all.size(), page, size);
    }

    @Override public Optional<LeadDetails> lead(String organization, String leadId) {
        try (Connection connection = dataSource.getConnection()) {
            Optional<Lead> lead = readLead(connection, organization, leadId);
            if (lead.isEmpty()) return Optional.empty();
            return Optional.of(new LeadDetails(lead.get(), matches(connection, leadId), history(connection, leadId),
                    duplicateLinks(connection, organization, leadId), "LOCAL_QUERY_ONLY"));
        } catch (Exception problem) { throw unavailable(); }
    }

    @Override public Lead transition(String organization, String leadId, int expectedVersion, String from, String to,
                                     String reason, String actor, String requestId, Instant now) {
        return tx(connection -> {
            int changed = execute(connection, "UPDATE enf_discovered_lead SET processing_status=?,version_no=version_no+1,updated_by=?,updated_at=? WHERE lead_id=? AND organization_ref=? AND processing_status=? AND version_no=?",
                    to, actor, time(now), leadId, organization, from, expectedVersion);
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedVersion");
            Lead lead = readLead(connection, organization, leadId).orElseThrow();
            history(connection, lead, from, to, reason, actor, requestId, now);
            audit(connection, organization, "LEAD", leadId, "STATUS_CHANGED", actor, requestId, now, Map.of("from", from, "to", to));
            return lead;
        });
    }

    @Override public DuplicateLink markDuplicate(String organization, DuplicateCommand command, String actor, String requestId, Instant now) {
        return tx(connection -> {
            String left = command.leftLeadId().compareTo(command.rightLeadId()) < 0 ? command.leftLeadId() : command.rightLeadId();
            String right = left.equals(command.leftLeadId()) ? command.rightLeadId() : command.leftLeadId();
            String id = UUID.randomUUID().toString();
            try { execute(connection, "INSERT INTO enf_discovered_lead_duplicate (link_id,organization_ref,left_lead_id,right_lead_id,link_status,version_no,reason_text,marked_by,marked_at,reviewed_by,reviewed_at) VALUES (?,?,?,?,'SUSPECTED',1,?,?,?,NULL,NULL)",
                    id, organization, left, right, command.reason(), actor, time(now)); }
            catch (Exception problem) { if (isConstraint(problem)) throw failure("DUPLICATE_LINK_EXISTS", "duplicate"); throw problem; }
            audit(connection, organization, "DUPLICATE_LINK", id, "SUSPECTED", actor, requestId, now, Map.of("left", left, "right", right));
            return readDuplicate(connection, organization, id).orElseThrow();
        });
    }

    @Override public DuplicateLink reviewDuplicate(String organization, String linkId, DuplicateReview command,
                                                   String actor, String requestId, Instant now) {
        return tx(connection -> {
            int changed = execute(connection, "UPDATE enf_discovered_lead_duplicate SET link_status=?,version_no=version_no+1,reason_text=?,reviewed_by=?,reviewed_at=? WHERE link_id=? AND organization_ref=? AND link_status='SUSPECTED' AND version_no=?",
                    command.action(), command.reason(), actor, time(now), linkId, organization, command.expectedVersion());
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedVersion");
            audit(connection, organization, "DUPLICATE_LINK", linkId, command.action(), actor, requestId, now, Map.of("status", command.action()));
            return readDuplicate(connection, organization, linkId).orElseThrow();
        });
    }

    private Optional<RuleVersion> readRule(Connection c, String org, String id, int version) throws Exception {
        String sql = "SELECT rule_id,organization_ref,rule_version,revision_no,status,rule_name,category,rule_description,engine_type,usage_scope,condition_json,content_sha256,created_by,created_at,updated_by,updated_at,published_by,published_at FROM enf_discovery_rule_version WHERE organization_ref=? AND rule_id=? AND rule_version=?";
        try (PreparedStatement s = c.prepareStatement(sql)) { bind(s, org, id, version); try (ResultSet r=s.executeQuery()) {
            if (!r.next()) return Optional.empty();
            return Optional.of(new RuleVersion(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),ruleSources(c,org,id,version),mapper.readValue(r.getString(11),Condition.class),r.getString(12),r.getString(13),instant(r.getTimestamp(14)),r.getString(15),instant(r.getTimestamp(16)),r.getString(17),instant(r.getTimestamp(18))));
        }}
    }
    private List<String> ruleSources(Connection c,String org,String id,int version)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT source_id FROM enf_discovery_rule_source WHERE organization_ref=? AND rule_id=? AND rule_version=? ORDER BY source_id")){bind(s,org,id,version);try(ResultSet r=s.executeQuery()){List<String>x=new ArrayList<>();while(r.next())x.add(r.getString(1));return x;}}}
    private void insertMatch(Connection c,String leadId,RuleMatch match,Instant now)throws Exception{String matchId;try(PreparedStatement s=c.prepareStatement("SELECT match_id FROM enf_discovered_lead_match WHERE lead_id=? AND rule_id=? AND rule_version=? AND rule_revision=?")){bind(s,leadId,match.ruleId(),match.ruleVersion(),match.ruleRevision());try(ResultSet r=s.executeQuery()){if(r.next())return;}}matchId=UUID.randomUUID().toString();execute(c,"INSERT INTO enf_discovered_lead_match (match_id,lead_id,rule_id,rule_version,rule_revision,rule_sha256,suspected_type,matched_at) VALUES (?,?,?,?,?,?,?,?)",matchId,leadId,match.ruleId(),match.ruleVersion(),match.ruleRevision(),match.ruleDigest(),match.suspectedType(),time(now));int i=0;for(ConditionResult result:match.conditionResults())execute(c,"INSERT INTO enf_discovered_lead_condition (result_id,match_id,sequence_no,node_id,operator_name,field_path,matched,reason_code) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),matchId,++i,result.nodeId(),result.operator(),result.fieldPath(),result.matched()?1:0,result.reasonCode());}
    private List<String> leadTypes(Connection c,String leadId)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT DISTINCT suspected_type FROM enf_discovered_lead_match WHERE lead_id=? ORDER BY suspected_type")){bind(s,leadId);try(ResultSet r=s.executeQuery()){List<String>x=new ArrayList<>();while(r.next())x.add(r.getString(1));return x;}}}
    private Optional<Lead> findLeadBySource(Connection c,String org,String source,String record,String version)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT lead_id FROM enf_discovered_lead WHERE organization_ref=? AND source_id=? AND source_record_id=? AND source_version=?")){bind(s,org,source,record,version);try(ResultSet r=s.executeQuery()){return r.next()?readLead(c,org,r.getString(1)):Optional.empty();}}}
    private Optional<Lead> readLead(Connection c,String org,String id)throws Exception{List<Lead>x=queryLeads(c," WHERE organization_ref=? AND lead_id=?",org,id);return x.stream().findFirst();}
    private List<Lead> queryLeads(String suffix,Object...values){try(Connection c=dataSource.getConnection()){return queryLeads(c,suffix,values);}catch(Exception problem){throw unavailable();}}
    private List<Lead> queryLeads(Connection c,String suffix,Object...values)throws Exception{String sql="SELECT lead_id,organization_ref,source_id,source_record_id,source_version,snapshot_id,snapshot_sha256,source_availability,source_excerpt,suspected_types_json,processing_status,review_required,usage_scope,conclusion_scope,first_found_at,version_no,updated_by,updated_at,request_id FROM enf_discovered_lead"+suffix;try(PreparedStatement s=c.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){List<Lead>x=new ArrayList<>();while(r.next())x.add(new Lead(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),mapper.readValue(r.getString(10),new TypeReference<List<String>>(){}),r.getString(11),r.getInt(12)==1,r.getString(13),r.getString(14),instant(r.getTimestamp(15)),r.getInt(16),r.getString(17),instant(r.getTimestamp(18)),r.getString(19)));return List.copyOf(x);}}}
    private Optional<DiscoveryTask> readTask(Connection c,String org,String id)throws Exception{String sql="SELECT task_id,organization_ref,requester_ref,status,processed_count,matched_count,lead_count,failed_count,input_sha256,request_id,created_at,started_at,completed_at FROM enf_clue_discovery_task WHERE organization_ref=? AND task_id=?";try(PreparedStatement s=c.prepareStatement(sql)){bind(s,org,id);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new DiscoveryTask(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getString(9),r.getString(10),instant(r.getTimestamp(11)),instant(r.getTimestamp(12)),instant(r.getTimestamp(13)),taskItems(c,id),taskLeadIds(c,id)));}}}
    private Optional<DiscoveryTask> taskByKey(Connection c,String org,String actor,String key)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT task_id FROM enf_clue_discovery_task WHERE organization_ref=? AND requester_ref=? AND idempotency_key=?")){bind(s,org,actor,key);try(ResultSet r=s.executeQuery()){return r.next()?readTask(c,org,r.getString(1)):Optional.empty();}}}
    private List<TaskItem> taskItems(Connection c,String id)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT sequence_no,snapshot_id,source_id,source_record_id,source_version,rule_id,rule_version,item_status,reason_code,retryable,lead_id FROM enf_clue_discovery_item WHERE task_id=? ORDER BY sequence_no")){bind(s,id);try(ResultSet r=s.executeQuery()){List<TaskItem>x=new ArrayList<>();while(r.next())x.add(new TaskItem(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7)==null?null:r.getInt(7),r.getString(8),r.getString(9),r.getInt(10)==1,r.getString(11)));return x;}}}
    private List<String> taskLeadIds(Connection c,String id)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT lead_id FROM enf_clue_discovery_task_lead WHERE task_id=? ORDER BY lead_id")){bind(s,id);try(ResultSet r=s.executeQuery()){List<String>x=new ArrayList<>();while(r.next())x.add(r.getString(1));return x;}}}
    private List<RuleMatch> matches(Connection c,String leadId)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT match_id,rule_id,rule_version,rule_revision,rule_sha256,suspected_type FROM enf_discovered_lead_match WHERE lead_id=? ORDER BY matched_at")){bind(s,leadId);try(ResultSet r=s.executeQuery()){List<RuleMatch>x=new ArrayList<>();while(r.next())x.add(new RuleMatch(r.getString(2),r.getInt(3),r.getInt(4),r.getString(5),r.getString(6),conditions(c,r.getString(1))));return x;}}}
    private List<ConditionResult> conditions(Connection c,String matchId)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT node_id,operator_name,field_path,matched,reason_code FROM enf_discovered_lead_condition WHERE match_id=? ORDER BY sequence_no")){bind(s,matchId);try(ResultSet r=s.executeQuery()){List<ConditionResult>x=new ArrayList<>();while(r.next())x.add(new ConditionResult(r.getString(1),r.getString(2),r.getString(3),r.getInt(4)==1,r.getString(5),null));return x;}}}
    private void history(Connection c,Lead lead,String from,String to,String reason,String actor,String requestId,Instant now)throws Exception{execute(c,"INSERT INTO enf_discovered_lead_status_history (history_id,lead_id,organization_ref,lead_version,from_status,to_status,reason_text,actor_ref,occurred_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),lead.leadId(),lead.organizationRef(),lead.version(),from,to,reason,actor,time(now),requestId);}
    private List<StatusHistory> history(Connection c,String leadId)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT lead_version,from_status,to_status,reason_text,actor_ref,occurred_at,request_id FROM enf_discovered_lead_status_history WHERE lead_id=? ORDER BY lead_version")){bind(s,leadId);try(ResultSet r=s.executeQuery()){List<StatusHistory>x=new ArrayList<>();while(r.next())x.add(new StatusHistory(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),instant(r.getTimestamp(6)),r.getString(7)));return x;}}}
    private List<DuplicateLink> duplicateLinks(Connection c,String org,String leadId)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT link_id,left_lead_id,right_lead_id,link_status,version_no,reason_text,marked_by,marked_at,reviewed_by,reviewed_at FROM enf_discovered_lead_duplicate WHERE organization_ref=? AND (left_lead_id=? OR right_lead_id=?) ORDER BY marked_at")){bind(s,org,leadId,leadId);try(ResultSet r=s.executeQuery()){List<DuplicateLink>x=new ArrayList<>();while(r.next())x.add(mapDuplicate(r));return x;}}}
    private Optional<DuplicateLink> readDuplicate(Connection c,String org,String id)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT link_id,left_lead_id,right_lead_id,link_status,version_no,reason_text,marked_by,marked_at,reviewed_by,reviewed_at FROM enf_discovered_lead_duplicate WHERE organization_ref=? AND link_id=?")){bind(s,org,id);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(mapDuplicate(r)):Optional.empty();}}}
    private static DuplicateLink mapDuplicate(ResultSet r)throws Exception{return new DuplicateLink(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getString(6),r.getString(7),instant(r.getTimestamp(8)),r.getString(9),instant(r.getTimestamp(10)));}
    private void audit(Connection c,String org,String type,String id,String action,String actor,String requestId,Instant now,Object detail)throws Exception{execute(c,"INSERT INTO enf_clue_discovery_audit (audit_id,organization_ref,object_type,object_id,action_type,actor_ref,occurred_at,request_id,detail_json) VALUES (?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),org,type,id,action,actor,time(now),requestId,json(detail));}
    private <T>T tx(SqlWork<T> work){try(Connection c=dataSource.getConnection()){c.setAutoCommit(false);try{T value=work.run(c);c.commit();return value;}catch(Exception problem){c.rollback();throw problem;}}catch(DiscoveryException problem){throw problem;}catch(Exception problem){throw unavailable();}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception problem){throw unavailable();}}
    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static DiscoveryException unavailable(){return failure("DISCOVERY_STORE_UNAVAILABLE",null);}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws Exception;}
}
