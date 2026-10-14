package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRetrievalConfidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Controlled approval states are C only; the input bytes are unchanged real collected patents. */
class ProductPatentSearchSourceBatchTest {
    @Test void indexFaultExcludesWholeSourceButAuthorityAndSharedFailuresPropagate() {
        var first = new ProductSourceAvailability.Active("first", "contract", "v1", "d1", 1, 2,
                "manifest.json", "a".repeat(64), "b".repeat(64));
        var second = new ProductSourceAvailability.Active("second", "contract", "v1", "d2", 1, 2,
                "manifest.json", "a".repeat(64), "b".repeat(64));
        var document = new cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery.Document(
                "second", "CN104900909A", "v1", "v1", "a".repeat(64), "title", Map.of("TITLE", "battery"));
        var batch = new ProductPatentSearchSourceBatch.Batch(List.of(first, second), List.of(document), Map.of());
        var index = mock(ProductSemanticIndex.class);
        doThrow(new ProductSemanticIndex.Unavailable("missing")).when(index).validate(second);
        var batches = new ProductPatentSearchSourceBatch();
        var partial = batches.validateIndexes(batch, index);
        assertThat(partial.documents()).isEmpty();
        assertThat(partial.successfulSources()).containsExactly("first");
        assertThat(partial.failures()).containsExactlyEntriesOf(Map.of("second", "SOURCE_INDEX_UNAVAILABLE"));
        doThrow(new ProductSemanticIndex.Unavailable("missing")).when(index).validate(first);
        assertThatThrownBy(() -> batches.validateIndexes(batch, index))
                .hasMessage("All requested source indexes are unavailable");
        doThrow(new IllegalStateException("Shared model unavailable")).when(index).validate(first);
        assertThatThrownBy(() -> batches.validateIndexes(batch, index)).hasMessage("Shared model unavailable");
        doThrow(new IllegalStateException("Current approval revoked")).when(index).validate(first);
        assertThatThrownBy(() -> batches.validateIndexes(batch, index)).hasMessage("Current approval revoked");
    }

    @Test void onlyContentFailureIsIsolatedAndFailedSourceCannotReachRecall() throws Exception {
        var mapper=new ObjectMapper();Path root=Path.of("data/patents").toAbsolutePath();
        Path manifestPath=root.resolve("public-pilot-v1/manifest.json");
        var manifest=mapper.readTree(manifestPath.toFile());
        var readable=new ProductSourceAvailability.Active("readable","contract",manifest.path("datasetId").asText()
                +":"+manifest.path("generatedAt").asText(),"decision",1,2,"public-pilot-v1/manifest.json",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifestPath))),
                manifest.path("recordsSha256").asText());
        var unavailable=new ProductSourceAvailability.Active("unavailable","contract","not-readable",
                "decision",1,2,"missing/manifest.json","a".repeat(64),"b".repeat(64));
        var availability=mock(ProductSourceAvailability.class);
        when(availability.require("readable","TITLE","QUERY")).thenReturn(readable);
        when(availability.require("unavailable","TITLE","QUERY")).thenReturn(unavailable);
        var loader=new ProductCollectedPatentTitles(new ProductSourceEvidenceFiles(root,10_000_000),mapper);
        var batches=new ProductPatentSearchSourceBatch();
        var partial=batches.load(List.of("readable","unavailable"),ProductPatentSearchFilters.none(),availability,loader);
        assertThat(partial.snapshots()).hasSize(2);
        assertThat(partial.successfulSources()).containsExactly("readable");
        assertThat(partial.documents()).hasSize(44).allSatisfy(doc->assertThat(doc.sourceId()).isEqualTo("readable"));
        assertThat(partial.failures()).containsExactlyEntriesOf(Map.of("unavailable","SOURCE_CONTENT_UNAVAILABLE"));
        assertThat(partial.confidence(new PatentRetrievalConfidence.Assessment(PatentRetrievalConfidence.Status.NORMAL,
                "rule","reason")).reason()).isEqualTo("PARTIAL_SOURCE_COVERAGE");
        assertThatThrownBy(()->batches.load(List.of("unavailable"),ProductPatentSearchFilters.none(),availability,loader))
                .isInstanceOf(IllegalStateException.class).hasMessage("All requested source contents are unavailable");

        when(availability.require("readable","PUBLICATION_DATE","QUERY")).thenReturn(readable);
        when(availability.require("unavailable","PUBLICATION_DATE","QUERY")).thenReturn(unavailable);
        var empty=batches.load(List.of("readable","unavailable"),ProductPatentSearchFilters.validated(
                java.time.LocalDate.of(2030,1,1),java.time.LocalDate.of(2030,12,31),
                List.of(),List.of()),availability,loader);
        assertThat(empty.documents()).isEmpty();
        assertThat(empty.successfulSources()).containsExactly("readable");
        assertThat(empty.confidence(new PatentRetrievalConfidence().lexicalOnly(true)).status())
                .isEqualTo(PatentRetrievalConfidence.Status.LOW_CONFIDENCE);
        when(availability.require("unavailable","TITLE","QUERY"))
                .thenThrow(new IllegalStateException("Current approval revoked"));
        assertThatThrownBy(()->batches.load(List.of("readable","unavailable"),
                ProductPatentSearchFilters.none(),availability,loader)).hasMessage("Current approval revoked");
    }
}
