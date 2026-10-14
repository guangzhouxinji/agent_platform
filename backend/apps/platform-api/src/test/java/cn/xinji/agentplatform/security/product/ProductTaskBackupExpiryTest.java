package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self-authored filesystem bytes only: no native DM8 dump, old originals, identities or network. */
class ProductTaskBackupExpiryTest {
    @TempDir Path root;
    private static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z"), END=NOW.plusSeconds(60);
    private final byte[] key=new byte[32];
    private Path export,evidence;
    private String exportSha,originalSha;
    private ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
    private ProductTaskBackupBundle bundle(Clock clock){return new ProductTaskBackupBundle(mapper(),clock);}
    private Clock valid(){return Clock.fixed(NOW,ZoneOffset.UTC);}
    private ProductTaskBackupBundle.Snapshot snapshot(){return new ProductTaskBackupBundle.Snapshot(
            "self-authored-batch","https://identity.example.org","SELF_AUTHORED", "unit-key",7,
            "a".repeat(64),Map.of("UNIT",new ProductTaskBackupBundle.SourceEpoch(1,"READY","b".repeat(64))),
            NOW.minusSeconds(1),END,"self-authored-retention",1);}
    @BeforeEach void ownBytes()throws Exception{
        export=root.resolve("self-authored-export.bin");Files.writeString(export,"SELF_AUTHORED_NOT_A_DM8_NATIVE_EXPORT");
        evidence=Files.createDirectory(root.resolve("evidence"));Files.writeString(evidence.resolve("original.txt"),"self-authored original input");
        exportSha=sha(export);originalSha=sha(evidence.resolve("original.txt"));
    }
    private String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    private void unchanged()throws Exception{assertThat(sha(export)).isEqualTo(exportSha);assertThat(sha(evidence.resolve("original.txt"))).isEqualTo(originalSha);}
    private Path sealed()throws Exception{Path path=root.resolve("source.enc");bundle(valid()).seal(export,evidence,List.of("original.txt"),path,key,snapshot());return path;}
    private void noTemporary()throws Exception{try(var paths=Files.list(root)){assertThat(paths.map(p->p.getFileName().toString()).filter(n->n.startsWith(".con-")).toList()).isEmpty();}}
    private void sealWith(Clock c,Path target)throws Exception{bundle(c).seal(export,evidence,List.of("original.txt"),target,key,snapshot());}
    @Test void normalBatchPreservesFormatBytesAndSnapshot()throws Exception{
        Path source=sealed(),target=root.resolve("isolated");String before=sha(source);
        var result=bundle(valid()).open(source,target,key,1_000_000);
        assertThat(result.snapshot()).isEqualTo(snapshot());assertThat(result.entries()).hasSize(2);
        assertThat(Files.readAllBytes(target.resolve("db/primary.dmp"))).isEqualTo(Files.readAllBytes(export));
        assertThat(Files.readAllBytes(target.resolve("files/original.txt"))).isEqualTo(Files.readAllBytes(evidence.resolve("original.txt")));
        assertThat(new String(Files.readAllBytes(source),0,8,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("CONBKP01");
        assertThat(sha(source)).isEqualTo(before);unchanged();noTemporary();
    }
    @Test void sealExpiringAfterEntryCannotPublishOrReturnSuccess()throws Exception{
        Path target=root.resolve("expired.enc");
        assertThatThrownBy(()->sealWith(new CrossingClock(2),target)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();unchanged();noTemporary();
    }
    @Test void openExpiringAfterValidationCannotPublishExtraction()throws Exception{
        Path source=sealed(),target=root.resolve("expired-target");String before=sha(source);
        assertThatThrownBy(()->bundle(new CrossingClock(2)).open(source,target,key,1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();assertThat(sha(source)).isEqualTo(before);unchanged();noTemporary();
    }
    @Test void sealExpiryAfterAtomicMoveRejectsSuccessAndPreservesEncryptedScene()throws Exception{
        Path target=root.resolve("partial.enc");
        assertThatThrownBy(()->sealWith(new CrossingClock(8),target)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.isRegularFile(target)).isTrue();unchanged();noTemporary();
        assertThatThrownBy(()->bundle(Clock.fixed(END,ZoneOffset.UTC)).open(target,root.resolve("still-closed"),key,1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(root.resolve("still-closed"))).isFalse();
    }
    @Test void openExpiryAfterAtomicMoveRejectsSuccessAndPreservesIsolatedScene()throws Exception{
        Path source=sealed(),target=root.resolve("isolated-partial");String before=sha(source);
        assertThatThrownBy(()->bundle(new CrossingClock(8)).open(source,target,key,1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(target.resolve("db/primary.dmp"))).isEqualTo(Files.readAllBytes(export));
        assertThat(sha(source)).isEqualTo(before);unchanged();noTemporary();
    }
    @Test void alreadyExpiredSnapshotFailsBeforePublishing()throws Exception{
        Path source=sealed(),target=root.resolve("expired-entry");
        assertThatThrownBy(()->bundle(Clock.fixed(END,ZoneOffset.UTC)).open(source,target,key,1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();unchanged();noTemporary();
    }
    @Test void wrongKeyNeverPublishesOrChangesSource()throws Exception{
        Path source=sealed(),target=root.resolve("wrong-key");String before=sha(source);byte[] wrong=key.clone();wrong[0]=1;
        assertThatThrownBy(()->bundle(valid()).open(source,target,wrong,1_000_000)).isInstanceOf(Exception.class);
        assertThat(Files.exists(target)).isFalse();assertThat(sha(source)).isEqualTo(before);unchanged();noTemporary();
    }
    @Test void tamperedCopyNeverPublishesOrChangesOriginal()throws Exception{
        Path source=sealed(),target=root.resolve("tamper-target"),copy=root.resolve("tampered.enc");String before=sha(source);
        byte[] bytes=Files.readAllBytes(source);bytes[bytes.length-1]^=1;Files.write(copy,bytes);
        assertThatThrownBy(()->bundle(valid()).open(copy,target,key,1_000_000)).isInstanceOf(Exception.class);
        assertThat(Files.exists(target)).isFalse();assertThat(sha(source)).isEqualTo(before);unchanged();noTemporary();
    }
    @Test void sizeLimitRetainsClosedTarget()throws Exception{
        Path source=sealed(),target=root.resolve("too-small");
        assertThatThrownBy(()->bundle(valid()).open(source,target,key,16)).isInstanceOf(Exception.class);
        assertThat(Files.exists(target)).isFalse();unchanged();noTemporary();
    }
    @Test void duplicateReferencesCannotCreateAnotherPackage()throws Exception{
        Path target=root.resolve("duplicate.enc");
        assertThatThrownBy(()->bundle(valid()).seal(export,evidence,List.of("original.txt","original.txt"),target,key,snapshot())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();unchanged();
    }
    @Test void pathEscapeCannotCreatePackage()throws Exception{
        Path target=root.resolve("escape.enc");
        assertThatThrownBy(()->bundle(valid()).seal(export,evidence,List.of("../self-authored-export.bin"),target,key,snapshot())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();unchanged();
    }
    @Test void existingTargetIsNeverOverwritten()throws Exception{
        Path source=sealed(),target=root.resolve("existing");Files.writeString(target,"preserve existing bytes");String before=sha(target);
        assertThatThrownBy(()->bundle(valid()).open(source,target,key,1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sha(target)).isEqualTo(before);unchanged();
    }
    @Test void invalidAuthorityDoesNotAcquireMeaningFromNewExpiryChecks()throws Exception{
        Path target=root.resolve("bad-authority.enc");var s=snapshot();var invalid=new ProductTaskBackupBundle.Snapshot(s.batchId(),s.issuer(),s.primarySchema(),s.keyId(),-1,s.grantHistorySha(),s.sourceWatermarks(),s.createdAt(),s.expiresAt(),s.retentionRef(),s.retentionVersion());
        assertThatThrownBy(()->bundle(valid()).seal(export,evidence,List.of("original.txt"),target,key,invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(target)).isFalse();unchanged();
    }
    /** Clock crosses the existing deadline during the actual operation, not a real use approval. */
    private static final class CrossingClock extends Clock{
        private final int validCalls;private int calls;
        CrossingClock(int validCalls){this.validCalls=validCalls;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return ++calls<=validCalls?NOW:END;}
    }
}
