package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceStoreSupport.*;
import static cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class Dm8EnforcementClueParsingStore implements Store {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Dm8EnforcementClueParsingStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public PreparedTask prepare(String organization, String actor, String leadId, String idempotencyKey,
                                String requestId, String parserVersion, Instant now) {
        return tx(connection -> {
            ParseInput input = readInput(connection, organization, leadId);
            String digest = sha256(leadId + "|" + input.leadVersion() + "|" + input.snapshotId() + "|"
                    + input.snapshotDigest() + "|" + parserVersion);
            Optional<ParseTask> replay = taskByKey(connection, organization, actor, idempotencyKey);
            if (replay.isPresent()) {
                String stored = taskDigest(connection, replay.get().taskId());
                if (!digest.equals(stored)) throw failure("IDEMPOTENCY_CONFLICT", "idempotencyKey");
                return new PreparedTask(replay.get(), null, true);
            }
            String taskId = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO enf_clue_parse_task (task_id,organization_ref,lead_id,requester_ref,idempotency_key,input_sha256,status,result_version,field_count,issue_count,error_code,parser_version,request_id,created_at,completed_at) VALUES (?,?,?,?,?,?,'running',0,0,0,NULL,?,?,?,NULL)",
                    taskId, organization, leadId, actor, idempotencyKey, digest, parserVersion, requestId, time(now));
            execute(connection, "INSERT INTO enf_clue_parse_input (task_id,lead_version,snapshot_id,snapshot_sha256,parser_version) VALUES (?,?,?,?,?)",
                    taskId, input.leadVersion(), input.snapshotId(), input.snapshotDigest(), parserVersion);
            audit(connection, organization, "PARSE_TASK", taskId, "STARTED", actor, requestId, now,
                    Map.of("leadId", leadId, "inputSha256", digest));
            ParseTask task = new ParseTask(taskId, leadId, "running", 0, 0, 0, null, parserVersion,
                    requestId, now, null);
            return new PreparedTask(task, input, false);
        });
    }

    @Override
    public ParseTask complete(String taskId, String organization, String actor, String requestId,
                              Extraction extraction, Instant now) {
        return tx(connection -> {
            ParseTask task = readTask(connection, organization, taskId).orElseThrow();
            if (!"running".equals(task.status())) return task;
            int version = nextVersion(connection, organization, task.leadId());
            String previous = latestResultId(connection, organization, task.leadId()).orElse(null);
            String resultId = UUID.randomUUID().toString();
            insertResult(connection, resultId, organization, task.leadId(), version, taskId, previous,
                    "MACHINE", "PENDING_REVIEW", task.parserVersion(), extraction.modelId(), extraction.modelVersion(),
                    extraction.modelUsage(), extraction.priorityLevel(), extraction.priorityReason(), actor, requestId, now);
            insertFields(connection, resultId, extraction.fields());
            insertIssues(connection, resultId, extraction.issues());
            String status = extraction.issues().isEmpty() ? "succeeded" : "partial_failed";
            execute(connection, "UPDATE enf_clue_parse_task SET status=?,result_version=?,field_count=?,issue_count=?,completed_at=? WHERE task_id=? AND organization_ref=? AND status='running'",
                    status, version, extraction.fields().size(), extraction.issues().size(), time(now), taskId, organization);
            audit(connection, organization, "PARSE_RESULT", resultId, "MACHINE_CREATED", actor, requestId, now,
                    Map.of("version", version, "status", status));
            return readTask(connection, organization, taskId).orElseThrow();
        });
    }

    @Override public List<ParseTask> tasks(String organization, String leadId) {
        String sql = "SELECT task_id FROM enf_clue_parse_task WHERE organization_ref=? AND lead_id=? ORDER BY created_at DESC";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, leadId); try (ResultSet result = statement.executeQuery()) {
                List<ParseTask> values = new ArrayList<>();
                while (result.next()) values.add(readTask(connection, organization, result.getString(1)).orElseThrow());
                return List.copyOf(values);
            }
        } catch (Exception problem) { throw unavailable(); }
    }

    @Override public List<ParseResult> results(String organization, String leadId) {
        try (Connection connection = dataSource.getConnection()) { return readResults(connection, organization, leadId); }
        catch (Exception problem) { throw unavailable(); }
    }

    @Override public Optional<ParseResult> result(String organization, String leadId, int version) {
        try (Connection connection = dataSource.getConnection()) { return readResult(connection, organization, leadId, version); }
        catch (Exception problem) { throw unavailable(); }
    }

    @Override
    public ParseResult revise(String organization, String leadId, int expectedVersion, List<FieldValue> fields,
                              String reason, String actor, String requestId, Instant now) {
        return tx(connection -> {
            ParseResult previous = latestResult(connection, organization, leadId)
                    .orElseThrow(() -> failure("PARSE_RESULT_NOT_FOUND", "leadId"));
            if (previous.version() != expectedVersion) throw failure("VERSION_CONFLICT", "expectedVersion");
            if (!List.of("PENDING_REVIEW", "RETURNED").contains(previous.reviewStatus())) {
                throw failure("RESULT_IMMUTABLE", "reviewStatus");
            }
            int version = expectedVersion + 1;
            String resultId = UUID.randomUUID().toString();
            insertResult(connection, resultId, organization, leadId, version, null, previous.resultId(),
                    "HUMAN_REVISION", "PENDING_REVIEW", previous.parserVersion(), null, null, "NO_MODEL_USED",
                    "TO_BE_EVALUATED", "FACTORS_NOT_CONFIRMED", actor, requestId, now);
            insertFields(connection, resultId, fields);
            execute(connection, "INSERT INTO enf_clue_parse_change (change_id,result_id,change_order,before_json,after_json,reason_text,actor_ref,changed_at,request_id) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), resultId, 1, json(previous.fields()), json(fields), reason,
                    actor, time(now), requestId);
            audit(connection, organization, "PARSE_RESULT", resultId, "HUMAN_REVISED", actor, requestId, now,
                    Map.of("previousResultId", previous.resultId(), "version", version));
            return readResult(connection, organization, leadId, version).orElseThrow();
        });
    }

    @Override
    public ParseResult review(String organization, String leadId, int version, String action, String reason,
                              String actor, String requestId, Instant now) {
        return tx(connection -> {
            ParseResult current = readResult(connection, organization, leadId, version)
                    .orElseThrow(() -> failure("PARSE_RESULT_NOT_FOUND", "version"));
            if (!"PENDING_REVIEW".equals(current.reviewStatus())) throw failure("INVALID_STATE_TRANSITION", "action");
            String next = "CONFIRM".equals(action) ? "CONFIRMED" : "RETURNED";
            int changed = execute(connection, "UPDATE enf_clue_parse_result SET review_status=?,updated_by=?,updated_at=? WHERE result_id=? AND organization_ref=? AND review_status='PENDING_REVIEW'",
                    next, actor, time(now), current.resultId(), organization);
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedVersion");
            execute(connection, "INSERT INTO enf_clue_parse_review (review_id,result_id,result_version,from_status,to_status,reason_text,reviewer_ref,reviewed_at,request_id) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), current.resultId(), version, "PENDING_REVIEW", next, reason,
                    actor, time(now), requestId);
            audit(connection, organization, "PARSE_RESULT", current.resultId(), next, actor, requestId, now,
                    Map.of("version", version));
            return readResult(connection, organization, leadId, version).orElseThrow();
        });
    }

    private ParseInput readInput(Connection connection, String organization, String leadId) throws Exception {
        String sql = "SELECT l.version_no,l.snapshot_id,l.snapshot_sha256,l.suspected_types_json,l.processing_status,l.source_availability,s.content_sha256,s.payload_json FROM enf_discovered_lead l JOIN enf_source_record_snapshot s ON s.snapshot_id=l.snapshot_id AND s.organization_ref=l.organization_ref WHERE l.organization_ref=? AND l.lead_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, leadId); try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw failure("LEAD_NOT_FOUND", "leadId");
                if (!List.of("PENDING_VERIFICATION", "VERIFYING").contains(result.getString(5))) {
                    throw failure("LEAD_STATE_NOT_PARSEABLE", "leadId");
                }
                if (!"AVAILABLE".equals(result.getString(6))) throw failure("SOURCE_WITHDRAWN", "leadId");
                if (!result.getString(3).equals(result.getString(7))) throw failure("FROZEN_INPUT_CHANGED", "snapshot");
                return new ParseInput(leadId, result.getInt(1), result.getString(2), result.getString(3),
                        classifications(connection, leadId),
                        mapper.readValue(result.getString(8), new TypeReference<Map<String, Object>>() {}));
            }
        }
    }

    private List<ClassificationInput> classifications(Connection connection, String leadId) throws Exception {
        String sql = "SELECT suspected_type,rule_id,rule_version,rule_revision,rule_sha256 FROM enf_discovered_lead_match WHERE lead_id=? ORDER BY suspected_type,rule_id,rule_version,rule_revision";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, leadId); try (ResultSet result = statement.executeQuery()) {
                List<ClassificationInput> values = new ArrayList<>();
                while (result.next()) values.add(new ClassificationInput(result.getString(1), result.getString(2),
                        result.getInt(3), result.getInt(4), result.getString(5)));
                return List.copyOf(values);
            }
        }
    }

    private Optional<ParseTask> readTask(Connection connection, String organization, String taskId) throws Exception {
        String sql = "SELECT task_id,lead_id,status,result_version,field_count,issue_count,error_code,parser_version,request_id,created_at,completed_at FROM enf_clue_parse_task WHERE organization_ref=? AND task_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, taskId); try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new ParseTask(result.getString(1), result.getString(2), result.getString(3),
                        result.getInt(4), result.getInt(5), result.getInt(6), result.getString(7), result.getString(8),
                        result.getString(9), instant(result.getTimestamp(10)), instant(result.getTimestamp(11))));
            }
        }
    }

    private Optional<ParseTask> taskByKey(Connection connection, String organization, String actor, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT task_id FROM enf_clue_parse_task WHERE organization_ref=? AND requester_ref=? AND idempotency_key=?")) {
            bind(statement, organization, actor, key); try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readTask(connection, organization, result.getString(1)) : Optional.empty();
            }
        }
    }

    private String taskDigest(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT input_sha256 FROM enf_clue_parse_task WHERE task_id=?")) {
            bind(statement, taskId); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getString(1); }
        }
    }

    private List<ParseResult> readResults(Connection connection, String organization, String leadId) throws Exception {
        String sql = "SELECT result_version FROM enf_clue_parse_result WHERE organization_ref=? AND lead_id=? ORDER BY result_version DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, leadId); try (ResultSet result = statement.executeQuery()) {
                List<ParseResult> values = new ArrayList<>();
                while (result.next()) values.add(readResult(connection, organization, leadId, result.getInt(1)).orElseThrow());
                return List.copyOf(values);
            }
        }
    }

    private Optional<ParseResult> latestResult(Connection connection, String organization, String leadId) throws Exception {
        List<ParseResult> values = readResults(connection, organization, leadId);
        return values.stream().findFirst();
    }

    private Optional<ParseResult> readResult(Connection connection, String organization, String leadId, int version) throws Exception {
        String sql = "SELECT r.result_id,r.result_version,r.previous_result_id,r.revision_kind,r.review_status,r.parser_version,r.model_id,r.model_version,r.model_usage,r.priority_level,r.priority_reason,r.not_legal_probability,r.review_required,r.created_by,r.created_at,r.updated_by,r.updated_at,r.request_id,l.source_availability FROM enf_clue_parse_result r JOIN enf_discovered_lead l ON l.lead_id=r.lead_id AND l.organization_ref=r.organization_ref WHERE r.organization_ref=? AND r.lead_id=? AND r.result_version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, leadId, version); try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String id = result.getString(1);
                boolean reviewRequired = result.getInt(13) == 1 || "WITHDRAWN".equals(result.getString(19));
                return Optional.of(new ParseResult(id, leadId, result.getInt(2), result.getString(3), result.getString(4),
                        result.getString(5), result.getString(6), result.getString(7), result.getString(8), result.getString(9),
                        result.getString(10), result.getString(11), result.getInt(12) == 1, reviewRequired,
                        fields(connection, id), issues(connection, id), result.getString(14), instant(result.getTimestamp(15)),
                        result.getString(16), instant(result.getTimestamp(17)), result.getString(18)));
            }
        }
    }

    private List<FieldValue> fields(Connection connection, String resultId) throws Exception {
        String sql = "SELECT field_kind,item_order,role_as_stated,raw_value,normalized_value,credit_code,source_fragment,field_path,source_kind,reference_status,source_ref,source_version,source_sha256 FROM enf_clue_parse_field WHERE result_id=? ORDER BY item_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, resultId); try (ResultSet result = statement.executeQuery()) {
                List<FieldValue> values = new ArrayList<>();
                while (result.next()) values.add(new FieldValue(result.getString(1), result.getInt(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getString(7), result.getString(8),
                        result.getString(9), result.getString(10), result.getString(11), result.getString(12), result.getString(13)));
                return List.copyOf(values);
            }
        }
    }

    private List<ParseIssue> issues(Connection connection, String resultId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT issue_kind,field_path,reason_code FROM enf_clue_parse_issue WHERE result_id=? ORDER BY issue_order")) {
            bind(statement, resultId); try (ResultSet result = statement.executeQuery()) {
                List<ParseIssue> values = new ArrayList<>();
                while (result.next()) values.add(new ParseIssue(result.getString(1), result.getString(2), result.getString(3)));
                return List.copyOf(values);
            }
        }
    }

    private void insertResult(Connection connection, String resultId, String organization, String leadId, int version,
                              String taskId, String previous, String revision, String review, String parser,
                              String modelId, String modelVersion, String modelUsage, String priority, String priorityReason,
                              String actor, String requestId, Instant now) throws Exception {
        execute(connection, "INSERT INTO enf_clue_parse_result (result_id,organization_ref,lead_id,result_version,task_id,previous_result_id,revision_kind,review_status,parser_version,model_id,model_version,model_usage,priority_level,priority_reason,not_legal_probability,review_required,created_by,created_at,updated_by,updated_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,0,?,?,?,?,?)",
                resultId, organization, leadId, version, taskId, previous, revision, review, parser, modelId, modelVersion,
                modelUsage, priority, priorityReason, actor, time(now), actor, time(now), requestId);
    }

    private void insertFields(Connection connection, String resultId, List<FieldValue> fields) throws Exception {
        for (FieldValue field : fields) execute(connection, "INSERT INTO enf_clue_parse_field (field_id,result_id,field_kind,item_order,role_as_stated,raw_value,normalized_value,credit_code,source_fragment,field_path,source_kind,reference_status,source_ref,source_version,source_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), resultId, field.kind(), field.order(), field.role(), field.rawValue(),
                field.normalizedValue(), field.creditCode(), field.sourceFragment(), field.fieldPath(), field.sourceKind(),
                field.referenceStatus(), field.sourceRef(), field.sourceVersion(), field.sourceDigest());
    }

    private void insertIssues(Connection connection, String resultId, List<ParseIssue> issues) throws Exception {
        int order = 0;
        for (ParseIssue issue : issues) execute(connection, "INSERT INTO enf_clue_parse_issue (issue_id,result_id,issue_order,issue_kind,field_path,reason_code) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), resultId, ++order, issue.kind(), issue.fieldPath(), issue.reasonCode());
    }

    private int nextVersion(Connection connection, String organization, String leadId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(result_version),0)+1 FROM enf_clue_parse_result WHERE organization_ref=? AND lead_id=?")) {
            bind(statement, organization, leadId); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }
    private Optional<String> latestResultId(Connection connection, String organization, String leadId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT result_id FROM enf_clue_parse_result WHERE organization_ref=? AND lead_id=? ORDER BY result_version DESC FETCH FIRST 1 ROWS ONLY")) {
            bind(statement, organization, leadId); try (ResultSet result = statement.executeQuery()) { return result.next() ? Optional.of(result.getString(1)) : Optional.empty(); }
        }
    }
    private void audit(Connection connection, String organization, String type, String id, String action,
                       String actor, String requestId, Instant now, Object detail) throws Exception {
        execute(connection, "INSERT INTO enf_clue_parse_audit (audit_id,organization_ref,object_type,object_id,action_type,actor_ref,occurred_at,request_id,detail_json) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), organization, type, id, action, actor, time(now), requestId, json(detail));
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception problem) { throw unavailable(); } }
    private <T> T tx(SqlWork<T> work) { try (Connection connection = dataSource.getConnection()) { connection.setAutoCommit(false); try { T value = work.run(connection); connection.commit(); return value; } catch (Exception problem) { connection.rollback(); throw problem; } } catch (ParsingException problem) { throw problem; } catch (Exception problem) { throw unavailable(); } }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception problem) { throw unavailable(); } }
    private static ParsingException unavailable() { return EnforcementClueParsingService.failure("PARSING_STORE_UNAVAILABLE", null); }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws Exception; }
}
