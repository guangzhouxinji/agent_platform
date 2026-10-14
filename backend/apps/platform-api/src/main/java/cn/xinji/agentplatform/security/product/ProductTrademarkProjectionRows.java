package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkProjectionCodec.check;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.DateValue;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** V008 rows are checked against freshly verified originals, including indexes outside the requested page. */
final class ProductTrademarkProjectionRows {
    private final JdbcTemplate db;
    private final ProductTrademarkProjectionCodec codec;
    ProductTrademarkProjectionRows(JdbcTemplate db, ProductTrademarkProjectionCodec codec) {
        this.db = db; this.codec = codec;
    }

    String insertOrReuse(VerifiedTrademarkCapture capture, ProjectedTrademarkRecord record, byte[] raw,
                         String batchId, Instant now) {
        var ref = record.observation().ref();
        List<String> existing = db.queryForList("SELECT record_version_id FROM kb_record_version "
                + "WHERE domain_code='TRADEMARK' AND source_id=? AND stable_record_key=? AND source_version=? FOR UPDATE",
                String.class, ref.sourceId(), ref.recordId(), ref.version());
        if (!existing.isEmpty()) {
            check(existing.size() == 1); verify(existing.getFirst(), capture, record, raw); return existing.getFirst();
        }
        String id = UUID.randomUUID().toString(), rawId = UUID.randomUUID().toString();
        db.update("INSERT INTO kb_raw_record(raw_record_id,batch_id,source_record_id,content_type,content_path,"
                + "raw_content,content_sha256,captured_at) VALUES(?,?,?,'application/json',?,?,?,?)",
                rawId, batchId, ref.recordId(), record.rawRef(), new String(raw, StandardCharsets.UTF_8),
                record.rawSha256(), Timestamp.from(record.capturedAt()));
        db.update("INSERT INTO kb_record_version(record_version_id,domain_code,stable_record_key,source_id,source_version,"
                + "raw_record_id,normalized_payload,normalized_sha256,data_mode,imported_at) "
                + "VALUES(?,'TRADEMARK',?,?,?,?,?,?,'PRODUCT_SOURCE_LOCAL',?)", id, ref.recordId(), ref.sourceId(),
                ref.version(), rawId, codec.normalized(record, capture.nature()).toString(), record.normalizedSha256(), Timestamp.from(now));
        var view = record.observation();
        db.update("INSERT INTO kb_trademark(record_version_id,registration_number,mark_name,registration_date,valid_until,"
                + "source_status,source_ref) VALUES(?,?,?,?,?,?,?)", id, view.registrationNumber(), view.name(),
                date(view.registered()), date(view.validUntil()), view.sourceStatus(), view.sourceRef());
        for (int i = 0; i < view.applicants().size(); i++)
            db.update("INSERT INTO kb_trademark_party(record_version_id,party_order,party_name,address_text) VALUES(?,?,?,?)",
                    id, i, view.applicants().get(i), view.address());
        for (int i = 0; i < view.classes().size(); i++) {
            var category = view.classes().get(i);
            db.update("INSERT INTO kb_trademark_class(record_version_id,class_order,class_code,class_name,goods_services,similar_groups) "
                    + "VALUES(?,?,?,?,?,?)", id, i, category.code(), category.name(), codec.strings(category.items()), codec.strings(category.similarGroups()));
        }
        for (FieldEvidence field : record.fields())
            db.update("INSERT INTO kb_field_provenance(provenance_id,record_version_id,field_path,value_state,value_sha256,"
                    + "source_locator,extraction_method,review_status,derived_flag,method_version) "
                    + "VALUES(?,?,?,?,?,?,'TM_JSON_TOKEN','SOURCE_OBSERVED','N',?)", UUID.randomUUID().toString(), id,
                    field.pointer(), state(field), field.tokenSha256(), codec.locator(field), capture.mapping().id() + ":" + capture.mapping().version());
        return id;
    }

