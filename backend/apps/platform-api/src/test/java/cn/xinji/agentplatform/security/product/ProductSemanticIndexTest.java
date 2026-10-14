package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSemanticIndexTest {
    private static final Instant NOW = Instant.parse("2026-09-29T06:00:00Z");
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test void readsAllSnapshotVectorsInOneQueryAndStillRejectsChangedEvidence() throws Exception {
        Fixture fixture=fixture(new DeterministicProvider(false));
        fixture.index.build("source","batch-build","maintainer#certificate");
        org.mockito.Mockito.clearInvocations(fixture.db);
        fixture.index.validate(fixture.active);
        var matches=fixture.index.search("source","battery storage",2);
        assertThat(matches).extracting(ProductSemanticIndex.Match::semanticScore).containsExactly(1.0,0.0);
        org.mockito.Mockito.verify(fixture.db,org.mockito.Mockito.times(2)).query(
                org.mockito.ArgumentMatchers.startsWith("SELECT b.document_id"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class),
                org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.eq("capture-v1"),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("TITLE"));
        fixture.db.update("UPDATE con4_document_vector_binding SET locator_ref='wrong#title'");
        assertThatThrownBy(()->fixture.index.validate(fixture.active))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class).hasMessageContaining("incomplete");
        assertThatThrownBy(()->fixture.index.search("source","battery storage",2))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class);
    }

    @Test void versionedIndexBuildsIncrementallyAndRanksByRealVector() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        var first = fixture.index.build("source", "build-1", "maintainer#certificate");
        assertThat(first.documentCount()).isEqualTo(2);
        assertThat(first.embeddedCount()).isEqualTo(2);
        assertThat(first.reusedCount()).isZero();
        assertThat(first.modelVersion()).contains("paraphrase-multilingual-MiniLM-L12-v2");
        assertThat(fixture.index.build("source", "build-1", "maintainer#certificate")).isEqualTo(first);

        var second = fixture.index.build("source", "build-2", "maintainer#certificate");
        assertThat(second.embeddedCount()).isZero();
        assertThat(second.reusedCount()).isEqualTo(2);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector", Integer.class))
                .isEqualTo(2);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector_binding", Integer.class))
                .isEqualTo(2);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build", Integer.class))
                .isEqualTo(2);

        var matches = fixture.index.search("source", "battery storage", 2);
        assertThat(matches).extracting(ProductSemanticIndex.Match::documentId)
                .containsExactly("battery-document", "coating-document");
        assertThat(matches.getFirst().semanticScore()).isGreaterThan(matches.getLast().semanticScore());
    }

    @Test void unchangedDocumentBytesReuseVectorAcrossSourceSnapshotsWithNewLocator() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        fixture.index.build("source", "build-1", "maintainer#certificate");
        var next = new ProductSourceAvailability.Active("source", "contract", "capture-v2",
                "decision", 2, 3, "manifest-v2.json", SHA_A, SHA_B);
        when(fixture.availability.require("source", "TITLE", "QUERY")).thenReturn(next);
        when(fixture.titles.load(next)).thenReturn(List.of(
                document("battery-document", "capture-v2", SHA_A, "battery storage"),
                document("coating-document", "capture-v2", SHA_B, "protective coating")));

        var second = fixture.index.build("source", "build-v2", "maintainer#certificate");
        assertThat(second.embeddedCount()).isZero();
        assertThat(second.reusedCount()).isEqualTo(2);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector", Integer.class))
                .isEqualTo(2);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector_binding", Integer.class))
                .isEqualTo(4);
        assertThat(fixture.index.search("source", "battery", 2).getFirst().sourceVersion())
                .isEqualTo("capture-v2");
    }

    @Test void changedBytesIncompleteIndexAndProviderFailureCloseWithoutFalseSuccess() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        fixture.index.build("source", "build-1", "maintainer#certificate");
        when(fixture.titles.load(fixture.active)).thenReturn(List.of(
                document("battery-document", SHA_B, "different invention"),
                document("coating-document", SHA_B, "protective coating")));
        assertThatThrownBy(() -> fixture.index.build("source", "build-conflict", "maintainer#certificate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts");
        fixture.db.update("DELETE FROM con4_document_vector_binding WHERE document_id='coating-document'");
        fixture.db.update("DELETE FROM con4_document_vector WHERE document_id='coating-document'");
        assertThatThrownBy(() -> fixture.index.search("source", "battery", 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");

        Fixture failed = fixture(new DeterministicProvider(true));
        assertThatThrownBy(() -> failed.index.build("source", "failed-build", "maintainer#certificate"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provider unavailable");
        assertThat(failed.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build", Integer.class)).isZero();
        assertThat(failed.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector", Integer.class)).isZero();
    }

    @Test void preflightRejectsMissingCorruptAndNonFiniteVectorsWithoutCallingEmbedding() throws Exception {
        var provider = org.mockito.Mockito.spy(new DeterministicProvider(false));
        Fixture fixture = fixture(provider);
        fixture.index.build("source", "preflight-build", "maintainer#certificate");
        org.mockito.Mockito.clearInvocations(provider);
        fixture.index.validate(fixture.active);
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).embed(org.mockito.ArgumentMatchers.anyList());
        fixture.db.update("UPDATE con4_document_vector SET vector_sha=?", SHA_B);
        assertThatThrownBy(() -> fixture.index.validate(fixture.active))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class);
        byte[] invalid = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(Float.NaN).putFloat(0).putFloat(0).array();
        String invalidSha = ProductVectorCodec.sha(invalid);
        fixture.db.update("UPDATE con4_document_vector SET vector_data=?,vector_sha=?", invalid, invalidSha);
        fixture.db.update("UPDATE con4_document_vector_binding SET vector_sha=?", invalidSha);
        assertThatThrownBy(() -> fixture.index.validate(fixture.active))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class).hasMessage("Semantic vector values changed");
        fixture.db.update("DELETE FROM con4_document_vector_binding");
        assertThatThrownBy(() -> fixture.index.validate(fixture.active))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class).hasMessageContaining("incomplete");
    }

    @Test void preflightDoesNotClassifyDatabaseOrChangedAuthorityAsSourceIndexFault() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        fixture.db.execute("DROP TABLE con4_document_vector_binding");
        assertThatThrownBy(() -> fixture.index.validate(fixture.active))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .isNotInstanceOf(ProductSemanticIndex.Unavailable.class);
        when(fixture.availability.require("source", "TITLE", "QUERY"))
                .thenThrow(new IllegalStateException("Current approval revoked"));
        assertThatThrownBy(() -> fixture.index.validate(fixture.active))
                .hasMessage("Current approval revoked").isNotInstanceOf(ProductSemanticIndex.Unavailable.class);
    }

    @Test void allSelectedFieldsBuildIndependentlyReplayAndReadWithoutTitleVectorReuse() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        fixture.index.build("source","title-build","mechanical-C-maintainer");
        for (String field : List.of("ABSTRACT","CLAIMS","DESCRIPTION")) {
            when(fixture.availability.require("source",field,"QUERY")).thenReturn(fixture.active);
            when(fixture.titles.loadField(fixture.active,field)).thenReturn(List.of(
                    fieldDocument("battery-document",SHA_A,field,"protective coating"),
                    fieldDocument("coating-document",SHA_B,field,"battery storage")));
            var receipt = fixture.index.buildField("source",field,"build-" + field,"mechanical-C-maintainer");
            assertThat(receipt.field()).isEqualTo(field);
            assertThat(receipt.modelVersion()).contains(PatentEmbeddingProvider.COMPLETE_REPRESENTATION_VERSION)
                    .endsWith("/W128/D3");
            assertThat(receipt.documentCount()).isEqualTo(2);
            assertThat(receipt.embeddedCount()).isEqualTo(2);
            assertThat(fixture.index.buildField("source",field,"build-" + field,"mechanical-C-maintainer"))
                    .isEqualTo(receipt);
            fixture.index.validateField(fixture.active,field);
            assertThat(fixture.index.searchField("source",field,"battery",2)).extracting(ProductSemanticIndex.Match::documentId)
                    .containsExactly("coating-document","battery-document");
            var reused = fixture.index.buildField("source",field,"incremental-" + field,"mechanical-C-maintainer");
            assertThat(reused.embeddedCount()).isZero(); assertThat(reused.reusedCount()).isEqualTo(2);
        }
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector",Integer.class)).isEqualTo(8);
        assertThat(fixture.index.search("source","battery",2).getFirst().documentId()).isEqualTo("battery-document");
    }

    @Test void fieldReplayConflictCurrentPermissionAndWrongProjectionNeverProduceSuccessfulBuild() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        fixture.index.build("source","same-key","mechanical-C-maintainer");
        when(fixture.availability.require("source","ABSTRACT","QUERY")).thenReturn(fixture.active);
        assertThatThrownBy(() -> fixture.index.buildField("source","ABSTRACT","same-key","mechanical-C-maintainer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        when(fixture.titles.loadField(fixture.active,"ABSTRACT")).thenReturn(List.of(document("doc",SHA_A,"title only")));
        assertThatThrownBy(() -> fixture.index.buildField("source","ABSTRACT","wrong-projection","mechanical-C-maintainer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("selected");
        when(fixture.availability.require("source","CLAIMS","QUERY")).thenThrow(new IllegalStateException("Current field permission unavailable"));
        assertThatThrownBy(() -> fixture.index.buildField("source","CLAIMS","not-permitted","mechanical-C-maintainer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("permission");
        assertThatThrownBy(() -> fixture.index.buildField("source","LEGAL_STATUS","unsupported","mechanical-C-maintainer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build",Integer.class)).isEqualTo(1);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector",Integer.class)).isEqualTo(2);
    }

    @Test void sourceEpochChangeDuringFieldEmbeddingPreventsAllWrites() throws Exception {
        Fixture fixture = fixture(new DeterministicProvider(false));
        when(fixture.availability.require("source","ABSTRACT","QUERY")).thenReturn(fixture.active);
        when(fixture.titles.loadField(fixture.active,"ABSTRACT")).thenReturn(List.of(fieldDocument("doc",SHA_A,"ABSTRACT","battery storage")));
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("Current source watermark changed"))
                .when(fixture.watermark).assertUnchanged("source",fixture.active.epoch());
        assertThatThrownBy(() -> fixture.index.buildField("source","ABSTRACT","changed-source","mechanical-C-maintainer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("watermark");
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build",Integer.class)).isZero();
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector",Integer.class)).isZero();
    }

    @Test void providerWithoutCompleteRepresentationCannotFallBackToTitleEmbedding() throws Exception {
        var delegate = new DeterministicProvider(false);
        var unsupported = org.mockito.Mockito.spy(new PatentEmbeddingProvider() {
            @Override public Model model() { return delegate.model(); }
            @Override public List<float[]> embed(List<String> texts) { return delegate.embed(texts); }
        });
        Fixture fixture = fixture(unsupported);
        when(fixture.availability.require("source","CLAIMS","QUERY")).thenReturn(fixture.active);
        when(fixture.titles.loadField(fixture.active,"CLAIMS")).thenReturn(List.of(fieldDocument("doc",SHA_A,"CLAIMS","battery storage")));
        assertThatThrownBy(() -> fixture.index.buildField("source","CLAIMS","unsupported-complete","mechanical-C-maintainer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Complete semantic");
        org.mockito.Mockito.verify(unsupported,org.mockito.Mockito.never()).embed(org.mockito.ArgumentMatchers.anyList());
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_document_vector",Integer.class)).isZero();
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build",Integer.class)).isZero();
    }

    @Test void currentAdmissionNeedsItsOwnBuildEvenWhenAllVectorBytesCanBeReused() throws Exception {
        var provider=org.mockito.Mockito.spy(new DeterministicProvider(false));
        Fixture f=fixture(provider);
        f.index.build("source","old-build","maintainer");
        org.mockito.Mockito.clearInvocations(provider);
        var newer=new ProductSourceAvailability.Active("source","contract","capture-v1",
                "decision",3,4,"manifest.json",SHA_A,SHA_B);
        when(f.availability.require("source","TITLE","QUERY")).thenReturn(newer);
        var originalDocuments=f.titles.load(f.active);
        when(f.titles.load(newer)).thenReturn(originalDocuments);
        assertThatThrownBy(() -> f.index.validateField(newer,"TITLE"))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class).hasMessageContaining("current admission");
        assertThatThrownBy(() -> f.index.search("source","battery",2))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class);
        org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).embed(org.mockito.ArgumentMatchers.anyList());
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build",Integer.class)).isEqualTo(1);
        var built=f.index.build("source","new-explicit-build","maintainer");
        assertThat(built.embeddedCount()).isZero();
        f.index.validateField(newer,"TITLE");
        assertThat(f.index.search("source","battery",2)).hasSize(2);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build",Integer.class)).isEqualTo(2);
    }

    @Test void absentFutureOrCorruptBuildNeverPassesOnValidVectors() throws Exception {
        for(String mutation:List.of("DELETE FROM con4_vector_build",
                "UPDATE con4_vector_build SET completed_at=TIMESTAMP '2030-01-01 00:00:00'",
                "UPDATE con4_vector_build SET document_count=3,embedded_count=3",
                "UPDATE con4_vector_build SET build_sha='" + "0".repeat(64) + "'")) {
            Fixture f=fixture(new DeterministicProvider(false));
            f.index.build("source","build","maintainer"); f.db.update(mutation);
            assertThatThrownBy(() -> f.index.validate(f.active)).isInstanceOf(ProductSemanticIndex.Unavailable.class);
            assertThatThrownBy(() -> f.index.search("source","battery",2)).isInstanceOf(ProductSemanticIndex.Unavailable.class);
        }
    }

    @Test void matchingAdmissionWithConflictingBuildsFailsButIdenticalReusedReceiptsRemainValid() throws Exception {
        Fixture f=fixture(new DeterministicProvider(false));
        f.index.build("source","first","maintainer"); f.index.build("source","second","maintainer");
        f.index.validate(f.active);
        f.db.update("UPDATE con4_vector_build SET build_sha=? WHERE request_id='second'","0".repeat(64));
        assertThatThrownBy(() -> f.index.validate(f.active)).isInstanceOf(ProductSemanticIndex.Unavailable.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM con4_vector_build",Integer.class)).isEqualTo(2);
    }

    private static PatentLexicalQuery.Document fieldDocument(String id,String sha,String field,String text) {
        return new PatentLexicalQuery.Document("source",id,"v1","capture-v1",sha,
                "capture-v1/raw/" + id + ".json#" + field,Map.of(field,text));
    }

    private static Fixture fixture(PatentEmbeddingProvider provider) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:semantic-index-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate db = org.mockito.Mockito.spy(new JdbcTemplate(source));
        db.execute("CREATE TABLE con4_source_admission(decision_id VARCHAR(36),source_id VARCHAR(200),"
                + "contract_version VARCHAR(100),PRIMARY KEY(decision_id),"
                + "UNIQUE(decision_id,source_id,contract_version))");
        db.update("INSERT INTO con4_source_admission VALUES('decision','source','contract')");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V067__product_search_semantic_index.sql")));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V070__product_semantic_vector_snapshot_binding.sql")));
        }
        var active = new ProductSourceAvailability.Active("source", "contract", "capture-v1",
                "decision", 1, 2, "manifest.json", SHA_A, SHA_B);
        ProductSourceAvailability availability = mock(ProductSourceAvailability.class);
        when(availability.require("source", "TITLE", "QUERY")).thenReturn(active);
        ProductSourceWatermark watermark = mock(ProductSourceWatermark.class);
        ProductCollectedPatentTitles titles = mock(ProductCollectedPatentTitles.class);
        when(titles.load(active)).thenReturn(List.of(
                document("battery-document", SHA_A, "battery storage"),
                document("coating-document", SHA_B, "protective coating")));
        var index = new ProductSemanticIndex(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), availability,
                watermark, titles, provider, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(db, index, titles, availability, active,watermark);
    }

    private static PatentLexicalQuery.Document document(String id, String sha, String title) {
        return document(id, "capture-v1", sha, title);
    }

    private static PatentLexicalQuery.Document document(String id, String sourceVersion,
            String sha, String title) {
        return new PatentLexicalQuery.Document("source", id, "v1", sourceVersion, sha,
                sourceVersion + "/raw/" + id + ".json#title", Map.of("TITLE", title));
    }

    private record Fixture(JdbcTemplate db, ProductSemanticIndex index,
                           ProductCollectedPatentTitles titles,
                           ProductSourceAvailability availability,
                           ProductSourceAvailability.Active active, ProductSourceWatermark watermark) { }

    private static final class DeterministicProvider implements PatentEmbeddingProvider {
        private final boolean unavailable;
        private DeterministicProvider(boolean unavailable) { this.unavailable = unavailable; }
        @Override public Model model() {
            return new Model("TEST_PROVIDER", "paraphrase-multilingual-MiniLM-L12-v2", "revision",
                    SHA_A, SHA_B, 3, 128, "MEAN_POOL_L2/v1", "Apache-2.0");
        }
        @Override public List<float[]> embed(List<String> texts) {
            if (unavailable) throw new IllegalStateException("provider unavailable");
            return texts.stream().map(text -> text.contains("battery")
                    ? new float[] {1, 0, 0} : new float[] {0, 1, 0}).toList();
        }
        // Mechanical C input has no truncating tokenizer; this is not a deployed model provider.
        @Override public List<float[]> embedComplete(List<String> texts) { return embed(texts); }
    }
}
