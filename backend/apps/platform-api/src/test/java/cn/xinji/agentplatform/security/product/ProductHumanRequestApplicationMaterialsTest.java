package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete request with controlled original bytes and real source/grant authority, not live source licensing. */
class ProductHumanRequestApplicationMaterialsTest {
    @TempDir Path root;
    private ProductHumanRequestApplicationFixture fixture;
    private String rawSha;
    private final ProductHumanRequestApplication.Command command = new ProductHumanRequestApplication.Command(7, null,
            List.of(new ProductHumanMaterialReferences.Selection("source-1", "document-1", "document-v1", Set.of("TITLE"))),
            "Clarify this document", "ORDINARY_ASSISTANCE");

    @BeforeEach void setup() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        var mapper = fixture.mapper;
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
        fixture.setup(root, true, "component-capture:2026-10-13",
                ProductCapturedArchive.digest(manifest), ProductCapturedArchive.digest(records));
    }

    @Test void titleOnlyRequestUsesDisplayAndSaveWithoutComparisonPermission() {
        assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE action_code='COMPARE'", Integer.class)).isZero();
        var result = request();
        var material = result.body().manifest().references().materials().getFirst();
        assertThat(material.rawSha()).isEqualTo(rawSha);
        assertThat(material.fieldLocators()).containsEntry("TITLE", "capture/raw.json#title");
        assertThat(result.body().manifest().references().sources().getFirst().use().comparisonFields()).isEmpty();
        assertThat(result.body().manifest().bounds().auditSaveUntil()).isEqualTo(fixture.base.task.expiresAt());
        assertThat(result.receipt().auditUntil()).isEqualTo(fixture.base.task.expiresAt());
        assertThat(fixture.base.db.queryForObject("SELECT manifest_json FROM con6_human_package", String.class))
                .doesNotContain("controlled original title", fixture.base.token);
        rows(1);
        verifyNoInteractions(fixture.comparisons);
    }

    @Test void displayPermissionCannotReplaceMissingSaveRights() throws Exception {
        fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true, "component-capture:2026-10-13",
                ProductCapturedArchive.digest(Files.readAllBytes(root.resolve("capture/manifest.json"))),
                ProductCapturedArchive.digest(Files.readAllBytes(root.resolve("capture/records.json"))), false);
        var base = fixture.base;
        var watermark = new ProductSourceWatermark(base.db, base.authority, new TransactionTemplate(base.manager));
        var source = new ProductSourceAvailability(base.db, watermark, base.clock);
        assertThat(source.requireFields("source-1", Set.of("TITLE"), "DISPLAY").sourceId()).isEqualTo("source-1");
        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source approval or field use is unavailable");
        rows(0);
    }

    @Test void changedOriginalAfterFourTableWriteRollsBackTheEntireRequest() {
        var changed = new AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            // Cleanup verification runs on the transaction thread, so this observes its uncommitted writes.
            // Raw verification uses virtual threads whose independent connections cannot observe those rows.
            if (fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_package", Integer.class) == 1
                    && changed.compareAndSet(false, true)) {
                rows(1);
                Files.writeString(root.resolve("capture/raw.json"), "changed external original");
            }
            return result;
        }).when(fixture.cleanup).assertUnchanged(any());
        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Source evidence digest changed");
        assertThat(changed).isTrue();
        rows(0);
    }

    @Test void shortDisplayGrantDoesNotBecomeSourceSaveRetention() {
        Instant shortUntil = fixture.base.clock.instant().plusSeconds(50);
        renewScope(1, shortUntil, "short-scope");
        var result = request();
        var manifest = result.body().manifest();
        assertThat(manifest.references().materials().getFirst().source().validUntil()).isEqualTo(shortUntil);
        assertThat(manifest.references().sources().getFirst().rights().saveUntil()).isEqualTo(fixture.base.task.expiresAt());
        assertThat(manifest.bounds().sourceSaveUntil()).isEqualTo(manifest.references().facts().expiresAt()).isAfter(shortUntil);
        assertThat(manifest.bounds().sourceUseUntil()).isAfter(shortUntil);
        assertThat(manifest.bounds().auditSaveUntil()).isEqualTo(fixture.base.task.expiresAt());
        assertThat(result.receipt().auditUntil()).isEqualTo(fixture.base.task.expiresAt());
        rows(1);
    }

    @Test void sourceSaveBeforeTaskWriteEndRejectsBeforeAnyRequestRowsAreInserted() {
        Instant saveUntil = fixture.base.clock.instant().plusSeconds(50);
        fixture.base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE field_code='TITLE' AND use_code='SAVE'",
                Timestamp.from(saveUntil));
        // Self-authored resealed fixture input, not a source decision or a runtime rights change.
        String digest = ProductSourceHistoryBatch.digest(fixture.base.db, "source-1", true);
        fixture.base.db.update("UPDATE con4_source_current SET history_sha=? WHERE source_id='source-1'", digest);
        fixture.base.authority.update("UPDATE id4_source_watermark SET history_sha=? WHERE source_id='source-1'", digest);
        assertThatThrownBy(this::request).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Task command lifetime exceeds its permitted receipt retention");
        rows(0);
    }

    @Test void unsealedSourceSaveDriftAfterWriteRollsBackAllFourRequestRows() {
        var changed = new AtomicBoolean();
        Instant saveUntil = fixture.base.clock.instant().plusSeconds(50);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_package", Integer.class) == 1
                    && changed.compareAndSet(false, true)) {
                rows(1);
                // An unsealed field mutation must fail the independent history guard, not become new rights.
                fixture.base.db.update("UPDATE con4_source_field_use SET valid_until=? WHERE field_code='TITLE' AND use_code='SAVE'",
                        Timestamp.from(saveUntil));
            }
            return result;
        }).when(fixture.cleanup).assertUnchanged(any());
        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class)
                .hasMessage("Source history differs from independent authority").hasNoCause();
        assertThat(changed).isTrue();
        rows(0);
        assertThat(fixture.base.db.queryForObject("SELECT valid_until FROM con4_source_field_use "
                + "WHERE field_code='TITLE' AND use_code='SAVE'", Timestamp.class).toInstant())
                .isEqualTo(fixture.base.task.expiresAt());
    }

    @Test void reauthorizedSameOriginalReplaysImmutableHistoricalPacketWithCurrentScope() {
        var original = request();
        var oldScope = original.body().manifest().references().materials().getFirst().source().scopeVersion();
        var renewed = renewScope(oldScope, fixture.base.clock.instant().plusSeconds(200), "renew-current-scope");
        assertThat(renewed.scopeVersion()).isGreaterThan(oldScope);
        var replay = request();
        assertThat(replay).isEqualTo(original);
        assertThat(replay.body().manifest().references().materials().getFirst().source().scopeVersion()).isEqualTo(oldScope);
        rows(1);
    }

    private ProductSourceScopeGrants.Receipt renewScope(long expectedVersion, Instant until, String requestId) {
        var base = fixture.base;
        var transactions = new TransactionTemplate(base.manager);
        var grants = new ProductGrants(base.db, transactions, base.clock);
        var watermark = new ProductSourceWatermark(base.db, base.authority, transactions);
        var scopes = new ProductSourceScopeGrants(base.db, transactions, new JdbcProductTaskDirectory(base.db, base.clock),
                grants, base.watermark, watermark, null, base.clock, "https://identity.example.org");
        return scopes.authorizeVerified(new ProductSourceScopeGrants.Input(base.task.id(), "member", "source-1", 7, 1,
                until, requestId, expectedVersion), "maintainer", base.task.organization());
    }

    private ProductHumanRequestApplication.Result request() {
        return fixture.application.request(fixture.base.token, fixture.base.task.id(), "material-request", command);
    }

    private void rows(int expected) {
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(fixture.base.db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(expected);
    }
}
