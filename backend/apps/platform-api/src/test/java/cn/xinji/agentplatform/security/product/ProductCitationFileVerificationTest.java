package cn.xinji.agentplatform.security.product;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Controlled byte-integrity C only, not source admission or product-data evidence. */
class ProductCitationFileVerificationTest {
    @TempDir Path root;
    private String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Test void entireBatchIsVerifiedAndLateMissingTamperedOrOversizedFileRejectsIt() throws Exception {
        var references = new ArrayList<ProductCitationFileVerification.Reference>();
        for (int i=0;i<20;i++) {
            byte[] bytes = ("component-byte-integrity-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String name = "original-"+i+".txt";
            Files.write(root.resolve(name),bytes);
            references.add(new ProductCitationFileVerification.Reference(name,sha(bytes)));
        }
        var files = new ProductSourceEvidenceFiles(root,1024);
        assertThatCode(() -> ProductCitationFileVerification.verify(files,references)).doesNotThrowAnyException();
        Files.writeString(root.resolve("original-19.txt"),"changed");
        assertThatThrownBy(() -> ProductCitationFileVerification.verify(files,references))
                .isInstanceOf(IllegalStateException.class);
        Files.delete(root.resolve("original-19.txt"));
        assertThatThrownBy(() -> ProductCitationFileVerification.verify(files,references))
                .isInstanceOf(IllegalStateException.class);
        Files.write(root.resolve("original-19.txt"),new byte[2048]);
        references.set(19,new ProductCitationFileVerification.Reference("original-19.txt",sha(new byte[2048])));
        assertThatThrownBy(() -> ProductCitationFileVerification.verify(files,references))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void sourceInventoryBeyondPageLimitStillVerifiesTheLastOriginal() throws Exception {
        var references = new ArrayList<ProductCitationFileVerification.Reference>();
        for (int i=0;i<205;i++) {
            byte[] bytes = ("controlled-source-original-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String name = "source-original-"+i+".txt";
            Files.write(root.resolve(name), bytes);
            references.add(new ProductCitationFileVerification.Reference(name,sha(bytes)));
        }
        var files = new ProductSourceEvidenceFiles(root,1024);
        assertThatCode(() -> ProductCollectedPatentTitles.verifyOriginals(files,references))
                .doesNotThrowAnyException();
        Files.writeString(root.resolve("source-original-204.txt"),"tampered last original");
        assertThatThrownBy(() -> ProductCollectedPatentTitles.verifyOriginals(files,references))
                .isInstanceOf(IllegalStateException.class);
        Files.delete(root.resolve("source-original-204.txt"));
        assertThatThrownBy(() -> ProductCollectedPatentTitles.verifyOriginals(files,references))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void emptyBatchIsNoOpButContractOverflowAndInterruptionFailClosed() {
        var files = new ProductSourceEvidenceFiles(root,1024);
        assertThatCode(() -> ProductCitationFileVerification.verify(files,List.of())).doesNotThrowAnyException();
        var missing = new ProductCitationFileVerification.Reference("missing.txt","a".repeat(64));
        assertThatThrownBy(() -> ProductCitationFileVerification.verify(files,Collections.nCopies(101,missing)))
                .isInstanceOf(IllegalStateException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ProductCitationFileVerification.verify(files,List.of(missing)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void fixedGlobalBudgetBlocksAndReturnsEveryPermitAfterSuccessFailureAndInterruption() throws Exception {
        var field=ProductCitationFileVerification.class.getDeclaredField("APPLICATION_READS");
        field.setAccessible(true);
        var global=(java.util.concurrent.Semaphore)field.get(null);
        assertThat(global.availablePermits()).isEqualTo(8);
        byte[] bytes="controlled-integrity-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("budget-original.txt"),bytes);
        var reference=new ProductCitationFileVerification.Reference("budget-original.txt",sha(bytes));
        var files=new ProductSourceEvidenceFiles(root,1024);
        var references=Collections.nCopies(20,reference);
        assertThat(global.tryAcquire(8)).isTrue();
        int held=8;
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                var future=executor.submit(()->ProductCitationFileVerification.verify(files,references));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while(global.getQueueLength()==0 && System.nanoTime()<deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
                assertThat(global.getQueueLength()).isPositive();
                assertThat(future.isDone()).isFalse();
                global.release(); held--;
                future.get(3,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(global.availablePermits()).isEqualTo(1);
            } finally { global.release(held); held=0; }
        } finally { if(held>0)global.release(held); }
        assertThat(global.availablePermits()).isEqualTo(8);
        Files.writeString(root.resolve("budget-original.txt"),"changed");
        assertThatThrownBy(()->ProductCitationFileVerification.verify(files,references))
                .isInstanceOf(IllegalStateException.class);
        assertThat(global.availablePermits()).isEqualTo(8);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->ProductCitationFileVerification.verify(files,List.of(reference)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(global.availablePermits()).isEqualTo(8);
    }
}
