package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real collected page projection; corruption remains a rejection, not substitute data. */
class ProductPageProjectionTest {
    private static final Path ROOT=Path.of("data/patents").toAbsolutePath();
    private static final String REF="patentsview-real-candidate-20261002-v3/manifest.json";
    private static final Set<String> FIELDS=Set.of("TITLE","CLAIMS");
    @TempDir Path temporary;
    @Test void pageEqualsFullProjectionAndUnknownDocumentIsRejected() throws Exception {
        var mapper=new ObjectMapper();var bytes=Files.readAllBytes(ROOT.resolve(REF));
        var files=new ProductSourceEvidenceFiles(ROOT,512L*1024*1024);
        var inventory=new ProductPatentsViewSourceInventory(files,mapper).selectedContent(REF,bytes);
        var snapshot=new ProductCollectedPatentTitles.CaptureSnapshot(inventory.sourceId(),inventory.sourceVersion(),REF,
                inventory.recordsSha(),inventory.recordsSha());
        var reader=new ProductPatentsViewSourceDocuments(files,mapper);
        var full=reader.read(snapshot,bytes,FIELDS);
        var ids=new LinkedHashSet<>(full.entries().keySet().stream().limit(20).toList());
        var page=reader.read(snapshot,bytes,FIELDS,ids);
        assertThat(full.entries()).hasSize(40);assertThat(page.entries()).hasSize(20);
        for(var id:ids) assertThat(page.entries().get(id)).isEqualTo(full.entries().get(id));
        assertThat(page.source()).isEqualTo(full.source());
        assertThat(page.collectedAt()).isEqualTo(full.collectedAt());assertThat(page.url()).isEqualTo(full.url());
        assertThatThrownBy(()->reader.read(snapshot,bytes,FIELDS,Set.of("UNKNOWN_DOCUMENT")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }
    @Test void offPageOriginalCorruptionStillRejectsPage() throws Exception {
        var mapper=new ObjectMapper();var bytes=Files.readAllBytes(ROOT.resolve(REF));
        var inventory=new ProductPatentsViewSourceInventory(new ProductSourceEvidenceFiles(ROOT,512L*1024*1024),mapper)
                .selectedContent(REF,bytes);
        var ids=new LinkedHashSet<>(inventory.documents().keySet().stream().limit(20).toList());
        var offPage=inventory.documents().values().stream().filter(original->!ids.contains(original.id())).findFirst().orElseThrow();
        var manifest=mapper.readTree(bytes);var parent=Path.of(REF).getParent();
        Files.createDirectories(temporary.resolve(parent));Files.copy(ROOT.resolve(REF),temporary.resolve(REF));
        for(var artifact:manifest.path("artifacts")) {
            var relative=parent.resolve(artifact.path("file").asText());var target=temporary.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(ROOT.resolve(relative),target);
        }
        Files.writeString(temporary.resolve(offPage.ref()),"corrupt off-page original");
        var snapshot=new ProductCollectedPatentTitles.CaptureSnapshot(inventory.sourceId(),inventory.sourceVersion(),REF,
                inventory.recordsSha(),inventory.recordsSha());
        var reader=new ProductPatentsViewSourceDocuments(new ProductSourceEvidenceFiles(temporary,512L*1024*1024),mapper);
        assertThatThrownBy(()->reader.read(snapshot,bytes,FIELDS,ids)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(ROOT.resolve(offPage.ref()))).isEqualTo(offPage.bytes());
    }
}