    void verify(String id, VerifiedTrademarkCapture capture, ProjectedTrademarkRecord record, byte[] raw) {
        var ref = record.observation().ref(); var view = record.observation();
        codec.normalized(record, capture.nature());
        one(db.query("SELECT v.domain_code,v.source_id,v.stable_record_key,v.source_version,v.data_mode,v.normalized_payload,"
                + "v.normalized_sha256,r.source_record_id,r.content_path,r.raw_content,r.content_sha256,r.captured_at,"
                + "r.content_type,r.request_url,r.response_metadata,v.effective_at,b.source_id,b.status,b.collector_version FROM kb_record_version v "
                + "JOIN kb_raw_record r ON r.raw_record_id=v.raw_record_id JOIN kb_ingest_batch b ON b.batch_id=r.batch_id "
                + "WHERE v.record_version_id=? FOR UPDATE", (rs, n) ->
                "TRADEMARK".equals(rs.getString(1)) && ref.sourceId().equals(rs.getString(2)) && ref.recordId().equals(rs.getString(3))
                && ref.version().equals(rs.getString(4)) && "PRODUCT_SOURCE_LOCAL".equals(rs.getString(5))
                && record.normalizedSha256().equals(codec.json.canonicalSha(codec.parse(rs.getString(6))))
                && record.normalizedSha256().equals(rs.getString(7))
                && ref.recordId().equals(rs.getString(8)) && record.rawRef().equals(rs.getString(9))
                && java.util.Arrays.equals(raw, rs.getString(10).getBytes(StandardCharsets.UTF_8))
                && record.rawSha256().equals(rs.getString(11)) && record.capturedAt().equals(rs.getTimestamp(12).toInstant())
                && "application/json".equals(rs.getString(13)) && rs.getString(14) == null && rs.getString(15) == null
                && rs.getTimestamp(16) == null && capture.sourceId().equals(rs.getString(17)) && "COMPLETED".equals(rs.getString(18))
                && capture.mapping().id().equals(rs.getString(19)), id));
        one(db.query("SELECT registration_number,mark_name,registration_date,valid_until,source_status,source_ref,"
                + "application_number,jurisdiction,filing_date FROM kb_trademark WHERE record_version_id=? FOR UPDATE", (rs, n) ->
                Objects.equals(view.registrationNumber(), rs.getString(1)) && Objects.equals(view.name(), rs.getString(2))
                && Objects.equals(date(view.registered()), rs.getDate(3)) && Objects.equals(date(view.validUntil()), rs.getDate(4))
                && Objects.equals(view.sourceStatus(), rs.getString(5)) && view.sourceRef().equals(rs.getString(6))
                && rs.getString(7) == null && rs.getString(8) == null && rs.getDate(9) == null, id));
        var parties = db.query("SELECT party_order,party_name,address_text FROM kb_trademark_party "
                + "WHERE record_version_id=? ORDER BY party_order FOR UPDATE", (rs, n) -> {
            int order = rs.getInt(1);
            return order == n && n < view.applicants().size() && view.applicants().get(n).equals(rs.getString(2))
                    && Objects.equals(view.address(), rs.getString(3));
        }, id);
        all(parties, view.applicants().size());
        var categories = db.query("SELECT class_order,class_code,class_name,goods_services,similar_groups FROM kb_trademark_class "
                + "WHERE record_version_id=? ORDER BY class_order FOR UPDATE", (rs, n) -> {
            if (rs.getInt(1) != n || n >= view.classes().size()) return false;
            var category = view.classes().get(n);
            return category.code().equals(rs.getString(2)) && Objects.equals(category.name(), rs.getString(3))
                    && codec.strings(category.items()).equals(rs.getString(4)) && codec.strings(category.similarGroups()).equals(rs.getString(5));
        }, id);
        all(categories, view.classes().size());
        var evidence = db.query("SELECT field_path,value_state,value_sha256,source_locator,extraction_method,review_status,"
                + "derived_flag,method_version,confidence_value FROM kb_field_provenance WHERE record_version_id=? "
                + "ORDER BY field_path FOR UPDATE", (rs, n) -> {
            var field = record.fields().stream().filter(value -> value.pointer().equals(rsString(rs, 1))).findFirst().orElse(null);
            return field != null && state(field).equals(rs.getString(2)) && Objects.equals(field.tokenSha256(), rs.getString(3))
                    && codec.locator(field).equals(rs.getString(4)) && "TM_JSON_TOKEN".equals(rs.getString(5))
                    && "SOURCE_OBSERVED".equals(rs.getString(6)) && "N".equals(rs.getString(7))
                    && (capture.mapping().id() + ":" + capture.mapping().version()).equals(rs.getString(8)) && rs.getBigDecimal(9) == null;
        }, id);
        all(evidence, record.fields().size());
        check(db.queryForObject("SELECT COUNT(*) FROM kb_trademark_image WHERE record_version_id=?", Integer.class, id) == 0);
        check(db.queryForObject("SELECT COUNT(*) FROM kb_trademark_event WHERE record_version_id=?", Integer.class, id) == 0);
    }

    private static String rsString(java.sql.ResultSet rs, int index) {
        try { return rs.getString(index); } catch (java.sql.SQLException unavailable) { throw new IllegalStateException("TRADEMARK_PROJECTION_UNAVAILABLE", unavailable); }
    }
    private static String state(FieldEvidence field) {
        return field.state() == ValueState.NULL ? "SOURCE_NOT_PROVIDED" : field.state().name();
    }
    private static Date date(DateValue value) { return value == null || value.day() == null ? null : Date.valueOf(value.day()); }
    private static void one(List<Boolean> values) { all(values, 1); }
    private static void all(List<Boolean> values, int count) { check(values.size() == count && values.stream().allMatch(Boolean.TRUE::equals)); }
}
