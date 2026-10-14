package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real original projection plus mechanical permission/index faults; no product admission or model evaluation. */
class ProductPatentSearchFieldBatchTest {
    private final Set<String> selected = Set.of("ABSTRACT","CLAIMS");
    private ProductSourceAvailability.Active active(String source) {
        return new ProductSourceAvailability.Active(source,"mechanical-C","v1","decision-C",1,2,
                "manifest.json","a".repeat(64),"b".repeat(64));
    }
    @Test void realNativeSourceIsProjectedOnceWithFiltersAndExactSelectedFields() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules(); var root = Path.of("data/patents").toAbsolutePath();
        String ref = "hupd-real-candidate-20261002-v2/manifest.json";
        byte[] bytes = Files.readAllBytes(root.resolve(ref)); var tree = mapper.readTree(bytes);
        String sha = ProductCapturedArchive.digest(bytes);
        var source = new ProductHupdSourceInventory(new ProductSourceEvidenceFiles(root,10_000_000),mapper).inspect(ref,bytes);
        var active = new ProductSourceAvailability.Active(source.sourceId(),"mechanical-C",source.sourceVersion(),
                "decision-C",1,2,ref,sha,sha);
        var availability = mock(ProductSourceAvailability.class);
        for (String field : selected) when(availability.require(active.sourceId(),field,"QUERY")).thenReturn(active);
        var loader = new ProductCollectedPatentTitles(new ProductSourceEvidenceFiles(root,10_000_000),mapper);
        var batch = new ProductPatentSearchSourceBatch().loadFields(List.of(active.sourceId()),
                ProductPatentSearchFilters.none(),availability,loader,selected);
        assertThat(batch.documents()).hasSize(20).allSatisfy(doc -> assertThat(doc.fields()).containsOnlyKeys(selected));
        assertThat(batch.failures()).isEmpty();
        assertThat(batch.fieldEvidence()).hasSize(20).allSatisfy(item -> {
            assertThat(item.fieldLocators()).containsOnlyKeys(selected);
            assertThat(item.fieldLocators().get("CLAIMS")).contains("/claims&utf8=");
        });
        when(availability.require(active.sourceId(),"PUBLICATION_DATE","QUERY")).thenReturn(active);
        var empty = new ProductPatentSearchSourceBatch().loadFields(List.of(active.sourceId()),
                ProductPatentSearchFilters.validated(LocalDate.of(2030,1,1),LocalDate.of(2030,12,31),List.of(),List.of()),
                availability,loader,selected);
        assertThat(empty.documents()).isEmpty(); assertThat(empty.failures()).isEmpty();
    }
    @Test void fieldAuthorityMismatchOrRevocationRejectsBeforeContentLoading() {
        var source = active("one"); var availability = mock(ProductSourceAvailability.class);
        var loader = mock(ProductCollectedPatentTitles.class);
        when(availability.require("one","ABSTRACT","QUERY")).thenReturn(source);
        when(availability.require("one","CLAIMS","QUERY")).thenReturn(new ProductSourceAvailability.Active(
                source.sourceId(),source.contractVersion(),source.sourceVersion(),source.decisionId(),2,2,
                source.manifestRef(),source.manifestSha(),source.recordsSha()));
        var batches = new ProductPatentSearchSourceBatch();
        assertThatThrownBy(() -> batches.loadFields(List.of("one"),ProductPatentSearchFilters.none(),availability,loader,selected))
                .hasMessage("Current source field snapshots differ");
        verifyNoInteractions(loader);
        when(availability.require("one","CLAIMS","QUERY")).thenThrow(new IllegalStateException("revoked"));
        assertThatThrownBy(() -> batches.loadFields(List.of("one"),ProductPatentSearchFilters.none(),availability,loader,selected))
                .hasMessage("revoked");
        verifyNoInteractions(loader);
    }
    @Test void oneMissingFieldIndexExcludesWholeSourceAndNeverUsesTitleIndex() {
        var first = active("one"); var second = active("two");
        var doc = new PatentLexicalQuery.Document("two","mechanical-C","v1","v1","a".repeat(64),
                "mechanical-locator",Map.of("ABSTRACT","mechanical text","CLAIMS","mechanical text"));
        var batch = new ProductPatentSearchSourceBatch.Batch(List.of(first,second),List.of(doc),Map.of());
        var index = mock(ProductSemanticIndex.class); var batches = new ProductPatentSearchSourceBatch();
        doThrow(new ProductSemanticIndex.Unavailable("missing claims index")).when(index).validateField(second,"CLAIMS");
        var partial = batches.validateIndexesFields(batch,index,selected);
        assertThat(partial.documents()).isEmpty(); assertThat(partial.successfulSources()).containsExactly("one");
        assertThat(partial.failures()).containsEntry("two","SOURCE_INDEX_UNAVAILABLE");
        verify(index,never()).validate(any()); verify(index,never()).validateField(any(),eq("TITLE"));
        doThrow(new ProductSemanticIndex.Unavailable("missing")).when(index).validateField(first,"CLAIMS");
        assertThatThrownBy(() -> batches.validateIndexesFields(batch,index,selected))
                .hasMessage("All requested source indexes are unavailable");
        doThrow(new IllegalStateException("current revoked")).when(index).validateField(first,"ABSTRACT");
        assertThatThrownBy(() -> batches.validateIndexesFields(batch,index,selected)).hasMessage("current revoked");
    }
    @Test void wrongProjectionCannotSilentlyDropAField() {
        var source = active("one"); var availability = mock(ProductSourceAvailability.class);
        for (String field : selected) when(availability.require("one",field,"QUERY")).thenReturn(source);
        var loader = mock(ProductCollectedPatentTitles.class);
        when(loader.loadFieldEvidence(source,ProductPatentSearchFilters.none(),selected)).thenReturn(List.of(
                new cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence(
                        new PatentLexicalQuery.Document("one","mechanical-C","v1","v1","a".repeat(64),"raw/file#abstract",
                                Map.of("ABSTRACT","mechanical text")),Map.of("ABSTRACT","raw/file#abstract"))));
        assertThatThrownBy(() -> new ProductPatentSearchSourceBatch().loadFields(List.of("one"),
                ProductPatentSearchFilters.none(),availability,loader,selected))
                .hasMessage("All requested source contents are unavailable");
    }
}
