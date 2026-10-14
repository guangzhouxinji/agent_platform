package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Frozen ENF-14 commands; candidates and reviews never mutate historical content. */
public final class DocumentDraftWorkflow {
    private final DocumentDraftService drafts; private final Inputs inputs; private final Access access;
    private final Store store; private final Clock clock; private final ObjectMapper json;
    public DocumentDraftWorkflow(DocumentDraftService drafts,Inputs inputs,Access access,Store store,Clock clock,ObjectMapper json) {
        this.drafts=drafts;this.inputs=inputs;this.access=access;this.store=store;this.clock=clock;
        this.json=json.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public Receipt regenerate(String actor,String key,String id,long expectedRevision,String baseSha,Input request,String preparedSha) {
        var base=drafts.read(actor,id,expectedRevision);
        require(actor,base,"REGENERATE");
        if(!base.contentSha256().equals(baseSha)||!base.manifest().organization().equals(request.organization())
                ||base.manifest().type()!=request.type()||!base.manifest().caseInput().id().equals(request.caseInput().id()))
            throw new IllegalArgumentException("Regeneration base context mismatch");
        var prepared=drafts.prepare(actor,id,request);
        if(!prepared.inputManifestHash().equals(preparedSha))throw new IllegalArgumentException("Prepared candidate manifest mismatch");
        var permitted=Set.of("MISSING_SOURCE_VALUE","UNCONFIRMED_PROTECTED_VALUE","PROTECTED_INPUT_UNCONFIRMED","MAPPING_INCOMPLETE");
        if(prepared.content()==null||prepared.diagnostics().stream().anyMatch(d->d.blocking()&&!permitted.contains(d.code())))
            throw new IllegalArgumentException("Candidate dependencies unavailable");
        String content=encode(prepared.content());String digest=sha(content);
        String fingerprint=sha("REGENERATE|"+id+"|"+expectedRevision+"|"+baseSha+"|"+preparedSha+"|"+digest);
        var receipt=store.command(key,fingerprint,actor,()->{
            require(actor,base,"REGENERATE");assertHead(base);inputs.assertCurrent(actor,request);
            String candidateId=UUID.randomUUID().toString();
            var candidate=new Candidate(candidateId,id,expectedRevision,baseSha,request,preparedSha,content,digest,
                    prepared.diagnostics(),difference(base.contentJson(),content),actor,key,clock.instant());
            store.addCandidate(candidate);inputs.assertCurrent(actor,request);require(actor,base,"REGENERATE");
            return new Receipt(key,"REGENERATE",id,expectedRevision,digest,candidateId);
        });
        require(actor,base,"REGENERATE");inputs.assertCurrent(actor,request);return receipt;
    }
    public List<Candidate> candidates(String actor,String id,long revision) {
        var base=drafts.read(actor,id,revision);require(actor,base,"READ");
        var values=store.candidates(id).stream().filter(c->c.baseRevision()==revision).toList();
        for(var value:values){inputs.assertReadable(actor,value.manifest());checked(value);}
        require(actor,base,"READ");return List.copyOf(values);
    }
    public Receipt adopt(String actor,String key,String id,long expectedRevision,String expectedSha,String candidateId) {
        var base=drafts.read(actor,id,expectedRevision);require(actor,base,"ADOPT");
        var candidate=store.candidate(candidateId).orElseThrow(()->new IllegalArgumentException("Candidate not found"));checked(candidate);
        if(!candidate.draftId().equals(id)||candidate.baseRevision()!=expectedRevision||!candidate.baseContentSha256().equals(expectedSha)
                ||!base.contentSha256().equals(expectedSha))throw new IllegalArgumentException("Candidate base mismatch");
        inputs.assertCurrent(actor,candidate.manifest());
        String fingerprint=sha("ADOPT|"+id+"|"+expectedRevision+"|"+expectedSha+"|"+candidateId+"|"+candidate.contentSha256());
        var receipt=store.command(key,fingerprint,actor,()->{
            require(actor,base,"ADOPT");assertHead(base);inputs.assertCurrent(actor,candidate.manifest());
            var next=new Draft(id,expectedRevision+1,candidate.manifest(),candidate.contentJson(),candidate.contentSha256(),
                    candidate.inputManifestHash(),candidate.diagnostics().stream().anyMatch(Diagnostic::blocking)?"INCOMPLETE_DRAFT":"PENDING_REVIEW",
                    candidate.diagnostics(),actor,true,key,clock.instant());
            store.append(next,expectedRevision);require(actor,base,"ADOPT");inputs.assertCurrent(actor,candidate.manifest());
            return new Receipt(key,"ADOPT",id,next.revision(),next.contentSha256(),candidateId);
        });
        require(actor,base,"ADOPT");inputs.assertCurrent(actor,candidate.manifest());return receipt;
    }
    public Receipt submit(String actor,String key,String id,long revision,String contentSha) {
        return reviewCommand(actor,key,id,revision,contentSha,"SUBMITTED","Explicit submission","SUBMIT_REVIEW");
    }
    public Receipt review(String actor,String key,String id,long revision,String contentSha,String outcome,String reason) {
        if(!Set.of("APPROVED","RETURNED").contains(outcome)||reason==null||reason.isBlank())
            throw new IllegalArgumentException("Explicit review outcome and reason required");
        return reviewCommand(actor,key,id,revision,contentSha,outcome,reason,"REVIEW");
    }
    public List<Review> reviews(String actor,String id,long revision) {
        var base=drafts.read(actor,id,revision);
        var result=List.copyOf(store.reviews(id,revision));
        // Confirmed review history is consumed under current READ, not the pre-query decision.
        require(actor,base,"READ");return result;
    }
    private Receipt reviewCommand(String actor,String key,String id,long revision,String expectedSha,String outcome,String reason,String action) {
        var base=drafts.read(actor,id,revision);require(actor,base,action);inputs.assertCurrent(actor,base.manifest());
        if(!base.contentSha256().equals(expectedSha))throw new IllegalArgumentException("Review version SHA mismatch");
        if(!"RETURNED".equals(outcome)&&base.missingItems().stream().anyMatch(Diagnostic::blocking))
            throw new IllegalArgumentException("Incomplete draft cannot be submitted or approved");
        String fingerprint=sha(action+"|"+id+"|"+revision+"|"+expectedSha+"|"+outcome+"|"+reason);
        var receipt=store.command(key,fingerprint,actor,()->{
            require(actor,base,action);assertHead(base);inputs.assertCurrent(actor,base.manifest());
            var prior=store.reviews(id,revision);
            String state=prior.isEmpty()?"PENDING_REVIEW":prior.getLast().outcome();
            if("SUBMITTED".equals(outcome)&&!Set.of("PENDING_REVIEW","RETURNED").contains(state))throw new IllegalArgumentException("Already submitted or reviewed");
            if(!"SUBMITTED".equals(outcome)&&!"SUBMITTED".equals(state))throw new IllegalArgumentException("Exact revision must be submitted before review");
            store.addReview(new Review(id,revision,expectedSha,outcome,reason,actor,key,clock.instant()));
            require(actor,base,action);inputs.assertCurrent(actor,base.manifest());
            return new Receipt(key,action,id,revision,expectedSha);
        });
        require(actor,base,action);inputs.assertCurrent(actor,base.manifest());return receipt;
    }
    private void assertHead(Draft base) {
        var all=store.history(base.id());
        if(all.isEmpty()||all.getLast().revision()!=base.revision()||!all.getLast().contentSha256().equals(base.contentSha256()))
            throw new IllegalArgumentException("Draft changed; stale operation rejected");
    }
    private void require(String actor,Draft base,String action) {access.require(actor,base.manifest().organization(),base.id(),base.revision(),action);}
    private void checked(Candidate candidate) {if(!sha(candidate.contentJson()).equals(candidate.contentSha256()))throw new IllegalStateException("Candidate SHA mismatch");}
    private List<Difference> difference(String before,String after) {
        try {
            var left=json.readTree(before);var right=json.readTree(after);var result=new ArrayList<Difference>();
            var a=new TreeMap<String,String>();var b=new TreeMap<String,String>();
            for(var section:left.path("sections"))for(var field:section.path("fields"))a.put(field.path("definition").path("id").asText(),field.toString());
            for(var section:right.path("sections"))for(var field:section.path("fields"))b.put(field.path("definition").path("id").asText(),field.toString());
            var ids=new TreeSet<>(a.keySet());ids.addAll(b.keySet());
            for(String field:ids)if(!Objects.equals(a.get(field),b.get(field)))result.add(new Difference(field,a.get(field),b.get(field)));
            for(String field:List.of("profile","rule","issues","corrections"))if(!left.path(field).equals(right.path(field)))
                result.add(new Difference("$"+field,left.path(field).toString(),right.path(field).toString()));
            return List.copyOf(result);
        }catch(Exception failure){throw new IllegalStateException("Candidate diff failed",failure);}
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Workflow serialization failed",failure);}}
    private static String sha(String value) {try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
