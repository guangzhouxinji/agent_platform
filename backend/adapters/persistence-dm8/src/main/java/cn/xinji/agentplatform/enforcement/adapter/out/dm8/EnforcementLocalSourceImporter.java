package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

public final class EnforcementLocalSourceImporter {
    public static final String BATCH_ID = "ENF-SYNTHETIC-LOCAL-V1";
    public static final String CATALOG_VERSION = "enforcement-local-source-v1";
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public EnforcementLocalSourceImporter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public ImportResult importSyntheticBaseline() {
        SyntheticEnforcementSourceAdapter seed = new SyntheticEnforcementSourceAdapter();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Payload> clues = payloads(seed.listClues());
                List<Payload> evidence = payloads(seed.listEvidence());
                SourceLocator rule = seed.activeRule();
                SourceLocator right = seed.historicalRightStatus("V0-SYN-CLUE-001").orElseThrow();
                String digest = datasetDigest(clues, evidence, rule, right);
                if (batchExists(connection)) {
                    verifyBatch(connection, digest, clues.size() + evidence.size() + 2);
                    verifyImportedRows(connection, clues.size(), evidence.size());
                    connection.commit();
                    return new ImportResult(0, 0, false);
                }
                insertBatch(connection, digest, clues.size() + evidence.size() + 2);
                for (int i = 0; i < clues.size(); i++) insertClue(connection, seed.listClues().get(i), clues.get(i));
                for (int i = 0; i < evidence.size(); i++) insertEvidence(connection, seed.listEvidence().get(i), evidence.get(i));
                execute(connection, "INSERT INTO enf_local_rule_snapshot VALUES (?,?,?,?,?)",
                        rule.sourceId(), rule.version(), BATCH_ID, rule.sourceSystem(), rule.location());
                execute(connection, "INSERT INTO enf_local_right_snapshot VALUES (?,?,?,?,?,?,?)",
                        right.sourceId(), "V0-SYN-CLUE-001", right.version(), BATCH_ID,
                        right.sourceSystem(), right.location(), "AVAILABLE");
                verifyImportedRows(connection, clues.size(), evidence.size());
                connection.commit();
                return new ImportResult(clues.size(), evidence.size(), true);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Local enforcement source import failed", failure);
        }
    }

    private <T> List<Payload> payloads(List<T> values) throws Exception {
        List<Payload> result = new ArrayList<>();
        for (T value : values) {
            String json = mapper.writeValueAsString(value);
            result.add(new Payload(json, EnforcementLocalSourceCodec.sha256(json)));
        }
        return result;
    }

    private void insertBatch(Connection c, String digest, int count) throws Exception {
        execute(c, "INSERT INTO enf_local_source_batch VALUES (?,?,?,?,?,?,?,?,?,?)",
                BATCH_ID, "ENF-SYNTHETIC-LOCAL", CATALOG_VERSION, "SYNTHETIC_LOCAL", count, digest,
                "INTERNAL_DEVELOPMENT_ONLY", "N", "COMPLETED", Timestamp.from(Instant.now()));
    }

    private void insertClue(Connection c, EnforcementSourcePort.ClueData value, Payload payload) throws Exception {
        SourceLocator source = value.source();
        execute(c, "INSERT INTO enf_local_clue VALUES (?,?,?,?,?,?,?,?)", source.sourceId(), source.version(),
                BATCH_ID, source.sourceSystem(), source.location(), "AVAILABLE", payload.json(), payload.sha256());
    }

    private void insertEvidence(Connection c, EnforcementSourcePort.EvidenceData value, Payload payload) throws Exception {
        SourceLocator source = value.source();
        execute(c, "INSERT INTO enf_local_evidence VALUES (?,?,?,?,?,?,?,?,?,?)", source.sourceId(), source.version(),
                BATCH_ID, value.relatedClueId(), source.sourceSystem(), source.location(), value.availability(),
                value.controlledResourceRef(), payload.json(), payload.sha256());
    }

    private boolean batchExists(Connection c) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM enf_local_source_batch WHERE batch_id=?")) {
            s.setString(1, BATCH_ID); try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private void verifyBatch(Connection c, String digest, int count) throws Exception {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT records_sha256,record_count FROM enf_local_source_batch WHERE batch_id=?")) {
            s.setString(1, BATCH_ID);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !digest.equals(r.getString(1)) || count != r.getInt(2)) {
                    throw new IllegalStateException("Existing enforcement source batch has different content");
                }
            }
        }
    }

    private void verifyImportedRows(Connection c, int clues, int evidence) throws Exception {
        if (count(c, "enf_local_clue") != clues
                || count(c, "enf_local_evidence") != evidence
                || count(c, "enf_local_rule_snapshot") != 1
                || count(c, "enf_local_right_snapshot") != 1) {
            throw new IllegalStateException("Existing enforcement source batch is incomplete");
        }
    }

    private int count(Connection c, String table) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE batch_id=?")) {
            s.setString(1, BATCH_ID);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Cannot count enforcement source records");
                return r.getInt(1);
            }
        }
    }

    private static String datasetDigest(List<Payload> clues, List<Payload> evidence, SourceLocator rule, SourceLocator right) {
        List<String> parts = new ArrayList<>();
        clues.forEach(value -> parts.add(value.sha256()));
        evidence.forEach(value -> parts.add(value.sha256()));
        parts.add(rule.toString()); parts.add(right.toString());
        return EnforcementLocalSourceCodec.sha256(String.join("|", parts));
    }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }

    private record Payload(String json, String sha256) {}
    public record ImportResult(int cluesImported, int evidenceImported, boolean batchCreated) {}
}
