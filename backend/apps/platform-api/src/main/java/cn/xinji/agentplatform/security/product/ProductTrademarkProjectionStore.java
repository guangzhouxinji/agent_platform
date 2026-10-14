package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkProjectionCodec.check;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal persistence only: its caller must establish current identity, source uses and action approval. */
final class ProductTrademarkProjectionStore {
    record Context(String organization, String taskId, long authorizationVersion, String maintainer,
                   String certificateSha, String decisionId, long sourceEpoch, long sourceRowVersion,
                   String retentionRef, long retentionVersion, String retentionSha, String approvedScopeSha,
                   String approvalSha, Instant expiresAt) {
        Context {
            for (String value : List.of(organization, taskId, maintainer, decisionId, retentionRef))
                ProductTrademarkCaptureJson.identity(value);
            for (String value : List.of(certificateSha, retentionSha, approvedScopeSha, approvalSha))
                check(value.equals(ProductTrademarkCaptureJson.digestText(value)));
            check(authorizationVersion > 0 && sourceEpoch > 0 && sourceRowVersion > 0 && retentionVersion > 0 && expiresAt != null);
        }
    }
    record Receipt(String id, String requestSha, Instant committedAt, Instant expiresAt, int recordCount) { }
    record Selection(Input originals, String receiptId, String requestSha) {
        Selection { Objects.requireNonNull(originals); ProductTrademarkCaptureJson.identity(receiptId); ProductTrademarkCaptureJson.digestText(requestSha); }
    }
    record Selected(VerifiedTrademarkCapture capture, Map<String, ProjectedTrademarkRecord> records, Instant expiresAt) {
        Selected { records = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(records)); }
    }
    private record Saved(Receipt receipt, Context context, String key, String payload) { }
    private record Bound(String versionId, String originId, Instant expiresAt) { }
    private final JdbcTemplate db;
    private final Clock clock;
    private final ProductTrademarkCaptureMapper mapper;
    private final ProductTrademarkProjectionCodec codec = new ProductTrademarkProjectionCodec();
    private final ProductTrademarkProjectionRows rows;

    ProductTrademarkProjectionStore(JdbcTemplate db, ObjectMapper mapper, Clock clock) {
        this.db = Objects.requireNonNull(db); this.clock = Objects.requireNonNull(clock);
        this.mapper = new ProductTrademarkCaptureMapper(mapper); this.rows = new ProductTrademarkProjectionRows(db, codec);
    }

    Receipt project(Input input, Context context, String key) {
        Connection connection = transaction(); ProductTrademarkCaptureJson.identity(key);
        check(clock.instant().isBefore(context.expiresAt()));
        VerifiedTrademarkCapture capture = mapper.map(input);
        check(!"UNKNOWN".equals(capture.nature().code()));
        ObjectNode payload = payload(capture, context, key); String requestSha = codec.json.canonicalSha(payload);
        var existing = db.queryForList("SELECT receipt_id FROM con1tm_capture_request WHERE organization_id=? "
                + "AND source_id=? AND contract_version=? AND idempotency_key=? FOR UPDATE", String.class,
                context.organization(), capture.sourceId(), capture.contractVersion(), key);
        if (!existing.isEmpty()) {
            check(existing.size() == 1); Saved saved = saved(existing.getFirst());
            if (!requestSha.equals(saved.receipt().requestSha())) throw new IllegalStateException("TRADEMARK_REQUEST_CONFLICT");
            verify(new Selection(input, saved.receipt().id(), requestSha)); return saved.receipt();
        }
        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint();
            String id = UUID.randomUUID().toString(); Instant now = clock.instant();
            Receipt receipt = new Receipt(id, requestSha, now, context.expiresAt(), capture.records().size());
            insertReceipt(capture, context, key, payload.toString(), receipt);
            String batch = batch(capture, now);
            Map<String, byte[]> raw = originals(input);
            for (ProjectedTrademarkRecord record : capture.records()) {
                var ref = record.observation().ref();
                var bindings = db.queryForList("SELECT record_version_id FROM con1tm_record_binding WHERE source_id=? "
                        + "AND contract_version=? AND source_record_id=? AND record_version=? FOR UPDATE", String.class,
                        capture.sourceId(), capture.contractVersion(), ref.recordId(), ref.version());
                if (bindings.isEmpty()) {
                    String versionId = rows.insertOrReuse(capture, record, raw.get(record.rawRef()), batch, now);
                    insertBinding(capture, record, versionId, id, context.expiresAt());
                }
            }
            verify(new Selection(input, id, requestSha));
            check(clock.instant().isBefore(context.expiresAt()));
            connection.releaseSavepoint(savepoint); return receipt;
        } catch (RuntimeException | java.sql.SQLException failed) {
            if (savepoint != null) {
                try { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
                catch (java.sql.SQLException rollback) {
                    ((ConnectionHolder) TransactionSynchronizationManager.getResource(db.getDataSource())).setRollbackOnly();
                    failed.addSuppressed(rollback);
                }
            }
            if (failed instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("TRADEMARK_PROJECTION_UNAVAILABLE", failed);
        }
    }

    Selected verify(Selection selected) {
        transaction(); Saved saved = saved(selected.receiptId());
        check(saved.receipt().requestSha().equals(selected.requestSha()) && clock.instant().isBefore(saved.receipt().expiresAt()));
        VerifiedTrademarkCapture capture = mapper.map(selected.originals());
        ObjectNode expected = payload(capture, saved.context(), saved.key());
        check(codec.json.canonicalSha(expected).equals(selected.requestSha())
                && saved.receipt().recordCount() == capture.records().size());
        Map<String, byte[]> raw = originals(selected.originals());
        Map<String, ProjectedTrademarkRecord> members = new LinkedHashMap<>();
        Instant[] earliest = {saved.receipt().expiresAt()};
        Map<String, Saved> origins = new java.util.HashMap<>(); origins.put(selected.receiptId(), saved);
        for (ProjectedTrademarkRecord record : capture.records()) {
            var ref = record.observation().ref();
            var ids = db.query("SELECT b.record_version_id,b.registered_source_version,b.raw_ref,b.raw_sha,b.raw_bytes,b.manifest_sha,"
                    + "b.normalized_sha,b.relation_sha,b.map_id,b.map_version,b.map_sha,b.source_nature,b.nature_evidence_ref,b.nature_evidence_sha,b.expires_at,"
                    + "origin.expires_at,origin.status,origin.source_id,origin.contract_version,origin.receipt_id FROM con1tm_record_binding b "
                    + "JOIN con1tm_capture_request origin ON origin.receipt_id=b.capture_request_id "
                    + "WHERE b.source_id=? AND b.contract_version=? AND b.source_record_id=? AND b.record_version=? FOR UPDATE",
                    (rs, n) -> {
                        check(capture.registeredSourceVersion().equals(rs.getString(2)) && record.rawRef().equals(rs.getString(3))
                                && record.rawSha256().equals(rs.getString(4)) && record.rawBytes() == rs.getLong(5)
                                && capture.manifestSha256().equals(rs.getString(6)) && record.normalizedSha256().equals(rs.getString(7))
                                && record.relationSha256().equals(rs.getString(8)) && capture.mapping().id().equals(rs.getString(9))
                                && capture.mapping().version().equals(rs.getString(10)) && capture.mapping().sha256().equals(rs.getString(11))
                                && capture.nature().code().equals(rs.getString(12)) && capture.nature().evidenceRef().equals(rs.getString(13))
                                && capture.nature().evidenceSha256().equals(rs.getString(14)) && clock.instant().isBefore(rs.getTimestamp(15).toInstant())
                                && rs.getTimestamp(15).equals(rs.getTimestamp(16)) && "COMMITTED".equals(rs.getString(17))
                                && capture.sourceId().equals(rs.getString(18)) && capture.contractVersion().equals(rs.getString(19)));
                        Instant until = rs.getTimestamp(15).toInstant();
                        if (until.isBefore(earliest[0])) earliest[0] = until;
                        return new Bound(rs.getString(1), rs.getString(20), until);
                    }, capture.sourceId(), capture.contractVersion(), ref.recordId(), ref.version());
            check(ids.size() == 1); Bound bound = ids.getFirst(); check(!members.containsKey(bound.versionId()));
            Saved origin = origins.computeIfAbsent(bound.originId(), this::saved);
            ObjectNode originPayload = payload(capture, origin.context(), origin.key());
            check(bound.expiresAt().equals(origin.receipt().expiresAt()) && codec.json.canonicalSha(originPayload).equals(origin.receipt().requestSha())
                    && origin.receipt().recordCount() == capture.records().size());
            rows.verify(bound.versionId(), capture, record, raw.get(record.rawRef())); members.put(bound.versionId(), record);
        }
        var linked = db.query("SELECT record_version_id,source_id,contract_version,source_record_id,record_version "
                + "FROM con1tm_record_binding WHERE capture_request_id=? FOR UPDATE", (rs, n) -> {
                    var record = members.get(rs.getString(1));
                    check(record != null && capture.sourceId().equals(rs.getString(2)) && capture.contractVersion().equals(rs.getString(3))
                            && record.observation().ref().recordId().equals(rs.getString(4)) && record.observation().ref().version().equals(rs.getString(5)));
                    return rs.getString(1);
                }, selected.receiptId());
        check(linked.stream().distinct().count() == linked.size());
        check(clock.instant().isBefore(earliest[0]));
        return new Selected(capture, members, earliest[0]);
    }

    private Saved saved(String id) {
        var saved = db.query("SELECT request_sha,committed_at,expires_at,record_count,organization_id,task_id,authorization_version,"
                + "maintainer_ref,certificate_sha,decision_id,source_epoch,source_row_version,retention_ref,retention_version,"
                + "retention_evidence_sha,idempotency_key,request_payload,status,source_id,contract_version,registered_source_version,"
                + "manifest_ref,manifest_sha,records_sha,inventory_sha,binding_set_sha,map_id,map_version,map_sha,approved_scope_sha,approval_sha "
                + "FROM con1tm_capture_request WHERE receipt_id=? FOR UPDATE", (rs, n) -> {
                    Instant until = rs.getTimestamp(3).toInstant(), committed = rs.getTimestamp(2).toInstant();
                    check("COMMITTED".equals(rs.getString(18)) && committed.isBefore(until) && clock.instant().isBefore(until));
                    ObjectNode node = (ObjectNode) codec.parse(rs.getString(17));
                    check(codec.json.canonicalSha(node).equals(rs.getString(1)));
                    var cap = node.path("capture"); var source = node.path("source"); var map = node.path("map");
                    check(source.path("id").asText().equals(rs.getString(19)) && source.path("contractVersion").asText().equals(rs.getString(20))
                            && source.path("registeredVersion").asText().equals(rs.getString(21)) && cap.path("manifestRef").asText().equals(rs.getString(22))
                            && cap.path("manifestSHA").asText().equals(rs.getString(23)) && cap.path("recordsSHA").asText().equals(rs.getString(24))
                            && cap.path("inventorySHA").asText().equals(rs.getString(25)) && cap.path("bindingSetSHA").asText().equals(rs.getString(26))
                            && map.path("id").asText().equals(rs.getString(27)) && map.path("version").asText().equals(rs.getString(28))
                            && map.path("SHA").asText().equals(rs.getString(29)));
                    Context context = new Context(rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getString(13), rs.getLong(14), rs.getString(15),
                            rs.getString(30), rs.getString(31), until);
                    return new Saved(new Receipt(id, rs.getString(1), rs.getTimestamp(2).toInstant(), context.expiresAt(), rs.getInt(4)),
                            context, rs.getString(16), rs.getString(17));
                }, id);
        check(saved.size() == 1); return saved.getFirst();
    }

    private ObjectNode payload(VerifiedTrademarkCapture capture, Context context, String key) {
        ObjectNode node = codec.json.object().put("schema", "TM-PROJECTION-REQUEST-C14N-V1")
                .put("operation", "TRADEMARK_CAPTURE_PROJECT_V1").put("idempotencyKey", key).put("approvalSHA", context.approvalSha());
        node.set("maintainer", codec.json.object().put("subject", context.maintainer()).put("certificateSHA", context.certificateSha())
                .put("approvedScopeSHA", context.approvedScopeSha()));
        node.put("organization", context.organization());
        node.set("task", codec.json.object().put("id", context.taskId()).put("authorizationVersion", context.authorizationVersion()));
        node.set("source", codec.json.object().put("id", capture.sourceId()).put("contractVersion", capture.contractVersion())
                .put("registeredVersion", capture.registeredSourceVersion()).put("decisionId", context.decisionId())
                .put("epoch", context.sourceEpoch()).put("rowVersion", context.sourceRowVersion()));
        ObjectNode cap = codec.json.object().put("manifestRef", capture.manifestRef())
                .put("manifestSHA", capture.manifestSha256()).put("recordsSHA", capture.recordsSha256()).put("inventorySHA", capture.inventorySha256())
                .put("bindingSetSHA", capture.bindingSetSha256()).put("recordCount", capture.records().size());
        var members = codec.json.array();
        for (var record : capture.records()) {
            var item = codec.json.object().put("stableId", record.observation().ref().recordId()).put("recordVersion", record.observation().ref().version())
                    .put("rawRef", record.rawRef()).put("rawSHA", record.rawSha256()).put("rawBytes", record.rawBytes());
            item.set("capturedAt", codec.json.instant(record.capturedAt())); members.add(item);
        }
        cap.set("members", members); node.set("capture", cap);
        node.set("map", codec.json.object().put("id", capture.mapping().id()).put("version", capture.mapping().version()).put("SHA", capture.mapping().sha256()));
        node.set("nature", codec.json.object().put("code", capture.nature().code()).put("evidenceRef", capture.nature().evidenceRef())
                .put("evidenceSHA", capture.nature().evidenceSha256()));
        node.set("retention", codec.json.object().put("ref", context.retentionRef()).put("version", context.retentionVersion()).put("evidenceSHA", context.retentionSha()));
        node.set("expiresAt", codec.json.instant(context.expiresAt())); return node;
    }

    private void insertReceipt(VerifiedTrademarkCapture cap, Context context, String key, String payload, Receipt receipt) {
        db.update("INSERT INTO con1tm_capture_request(receipt_id,organization_id,source_id,contract_version,idempotency_key,request_sha,"
                + "request_payload,task_id,authorization_version,maintainer_ref,certificate_sha,decision_id,source_epoch,source_row_version,"
                + "registered_source_version,manifest_ref,manifest_sha,records_sha,inventory_sha,binding_set_sha,map_id,map_version,map_sha,"
                + "retention_ref,retention_version,retention_evidence_sha,approved_scope_sha,approval_sha,expires_at,record_count,status,committed_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'COMMITTED',?)", receipt.id(), context.organization(),
                cap.sourceId(), cap.contractVersion(), key, receipt.requestSha(), payload, context.taskId(), context.authorizationVersion(),
                context.maintainer(), context.certificateSha(), context.decisionId(), context.sourceEpoch(), context.sourceRowVersion(),
                cap.registeredSourceVersion(), cap.manifestRef(), cap.manifestSha256(), cap.recordsSha256(), cap.inventorySha256(), cap.bindingSetSha256(),
                cap.mapping().id(), cap.mapping().version(), cap.mapping().sha256(), context.retentionRef(), context.retentionVersion(), context.retentionSha(),
                context.approvedScopeSha(), context.approvalSha(),
                Timestamp.from(context.expiresAt()), receipt.recordCount(), Timestamp.from(receipt.committedAt()));
    }
    private String batch(VerifiedTrademarkCapture cap, Instant now) {
        String id = "tm-" + ProductTrademarkCaptureJson.sha((cap.sourceId() + "\n" + cap.contractVersion() + "\n" + cap.manifestSha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var existing = db.queryForList("SELECT batch_id FROM kb_ingest_batch WHERE batch_id=? FOR UPDATE", String.class, id);
        if (existing.isEmpty()) db.update("INSERT INTO kb_ingest_batch(batch_id,dataset_id,source_id,collector_version,started_at,completed_at,"
                + "record_count,records_sha256,status) VALUES(?,?,?,?,?,?,?,?,'COMPLETED')", id, id, cap.sourceId(), cap.mapping().id(),
                Timestamp.from(now), Timestamp.from(now), cap.records().size(), cap.recordsSha256());
        else {
            var matches = db.query("SELECT dataset_id,source_id,collector_version,record_count,records_sha256,status,completed_at "
                    + "FROM kb_ingest_batch WHERE batch_id=?", (rs, n) -> id.equals(rs.getString(1)) && cap.sourceId().equals(rs.getString(2))
                    && cap.mapping().id().equals(rs.getString(3)) && cap.records().size() == rs.getInt(4)
                    && cap.recordsSha256().equals(rs.getString(5)) && "COMPLETED".equals(rs.getString(6)) && rs.getTimestamp(7) != null, id);
            check(matches.size() == 1 && matches.getFirst());
        }
        return id;
    }
    private void insertBinding(VerifiedTrademarkCapture cap, ProjectedTrademarkRecord record, String versionId, String receiptId, Instant until) {
        db.update("INSERT INTO con1tm_record_binding(source_id,contract_version,source_record_id,record_version,registered_source_version,"
                + "record_version_id,raw_ref,raw_sha,raw_bytes,manifest_sha,normalized_sha,relation_sha,map_id,map_version,map_sha,"
                + "source_nature,nature_evidence_ref,nature_evidence_sha,capture_request_id,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                cap.sourceId(), cap.contractVersion(), record.observation().ref().recordId(), record.observation().ref().version(), cap.registeredSourceVersion(),
                versionId, record.rawRef(), record.rawSha256(), record.rawBytes(), cap.manifestSha256(), record.normalizedSha256(), record.relationSha256(),
                cap.mapping().id(), cap.mapping().version(), cap.mapping().sha256(), cap.nature().code(), cap.nature().evidenceRef(), cap.nature().evidenceSha256(),
                receiptId, Timestamp.from(until));
    }
    private Map<String, byte[]> originals(Input input) {
        Map<String, byte[]> result = new LinkedHashMap<>(); input.originals().forEach(item -> result.put(item.ref(), item.bytes())); return result;
    }
    Connection transaction() {
        check(db.getDataSource() != null); Object resource = TransactionSynchronizationManager.getResource(db.getDataSource());
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()
                && resource instanceof ConnectionHolder && ((ConnectionHolder) resource).isSynchronizedWithTransaction());
        Connection connection = ((ConnectionHolder) resource).getConnection();
        try { check(!connection.isClosed() && !connection.getAutoCommit() && connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED); }
        catch (java.sql.SQLException unavailable) { throw new IllegalStateException("TRADEMARK_PROJECTION_UNAVAILABLE", unavailable); }
        return connection;
    }
    boolean belongsTo(javax.sql.DataSource source) { return db.getDataSource() == source; }
}
