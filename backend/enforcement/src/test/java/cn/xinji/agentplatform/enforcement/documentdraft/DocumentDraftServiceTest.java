package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Mapped;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Mode;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Trial;

/** Component evidence only: these port doubles never count as real DM8 or product I. */
class DocumentDraftServiceTest {
    private static final ObjectVersion V=new ObjectVersion("retained-input",1,"a".repeat(64));
    static Input input(Type type) {
        return new Input("org",type,V,V,V,List.of(V),List.of(V),null,null);
    }
    static final class Fixture implements Inputs,Access,Store {
        final Map<String,Candidate> candidates=new HashMap<>();
        final List<Review> reviews=new ArrayList<>();
        final Map<String,ExportArtifact> exports=new HashMap<>();
        final Map<String,byte[]> exportFiles=new HashMap<>();
        final Map<String,Draft> drafts=new HashMap<>();
        final Map<String,Receipt> receipts=new HashMap<>();
        final Map<String,String> fingerprints=new HashMap<>();
        final List<String> calls=new ArrayList<>();
        boolean published=true, denied=false, missing=false, changed=false, narrative=false, legalConfirmed=true;
        int writes, commandCalls, readableChecks;
        final Set<Long> deniedReadRevisions=new HashSet<>();
        final List<String> readCoordinates=new ArrayList<>();
        Runnable afterReadable=()->{}, afterReviewsRead=()->{};
        record RetainedState(int writes,int commandCalls,Map<String,Draft> drafts,Map<String,Receipt> receipts,
                Map<String,String> fingerprints,List<Review> reviews,Map<String,Candidate> candidates,
                Map<String,ExportArtifact> exports,Map<String,byte[]> exportFiles) { }
        RetainedState retainedState() {
            return new RetainedState(writes,commandCalls,Map.copyOf(drafts),Map.copyOf(receipts),Map.copyOf(fingerprints),
                    List.copyOf(reviews),Map.copyOf(candidates),Map.copyOf(exports),Map.copyOf(exportFiles));
        }
        String sourceText="Existing input";
        final DocumentDraftService service=new DocumentDraftService(this,this,this,Clock.systemUTC());
        public Resolved resolve(String actor,Input input) {
            var fields=missing?List.<Mapped>of():List.of(new Mapped("party_name",sourceText,"material", "1","page 1",Mode.DIRECT,false));
            var diagnostics=missing?List.of(new cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Diagnostic("party_name","party.name","MISSING_SOURCE_VALUE",true)):List.<cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Diagnostic>of();
            var field=new DocumentTemplateProfile.FieldDefinition("party_name","相关主体",narrative?"LONG_TEXT":"STRING",true,false,narrative);
            var m=new cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Mapping("party_name","party.name","1",Mode.DIRECT,null,cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Missing.BLOCK);
            var profile=new DocumentTemplateProfile("COMPONENT_PROFILE",1,"NON_OFFICIAL",input.type(),"组件配置",java.time.Instant.EPOCH,null,List.of("*"),List.of("*"),List.of(field),List.of(m),
                List.of(new DocumentTemplateProfile.Section("facts","事实",new DocumentTemplateProfile.Condition(DocumentTemplateProfile.Operator.ALWAYS,null,null),List.of("party_name"))));
            var rule=new cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Rule(input.rule().id(),input.rule().revision(),input.organization(),cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.State.PUBLISHED,input.template().id(),input.template().revision(),input.template().sha256(),"COMPONENT_SCHEMA","1",List.of(m));
            var sources=missing?Map.<String,DocumentProfileEngine.SourceDatum>of():Map.of("party.name",new DocumentProfileEngine.SourceDatum(sourceText,"material",1,"a".repeat(64),"page 1",true));
            return new Resolved(input,published,true,true,legalConfirmed,false,false,new Trial(!missing,false,fields,diagnostics),profile,rule,null,null,sources);
        }
        public void assertCurrent(String actor,Input input) { if(denied)throw new SecurityException("revoked");if(changed)throw new IllegalStateException("template/rule changed"); }
        public void assertReadable(String actor,Input input) {
            if(denied)throw new SecurityException("revoked");
            readableChecks++;afterReadable.run();
        }
        public void require(String actor,String org,String id,long revision,String action) {
            calls.add(action);
            if("READ".equals(action)) {
                readCoordinates.add(actor+"|"+org+"|"+id+"|"+revision);
                if(deniedReadRevisions.contains(revision))throw new SecurityException("draft READ revoked");
            }
            if(denied)throw new SecurityException("revoked");
        }
        public Optional<Draft> read(String id,long revision) {return Optional.ofNullable(drafts.get(id+"@"+revision));}
        public List<Draft> history(String id) {return drafts.values().stream().filter(d->d.id().equals(id)).sorted(Comparator.comparingLong(Draft::revision)).toList();}
        public void append(Draft draft,long expected) {
            long head=history(draft.id()).stream().mapToLong(Draft::revision).max().orElse(0);
            if(expected!=head||draft.revision()!=expected+1)throw new IllegalArgumentException("CAS");
            drafts.put(draft.id()+"@"+draft.revision(),draft);writes++;
        }
        public Optional<Candidate> candidate(String id){return Optional.ofNullable(candidates.get(id));}
        public List<Candidate> candidates(String id){return candidates.values().stream().filter(c->c.draftId().equals(id)).toList();}
        public void addCandidate(Candidate candidate){candidates.put(candidate.id(),candidate);}
        public List<Review> reviews(String id,long revision){
            var result=reviews.stream().filter(r->r.draftId().equals(id)&&r.revision()==revision).toList();
            afterReviewsRead.run();return result;
        }
        public void addReview(Review review){reviews.add(review);}
        public Optional<ExportArtifact> export(String id){return Optional.ofNullable(exports.get(id));}
        public byte[] exportBytes(String id){return exportFiles.get(id).clone();}
        public void addExport(ExportArtifact artifact,byte[] bytes){exports.put(artifact.id(),artifact);exportFiles.put(artifact.id(),bytes.clone());}
        public Receipt command(String key,String fingerprint,String actor,Supplier<Receipt> transaction) {
            commandCalls++;
            if(receipts.containsKey(key)) {
                if(!fingerprint.equals(fingerprints.get(key)))throw new IllegalArgumentException("idempotency conflict");
                return receipts.get(key);
            }
            Receipt receipt=transaction.get();receipts.put(key,receipt);fingerprints.put(key,fingerprint);return receipt;
        }
        Receipt generate(Input input,String key) {
            return service.generate("actor",key,"draft",input,service.prepare("actor","draft",input).inputManifestHash());
        }
    }
    @Test void prepareDoesNotWriteAndUnusedDependenciesDoNotBlock() {
        var f=new Fixture();var p=f.service.prepare("actor","draft",input(Type.FACT_RECORD));
        assertTrue(p.ready());assertEquals(0,f.writes);assertEquals(0,f.receipts.size());
    }
    @Test void generationFreezesManifestProvenanceAndHash() {
        var f=new Fixture();var in=input(Type.FACT_RECORD);var r=f.generate(in,"key");
        var draft=f.service.read("actor","draft",1);
        assertEquals(in,draft.manifest());assertEquals(r.contentSha256(),draft.contentSha256());
        assertTrue(draft.contentJson().contains("page 1"));assertTrue(draft.systemAssisted());
        assertEquals("PENDING_REVIEW",draft.reviewState());assertEquals("key",draft.requestId());
    }
    @Test void missingSourceCreatesExplicitIncompleteDraftNotInventedContent() {
        var f=new Fixture();f.missing=true;f.generate(input(Type.FACT_RECORD),"key");
        var draft=f.service.read("actor","draft",1);
        assertEquals("INCOMPLETE_DRAFT",draft.reviewState());assertTrue(draft.contentJson().contains("\"missing\":true"));
        assertFalse(draft.contentJson().contains("Existing input"));
        assertTrue(draft.missingItems().stream().anyMatch(d->d.code().equals("MISSING_SOURCE_VALUE")));
    }
    @Test void unpublishedTemplateFailsWithoutWritesOrReceipts() {
        var f=new Fixture();f.published=false;
        assertThrows(IllegalArgumentException.class,()->f.generate(input(Type.FACT_RECORD),"key"));
        assertEquals(0,f.writes);assertTrue(f.receipts.isEmpty());
    }
    @Test void absentFactReferenceRemainsMissingAndCannotBeApprovedOrExported() {
        var f=new Fixture();f.legalConfirmed=false;
        var in=new Input("org",Type.FACT_RECORD,V,V,V,List.of(V),List.of(),null,null);
        var prepared=f.service.prepare("actor","draft",in);assertFalse(prepared.ready());
        f.generate(in,"absent-reference");var draft=f.service.read("actor","draft",1);
        assertEquals("INCOMPLETE_DRAFT",draft.reviewState());assertTrue(draft.manifest().legalReferences().isEmpty());
        assertTrue(draft.missingItems().stream().anyMatch(d->d.field().equals("legal_references")&&d.blocking()));
        var workflow=new DocumentDraftWorkflow(f.service,f,f,f,Clock.systemUTC(),new com.fasterxml.jackson.databind.ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->workflow.submit("actor","submit","draft",1,draft.contentSha256()));
        assertThrows(IllegalArgumentException.class,()->workflow.review("actor","review","draft",1,draft.contentSha256(),"APPROVED","not an approval"));
        assertTrue(f.reviews.isEmpty());
    }
    @Test void suppliedUnconfirmedReferenceStillFailsClosedAndOtherTypesRequireReferences() {
        var f=new Fixture();f.legalConfirmed=false;
        assertThrows(IllegalArgumentException.class,()->f.generate(input(Type.FACT_RECORD),"unconfirmed"));
        assertTrue(f.drafts.isEmpty());assertTrue(f.receipts.isEmpty());
        for(var type:List.of(Type.CONDUCT_REVIEW,Type.PENALTY_INPUT_REVIEW))
            assertThrows(IllegalArgumentException.class,()->new Input("org",type,V,V,V,List.of(V),List.of(),V,V));
    }
    @Test void preparedManifestHashCannotBeSubstituted() {
        var f=new Fixture();assertThrows(IllegalArgumentException.class,()->
            f.service.generate("actor","key","draft",input(Type.FACT_RECORD),"b".repeat(64)));
        assertEquals(0,f.writes);
    }
    @Test void conductRequirementIsNotInferredFromFactRecord() {
        var f=new Fixture();assertFalse(f.service.prepare("actor","draft",input(Type.CONDUCT_REVIEW)).ready());
        assertThrows(IllegalArgumentException.class,()->f.generate(input(Type.CONDUCT_REVIEW),"key"));
        assertEquals(0,f.writes);
    }
    @Test void penaltyInputCannotUseUnreviewedOrMissingReport() {
        var f=new Fixture();var p=f.service.prepare("actor","draft",input(Type.PENALTY_INPUT_REVIEW));
        assertTrue(p.diagnostics().stream().anyMatch(d->d.code().equals("PENALTY_REVIEW_REQUIRED")));
        assertEquals(0,f.writes);
    }
    @Test void replayIsIdempotentButDoesNotBypassCurrentAuthorization() {
        var f=new Fixture();var in=input(Type.FACT_RECORD);
        assertEquals(f.generate(in,"key"),f.generate(in,"key"));assertEquals(1,f.writes);
        f.denied=true;assertThrows(SecurityException.class,()->f.generate(in,"key"));assertEquals(1,f.writes);
    }
    @Test void newTemplateApplicabilityDoesNotOverwriteOrBlockAuthorizedHistory() {
        var f=new Fixture();var in=input(Type.FACT_RECORD);f.generate(in,"key");
        var before=f.service.read("actor","draft",1);f.changed=true;
        assertThrows(IllegalStateException.class,()->f.service.prepare("actor","draft",in));
        assertEquals(before,f.service.read("actor","draft",1));assertEquals(1,f.writes);
        f.denied=true;assertThrows(SecurityException.class,()->f.service.read("actor","draft",1));
    }
    @Test void generationStoresProfileAndRuleSnapshotsNotOnlyFlatFieldValues() {
        var f=new Fixture();f.generate(input(Type.FACT_RECORD),"key");
        String content=f.service.read("actor","draft",1).contentJson();
        assertTrue(content.contains("\"schemaVersion\":1"));assertTrue(content.contains("COMPONENT_PROFILE"));
        assertTrue(content.contains("NON_OFFICIAL"));assertTrue(content.contains("\"rule\":"));
    }
    @Test void explicitNarrativeRevisionPreservesOriginalSourceAndHistory() {
        var f=new Fixture();f.narrative=true;f.generate(input(Type.FACT_RECORD),"create");
        var base=f.service.read("actor","draft",1);
        var receipt=f.service.revise("actor","edit","draft",1,base.contentSha256(),Map.of("party_name","Corrected narrative"),"Technical correction");
        var next=f.service.read("actor","draft",2);
        assertEquals(2,receipt.revision());assertEquals("PENDING_REVIEW",next.reviewState());
        assertEquals(base,f.service.read("actor","draft",1));assertEquals(base.manifest(),next.manifest());
        assertTrue(next.contentJson().contains("Corrected narrative"));
        assertTrue(next.contentJson().contains("Existing input"));assertTrue(next.contentJson().contains("Technical correction"));
        assertEquals(2,f.service.history("actor","draft").size());
    }
    @Test void revisionReplayIsStableAndRevocationFailsClosed() {
        var f=new Fixture();f.narrative=true;f.generate(input(Type.FACT_RECORD),"create");
        var base=f.service.read("actor","draft",1);var edits=Map.of("party_name","Corrected narrative");
        var first=f.service.revise("actor","edit","draft",1,base.contentSha256(),edits,"reason");
        assertEquals(first,f.service.revise("actor","edit","draft",1,base.contentSha256(),edits,"reason"));
        assertEquals(2,f.writes);f.denied=true;
        assertThrows(SecurityException.class,()->f.service.revise("actor","edit","draft",1,base.contentSha256(),edits,"reason"));
        assertEquals(2,f.writes);
    }
    @Test void protectedUnknownAndUnchangedCorrectionsDoNotWrite() {
        var f=new Fixture();f.generate(input(Type.FACT_RECORD),"create");var base=f.service.read("actor","draft",1);
        assertThrows(IllegalArgumentException.class,()->f.service.revise("actor","protected","draft",1,base.contentSha256(),Map.of("party_name","changed"),"reason"));
        assertThrows(IllegalArgumentException.class,()->f.service.revise("actor","unknown","draft",1,base.contentSha256(),Map.of("unknown","changed"),"reason"));
        assertEquals(1,f.writes);assertEquals(1,f.receipts.size());
        f.narrative=true;
        // Existing snapshot stays protected even if a newer template would allow editing.
        assertThrows(IllegalArgumentException.class,()->f.service.revise("actor","unchanged","draft",1,base.contentSha256(),Map.of("party_name","Existing input"),"reason"));
        assertEquals(1,f.writes);
    }
    @Test void staleRevisionAndWrongShaNeverOverwriteHistory() {
        var f=new Fixture();f.narrative=true;f.generate(input(Type.FACT_RECORD),"create");var base=f.service.read("actor","draft",1);
        assertThrows(IllegalArgumentException.class,()->f.service.revise("actor","wrong","draft",1,"b".repeat(64),Map.of("party_name","changed"),"reason"));
        f.service.revise("actor","first","draft",1,base.contentSha256(),Map.of("party_name","changed"),"reason");
        assertThrows(IllegalArgumentException.class,()->f.service.revise("actor","stale","draft",1,base.contentSha256(),Map.of("party_name","other"),"reason"));
        assertEquals(2,f.writes);assertEquals(2,f.receipts.size());
    }
    @Test void missingSourceCannotBeErasedByManualNarrative() {
        var f=new Fixture();f.narrative=true;f.missing=true;f.generate(input(Type.FACT_RECORD),"create");
        var base=f.service.read("actor","draft",1);
        f.service.revise("actor","edit","draft",1,base.contentSha256(),Map.of("party_name","Explicit user note"),"reason");
        var next=f.service.read("actor","draft",2);assertEquals("INCOMPLETE_DRAFT",next.reviewState());
        assertEquals(base.missingItems(),next.missingItems());
    }
    @Test void confirmedWriteRecoveryReadsExactHistoricalRevisionWithoutNewCommands() {
        var f=new Fixture();f.narrative=true;var receipt=f.generate(input(Type.FACT_RECORD),"create");
        var original=f.service.read("actor",receipt.draftId(),receipt.revision());
        f.service.revise("actor","edit","draft",1,original.contentSha256(),Map.of("party_name","Human edit"),"reason");
        f.changed=true; // Historical readability does not require current upstream/head state.
        f.deniedReadRevisions.add(2L);f.readCoordinates.clear();
        var retained=f.retainedState();
        assertEquals(original,f.service.read("actor",receipt.draftId(),receipt.revision()));
        assertEquals(List.of("actor|org|draft|1","actor|org|draft|1"),f.readCoordinates);
        assertEquals(retained,f.retainedState());
    }
    @Test void draftReadRevokedDuringSourceVerificationCannotReturnConfirmedContent() {
        var f=new Fixture();var receipt=f.generate(input(Type.FACT_RECORD),"create");
        var retained=f.retainedState();f.readCoordinates.clear();
        // Only the document READ changes; source READ remains permitted throughout verification.
        f.afterReadable=()->f.deniedReadRevisions.add(receipt.revision());
        assertThrows(SecurityException.class,()->f.service.read("actor",receipt.draftId(),receipt.revision()));
        assertEquals(1,f.readableChecks);
        assertEquals(List.of("actor|org|draft|1","actor|org|draft|1"),f.readCoordinates);
        assertEquals(retained,f.retainedState());
    }
    @Test void historyCannotReturnEarlierRevisionRevokedWhileReadingLaterRevision() {
        var f=new Fixture();f.narrative=true;f.generate(input(Type.FACT_RECORD),"create");
        var original=f.service.read("actor","draft",1);
        f.service.revise("actor","edit","draft",1,original.contentSha256(),Map.of("party_name","Human edit"),"reason");
        var retained=f.retainedState();int before=f.readableChecks;
        f.afterReadable=()->{if(f.readableChecks==before+2)f.deniedReadRevisions.add(1L);};
        assertThrows(SecurityException.class,()->f.service.history("actor","draft"));
        assertEquals(before+2,f.readableChecks);
        assertEquals(retained,f.retainedState());
    }
    @Test void exactObjectRevisionAndShaAreMandatory() {
        assertThrows(IllegalArgumentException.class,()->new ObjectVersion("id",0,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new ObjectVersion("id",1,"bad"));
    }
}
