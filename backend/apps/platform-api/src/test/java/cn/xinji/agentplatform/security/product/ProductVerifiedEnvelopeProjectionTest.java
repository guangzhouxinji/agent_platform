package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Actual collected originals and mechanical corruption C, not business substitutes. */
class ProductVerifiedEnvelopeProjectionTest {
    private static final Path ROOT=Path.of("data/patents").toAbsolutePath();
    private static final String REF="patentsview-real-candidate-20261002-v3/manifest.json";
    private static final Map<String,String> MAPPING=Map.of("TITLE","title","CLAIMS","claimsText");
    static final class CountingMapper extends ObjectMapper {
        final AtomicInteger fullTrees=new AtomicInteger();
        @Override public JsonNode readTree(byte[] bytes) throws IOException {
            fullTrees.incrementAndGet();return super.readTree(bytes);
        }
    }
    private static Collection<ProductPatentsViewSourceInventory.Original> originals() throws Exception {
        var files=new ProductSourceEvidenceFiles(ROOT,512L*1024*1024);
        return new ProductPatentsViewSourceInventory(files,new ObjectMapper())
                .selectedContent(REF,Files.readAllBytes(ROOT.resolve(REF))).documents().values();
    }
    @Test void fortyActualOriginalsKeepAllValuesAndLocatorsWithoutSecondFullTree() throws Exception {
        var originals=originals();assertThat(originals).hasSize(40);
        var mapper=new CountingMapper();
        for(var original:originals) for(var selected:List.of(Set.of("TITLE"),Set.of("CLAIMS"),Set.of("TITLE","CLAIMS"))) {
            int before=mapper.fullTrees.get();
            var expected=ProductCapturedPatentFields.readEnvelope(original.ref(),original.bytes(),original.sha(),selected,MAPPING,mapper);
            assertThat(mapper.fullTrees.get()).isEqualTo(before+1);
            var actual=ProductCapturedPatentFields.readVerifiedEnvelope(original,selected,MAPPING,mapper);
            assertThat(actual).isEqualTo(expected);
            assertThat(mapper.fullTrees.get()).isEqualTo(before+1);
            assertThat(original.record()).isEqualTo(new ObjectMapper().readTree(original.bytes()));
        }
    }
    @Test void selectedTreeDriftHashDriftMissingFieldDuplicateAndTrailingBytesStillReject() throws Exception {
        var original=originals().iterator().next();var mapper=new ObjectMapper();var selected=Set.of("TITLE","CLAIMS");
        var altered=original.record().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)altered).put("title","changed");
        assertThatThrownBy(()->ProductCapturedPatentFields.readVerifiedEnvelope(copy(original,original.bytes(),original.sha(),altered),selected,MAPPING,mapper))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("token differs");
        assertThatThrownBy(()->ProductCapturedPatentFields.readVerifiedEnvelope(copy(original,original.bytes(),"0".repeat(64),original.record()),selected,MAPPING,mapper))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("digest differs");
        assertThatThrownBy(()->ProductCapturedPatentFields.readVerifiedEnvelope(original,Set.of("ABSTRACT"),MAPPING,mapper))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
        String raw=new String(original.bytes(),java.nio.charset.StandardCharsets.UTF_8);
        for(String invalid:List.of(raw+" {}",raw.substring(0,raw.lastIndexOf('}'))+",\"title\":\"duplicate\"}")) {
            byte[] bytes=invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(()->ProductCapturedPatentFields.readVerifiedEnvelope(copy(original,bytes,ProductCapturedArchive.digest(bytes),original.record()),selected,MAPPING,mapper))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void tenConcurrentReadersDoNotShareMutableParsingStateOrModifyOriginals() throws Exception {
        var original=originals().iterator().next();var mapper=new CountingMapper();
        var expected=ProductCapturedPatentFields.readEnvelope(original.ref(),original.bytes(),original.sha(),Set.of("TITLE","CLAIMS"),MAPPING,mapper);
        try(var executor=Executors.newFixedThreadPool(10)) {
            List<Callable<ProductCapturedPatentFields.Content>> jobs=new ArrayList<>();
            for(int i=0;i<100;i++) jobs.add(()->ProductCapturedPatentFields.readVerifiedEnvelope(original,Set.of("TITLE","CLAIMS"),MAPPING,mapper));
            for(var result:executor.invokeAll(jobs)) assertThat(result.get()).isEqualTo(expected);
        }
        assertThat(mapper.fullTrees.get()).isEqualTo(1);
        assertThat(ProductCapturedArchive.digest(original.bytes())).isEqualTo(original.sha());
    }
    private static ProductPatentsViewSourceInventory.Original copy(ProductPatentsViewSourceInventory.Original from,byte[] bytes,String sha,JsonNode tree) {
        return new ProductPatentsViewSourceInventory.Original(from.id(),from.version(),from.ref(),sha,bytes,tree);
    }
}
