package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.LegalData;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort.MaterialCatalogItem;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

public final class AdjudicationLocalSourceImporter {
    public static final String BATCH_ID = "ADJ-SYNTHETIC-LOCAL-V1";
    public static final String CATALOG_VERSION = "adjudication-local-source-v1";
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public AdjudicationLocalSourceImporter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public ImportResult importSyntheticBaseline() {
        SyntheticAdjudicationSourceAdapter seed = new SyntheticAdjudicationSourceAdapter();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Payload<Source>> cases = payloads(seed.cases());
                List<Payload<MaterialCatalogItem>> materials = payloads(seed.materialItems());
                List<Payload<LegalData>> legal = payloads(seed.legalCandidates());
                Payload<Source> template = payload(seed.template());
                String digest = digest(cases, materials, legal, template);
                if (batchExists(connection)) {
                    verifyBatch(connection, digest, cases.size() + materials.size() + legal.size() + 1);
                    verifyRows(connection, cases.size(), materials.size(), legal.size());
                    connection.commit();
                    return new ImportResult(0, 0, 0, 0, false);
                }
                insertBatch(connection, digest, cases.size() + materials.size() + legal.size() + 1);
                for (Payload<Source> value : cases) insertCase(connection, value);
                for (Payload<MaterialCatalogItem> value : materials) insertMaterial(connection, value);
                for (Payload<LegalData> value : legal) insertLegal(connection, value);
                insertTemplate(connection, template);
                verifyRows(connection, cases.size(), materials.size(), legal.size());
                connection.commit();
                return new ImportResult(cases.size(), materials.size(), legal.size(), 1, true);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Local adjudication source import failed", failure);
        }
    }

    private <T> List<Payload<T>> payloads(List<T> values) throws Exception {
        List<Payload<T>> result = new ArrayList<>();
        for (T value : values) result.add(payload(value));
        return result;
    }

    private <T> Payload<T> payload(T value) throws Exception {
        String json = mapper.writeValueAsString(value);
        return new Payload<>(value, json, AdjudicationLocalSourceCodec.sha256(json));
    }

    private void insertBatch(Connection c, String digest, int count) throws Exception {
        execute(c, "INSERT INTO adj_local_source_batch VALUES (?,?,?,?,?,?,?,?,?,?)", BATCH_ID,
                "ADJ-SYNTHETIC-LOCAL", CATALOG_VERSION, "SYNTHETIC_LOCAL", count, digest,
                "INTERNAL_DEVELOPMENT_ONLY", "N", "COMPLETED", Timestamp.from(Instant.now()));
    }

    private void insertCase(Connection c, Payload<Source> value) throws Exception {
        Source source = value.value();
        execute(c, "INSERT INTO adj_local_case VALUES (?,?,?,?,?,?,?,?,?,?)", source.id(), source.version(), BATCH_ID,
                source.kind(), source.location(), "AVAILABLE", value.json(), value.sha256(), null, null);
    }

    private void insertMaterial(Connection c, Payload<MaterialCatalogItem> value) throws Exception {
        MaterialCatalogItem item = value.value();
        Source source = item.source();
        execute(c, "INSERT INTO adj_local_material VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", source.id(), source.version(), BATCH_ID,
                item.caseRef().id(), item.caseRef().version(), source.kind(), source.location(),
                item.data().available() ? "AVAILABLE" : "SOURCE_UNAVAILABLE", value.json(), value.sha256(), null, null);
    }

    private void insertLegal(Connection c, Payload<LegalData> value) throws Exception {
        LegalData legal = value.value();
        Source source = legal.source();
        execute(c, "INSERT INTO adj_local_legal_snapshot VALUES (?,?,?,?,?,?,?,?,?,?,?)", source.id(), source.version(), BATCH_ID,
                legal.code(), source.kind(), source.location(), "AVAILABLE", value.json(), value.sha256(), null, null);
    }

    private void insertTemplate(Connection c, Payload<Source> value) throws Exception {
        Source source = value.value();
        execute(c, "INSERT INTO adj_local_template_snapshot VALUES (?,?,?,?,?,?,?,?,?,?)", source.id(), source.version(), BATCH_ID,
                source.kind(), source.location(), "AVAILABLE", value.json(), value.sha256(), null, null);
    }

    private boolean batchExists(Connection c) throws Exception {
        try (PreparedStatement statement = c.prepareStatement("SELECT 1 FROM adj_local_source_batch WHERE batch_id=?")) {
            statement.setString(1, BATCH_ID);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void verifyBatch(Connection c, String digest, int count) throws Exception {
        try (PreparedStatement statement = c.prepareStatement("SELECT records_sha256,record_count FROM adj_local_source_batch WHERE batch_id=?")) {
            statement.setString(1, BATCH_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !digest.equals(result.getString(1)) || count != result.getInt(2)) {
                    throw new IllegalStateException("Existing adjudication source batch has different content");
                }
            }
        }
    }

    private void verifyRows(Connection c, int cases, int materials, int legal) throws Exception {
        if (count(c, "adj_local_case") != cases || count(c, "adj_local_material") != materials
                || count(c, "adj_local_legal_snapshot") != legal || count(c, "adj_local_template_snapshot") != 1) {
            throw new IllegalStateException("Existing adjudication source batch is incomplete");
        }
    }

    private int count(Connection c, String table) throws Exception {
        try (PreparedStatement statement = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE batch_id=?")) {
            statement.setString(1, BATCH_ID);
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw new IllegalStateException("Cannot count local source records"); return result.getInt(1); }
        }
    }

    private static String digest(List<? extends Payload<?>> cases, List<? extends Payload<?>> materials,
            List<? extends Payload<?>> legal, Payload<?> template) {
        List<String> values = new ArrayList<>();
        cases.forEach(value -> values.add(value.sha256())); materials.forEach(value -> values.add(value.sha256()));
        legal.forEach(value -> values.add(value.sha256())); values.add(template.sha256());
        return AdjudicationLocalSourceCodec.sha256(String.join("|", values));
    }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private record Payload<T>(T value, String json, String sha256) {}
    public record ImportResult(int casesImported, int materialsImported, int legalImported, int templatesImported, boolean batchCreated) {}
}
