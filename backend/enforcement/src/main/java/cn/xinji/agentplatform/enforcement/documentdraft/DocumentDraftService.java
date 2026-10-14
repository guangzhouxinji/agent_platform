package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Prepare never writes; generation freezes field provenance with the exact input manifest. */
public final class DocumentDraftService {
    private final Inputs inputs;
    private final Access access;
    private final Store store;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public DocumentDraftService(Inputs inputs, Access access, Store store, Clock clock) {
        this.inputs=Objects.requireNonNull(inputs);this.access=Objects.requireNonNull(access);
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
    }
    public Preparation prepare(String actor, String draftId, Input request) {
        requireActor(actor,draftId);
        access.require(actor,request.organization(),draftId,1,"PREPARE");
        var resolved=inputs.resolve(actor,request);
        if (!request.equals(resolved.manifest())) throw new IllegalStateException("Resolved manifest mismatch");
        var diagnostics=new ArrayList<Diagnostic>();
        check(diagnostics,resolved.templatePublished(),"TEMPLATE_NOT_PUBLISHED");
        check(diagnostics,resolved.rulePublished(),"RULE_NOT_PUBLISHED");
        check(diagnostics,resolved.exactTemplateRuleBinding(),"TEMPLATE_RULE_VERSION_MISMATCH");
        if(request.type()==Type.FACT_RECORD && request.legalReferences().isEmpty())
            diagnostics.add(new Diagnostic("legal_references","MISSING_SOURCE_VALUE",true));
        else check(diagnostics,resolved.legalConfirmed(),"LEGAL_REFERENCE_REVIEW_REQUIRED");
        if(request.type()!=Type.FACT_RECORD)
            check(diagnostics,request.conduct()!=null && resolved.conductReviewed(),"CONDUCT_REVIEW_REQUIRED");
        if(request.type()==Type.PENALTY_INPUT_REVIEW)
            check(diagnostics,request.penalty()!=null && resolved.penaltyReviewed(),"PENALTY_REVIEW_REQUIRED");
        var mapping=Objects.requireNonNull(resolved.mapping(),"Mapping result required");
        for(var d:mapping.diagnostics())diagnostics.add(new Diagnostic(d.fieldId(),d.code(),d.blocking()));
        check(diagnostics,mapping.complete(),"MAPPING_INCOMPLETE");
        var profile=Objects.requireNonNull(resolved.profile(),"Exact template profile required");
        var rule=Objects.requireNonNull(resolved.ruleSnapshot(),"Exact rule snapshot required");
        if(profile.documentType()!=request.type()||!rule.id().equals(request.rule().id())||rule.revision()!=request.rule().revision()
                ||!rule.templateId().equals(request.template().id())||rule.templateRevision()!=request.template().revision()
                ||!rule.templateSha256().equals(request.template().sha256()))throw new IllegalArgumentException("Profile/rule/manifest coordinate mismatch");
        DocumentProfileEngine.Content content=null;
        if(rule.state()==cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.State.PUBLISHED) {
            content=new DocumentProfileEngine().compose(profile,clock.instant(),resolved.region(),resolved.stage(),rule,resolved.sources(),mapping);
            for(var d:content.issues())diagnostics.add(new Diagnostic(d.field(),d.code(),d.blocking()));
        } else diagnostics.add(new Diagnostic("","RULE_NOT_PUBLISHED",true));
        inputs.assertCurrent(actor,request);
        access.require(actor,request.organization(),draftId,1,"PREPARE");
        return new Preparation(request,sha(encode(request)+"|"+encode(profile)+"|"+encode(rule)),diagnostics.stream().noneMatch(Diagnostic::blocking),
                mapping.reviewRequired()||content!=null&&content.reviewRequired(),mapping.fields(),diagnostics,content);
    }
    public Receipt generate(String actor, String key, String id, Input request, String preparedManifestHash) {
        requireActor(actor,id);
        access.require(actor,request.organization(),id,1,"CREATE");
        var prepared=prepare(actor,id,request);
        if(!prepared.inputManifestHash().equals(preparedManifestHash))throw new IllegalArgumentException("Prepared manifest hash mismatch");
        var missingCodes=List.of("MISSING_SOURCE_VALUE","UNCONFIRMED_PROTECTED_VALUE","PROTECTED_INPUT_UNCONFIRMED","MAPPING_INCOMPLETE");
        if(prepared.diagnostics().stream().anyMatch(d->d.blocking()&&!missingCodes.contains(d.code())))
            throw new IllegalArgumentException("Document preparation has blocking dependency or mapping gaps");
        String content=encode(Objects.requireNonNull(prepared.content(),"Structured profile content required"));
        String contentSha=sha(content);
        String fingerprint=sha(encode(request)+"|"+id+"|"+contentSha);
        var receipt=store.command(key,fingerprint,actor,()->{
            if(!store.history(id).isEmpty())throw new IllegalArgumentException("Draft already exists");
            inputs.assertCurrent(actor,request);
            access.require(actor,request.organization(),id,1,"CREATE");
            var draft=new Draft(id,1,request,content,contentSha,prepared.inputManifestHash(),
                    prepared.ready()?"PENDING_REVIEW":"INCOMPLETE_DRAFT",prepared.diagnostics(),actor,true,key,clock.instant());
            store.append(draft,0);
            inputs.assertCurrent(actor,request);
            access.require(actor,request.organization(),id,1,"CREATE");
            return new Receipt(key,"GENERATE",id,1,contentSha);
        });
        inputs.assertCurrent(actor,request);
        access.require(actor,request.organization(),id,1,"CREATE");
        return receipt;
    }
    public Receipt revise(String actor,String key,String id,long expectedRevision,String expectedContentSha,
            java.util.Map<String,String> changes,String reason) {
        var base=read(actor,id,expectedRevision);
        access.require(actor,base.manifest().organization(),id,expectedRevision,"EDIT");
        inputs.assertCurrent(actor,base.manifest());
        if(!base.contentSha256().equals(expectedContentSha))throw new IllegalArgumentException("Revision base SHA mismatch");
        var explicit=java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(changes));
        String fingerprint=sha("REVISE|"+id+"|"+expectedRevision+"|"+expectedContentSha+"|"+encode(explicit)+"|"+reason);
        var receipt=store.command(key,fingerprint,actor,()->{
            inputs.assertCurrent(actor,base.manifest());
            access.require(actor,base.manifest().organization(),id,expectedRevision,"EDIT");
            DocumentProfileEngine.Content original;
            try {original=json.readValue(base.contentJson(),DocumentProfileEngine.Content.class);}
            catch(Exception failure){throw new IllegalStateException("Structured draft content unreadable",failure);}
            String content=encode(new DocumentContentEditor().revise(original,explicit,actor,reason,clock.instant()));
            String digest=sha(content);
            var next=new Draft(id,expectedRevision+1,base.manifest(),content,digest,base.inputManifestHash(),
                    original.reviewBlocked()?"INCOMPLETE_DRAFT":"PENDING_REVIEW",base.missingItems(),actor,base.systemAssisted(),key,clock.instant());
            store.append(next,expectedRevision);
            inputs.assertCurrent(actor,base.manifest());
            access.require(actor,base.manifest().organization(),id,expectedRevision,"EDIT");
            return new Receipt(key,"REVISE",id,next.revision(),digest);
        });
        inputs.assertCurrent(actor,base.manifest());
        access.require(actor,base.manifest().organization(),id,expectedRevision,"EDIT");
        return receipt;
    }
    public Draft read(String actor,String id,long revision) {
        requireActor(actor,id);
        var draft=store.read(id,revision).orElseThrow(()->new IllegalArgumentException("Draft revision not found"));
        access.require(actor,draft.manifest().organization(),id,revision,"READ");
        inputs.assertReadable(actor,draft.manifest());
        if(!sha(draft.contentJson()).equals(draft.contentSha256()))throw new IllegalStateException("Draft content SHA mismatch");
        // Original/source verification must not freeze the document's earlier READ decision.
        access.require(actor,draft.manifest().organization(),id,revision,"READ");
        return draft;
    }
    public List<Draft> history(String actor,String id) {
        var history=store.history(id);
        if(history.isEmpty())throw new IllegalArgumentException("Draft not found");
        for(var draft:history)read(actor,id,draft.revision());
        var result=List.copyOf(history);
        // Later revision reads may outlive READ authority for an earlier retained revision.
        for(var draft:result)access.require(actor,draft.manifest().organization(),id,draft.revision(),"READ");
        return result;
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Document serialization failed",failure);}
    }
    private static void check(List<Diagnostic> diagnostics,boolean valid,String code) {
        if(!valid)diagnostics.add(new Diagnostic("",code,true));
    }
    private static void requireActor(String actor,String id) {
        if(actor==null||actor.isBlank()||id==null||id.isBlank()||id.length()>120)
            throw new IllegalArgumentException("Verified actor and draft id required");
    }
    private static String sha(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
