package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public final class Dm8EnforcementEvidenceStore implements Store {
    private final DataSource dataSource;

    public Dm8EnforcementEvidenceStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public EvidencePage list(String organization, String leadId, String type, String status, int page, int pageSize) {
        String where = " WHERE organization_ref=?" + (leadId == null ? "" : " AND related_lead_id=?")
                + (type == null ? "" : " AND evidence_type=?") + (status == null ? "" : " AND evidence_status=?");
        try (Connection connection = dataSource.getConnection()) {
            List<Object> values = filters(organization, leadId, type, status);
            long total;
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM enf_evidence_record" + where)) {
                bind(statement, values); try (ResultSet result = statement.executeQuery()) { result.next(); total = result.getLong(1); }
            }
            List<EvidenceSummary> items = new ArrayList<>();
            String sql = "SELECT r.evidence_id,r.evidence_name,r.evidence_type,r.evidence_status,r.related_lead_id,r.source_channel,r.record_version,r.updated_at," +
                    "(SELECT MAX(v.content_version) FROM enf_evidence_content_version v WHERE v.evidence_id=r.evidence_id) latest_version " +
                    "FROM enf_evidence_record r" + where + " ORDER BY r.updated_at DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            values.add((page - 1) * pageSize); values.add(pageSize);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, values); try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) items.add(new EvidenceSummary(result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6), result.getInt(7), result.getInt(9), instant(result.getTimestamp(8))));
                }
            }
            return new EvidencePage(items, total, page, pageSize);
        } catch (Exception error) { throw unavailable(error); }
    }

    @Override public EvidenceDetail detail(String organization, String evidenceId) {
        try (Connection connection = dataSource.getConnection()) { return detail(connection, organization, evidenceId); }
        catch (EvidenceException error) { throw error; }
        catch (Exception error) { throw unavailable(error); }
    }

    @Override
    public EvidenceDetail create(String organization, String actor, String requestId, Instant now,
                                 EvidenceInput evidence, ContentInput content) {
        return tx(connection -> {
            if (!leadExists(connection, organization, evidence.relatedLeadId())) throw failure("LEAD_NOT_FOUND", "relatedLeadId");
            String id = "EVD-" + UUID.randomUUID();
            execute(connection, "INSERT INTO enf_evidence_record(evidence_id,organization_ref,evidence_name,evidence_type,evidence_status,related_lead_id,related_case_ref,source_channel,collector_ref,collection_time,collection_missing_reason,physical_description,custody_information,record_version,created_by,created_at,updated_by,updated_at,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, organization, evidence.name(), evidence.type(), "PENDING_VERIFICATION", evidence.relatedLeadId(), evidence.relatedCaseRef(), evidence.sourceChannel(),
                    evidence.collection().collector(), time(evidence.collection().collectionTime()), evidence.collection().missingReason(), evidence.physicalDescription(), evidence.custodyInformation(),
                    1, actor, time(now), actor, time(now), requestId);
            insertContent(connection, id, 1, null, "ORIGINAL", actor, requestId, now, content);
            insertMetadata(connection, id, 1, evidence.name(), evidence.type(), evidence.collection(), evidence.physicalDescription(), evidence.custodyInformation(), "INITIAL_REGISTRATION", actor, now, requestId);
            audit(connection, organization, id, "CREATE", actor, now, requestId, duplicateDetail(connection, content.digest()));
            return detail(connection, organization, id);
        });
    }

    @Override
    public EvidenceDetail revise(String organization, String evidenceId, int expectedVersion, String actor, String requestId,
                                 Instant now, MetadataInput metadata, String reason) {
        return tx(connection -> {
            EvidenceDetail current = detail(connection, organization, evidenceId);
            if (current.recordVersion() != expectedVersion) throw failure("VERSION_CONFLICT", "expectedVersion");
            int next = expectedVersion + 1;
            int changed = execute(connection, "UPDATE enf_evidence_record SET evidence_name=?,evidence_type=?,collector_ref=?,collection_time=?,collection_missing_reason=?,physical_description=?,custody_information=?,record_version=?,updated_by=?,updated_at=?,request_id=? WHERE evidence_id=? AND organization_ref=? AND record_version=?",
                    metadata.name(), metadata.type(), metadata.collection().collector(), time(metadata.collection().collectionTime()), metadata.collection().missingReason(), metadata.physicalDescription(), metadata.custodyInformation(), next, actor, time(now), requestId, evidenceId, organization, expectedVersion);
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedVersion");
            insertMetadata(connection, evidenceId, next, metadata.name(), metadata.type(), metadata.collection(), metadata.physicalDescription(), metadata.custodyInformation(), reason, actor, now, requestId);
            audit(connection, organization, evidenceId, "REVISE_METADATA", actor, now, requestId, "recordVersion=" + next);
            return detail(connection, organization, evidenceId);
        });
    }

    @Override
    public EvidenceDetail addDerived(String organization, String evidenceId, int originalVersion, String derivedKind,
                                     String actor, String requestId, Instant now, ContentInput content) {
        return tx(connection -> {
            EvidenceDetail current = detail(connection, organization, evidenceId);
            if (current.versions().stream().noneMatch(item -> item.version() == originalVersion)) throw failure("CONTENT_VERSION_NOT_FOUND", "originalVersion");
            int next = current.versions().stream().mapToInt(ContentVersion::version).max().orElse(0) + 1;
            insertContent(connection, evidenceId, next, originalVersion, derivedKind, actor, requestId, now, content);
            audit(connection, organization, evidenceId, "ADD_DERIVED_VERSION", actor, now, requestId, "contentVersion=" + next);
            return detail(connection, organization, evidenceId);
        });
    }

    @Override
    public EvidenceDetail changeStatus(String organization, String evidenceId, int expectedVersion, String status,
                                       String reason, String actor, String requestId, Instant now) {
        return tx(connection -> {
            EvidenceDetail current = detail(connection, organization, evidenceId);
            if (current.recordVersion() != expectedVersion) throw failure("VERSION_CONFLICT", "expectedVersion");
            if (current.status().equals("ARCHIVED") || current.status().equals(status)) throw failure("INVALID_STATE_TRANSITION", "status");
            int next = expectedVersion + 1;
            int changed = execute(connection, "UPDATE enf_evidence_record SET evidence_status=?,record_version=?,updated_by=?,updated_at=?,request_id=? WHERE evidence_id=? AND organization_ref=? AND record_version=?",
                    status, next, actor, time(now), requestId, evidenceId, organization, expectedVersion);
            if (changed != 1) throw failure("VERSION_CONFLICT", "expectedVersion");
            execute(connection, "INSERT INTO enf_evidence_status_event(event_id,evidence_id,from_status,to_status,reason_text,actor_ref,occurred_at,request_id) VALUES(?,?,?,?,?,?,?,?)",
                    uuid(), evidenceId, current.status(), status, reason, actor, time(now), requestId);
            audit(connection, organization, evidenceId, "CHANGE_STATUS", actor, now, requestId, current.status() + "->" + status);
            return detail(connection, organization, evidenceId);
        });
    }

    @Override
    public ContentResource content(String organization, String evidenceId, int version, String action,
                                   String actor, String requestId, Instant now) {
        return tx(connection -> {
            detail(connection, organization, evidenceId);
            try (PreparedStatement statement = connection.prepareStatement("SELECT file_name,media_type,previewable,content_bytes FROM enf_evidence_content_version WHERE evidence_id=? AND content_version=?")) {
                bind(statement, evidenceId, version);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw failure("CONTENT_VERSION_NOT_FOUND", "version");
                    byte[] bytes = result.getBytes(4);
                    execute(connection, "INSERT INTO enf_evidence_access_log(access_id,evidence_id,content_version,action_type,actor_ref,organization_ref,occurred_at,request_id) VALUES(?,?,?,?,?,?,?,?)",
                            uuid(), evidenceId, version, action, actor, organization, time(now), requestId);
                    return new ContentResource(result.getString(1), result.getString(2), result.getInt(3) == 1, bytes);
                }
            }
        });
    }

    private static EvidenceDetail detail(Connection connection, String organization, String evidenceId) throws Exception {
        String sql = "SELECT evidence_id,organization_ref,evidence_name,evidence_type,evidence_status,related_lead_id,related_case_ref,source_channel,collector_ref,collection_time,collection_missing_reason,physical_description,custody_information,record_version,created_at,updated_at FROM enf_evidence_record WHERE evidence_id=? AND organization_ref=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, evidenceId, organization);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw failure("EVIDENCE_NOT_FOUND", "evidenceId");
                return new EvidenceDetail(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getString(5),
                        result.getString(6), result.getString(7), result.getString(8),
                        new CollectionInfo(result.getString(9), instant(result.getTimestamp(10)), result.getString(11)), result.getString(12), result.getString(13),
                        result.getInt(14), versions(connection, evidenceId), metadata(connection, evidenceId), statuses(connection, evidenceId),
                        instant(result.getTimestamp(15)), instant(result.getTimestamp(16)));
            }
        }
    }

    private static List<ContentVersion> versions(Connection connection, String evidenceId) throws Exception {
        List<ContentVersion> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT content_version,original_content_version,derived_kind,controlled_resource_ref,file_name,media_type,content_size,digest_algorithm,content_sha256,previewable,created_by,created_at FROM enf_evidence_content_version WHERE evidence_id=? ORDER BY content_version DESC")) {
            bind(statement, evidenceId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new ContentVersion(result.getInt(1), nullableInt(result, 2), result.getString(3), result.getString(4), result.getString(5), result.getString(6), nullableLong(result, 7), result.getString(8), result.getString(9), result.getInt(10) == 1, result.getString(11), instant(result.getTimestamp(12))));
            }
        }
        return values;
    }

    private static List<MetadataHistory> metadata(Connection connection, String evidenceId) throws Exception {
        List<MetadataHistory> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT record_version,evidence_name,evidence_type,change_reason,changed_by,changed_at FROM enf_evidence_metadata_history WHERE evidence_id=? ORDER BY record_version DESC")) {
            bind(statement, evidenceId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new MetadataHistory(result.getInt(1), result.getString(2), result.getString(3), result.getString(4), result.getString(5), instant(result.getTimestamp(6))));
            }
        }
        return values;
    }

    private static List<StatusEvent> statuses(Connection connection, String evidenceId) throws Exception {
        List<StatusEvent> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT from_status,to_status,reason_text,actor_ref,occurred_at FROM enf_evidence_status_event WHERE evidence_id=? ORDER BY occurred_at DESC")) {
            bind(statement, evidenceId); try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(new StatusEvent(result.getString(1), result.getString(2), result.getString(3), result.getString(4), instant(result.getTimestamp(5))));
            }
        }
        return values;
    }

    private static void insertContent(Connection connection, String evidenceId, int version, Integer originalVersion,
                                      String kind, String actor, String requestId, Instant now, ContentInput content) throws Exception {
        String controlled = content.controlledResourceRef() == null ? "controlled://local-evidence/" + evidenceId + "/versions/" + version : content.controlledResourceRef();
        execute(connection, "INSERT INTO enf_evidence_content_version(content_version_id,evidence_id,content_version,original_content_version,derived_kind,controlled_resource_ref,file_name,media_type,content_size,digest_algorithm,content_sha256,content_bytes,previewable,created_by,created_at,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                uuid(), evidenceId, version, originalVersion, kind, controlled, content.fileName(), content.mediaType(), content.bytes() == null ? null : content.bytes().length,
                content.digestAlgorithm(), content.digest(), content.bytes(), content.previewable() ? 1 : 0, actor, time(now), requestId);
    }

    private static void insertMetadata(Connection connection, String id, int version, String name, String type,
                                       CollectionInfo collection, String physical, String custody, String reason,
                                       String actor, Instant now, String requestId) throws Exception {
        execute(connection, "INSERT INTO enf_evidence_metadata_history(history_id,evidence_id,record_version,evidence_name,evidence_type,collector_ref,collection_time,collection_missing_reason,physical_description,custody_information,change_reason,changed_by,changed_at,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                uuid(), id, version, name, type, collection.collector(), time(collection.collectionTime()), collection.missingReason(), physical, custody, reason, actor, time(now), requestId);
    }

    private static boolean leadExists(Connection connection, String organization, String leadId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM enf_discovered_lead WHERE organization_ref=? AND lead_id=?")) {
            bind(statement, organization, leadId); try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }
    private static String duplicateDetail(Connection connection, String digest) throws Exception {
        if (digest == null) return "reference-only";
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM enf_evidence_content_version WHERE content_sha256=?")) {
            bind(statement, digest); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1) > 1 ? "possibleDuplicate=true" : "possibleDuplicate=false"; }
        }
    }
    private static void audit(Connection c, String org, String id, String action, String actor, Instant at, String request, String detail) throws Exception {
        execute(c, "INSERT INTO enf_evidence_audit(audit_id,organization_ref,evidence_id,action_type,actor_ref,occurred_at,request_id,detail_text) VALUES(?,?,?,?,?,?,?,?)", uuid(), org, id, action, actor, time(at), request, detail);
    }
    private <T> T tx(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try { T value = work.run(connection); connection.commit(); return value; }
            catch (Exception error) { connection.rollback(); throw error; }
        } catch (EvidenceException error) { throw error; }
        catch (Exception error) { throw unavailable(error); }
    }
    private static List<Object> filters(String organization, String leadId, String type, String status) {
        List<Object> values = new ArrayList<>(); values.add(organization);
        if (leadId != null) values.add(leadId); if (type != null) values.add(type); if (status != null) values.add(status); return values;
    }
    private static int execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bind(statement, Arrays.asList(values)); return statement.executeUpdate(); }
    }
    private static void bind(PreparedStatement statement, Object... values) throws Exception { bind(statement, Arrays.asList(values)); }
    private static void bind(PreparedStatement statement, List<?> values) throws Exception {
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof byte[] bytes) statement.setBytes(index + 1, bytes); else statement.setObject(index + 1, value);
        }
    }
    private static Integer nullableInt(ResultSet result, int index) throws Exception { int value = result.getInt(index); return result.wasNull() ? null : value; }
    private static Long nullableLong(ResultSet result, int index) throws Exception { long value = result.getLong(index); return result.wasNull() ? null : value; }
    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static EvidenceException unavailable(Exception error) { EvidenceException result = failure("EVIDENCE_STORE_UNAVAILABLE", null); result.initCause(error); return result; }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws Exception; }
}
