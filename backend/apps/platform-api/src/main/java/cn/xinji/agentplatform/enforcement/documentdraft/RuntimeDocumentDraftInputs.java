package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import cn.xinji.agentplatform.enforcement.documentrule.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import cn.xinji.agentplatform.enforcement.evidence.*;
import cn.xinji.agentplatform.enforcement.material.*;
import cn.xinji.agentplatform.enforcement.legalreference.*;
import cn.xinji.agentplatform.enforcement.conduct.ConductStore;
import cn.xinji.agentplatform.enforcement.penalty.PenaltyContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Product adapter: exact published objects, locked case materials, configured schema originals and current grants. */
public final class RuntimeDocumentDraftInputs implements Inputs {
    private final Dm8DocumentRuleStore rules; private final DocumentTemplateStore templates; private final DocumentTemplateFiles files;
    private final LegalReferenceStore legal; private final ConductStore conduct; private final PenaltyContract.Store penalty;
    private final FileDocumentRuleInputs mapping; private final MaterialAccess access; private final VerifiedEvidenceSource evidence;
    private final Path caseFile,catalogFile; private final DocumentProfileCatalog profiles; private final ObjectMapper json;
    private final MaterialRepository materials;private final MaterialFiles originals;
    public RuntimeDocumentDraftInputs(Dm8DocumentRuleStore rules,DocumentTemplateStore templates,DocumentTemplateFiles files,
            LegalReferenceStore legal,ConductStore conduct,PenaltyContract.Store penalty,MaterialRepository materials,MaterialFiles originals,
            MaterialAccess access,DocumentRuleDependencyHeads heads,ObjectMapper json,Path caseFile,Path catalogFile,DocumentProfileCatalog profiles) {
        this.rules=rules;this.templates=templates;this.files=files;this.legal=legal;this.conduct=conduct;this.penalty=penalty;
        this.access=access;this.caseFile=caseFile;this.catalogFile=catalogFile;this.profiles=profiles;
        this.materials=materials;this.originals=originals;
        this.json=json.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.mapping=new FileDocumentRuleInputs(catalogFile,json,templates,files,access,heads);
        this.evidence=new VerifiedEvidenceSource(caseFile,json,materials,originals,access,Clock.systemUTC());
    }
    @Override public Resolved resolve(String actor,Input input) {
        assertCurrent(actor,input);
        var rule=rules.read(input.rule().id(),input.rule().revision()).orElseThrow();
        var template=templates.revision(input.template().id(),input.template().revision()).orElseThrow();
        var profile=profiles.all().stream().filter(p->profileBinding(p).equals(template.approvalRef())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Exact profile binding unavailable; import the configured profile template"));
        if(profile.documentType()!=input.type())throw new IllegalArgumentException("Template document type mismatch");
        var expected=profile.fields().stream().map(f->f.id()+"|"+f.type()+"|"+f.requiredForReview()+"|"+f.repeated()).sorted().toList();
        var actual=template.fields().stream().map(f->f.id()+"|"+f.type()+"|"+f.required()+"|"+f.repeat()).sorted().toList();
        if(!expected.equals(actual))throw new IllegalArgumentException("Profile fields differ from the exact template version");
        var snapshot=catalog().schemas().stream().filter(s->s.organization().equals(input.organization())
                &&s.schema().id().equals(rule.schemaId())&&s.schema().version().equals(rule.schemaVersion())).toList();
        if(snapshot.size()!=1)throw new IllegalArgumentException("Exact source snapshot required");
        var source=snapshot.getFirst();
        // The external logical source version is not a MATERIAL resource ID/locked revision.
        // Match exact original bytes admitted into this case; preserve both coordinates separately.
        if(input.evidence().stream().noneMatch(v->v.sha256().equals(source.sourceSha256())))
            throw new IllegalArgumentException("Mapping original must be an admitted exact case material, not unrelated configured data");
        var schema=mapping.schema(actor,input.organization(),rule.schemaId(),rule.schemaVersion());
        var values=mapping.values(actor,input.organization(),schema,"DOCUMENT_MAPPING");
        var sources=new TreeMap<String,DocumentProfileEngine.SourceDatum>();
        values.forEach((path,value)->sources.put(path,new DocumentProfileEngine.SourceDatum(value.value(),value.sourceRef(),
                Long.parseLong(value.sourceVersion()),snapshot.getFirst().sourceSha256(),value.locator(),value.confirmed())));
        var trial=new DocumentMappingValidator(mapping).trial(actor,rule,"DOCUMENT_MAPPING");
        var context=caseJson();
        return new Resolved(input,true,true,true,!input.legalReferences().isEmpty(),input.conduct()!=null,input.penalty()!=null,trial,profile,rule,
                context.path("region").asText(null),context.path("stage").asText(null),sources);
    }
    @Override public void assertCurrent(String actor,Input input) {
        assertReadable(actor,input);
        var rule=rules.read(input.rule().id(),input.rule().revision()).orElseThrow();
        var head=rules.latest(input.rule().id()).orElseThrow();
        if(!input.organization().equals(rule.organization())||!hash(encode(rule)).equals(input.rule().sha256())
                ||head.revision()!=rule.revision()||rule.state()!=DocumentRuleContract.State.PUBLISHED
                ||!rule.templateId().equals(input.template().id())||rule.templateRevision()!=input.template().revision()
                ||!rule.templateSha256().equals(input.template().sha256()))throw new IllegalArgumentException("Published rule or exact binding changed");
        mapping.assertCurrent(actor,input.organization(),rule,"DOCUMENT_MAPPING");
        if(!hash(read(caseFile)).equals(input.caseInput().sha256()))throw new IllegalArgumentException("Case original SHA mismatch");
        var snapshot=evidence.read(input.caseInput().id(),Long.toString(input.caseInput().revision()),input.evidence().stream()
                .map(v->new EvidenceTrialSource.Ref(v.id(),Long.toString(v.revision()),v.sha256())).toList());
        if(!snapshot.organization().equals(input.organization()))throw new SecurityException("Case organization mismatch");
        for(var ref:input.legalReferences()) {
            var value=legal.reference(ref.id(),ref.revision()).orElseThrow();
            var search=legal.search(value.searchId(),value.searchRevision()).orElseThrow();
            if(legal.latestReferenceVersion(ref.id())!=ref.revision()||value.reviewState()!=LegalReferenceContract.ReviewState.CONFIRMED
                    ||value.bindingState()!=LegalReferenceContract.BindingState.ACTIVE||!search.caseRef().equals(input.caseInput().id()))
                throw new IllegalArgumentException("Case legal reference is not current and confirmed");
        }
        if(input.type()!=Type.FACT_RECORD) {
            if(input.conduct()==null)throw new IllegalArgumentException("Reviewed conduct input required");
            var ref=input.conduct();access.require(actor,input.organization(),"CONDUCT_ANALYSIS",ref.id(),ref.revision(),"READ","DISPLAY");
            var report=conduct.report(ref.id(),ref.revision()).orElseThrow();
            if(!hash(encode(report)).equals(ref.sha256())||conduct.latestReportVersion(ref.id())!=ref.revision()
                    ||!"REVIEWED".equals(report.reviewState())||!report.organization().equals(input.organization())
                    ||!report.caseRef().equals(input.caseInput().id())||!report.caseVersion().equals(Long.toString(input.caseInput().revision())))
                throw new IllegalArgumentException("Conduct report is not a current reviewed case input");
        }
        if(input.type()==Type.PENALTY_INPUT_REVIEW) {
            if(input.penalty()==null)throw new IllegalArgumentException("Reviewed penalty input required");
            var ref=input.penalty();access.require(actor,input.organization(),"PENALTY_SUGGESTION",ref.id(),ref.revision(),"READ","PENALTY_ANALYSIS");
            var value=penalty.suggestion(ref.id(),ref.revision()).orElseThrow();
            if(!hash(encode(value)).equals(ref.sha256())||penalty.latestSuggestion(ref.id()).orElseThrow().revision()!=ref.revision()
                    ||value.state()!=PenaltyContract.SuggestionState.REVIEWED||!value.organization().equals(input.organization())
                    ||!value.input().caseId().equals(input.caseInput().id())||value.input().caseRevision()!=input.caseInput().revision())
                throw new IllegalArgumentException("Penalty suggestion is not a current reviewed case input");
        }
    }
    @Override public void assertReadable(String actor,Input input) {
        access.require(actor,input.organization(),"CASE",input.caseInput().id(),input.caseInput().revision(),"READ","DOCUMENT_DRAFT");
        access.require(actor,input.organization(),"DOCUMENT_RULE",input.rule().id(),input.rule().revision(),"READ","DOCUMENT_MAPPING");
        var exactRule=rules.read(input.rule().id(),input.rule().revision()).orElseThrow(()->new IllegalStateException("Historical rule missing"));
        if(!exactRule.organization().equals(input.organization())||!hash(encode(exactRule)).equals(input.rule().sha256()))throw new IllegalStateException("Historical rule SHA mismatch");
        access.require(actor,input.organization(),"DOCUMENT_TEMPLATE",input.template().id(),input.template().revision(),"READ","DOCUMENT_TEMPLATE");
        var template=templates.revision(input.template().id(),input.template().revision()).orElseThrow();
        if(!template.scope().organization().equals(input.organization())||!template.fileSha256().equals(input.template().sha256())
                ||!hash(files.read(template.fileKey())).equals(input.template().sha256()))throw new IllegalStateException("Template original unavailable or changed");
        for(var ref:input.evidence()) {
            access.require(actor,input.organization(),"MATERIAL",ref.id(),ref.revision(),"READ","DOCUMENT_DRAFT");
            var material=materials.material(ref.id()).orElseThrow(()->new IllegalStateException("Material original missing"));
            if(material.version()!=ref.revision()||!material.organization().equals(input.organization())||!material.sha256().equals(ref.sha256())
                    ||!hash(originals.read(material.fileKey())).equals(ref.sha256()))throw new IllegalStateException("Material original SHA mismatch");
        }
        for(var ref:input.legalReferences()) {
            access.require(actor,input.organization(),"LEGAL_REFERENCE",ref.id(),ref.revision(),"READ","LEGAL_REFERENCE");
            var value=legal.reference(ref.id(),ref.revision()).orElseThrow();
            if(!value.organization().equals(input.organization())||!ref.sha256().equals(value.verbatimSha256())
                    ||!hash(value.verbatimText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(ref.sha256()))
                throw new IllegalStateException("Legal original SHA mismatch");
        }
        if(input.conduct()!=null)access.require(actor,input.organization(),"CONDUCT_ANALYSIS",input.conduct().id(),input.conduct().revision(),"READ","DISPLAY");
        if(input.penalty()!=null)access.require(actor,input.organization(),"PENALTY_SUGGESTION",input.penalty().id(),input.penalty().revision(),"READ","PENALTY_ANALYSIS");
    }
    public List<Input> options(String actor,String organization,org.springframework.jdbc.core.JdbcTemplate db) {
        return audit(actor,organization,db).inputs();
    }
    public DocumentDraftInputAudit.Report audit(String actor,String organization,org.springframework.jdbc.core.JdbcTemplate db) {
        return new DocumentDraftInputAudit(this,db,access,json,caseFile,catalogFile,profiles,templates,rules,legal,materials,originals,evidence).inspect(actor,organization);
    }
    private FileDocumentRuleInputs.Catalog catalog(){try{return json.readValue(read(catalogFile),FileDocumentRuleInputs.Catalog.class);}catch(Exception failure){throw new IllegalStateException("Source catalog unavailable",failure);}}
    private com.fasterxml.jackson.databind.JsonNode caseJson(){try{return json.readTree(read(caseFile));}catch(Exception failure){throw new IllegalStateException("Case context unavailable",failure);}}
    public String profileBinding(DocumentTemplateProfile profile){return "PROFILE/"+profile.id()+"@"+profile.revision()+"#"+hash(encode(profile));}
    private byte[] encode(Object value){try{return json.writeValueAsBytes(value);}catch(Exception failure){throw new IllegalStateException("Source serialization failed",failure);}}
    private static byte[] read(Path path){try{return Files.readAllBytes(path);}catch(Exception failure){throw new IllegalStateException("Original unavailable",failure);}}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
