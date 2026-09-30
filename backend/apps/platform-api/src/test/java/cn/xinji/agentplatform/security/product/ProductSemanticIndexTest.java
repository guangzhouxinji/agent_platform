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

    private static Fixture fixture(PatentEmbeddingProvider provider) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:semantic-index-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(source);
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
        return new Fixture(db, index, titles, availability, active);
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
                           ProductSourceAvailability.Active active) { }

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
    }
}
