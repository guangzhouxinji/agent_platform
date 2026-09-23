package cn.xinji.mockcenter.adapter.out.dm8;

import cn.xinji.mockcenter.application.MockCenterPatentCatalog;
import cn.xinji.mockcenter.application.MockCenterPatentCatalogImporter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/** DM8 storage for a local PUBLIC_PILOT development catalog, never a center data store. */
public final class Dm8MockCenterPatentCatalog implements MockCenterPatentCatalog, MockCenterPatentCatalogImporter {
    private final Supplier<Connection> connections;
    private final ObjectMapper json;

    public Dm8MockCenterPatentCatalog(Supplier<Connection> connections, ObjectMapper json) { this.connections = connections; this.json = json; }

    @Override public void importBatch(ImportBatch batch, List<PatentRecord> records) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                ensureBatch(connection, batch);
                for (PatentRecord record : records) insertImmutable(connection, batch.batchId(), record);
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        } catch (Exception failure) { throw new IllegalStateException("DM8 mock center import failed", failure); }
    }

    @Override public Metadata metadata() {
        try (Connection connection = connections.get(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*), MAX(catalog_version), MAX(import_batch_id) FROM mock_center_patent_record")) {
            rows.next(); return new Metadata(rows.getLong(1), value(rows.getString(2)), value(rows.getString(3)));
        } catch (SQLException failure) { throw new IllegalStateException("DM8 mock center metadata failed", failure); }
    }

    @Override public Page search(Query query) {
        int page = Math.max(1, query.page()), pageSize = Math.min(50, Math.max(1, query.pageSize()));
        String filter = " WHERE (?='' OR LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(abstract_text) LIKE ? ESCAPE '\\') AND (?='' OR publication_number=?)";
        try (Connection connection = connections.get()) {
            long total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM mock_center_patent_record" + filter)) { bindFilter(count, query); try (ResultSet rows = count.executeQuery()) { rows.next(); total = rows.getLong(1); } }
            String sql = "SELECT record_id,source_id,source_record_id,source_version,publication_number,application_number,title,applicants_json,inventors_json,application_date,publication_date,ipc_json,abstract_text,abstract_state,source_url,collected_at,content_digest,catalog_version FROM mock_center_patent_record" + filter + " ORDER BY publication_date DESC NULLS LAST, source_record_id ASC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            try (PreparedStatement select = connection.prepareStatement(sql)) { int index = bindFilter(select, query); select.setInt(index++, (page - 1) * pageSize); select.setInt(index, pageSize); try (ResultSet rows = select.executeQuery()) { List<PatentRecord> items = new ArrayList<>(); while (rows.next()) items.add(map(rows)); return new Page(items, page, pageSize, total); } }
        } catch (SQLException failure) { throw new IllegalStateException("DM8 mock center search failed", failure); }
    }

    @Override public Optional<PatentRecord> find(String recordId) {
        String sql = "SELECT record_id,source_id,source_record_id,source_version,publication_number,application_number,title,applicants_json,inventors_json,application_date,publication_date,ipc_json,abstract_text,abstract_state,source_url,collected_at,content_digest,catalog_version FROM mock_center_patent_record WHERE record_id=?";
        try (Connection connection = connections.get(); PreparedStatement select = connection.prepareStatement(sql)) { select.setString(1, recordId); try (ResultSet rows = select.executeQuery()) { return rows.next() ? Optional.of(map(rows)) : Optional.empty(); } }
        catch (SQLException failure) { throw new IllegalStateException("DM8 mock center detail failed", failure); }
    }

    private void ensureBatch(Connection connection, ImportBatch batch) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT dataset_version,dataset_digest,record_count FROM mock_center_catalog_import WHERE batch_id=?")) {
            select.setString(1, batch.batchId());
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    if (!batch.datasetVersion().equals(rows.getString(1)) || !batch.datasetDigest().equals(rows.getString(2)) || batch.recordCount() != rows.getInt(3)) throw new IllegalStateException("IMPORT_BATCH_CONFLICT");
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mock_center_catalog_import (batch_id,dataset_version,dataset_digest,record_count,imported_at) VALUES (?,?,?,?,?)")) {
            insert.setString(1,batch.batchId()); insert.setString(2,batch.datasetVersion()); insert.setString(3,batch.datasetDigest()); insert.setInt(4,batch.recordCount()); insert.setTimestamp(5,Timestamp.from(java.time.Instant.parse(batch.importedAt()))); insert.executeUpdate();
        }
    }
    private void insertImmutable(Connection connection, String batchId, PatentRecord r) throws Exception {
        try (PreparedStatement select = connection.prepareStatement("SELECT content_digest FROM mock_center_patent_record WHERE source_id=? AND source_record_id=? AND source_version=?")) {
            select.setString(1,r.sourceId()); select.setString(2,r.sourceRecordId()); select.setString(3,r.sourceVersion());
            try (ResultSet rows=select.executeQuery()) { if(rows.next()){ if(!r.contentDigest().equals(rows.getString(1))) throw new IllegalStateException("SOURCE_VERSION_CONTENT_CONFLICT"); return; } }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mock_center_patent_record (record_id,source_id,source_record_id,source_version,publication_number,application_number,title,applicants_json,inventors_json,application_date,publication_date,ipc_json,abstract_text,abstract_state,source_url,collected_at,content_digest,catalog_version,import_batch_id,data_mode,authority_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1,r.recordId()); insert.setString(2,r.sourceId()); insert.setString(3,r.sourceRecordId()); insert.setString(4,r.sourceVersion()); bindInsertRecord(insert,r,batchId); insert.setString(20,"PUBLIC_PILOT"); insert.setString(21,"NON_CENTER_PUBLIC_SOURCE"); insert.executeUpdate();
        }
    }
    private void bindInsertRecord(PreparedStatement s, PatentRecord r, String batchId) throws Exception {
        int at = 5;
        s.setString(at++, r.publicationNumber()); s.setString(at++, r.applicationNumber()); s.setString(at++, r.title()); s.setString(at++, json.writeValueAsString(r.applicants())); s.setString(at++, json.writeValueAsString(r.inventors())); s.setDate(at++, date(r.applicationDate())); s.setDate(at++, date(r.publicationDate())); s.setString(at++, json.writeValueAsString(r.ipc())); s.setString(at++, r.abstractText()); s.setString(at++, r.abstractState()); s.setString(at++, r.sourceUrl()); s.setString(at++, r.collectedAt()); s.setString(at++, r.contentDigest()); s.setString(at++, r.catalogVersion()); s.setString(at, batchId);
    }
    private int bindFilter(PreparedStatement s, Query q) throws SQLException { String text = value(q.text()).toLowerCase(Locale.ROOT), pattern="%"+escapeLike(text)+"%", number = value(q.publicationNumber()); s.setString(1,text); s.setString(2,pattern); s.setString(3,pattern); s.setString(4,number); s.setString(5,number); return 6; }
    private PatentRecord map(ResultSet r) throws SQLException { try { return new PatentRecord(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),json.readValue(r.getString(8),new TypeReference<List<String>>(){}),json.readValue(r.getString(9),new TypeReference<List<String>>(){}),localDate(r.getDate(10)),localDate(r.getDate(11)),json.readValue(r.getString(12),new TypeReference<List<String>>(){}),r.getString(13),r.getString(14),r.getString(15),r.getString(16),r.getString(17),r.getString(18)); } catch (Exception failure) { throw new SQLException("Cannot map mock-center record", failure); } }
    private static java.sql.Date date(LocalDate value) { return value == null ? null : java.sql.Date.valueOf(value); }
    private static LocalDate localDate(java.sql.Date value) { return value == null ? null : value.toLocalDate(); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String escapeLike(String value) { return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_"); }
}
