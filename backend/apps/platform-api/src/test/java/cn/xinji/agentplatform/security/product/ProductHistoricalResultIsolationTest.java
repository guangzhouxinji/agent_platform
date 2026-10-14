package cn.xinji.agentplatform.security.product;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductHistoricalResultIsolationTest {
    @TempDir Path temporary;
    @Test void missingExactTupleRejectsArbitraryIdAndPreservesEngineeringCopyBytes() throws Exception {
        var db=new ProductHistoricalResultIsolationFixture();
        byte[] before=db.originalBytes(); String sha=ProductVectorCodec.sha(before);
        Path dir=temporary;
        Files.write(dir.resolve("engineering-own-original.bin"),before);
        byte[] recovered=Files.readAllBytes(dir.resolve("engineering-own-original.bin"));
        assertArrayEquals(before,recovered); assertEquals(sha,ProductVectorCodec.sha(recovered));
        var restored=new ProductHistoricalResultIsolationFixture(new String(recovered,java.nio.charset.StandardCharsets.UTF_8).split("\\n")[0]);
        assertArrayEquals(recovered,restored.originalBytes());
        assertThrows(ProductHistoricalResultIsolation.Unverified.class,()->new ProductHistoricalResultIsolation(restored).require(restored.id));
        var failure=assertThrows(ProductHistoricalResultIsolation.Unverified.class,()->new ProductHistoricalResultIsolation(db).require(db.id));
        assertEquals(ProductHistoricalResultIsolation.CODE,failure.getReason());
        assertFalse(failure.getReason().contains("OWN_DOC"));
        assertEquals("EXACT_TUPLE_BUILD_MISSING",failure.gaps().getFirst().missing());
        assertArrayEquals(before,db.originalBytes()); assertEquals(0,db.writes);
        String receipt="OWN_ENGINEERING_DATA_NOT_THREE_REAL_HISTORIES\noriginal="+sha+"\nrecovered="+ProductVectorCodec.sha(recovered)+"\nwrites="+db.writes+"\n";
        saveOptional("engineering-own-original.bin",before);
        saveOptional("engineering-copy-sha.txt",receipt.getBytes(StandardCharsets.UTF_8));
    }
    @Test void completeUnrelatedHistoricalTupleRemainsUsableWithoutCurrentVersionSubstitution() {
        var db=new ProductHistoricalResultIsolationFixture(); db.closed();
        assertDoesNotThrow(()->new ProductHistoricalResultIsolation(db).require(db.id));
        assertEquals(0,db.writes);
    }
    @Test void anotherAdmissionCannotSubstituteEvenWhenDigestMatches() {
        var db=new ProductHistoricalResultIsolationFixture(); db.closed(); db.wrongTuple=true;
        assertEquals("EXACT_TUPLE_BUILD_MISSING",new ProductHistoricalResultIsolation(db).inspect(db.id).getFirst().missing());
    }
    @Test void futureBuildCannotClosePastExecution() {
        var db=new ProductHistoricalResultIsolationFixture();
        db.builds=List.of(new ProductHistoricalResultIsolation.Build(db.buildSha,1,db.executed.plusSeconds(1)));
        assertEquals("BUILD_AFTER_EXECUTION",new ProductHistoricalResultIsolation(db).inspect(db.id).getFirst().missing());
    }
    @Test void conflictingHistoricalBuildsAreQuarantined() {
        var db=new ProductHistoricalResultIsolationFixture(); db.closed();
        db.builds=List.of(db.builds.getFirst(),new ProductHistoricalResultIsolation.Build("a".repeat(64),1,db.executed));
        assertEquals("CONFLICTING_BUILD_WITNESSES",new ProductHistoricalResultIsolation(db).inspect(db.id).getFirst().missing());
    }
    @Test void repeatedIdenticalWitnessIsNotAnInventedConflict() {
        var db=new ProductHistoricalResultIsolationFixture(); db.closed(); db.builds=List.of(db.builds.getFirst(),db.builds.getFirst());
        assertTrue(new ProductHistoricalResultIsolation(db).inspect(db.id).isEmpty());
    }
    @Test void badResultDigestHardRejectsEvenWithMissingBuild() {
        var db=new ProductHistoricalResultIsolationFixture(); db.resultSha="f".repeat(64);
        assertThrows(IllegalStateException.class,()->new ProductHistoricalResultIsolation(db).inspect(db.id));
    }
    @Test void malformedBuildDigestIsHardCorruptionNotRecoverableGap() {
        var db=new ProductHistoricalResultIsolationFixture();
        db.builds=List.of(new ProductHistoricalResultIsolation.Build("broken",1,db.executed));
        assertThrows(IllegalStateException.class,()->new ProductHistoricalResultIsolation(db).inspect(db.id));
    }
    @Test void corruptedVectorOrWrongBuildDigestIsHardRejected() {
        var db=new ProductHistoricalResultIsolationFixture(); db.closed(); db.badVector=true;
        assertThrows(IllegalStateException.class,()->new ProductHistoricalResultIsolation(db).require(db.id));
        db.badVector=false; db.builds=List.of(new ProductHistoricalResultIsolation.Build("f".repeat(64),1,db.executed));
        assertThrows(IllegalStateException.class,()->new ProductHistoricalResultIsolation(db).require(db.id));
    }
    @Test void legacyModelCannotBeInferredFromSoleOrCurrentBuild() {
        var db=new ProductHistoricalResultIsolationFixture(); db.legacy=true; db.modelTurns=true;
        db.resultSha=db.codec.resultDigest(List.of(db.member),false); db.closed();
        var gaps=new ProductHistoricalResultIsolation(db).inspect(db.id);
        assertEquals("RECORDED_MODEL_VERSION_MISSING",gaps.getFirst().missing());
        assertNull(gaps.getFirst().tuple().model());
    }
    @Test void readOnlyReportListsSelectionAndModelDependencyAndNeverClaimsFullIntegrity() throws Exception {
        var db=new ProductHistoricalResultIsolationFixture(); db.modelTurns=true;
        var isolation=new ProductHistoricalResultIsolation(db);
        var report=isolation.report(isolation.inspect(db.id),false);
        saveOptional("engineering-recovery-report.json",db.codec.json(report).getBytes(StandardCharsets.UTF_8));
        assertFalse(report.overallIntegrityVerified()); assertFalse(report.fullOriginalValidationCompleted());
        assertTrue(report.dependencyImpacts().stream().anyMatch(s->s.startsWith("SELECTION:OWN_SELECTION:")));
        assertTrue(report.dependencyImpacts().stream().anyMatch(s->s.startsWith("MODEL_TURN:OWN_TURN:")));
        assertEquals(0,db.writes);
    }
    @Test void strictSearchRecoveryRemainsRedWhileSeparateReportExposesMissingWitness() {
        var db=new ProductHistoricalResultIsolationFixture();
        var verifier=new ProductPatentSearchRecoveryVerifier(db,temporary,1000);
        assertThrows(ProductHistoricalResultIsolation.Unverified.class,verifier::verify);
        var report=verifier.report();
        assertFalse(report.overallIntegrityVerified()); assertFalse(report.fullOriginalValidationCompleted());
        assertEquals("EXACT_TUPLE_BUILD_MISSING",report.gaps().getFirst().missing());
        assertEquals(0,db.writes);
    }
    @Test void productReceiptReplayRejectsIsolationButInternalOriginalReceiptStaysUntouched() {
        var db=new ProductHistoricalResultIsolationFixture();
        var receipts=new ProductPatentSearchReceipts(db);
        assertThrows(ProductHistoricalResultIsolation.Unverified.class,()->receipts.read(db.id));
        assertEquals(db.resultSha,receipts.readOriginalEvidence(db.id).resultSha());
        assertEquals(0,db.writes);
    }
    @Test void productReceiptWithClosedHistoricalTupleIsStillAvailable() {
        var db=new ProductHistoricalResultIsolationFixture(); db.closed();
        assertEquals(db.resultSha,new ProductPatentSearchReceipts(db).read(db.id).resultSha());
    }
    @Test void separateRecoveryReportDoesNotSwallowBadDigest() {
        var db=new ProductHistoricalResultIsolationFixture(); db.resultSha="f".repeat(64);
        var verifier=new ProductPatentSearchRecoveryVerifier(db,temporary,1000);
        assertThrows(IllegalStateException.class,verifier::report);
    }
    @Test void engineeringMetadataNeverInventsTitleProvenanceOrUnknownTables() {
        var db=new ProductHistoricalResultIsolationFixture();
        for(boolean modelTurns:List.of(false,true)) {
            db.modelTurns=modelTurns;
            var schema=new ProductObservedTables(db);
            assertEquals(modelTurns,schema.present("CON3_MODEL_TURN"));
            assertFalse(schema.present("CON4_RESULT_TITLE_BUILD"));
            assertFalse(schema.present("UNKNOWN_ENGINEERING_TABLE"));
        }
    }
    /** Optional engineering originals export; never controls test working data or acceptance. */
    private static void saveOptional(String name,byte[] bytes) throws Exception {
        String configured=System.getProperty("isolation.evidence.dir");
        if(configured==null || configured.isBlank()) return;
        Path output=Path.of(configured).toAbsolutePath();
        Files.createDirectories(output); Files.write(output.resolve(name),bytes);
    }
}






