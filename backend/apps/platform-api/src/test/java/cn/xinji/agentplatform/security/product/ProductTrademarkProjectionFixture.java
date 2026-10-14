package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.sha;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class ProductTrademarkProjectionFixture {
    static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z"), UNTIL = NOW.plusSeconds(3600);
    static final String GENERATED = NOW.toString(), VERSION = "dataset:" + GENERATED;
    final ObjectMapper json = new ObjectMapper();
    final JdbcDataSource source = new JdbcDataSource();
    final JdbcTemplate db;
    final TransactionTemplate transactions;
    final MutableClock clock = new MutableClock();
    final ProductTrademarkProjectionStore store;
    final ProductTrademarkProjectionQuery query;
    final ProductTrademarkProjectionStore.Context context = new ProductTrademarkProjectionStore.Context(
            "organization", "task", 7, "real-maintainer", "a".repeat(64), "decision", 3, 4,
            "source-retention", 2, "b".repeat(64), "c".repeat(64), "d".repeat(64), UNTIL);

    ProductTrademarkProjectionFixture() throws Exception {
        source.setURL("jdbc:h2:mem:trademark-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("db/dm8/V008__local_public_knowledge_base_v1.sql"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Repository migration files missing");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(Files.readAllBytes(path.resolve("db/dm8/V008__local_public_knowledge_base_v1.sql"))));
            // Only the nullable-column spelling differs; the V113 relations and checks are executed unchanged.
            String migration = Files.readString(path.resolve("db/dm8/V113__product_trademark_projection.sql"))
                    .replace("MODIFY mark_name NULL", "ALTER COLUMN mark_name DROP NOT NULL");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
        }
        db.update("INSERT INTO kb_source(source_id,source_name,entry_url,access_mode,authority_status,license_review_status,usage_decision,created_at) "
                + "VALUES('source','Isolated SQL test source','https://source.invalid','CONTROLLED','TEST_ONLY','TEST_ONLY','TEST_ONLY',CURRENT_TIMESTAMP)");
        store = new ProductTrademarkProjectionStore(db, json, clock); query = new ProductTrademarkProjectionQuery(db, store, clock);
    }

    <T> T inTransaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    ProductTrademarkProjectionStore.Selection project(Input input) {
        var receipt = inTransaction(() -> store.project(input, context, "original-key"));
        return new ProductTrademarkProjectionStore.Selection(input, receipt.id(), receipt.requestSha());
    }
    Input input(int count) { return input(count, detail -> { }); }
    Input input(int count, Consumer<ObjectNode> change) {
        return input(count,change,i->String.format(java.util.Locale.ROOT,"r%02d",i));
    }
    Input input(int count, Consumer<ObjectNode> change, java.util.function.IntFunction<String> ids) {
        ObjectNode records = json.createObjectNode().put("datasetId", "dataset").put("generatedAt", GENERATED);
        var rows = records.putArray("records");
        ObjectNode manifest = json.createObjectNode().put("datasetId", "dataset").put("generatedAt", GENERATED)
                .put("recordsFile", "records.json").put("recordCount", count);
        manifest.putObject("source").put("apiBaseUrl", "https://source.invalid"); var inventory = manifest.putArray("rawResponses");
        List<Artifact> originals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = ids.apply(i), file = "raw" + i + ".json";
            ObjectNode detail = json.createObjectNode().put("sourceRecordId", id).put("registrationNumber", i == 0 ? "00012A" : "number-" + i)
                    .put("name", "Brand %_ " + i).put("applicantAddress", "Original address").put("registrationDate", "2024-02-29")
                    .put("validUntil", "2034-02").put("sourceStatus", "source-native-status").put("sourceUpdatedAt", "Original source time");
            detail.putArray("applicants").add("Owner %_ A").add("Owner %_ B");
            var classes = detail.putArray("classes");
            for (String code : List.of("09", "42")) {
                var category = classes.addObject().put("code", code).put("name", "Class " + code);
                category.putArray("goodsServices").add("First item").add("Second item");
                category.putArray("similarGroups").add(code + "01").add(code + "02");
            }
            change.accept(detail);
            Artifact raw = artifact("capture/" + file, detail.toString().getBytes(StandardCharsets.UTF_8)); originals.add(raw);
            rows.addObject().put("sourceId", "source").put("sourceRecordId", id).put("sourceVersion", "RAW_SHA256:" + raw.sha256())
                    .put("capturedAt", GENERATED).put("detailRawFile", file).put("sourceRef", "source-ref-" + i);
            inventory.addObject().put("file", file).put("sha256", raw.sha256()).put("bytes", raw.byteLength());
        }
        Artifact record = artifact("capture/records.json", records.toString().getBytes(StandardCharsets.UTF_8));
        manifest.put("recordsSha256", record.sha256());
        ObjectNode nature = json.createObjectNode().put("schema", "TM-SOURCE-NATURE-V1").put("sourceId", "source")
                .put("contractVersion", "contract-v1").put("sourceVersion", VERSION).put("sourceNature", "RESTRICTED");
        return new Input("source", "contract-v1", VERSION, artifact("capture/manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8)),
                record, originals, ProductTrademarkCaptureMapper.MAPPING, artifact("capture/nature.json", nature.toString().getBytes(StandardCharsets.UTF_8)));
    }
    String versionId(String record) { return db.queryForObject("SELECT record_version_id FROM kb_record_version WHERE stable_record_key=?", String.class, record); }
    static Artifact artifact(String ref, byte[] bytes) { return new Artifact(ref, sha(bytes), bytes.length, bytes); }
    static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
