package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual collected-byte C only: no product-enabled source, grant, model, search or benchmark. */
class ProductPatentsViewSourceDocumentsTest {
    private static final Path ROOT = Path.of("data/patents").toAbsolutePath();
    private static final String REF = "patentsview-real-candidate-20261002-v2/manifest.json";
    private static final long BUDGET = 512L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static byte[] manifest;
    private static ProductPatentsViewSourceInventory.Result inspected;

    @BeforeAll static void deepInspectActualFullOriginals() throws Exception {
        manifest = Files.readAllBytes(ROOT.resolve(REF));
        inspected = new ProductPatentsViewSourceInventory(files(),MAPPER).inspect(REF,manifest);
    }
    private static ProductSourceEvidenceFiles files() { return new ProductSourceEvidenceFiles(ROOT,BUDGET); }
    private static ProductCollectedPatentTitles.CaptureSnapshot snapshot() {
        return new ProductCollectedPatentTitles.CaptureSnapshot(inspected.sourceId(),inspected.sourceVersion(),
                REF,inspected.recordsSha(),inspected.recordsSha());
    }

    @Test void fullArchivesRowsEnvelopeAndInventoryAreActuallyBound() {
        assertThat(inspected.records()).isEqualTo(20);
        assertThat(inspected.originals()).isEqualTo(277);
        assertThat(inspected.references()).hasSize(278).contains(REF,
                "patentsview-real-candidate-20261002-v2/raw/archives/g_patent.tsv.zip",
                "patentsview-real-candidate-20261002-v2/raw/archives/g_claims_1976.tsv.zip");
        assertThat(inspected.recordsSha()).isEqualTo(ProductCapturedArchive.digest(manifest));
        assertThat(inspected.provenance()).isEqualTo("https://zenodo.org/records/15062183");
        assertThat(inspected.sourceVersion()).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                .isEqualTo("CAPTURE_SHA256:" + inspected.recordsSha());
    }

    @Test void explicitFieldProjectionUsesExactEnvelopeByteTokensWithoutMissingFieldFallback() throws Exception {
        var adapter = new ProductPatentsViewSourceDocuments(files(),MAPPER);
        for (Set<String> selected : List.of(Set.of("TITLE"),Set.of("CLAIMS"),Set.of("TITLE","CLAIMS"))) {
            var capture = adapter.read(snapshot(),manifest,selected);
            assertThat(capture.documents(ProductPatentSearchFilters.none())).hasSize(20)
                    .allSatisfy(document -> assertThat(document.fields().keySet()).isEqualTo(selected));
            for (var entry : capture.entries().values()) {
                byte[] raw = files().verified(entry.rawRef(),entry.sha());
                for (String field : selected) {
                    String locator = entry.content().locators().get(field);
                    String[] offsets = locator.split("utf8=")[1].split(":");
                    int start = Integer.parseInt(offsets[0]), end = Integer.parseInt(offsets[1]);
                    assertThat(MAPPER.readTree(raw,start,end-start).asText()).isEqualTo(entry.content().values().get(field));
                }
                assertThat(entry.version()).isEqualTo("RAW_SHA256:" + entry.sha());
            }
        }
        assertThatThrownBy(() -> adapter.read(snapshot(),manifest,Set.of("ABSTRACT")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> adapter.read(snapshot(),manifest,Set.of("LEGAL_STATUS")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oldOrUnboundCaptureCannotSupplyCurrentContent() {
        var original = snapshot();
        var wrong = new ProductCollectedPatentTitles.CaptureSnapshot(original.sourceId(),original.sourceVersion(),
                original.manifestRef(),original.manifestSha(),"0".repeat(64));
        assertThatThrownBy(() -> new ProductPatentsViewSourceDocuments(files(),MAPPER).read(wrong,manifest))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("verification snapshot");
        assertThatThrownBy(() -> new ProductPatentsViewSourceInventory(new ProductSourceEvidenceFiles(ROOT,10_000_000),MAPPER)
                .inspect(REF,manifest)).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Archive reference or configured budget invalid");
    }

    @Test void recoveryReferencesIncludeFullNativeProofWithoutMistakingItForHupdOrSurechembl() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:patentsview-recovery-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_source_verification(source_id VARCHAR(200),source_version VARCHAR(100),"
                + "manifest_ref VARCHAR(500),manifest_sha VARCHAR(64),records_sha VARCHAR(64),"
                + "inventory_sha VARCHAR(64),record_count BIGINT,raw_count BIGINT)");
        db.update("INSERT INTO con4_source_verification VALUES(?,?,?,?,?,?,?,?)",inspected.sourceId(),inspected.sourceVersion(),
                REF,inspected.recordsSha(),inspected.recordsSha(),inspected.inventorySha(),inspected.records(),inspected.originals());
        assertThat(new ProductSourceRecoveryFiles(db,ROOT,BUDGET).references()).isEqualTo(inspected.references());
        db.update("UPDATE con4_source_verification SET manifest_ref=?","missing-native-manifest.json");
        assertThatThrownBy(() -> new ProductSourceRecoveryFiles(db,ROOT,BUDGET).verify()).isInstanceOf(IllegalStateException.class);
    }
}
