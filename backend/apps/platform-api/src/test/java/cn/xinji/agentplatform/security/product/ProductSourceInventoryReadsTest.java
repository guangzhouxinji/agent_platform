package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductSourceInventoryReadsTest {
    @TempDir Path root;
    @Test void readsActualOriginalsInOrderAndConcurrentRequestsAreIndependent() throws Exception {
        Path capture=Path.of("data/patents/patentsview-real-candidate-20261002-v3/records").toAbsolutePath();
        var files=new ProductSourceEvidenceFiles(capture,512L*1024*1024);
        List<ProductSourceInventoryReads.Reference> references=new ArrayList<>();
        try(var paths=Files.list(capture)) {
            for(var path:paths.sorted().toList()) {
                var bytes=Files.readAllBytes(path);var name=path.getFileName().toString();
                references.add(new ProductSourceInventoryReads.Reference(name,name,ProductCapturedArchive.digest(bytes),bytes.length));
            }
        }
        assertThat(references).hasSize(40);
        try(var executor=Executors.newFixedThreadPool(10)) {
            List<Callable<Map<String,byte[]>>> jobs=new ArrayList<>();
            for(int i=0;i<10;i++) jobs.add(()->ProductSourceInventoryReads.read(files,references));
            for(var job:executor.invokeAll(jobs)) {
                var actual=job.get();assertThat(new ArrayList<>(actual.keySet())).isEqualTo(references.stream().map(ProductSourceInventoryReads.Reference::name).toList());
                for(var reference:references) assertThat(actual.get(reference.name())).isEqualTo(Files.readAllBytes(capture.resolve(reference.path())));
            }
        }
    }
    @Test void corruptionLengthMissingAndPathFailuresNeverReturnPartialInventory() throws Exception {
        byte[] actual=Files.readAllBytes(Path.of("data/patents/patentsview-real-candidate-20261002-v3/manifest.json"));
        Files.write(root.resolve("original.json"),actual);
        var files=new ProductSourceEvidenceFiles(root,512L*1024*1024);var sha=ProductCapturedArchive.digest(actual);
        var good=new ProductSourceInventoryReads.Reference("good","original.json",sha,actual.length);
        for(var bad:List.of(new ProductSourceInventoryReads.Reference("bad","original.json","0".repeat(64),actual.length),
                new ProductSourceInventoryReads.Reference("bad","original.json",sha,actual.length+1),
                new ProductSourceInventoryReads.Reference("bad","missing.json",sha,actual.length),
                new ProductSourceInventoryReads.Reference("bad","../original.json",sha,actual.length)))
            assertThatThrownBy(()->ProductSourceInventoryReads.read(files,List.of(good,bad))).isInstanceOf(IllegalStateException.class);
        assertThat(ProductSourceInventoryReads.read(files,List.of(good)).get("good")).isEqualTo(actual);
    }
}
