package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Controlled original files and real JWT/SQL; not actual source activation or business acceptance. */
class ProductHumanMaterialReferencesTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductHumanRequestLocksTest fixture = new ProductHumanRequestLocksTest();
    private ProductHumanMaterialReferences loader;
    private ProductSourceEvidenceFiles files;
    private String rawSha;
    private final ProductHumanMaterialReferences.Selection selection =
            new ProductHumanMaterialReferences.Selection("source-1", "document-1", "document-v1", Set.of("TITLE"));

    @BeforeEach void setup() throws Exception {
        Files.createDirectory(root.resolve("capture"));
        byte[] raw = mapper.writeValueAsBytes(Map.of("title", "controlled original title"));
        Files.write(root.resolve("capture/raw.json"), raw);
        rawSha = ProductCapturedArchive.digest(raw);
        byte[] records = mapper.writeValueAsBytes(Map.of("datasetId", "component-capture", "generatedAt", "2026-10-13",
                "records", List.of(Map.of("sourceRecordId", "document-1", "sourceVersion", "document-v1",
                    "detailRawFile", "raw.json", "title", "controlled original title"))));
        Files.write(root.resolve("capture/records.json"), records);
        byte[] manifest = mapper.writeValueAsBytes(Map.of("datasetId", "component-capture", "generatedAt", "2026-10-13",
                "recordsFile", "records.json", "recordCount", 1, "rawResponses",
                List.of(Map.of("type", "DETAIL", "file", "raw.json", "sha256", rawSha))));
        Files.write(root.resolve("capture/manifest.json"), manifest);
        fixture.setupCapture("component-capture:2026-10-13", "capture/manifest.json",
                ProductCapturedArchive.digest(manifest), ProductCapturedArchive.digest(records));
        files = spy(new ProductSourceEvidenceFiles(root, 1024 * 1024));
        loader = new ProductHumanMaterialReferences(new ProductCollectedPatentTitles(files, mapper));
    }

    @Test void bindsActualRawDigestLocatorAndExactScopeWithoutCopyingBodyOrGrant() {
        var reference = read(selection);
        assertThat(reference.rawSha()).isEqualTo(rawSha);
        assertThat(reference.fieldLocators()).containsEntry("TITLE", "capture/raw.json#title");
        assertThat(reference.source().source().sourceVersion()).isEqualTo("component-capture:2026-10-13");
        assertThat(reference.source().scopeVersion()).isEqualTo(1);
        assertThat(new ProductComparisonJson(mapper).canonical(reference)).doesNotContain("controlled original title", "grantId", "token");
    }

    @ParameterizedTest @ValueSource(strings = {"document", "version", "field", "raw", "manifest", "records"})
    void unknownCoordinatesOrChangedOriginalsRefuseInsteadOfSavingFalseReferences(String fault) throws Exception {
        var chosen = selection;
        switch (fault) {
            case "document" -> chosen = new ProductHumanMaterialReferences.Selection("source-1", "other", "document-v1", Set.of("TITLE"));
            case "version" -> chosen = new ProductHumanMaterialReferences.Selection("source-1", "document-1", "other", Set.of("TITLE"));
            case "field" -> chosen = new ProductHumanMaterialReferences.Selection("source-1", "document-1", "document-v1", Set.of("ABSTRACT"));
            case "raw", "manifest", "records" -> Files.writeString(root.resolve("capture/" + fault + ".json"), "changed bytes");
            default -> throw new AssertionError(fault);
        }
        var finalChoice = chosen;
        assertThatThrownBy(() -> read(finalChoice)).isInstanceOf(IllegalStateException.class);
    }

    @Test void pendingAuthorityAfterLastOriginalReadRollsBackAllFourRequestTables() {
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            byte[] bytes = (byte[]) call.callRealMethod();
            if ("capture/raw.json".equals(call.getArgument(0)) && reads.incrementAndGet() == 2)
                fixture.base.authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id='changed'");
            return bytes;
        }).when(files).verified(anyString(), anyString());
        var base = fixture.base;
        var store = new ProductHumanRequestStore(base.db, mapper, base.clock);
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), fixture.locks,
                context -> List.of(selection.use()), (context, plan) -> {
                    store.save(context, base.manifest(context), base.requestKey());
                    return loader.load(context, plan, selection, base.decoder.decode(base.token));
                })).isInstanceOf(IllegalStateException.class);
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isZero();
    }

    @Test void savedPlanCannotReadOutsideItsOriginalTransaction() {
        var base = fixture.base;
        var plan = base.transaction.executeRequest(base.token, base.task.id(), fixture.locks,
                context -> List.of(selection.use()), (context, held) -> held);
        var context = new ProductHumanTaskTransaction.Context(base.task,
                new cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling.Actor(
                    "https://identity.example.org", "member",
                    cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling.ActorKind.HUMAN));
        assertThatThrownBy(() -> loader.load(context, plan, selection, base.decoder.decode(base.token)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> base.transaction.execute(base.token, base.task.id(), current ->
                loader.load(current, plan, selection, base.decoder.decode(base.token)))).isInstanceOf(IllegalStateException.class);
    }

    private ProductHumanMaterialReferences.Reference read(ProductHumanMaterialReferences.Selection chosen) {
        var base = fixture.base;
        return base.transaction.executeRequest(base.token, base.task.id(), fixture.locks,
                context -> List.of(selection.use()), (context, plan) -> loader.load(context, plan, chosen, base.decoder.decode(base.token)));
    }
}
