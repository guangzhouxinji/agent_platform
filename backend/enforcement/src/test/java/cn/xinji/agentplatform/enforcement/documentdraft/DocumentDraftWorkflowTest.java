package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;

/** C only; explicit port doubles do not count as a product HTTP/browser or DM8 result. */
class DocumentDraftWorkflowTest {
    final DocumentDraftServiceTest.Fixture f=new DocumentDraftServiceTest.Fixture();
    final DocumentDraftWorkflow workflow=new DocumentDraftWorkflow(f.service,f,f,f,Clock.systemUTC(),new ObjectMapper());
    final Input input=DocumentDraftServiceTest.input(Type.FACT_RECORD);
    Draft generated(){f.narrative=true;f.generate(input,"generate");return f.service.read("actor","draft",1);}
    Receipt candidate(Draft base,String key){return workflow.regenerate("actor",key,"draft",base.revision(),base.contentSha256(),input,f.service.prepare("actor","draft",input).inputManifestHash());}
    @Test void regeneratedCandidateNeverOverwritesCurrentAndAdoptionIsExplicit() {
        var base=generated();f.sourceText="Explicit controlled new input";var result=candidate(base,"candidate");
        assertEquals(1,f.writes);assertEquals(base,f.service.read("actor","draft",1));
        var c=workflow.candidates("actor","draft",1).getFirst();assertEquals(result.candidateId(),c.id());
        assertTrue(c.differences().stream().anyMatch(d->d.field().equals("party_name")));
        var adopted=workflow.adopt("actor","adopt","draft",1,base.contentSha256(),c.id());
        assertEquals(2,adopted.revision());assertEquals(2,f.writes);
        assertEquals(base,f.service.read("actor","draft",1));assertEquals("PENDING_REVIEW",f.service.read("actor","draft",2).reviewState());
    }
    @Test void candidateReplayAndAdoptionReplayDoNotDuplicateWrites() {
        var base=generated();var result=candidate(base,"candidate");assertEquals(result,candidate(base,"candidate"));
        var adopted=workflow.adopt("actor","adopt","draft",1,base.contentSha256(),result.candidateId());
        assertEquals(adopted,workflow.adopt("actor","adopt","draft",1,base.contentSha256(),result.candidateId()));
        assertEquals(1,f.candidates.size());assertEquals(2,f.writes);
    }
    @Test void concurrentRevisionPreventsCandidateAdoptionAndNewRegeneration() {
        var base=generated();var result=candidate(base,"candidate");
        f.service.revise("actor","edit","draft",1,base.contentSha256(),Map.of("party_name","Explicit human edit"),"reason");
        assertThrows(IllegalArgumentException.class,()->workflow.adopt("actor","adopt","draft",1,base.contentSha256(),result.candidateId()));
        assertThrows(IllegalArgumentException.class,()->candidate(base,"stale-candidate"));
        assertEquals(1,f.candidates.size());assertEquals(2,f.writes);
    }
    @Test void withdrawnAccessClosesReplayAndCandidateRead() {
        var base=generated();var c=candidate(base,"candidate");f.denied=true;
        assertThrows(SecurityException.class,()->candidate(base,"candidate"));
        assertThrows(SecurityException.class,()->workflow.candidates("actor","draft",1));
        assertThrows(SecurityException.class,()->workflow.adopt("actor","adopt","draft",1,base.contentSha256(),c.candidateId()));
        assertEquals(1,f.writes);
    }
    @Test void reviewIsBoundToSubmittedExactVersionAndCannotTransferToRevision() {
        var base=generated();assertThrows(IllegalArgumentException.class,()->workflow.review("reviewer","review","draft",1,base.contentSha256(),"APPROVED","Technical review"));
        var submitted=workflow.submit("actor","submit","draft",1,base.contentSha256());
        assertEquals(submitted,workflow.submit("actor","submit","draft",1,base.contentSha256()));
        var reviewed=workflow.review("reviewer","review","draft",1,base.contentSha256(),"APPROVED","Technical review");
        assertEquals(reviewed,workflow.review("reviewer","review","draft",1,base.contentSha256(),"APPROVED","Technical review"));
        f.service.revise("actor","edit","draft",1,base.contentSha256(),Map.of("party_name","Explicit edit"),"reason");
        assertTrue(workflow.reviews("actor","draft",2).isEmpty());assertEquals(2,workflow.reviews("actor","draft",1).size());
        assertThrows(IllegalArgumentException.class,()->workflow.submit("actor","stale-submit","draft",1,base.contentSha256()));
    }
    @Test void confirmedReviewRecoveryReadsHistoricalRevisionWithoutReplayingWrites() {
        var base=generated();workflow.submit("actor","submit","draft",1,base.contentSha256());
        var receipt=workflow.review("reviewer","approve","draft",1,base.contentSha256(),"APPROVED","Technical review");
        var original=java.util.List.copyOf(f.reviews);
        f.service.revise("actor","edit","draft",1,base.contentSha256(),Map.of("party_name","Human edit"),"reason");
        f.changed=true;f.deniedReadRevisions.add(2L);f.readCoordinates.clear();
        var retained=f.retainedState();
        assertEquals(original,workflow.reviews("reader",receipt.draftId(),receipt.revision()));
        assertEquals(java.util.List.of("reader|org|draft|1","reader|org|draft|1","reader|org|draft|1"),f.readCoordinates);
        assertEquals(retained,f.retainedState());
    }
    @Test void reviewReadRevokedDuringRecordReadCannotReturnConfirmedReview() {
        var base=generated();workflow.submit("actor","submit","draft",1,base.contentSha256());
        var receipt=workflow.review("reviewer","approve","draft",1,base.contentSha256(),"APPROVED","Technical review");
        var retained=f.retainedState();f.readCoordinates.clear();
        f.afterReviewsRead=()->f.deniedReadRevisions.add(receipt.revision());
        assertThrows(SecurityException.class,()->workflow.reviews("reader",receipt.draftId(),receipt.revision()));
        assertEquals(java.util.List.of("reader|org|draft|1","reader|org|draft|1","reader|org|draft|1"),f.readCoordinates);
        assertEquals(retained,f.retainedState());
    }
    @Test void emptyReviewReadAlsoFailsClosedWhenReadRevokedDuringRecordRead() {
        var base=generated();var retained=f.retainedState();
        f.afterReviewsRead=()->f.deniedReadRevisions.add(base.revision());
        assertThrows(SecurityException.class,()->workflow.reviews("reader",base.id(),base.revision()));
        assertEquals(retained,f.retainedState());
    }
    @Test void returnedRevisionCanBeResubmittedWithoutOverwritingPriorReviews() {
        var base=generated();workflow.submit("actor","submit","draft",1,base.contentSha256());
        workflow.review("reviewer","return","draft",1,base.contentSha256(),"RETURNED","Explicit return");
        workflow.submit("actor","resubmit","draft",1,base.contentSha256());
        workflow.review("reviewer","approve","draft",1,base.contentSha256(),"APPROVED","Technical review");
        assertEquals(4,workflow.reviews("actor","draft",1).size());assertEquals(1,f.writes);
    }
    @Test void incompleteInputCannotBeSubmittedOrApproved() {
        f.missing=true;var base=generated();assertThrows(IllegalArgumentException.class,()->workflow.submit("actor","submit","draft",1,base.contentSha256()));
        assertTrue(f.reviews.isEmpty());assertEquals(1,f.receipts.size());
    }
    @Test void wrongDigestOrWrongCandidateContextHasNoWorkflowSideEffects() {
        var base=generated();assertThrows(IllegalArgumentException.class,()->workflow.submit("actor","submit","draft",1,"b".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->workflow.adopt("actor","adopt","draft",1,base.contentSha256(),"absent"));
        assertTrue(f.reviews.isEmpty());assertTrue(f.candidates.isEmpty());assertEquals(1,f.receipts.size());
    }
    @Test void changedDependencyAfterCandidateCreationRejectsAdoptionWithoutWrites() {
        var base=generated();var candidate=candidate(base,"candidate-before-change");
        var receipts=Map.copyOf(f.receipts);f.changed=true;
        assertThrows(IllegalStateException.class,()->workflow.adopt("actor","adopt-after-change","draft",1,base.contentSha256(),candidate.candidateId()));
        assertEquals(base,f.service.read("actor","draft",1));assertEquals(receipts,f.receipts);
        assertEquals(1,f.writes);assertEquals(1,f.candidates.size());assertTrue(f.reviews.isEmpty());
    }
    @Test void adoptedCandidateReplayCannotBypassCurrentRevocation() {
        var base=generated();var candidate=candidate(base,"candidate-before-revocation");
        workflow.adopt("actor","explicit-adopt","draft",1,base.contentSha256(),candidate.candidateId());
        var receipts=Map.copyOf(f.receipts);var revisions=Map.copyOf(f.drafts);f.denied=true;
        assertThrows(SecurityException.class,()->workflow.adopt("actor","explicit-adopt","draft",1,base.contentSha256(),candidate.candidateId()));
        assertEquals(receipts,f.receipts);assertEquals(revisions,f.drafts);assertEquals(2,f.writes);assertTrue(f.reviews.isEmpty());
    }
    @Test void missingFactRemainsBlockedAcrossRevisionCandidateAdoptionAndExport() {
        f.missing=true;var base=generated();
        f.service.revise("actor","explicit-note","draft",1,base.contentSha256(),Map.of("party_name","Controlled note, not verified source"),"Component-only correction");
        var current=f.service.read("actor","draft",2);
        var receipt=workflow.regenerate("actor","incomplete-candidate","draft",2,current.contentSha256(),input,f.service.prepare("actor","draft",input).inputManifestHash());
        assertEquals(current,f.service.read("actor","draft",2));
        workflow.adopt("actor","explicit-incomplete-adopt","draft",2,current.contentSha256(),receipt.candidateId());
        var adopted=f.service.read("actor","draft",3);assertEquals("INCOMPLETE_DRAFT",adopted.reviewState());assertEquals(base.manifest(),adopted.manifest());
        assertTrue(adopted.missingItems().stream().anyMatch(Diagnostic::blocking));assertEquals(base,f.service.read("actor","draft",1));
        var receipts=Map.copyOf(f.receipts);
        assertThrows(IllegalArgumentException.class,()->workflow.submit("actor","invalid-submit","draft",3,adopted.contentSha256()));
        assertThrows(IllegalArgumentException.class,()->workflow.review("reviewer","invalid-approval","draft",3,adopted.contentSha256(),"APPROVED","Not a human approval"));
        var renderer=new ExportRenderer(){public String version(){return "COMPONENT_NO_RENDER";}public byte[] render(String actor,Draft draft){throw new AssertionError("Blocked draft reached renderer");}};
        var export=new DocumentDraftExportService(f.service,f,f,f,renderer,Clock.systemUTC());
        assertThrows(IllegalArgumentException.class,()->export.create("actor","invalid-export","draft",3,adopted.contentSha256()));
        assertEquals(receipts,f.receipts);assertEquals(3,f.writes);assertTrue(f.reviews.isEmpty());assertTrue(f.exports.isEmpty());
    }
}
