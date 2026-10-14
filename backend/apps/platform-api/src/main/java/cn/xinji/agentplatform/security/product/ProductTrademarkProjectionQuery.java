package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkProjectionCodec.check;
import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.require;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only a fully authenticated finite capture member set can participate in COUNT, filtering and paging. */
final class ProductTrademarkProjectionQuery {
    record Result(List<Observation> items, long total, boolean hasNext) {
        Result { items = List.copyOf(items); }
    }
    private record Range(Map<String, ProductTrademarkCapture.ProjectedTrademarkRecord> members,
                         List<ProductTrademarkProjectionStore.Selected> captures) { }
    private final JdbcTemplate db;
    private final ProductTrademarkProjectionStore store;
    private final Clock clock;
    ProductTrademarkProjectionQuery(JdbcTemplate db, ProductTrademarkProjectionStore store, Clock clock) {
        this.db = java.util.Objects.requireNonNull(db); this.store = java.util.Objects.requireNonNull(store);
        this.clock = java.util.Objects.requireNonNull(clock);
        check(store.belongsTo(db.getDataSource()));
    }

    Result query(List<ProductTrademarkProjectionStore.Selection> selected, Query query) {
        require(query.page().cursor() == null, Code.INVALID_REQUEST);
        var connection = store.transaction(); Range range = range(selected);
        if (range.members().isEmpty()) { current(range); return new Result(List.of(), 0, false); }
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE (");
        var ids = new ArrayList<>(range.members().keySet());
        for (int start = 0; start < ids.size(); start += 1000) {
            if (start > 0) where.append(" OR ");
            int end = Math.min(ids.size(), start + 1000);
            where.append("t.record_version_id IN (").append(placeholders(end - start)).append(")");
            parameters.addAll(ids.subList(start, end));
        }
        where.append(")");
        if (!blank(query.registrationNumber())) condition(where, parameters, "t.registration_number=?", query.registrationNumber());
        if (!blank(query.name())) condition(where, parameters, "INSTR(t.mark_name,?)>0", query.name());
        if (!blank(query.applicant())) condition(where, parameters,
                "EXISTS(SELECT 1 FROM kb_trademark_party p WHERE p.record_version_id=t.record_version_id AND INSTR(p.party_name,?)>0)", query.applicant());
        if (!query.categories().isEmpty()) {
            where.append(" AND EXISTS(SELECT 1 FROM kb_trademark_class c WHERE c.record_version_id=t.record_version_id AND c.class_code IN (")
                    .append(placeholders(query.categories().size())).append("))");
            parameters.addAll(query.categories().stream().sorted().toList());
        }
        if (!query.statuses().isEmpty()) {
            where.append(" AND CASE WHEN t.source_status IS NULL THEN NULL ELSE 'UNMAPPED' END IN (")
                    .append(placeholders(query.statuses().size())).append(")");
            parameters.addAll(query.statuses().stream().sorted().toList());
        }
        date(where, parameters, "t.registration_date", ">=", query.registeredFrom());
        date(where, parameters, "t.registration_date", "<=", query.registeredTo());
        date(where, parameters, "t.valid_until", ">=", query.validFrom()); date(where, parameters, "t.valid_until", "<=", query.validTo());
        String from = " FROM kb_trademark t JOIN kb_record_version v ON v.record_version_id=t.record_version_id";
        Long total = db.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        check(total != null && total >= 0);
        var pageParameters = new ArrayList<>(parameters); pageParameters.add(query.page().offset()); pageParameters.add(query.page().size());
        var page = db.queryForList("SELECT t.record_version_id" + from + where
                + " ORDER BY t.registration_date DESC NULLS LAST,v.source_id,v.stable_record_key OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                String.class, pageParameters.toArray());
        check(page.stream().allMatch(range.members()::containsKey) && page.stream().distinct().count() == page.size());
        current(range); check(connection == store.transaction());
        return new Result(page.stream().map(id -> range.members().get(id).observation()).toList(), total,
                query.page().offset() + page.size() < total);
    }

    Observation detail(List<ProductTrademarkProjectionStore.Selection> selected, RecordRef ref) {
        var connection = store.transaction(); Range range = range(selected);
        var matches = range.members().entrySet().stream().filter(entry -> entry.getValue().observation().ref().equals(ref)).toList();
        require(matches.size() == 1, Code.RECORD_NOT_FOUND);
        var entry = matches.getFirst();
        Integer count = db.queryForObject("SELECT COUNT(*) FROM kb_record_version v JOIN kb_trademark t "
                + "ON t.record_version_id=v.record_version_id WHERE v.record_version_id=? AND v.source_id=? "
                + "AND v.stable_record_key=? AND v.source_version=? AND v.domain_code='TRADEMARK'",
                Integer.class, entry.getKey(), ref.sourceId(), ref.recordId(), ref.version());
        check(count != null && count == 1); current(range); check(connection == store.transaction());
        return entry.getValue().observation();
    }

    private Range range(List<ProductTrademarkProjectionStore.Selection> selected) {
        check(selected != null && !selected.isEmpty() && selected.size() <= 8);
        Set<String> sources = new java.util.HashSet<>(); long bytes = 0;
        Map<String, ProductTrademarkCapture.ProjectedTrademarkRecord> members = new LinkedHashMap<>();
        List<ProductTrademarkProjectionStore.Selected> captures = new ArrayList<>();
        for (var selection : selected) {
            check(sources.add(selection.originals().sourceId()));
            var input = selection.originals();
            bytes = Math.addExact(bytes, input.manifest().byteLength() + input.records().byteLength());
            if (input.natureEvidence() != null) bytes = Math.addExact(bytes, input.natureEvidence().byteLength());
            for (var raw : input.originals()) bytes = Math.addExact(bytes, raw.byteLength());
            check(bytes <= 128L * ProductTrademarkCaptureJson.MIB);
            var capture = store.verify(selection); captures.add(capture);
            capture.records().forEach((id, record) -> { check(!members.containsKey(id)); members.put(id, record); });
            check(members.size() <= 5000);
        }
        return new Range(members, captures);
    }
    private void current(Range range) { range.captures().forEach(capture -> check(clock.instant().isBefore(capture.expiresAt()))); }
    private static String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void condition(StringBuilder sql, List<Object> values, String clause, Object value) { sql.append(" AND ").append(clause); values.add(value); }
    private static void date(StringBuilder sql, List<Object> values, String column, String operator, java.time.LocalDate value) {
        if (value != null) condition(sql, values, column + operator + "?", java.sql.Date.valueOf(value));
    }
}
