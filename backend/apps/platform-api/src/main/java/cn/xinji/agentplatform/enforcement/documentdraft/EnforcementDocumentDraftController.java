package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import cn.xinji.agentplatform.enforcement.documentrule.*;
import cn.xinji.agentplatform.enforcement.material.*;
import cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceStore;
import cn.xinji.agentplatform.enforcement.conduct.ConductStore;
import cn.xinji.agentplatform.enforcement.penalty.PenaltyContract;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/enforcement/document-drafts")
@ConditionalOnProperty(name="enforcement.v2.enabled",havingValue="true")
public final class EnforcementDocumentDraftController {
    public record Generate(Input input,String preparedManifestHash) { }
    public record Edit(long expectedRevision,String expectedContentSha,Map<String,String> changes,String reason) { }
    public record Regenerate(long expectedRevision,String expectedContentSha,Input input,String preparedManifestHash) { }
    public record Adopt(long expectedRevision,String expectedContentSha,String candidateId) { }
    public record ReviewInput(long revision,String contentSha,String outcome,String reason) { }
    public record ExportInput(long revision,String contentSha) { }
    @PostMapping("/{id}/exports") public Receipt export(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody ExportInput input) {
        return context(jwt).exports().create(actor(jwt),key,id,input.revision(),input.contentSha());
    }
    @GetMapping("/exports/{exportId}") public ExportArtifact exportMetadata(@AuthenticationPrincipal Jwt jwt,@PathVariable String exportId) {
        return context(jwt).exports().read(actor(jwt),exportId);
    }
    @GetMapping("/exports/{exportId}/file") public ResponseEntity<byte[]> exportFile(@AuthenticationPrincipal Jwt jwt,@PathVariable String exportId) {
        var service=context(jwt).exports();var artifact=service.read(actor(jwt),exportId);var bytes=service.bytes(actor(jwt),exportId);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Artifact-SHA256",artifact.artifactSha256())
            .header("Content-Disposition","attachment; filename=\"document-"+exportId+".docx\"")
            .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).body(bytes);
    }
    public record BaselineImport(String profileId,long profileRevision,String organization,String region,String procedure,String evidenceRef) { }
    @GetMapping("/baseline-profiles") public List<DocumentTemplateProfile> baselineProfiles(@AuthenticationPrincipal Jwt jwt,@RequestParam String organization) {
        var ctx=context(jwt);ctx.productAccess().require(actor(jwt),organization,"DOCUMENT_TEMPLATE","*",1,"READ","DOCUMENT_TEMPLATE");
        return profiles.all().stream().filter(p->"NON_OFFICIAL".equals(p.classification())).toList();
    }
    @PostMapping("/baseline-templates") public DocumentTemplateContract.Receipt importBaseline(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")String key,@RequestBody BaselineImport input) {
        var ctx=context(jwt);var profile=profiles.exact(input.profileId(),input.profileRevision());
        if(!"NON_OFFICIAL".equals(profile.classification())||input.evidenceRef()==null||input.evidenceRef().isBlank()
                ||key.length()>100)throw new IllegalArgumentException("Explicit project baseline evidence required");
        String id=profile.id();
        for(String action:List.of("CREATE","PUBLISH"))ctx.productAccess().require(actor(jwt),input.organization(),"DOCUMENT_TEMPLATE",id,1,action,"DOCUMENT_TEMPLATE");
        var service=new DocumentTemplateService(templates,templateFiles,
                (a,o,resource,revision,action)->ctx.productAccess().require(a,o,"DOCUMENT_TEMPLATE",resource,revision,action,"DOCUMENT_TEMPLATE"),Clock.systemUTC(),Set.of("SimSun"));
        var metadata=new DocumentTemplateContract.DraftInput(id,profile.title(),profile.documentType().name(),
                new DocumentTemplateContract.Scope(input.organization(),input.region(),input.procedure()),ctx.source().profileBinding(profile),
                profile.fields().stream().map(f->new DocumentTemplateContract.Field(f.id(),f.type(),f.requiredForReview(),f.repeated(),null)).toList(),List.of("SimSun"));
        var created=service.create(actor(jwt),key+"-create",metadata,id+".docx",new DocumentProfileTemplateAsset().create(profile));
        return service.publish(actor(jwt),key+"-publish",id,created.revision(),new DocumentTemplateContract.TransitionInput(created.revision(),input.evidenceRef()));
    }
    public record Problem(String code,String message,String requestId) { }
    private final Dm8DocumentDraftStore store; private final Dm8DocumentRuleStore rules; private final DocumentTemplateStore templates;
    private final DocumentTemplateFiles templateFiles; private final MaterialRepository materials; private final MaterialFiles originals;
    private final LegalReferenceStore legal; private final ConductStore conduct; private final PenaltyContract.Store penalty;
    private final ProductGrantWatermark watermark; private final JdbcTemplate db; private final ObjectMapper json;
    private final DocumentProfileCatalog profiles; private final DocumentRuleDependencyHeads heads;
    private final String issuer,caseFile,catalog;
    public EnforcementDocumentDraftController(Dm8DocumentDraftStore store,Dm8DocumentRuleStore rules,DocumentTemplateStore templates,
            DocumentTemplateFiles templateFiles,MaterialRepository materials,MaterialFiles originals,LegalReferenceStore legal,
            ConductStore conduct,PenaltyContract.Store penalty,ProductGrantWatermark watermark,JdbcTemplate db,ObjectMapper json,
            DocumentProfileCatalog profiles,DocumentRuleDependencyHeads heads,
            @Value("${product.identity.issuer:}")String issuer,
            @Value("${enforcement.v2.evidence.case-input-file:}")String caseFile,
            @Value("${enforcement.v2.document-rule.catalog-file:}")String catalog) {
        this.store=store;this.rules=rules;this.templates=templates;this.templateFiles=templateFiles;this.materials=materials;
        this.originals=originals;this.legal=legal;this.conduct=conduct;this.penalty=penalty;this.watermark=watermark;this.db=db;
        this.json=json;this.profiles=profiles;this.heads=heads;this.issuer=issuer;this.caseFile=caseFile;this.catalog=catalog;
    }
    @GetMapping("/inputs") public List<Input> options(@AuthenticationPrincipal Jwt jwt,@RequestParam String organization) {
        var ctx=context(jwt);ctx.access().require(actor(jwt),organization,"*",1,"PREPARE");
        return ctx.source().options(actor(jwt),organization,db);
    }
    @GetMapping("/input-diagnostics") public DocumentDraftInputAudit.Report inputDiagnostics(@AuthenticationPrincipal Jwt jwt,@RequestParam String organization) {
        var ctx=context(jwt);ctx.access().require(actor(jwt),organization,"*",1,"PREPARE");
        return ctx.source().audit(actor(jwt),organization,db);
    }
    @PostMapping("/{id}/prepare") public Preparation prepare(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Input input){return context(jwt).drafts().prepare(actor(jwt),id,input);}
    @PostMapping("/{id}/generate") public Receipt generate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody Generate input){return context(jwt).drafts().generate(actor(jwt),key,id,input.input(),input.preparedManifestHash());}
    @GetMapping("/{id}") public Draft read(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam long revision){return context(jwt).drafts().read(actor(jwt),id,revision);}
    @GetMapping("/{id}/history") public List<Draft> history(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return context(jwt).drafts().history(actor(jwt),id);}
    @PostMapping("/{id}/revisions") public Receipt revise(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody Edit input){return context(jwt).drafts().revise(actor(jwt),key,id,input.expectedRevision(),input.expectedContentSha(),input.changes(),input.reason());}
    @PostMapping("/{id}/candidates") public Receipt regenerate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody Regenerate input){return context(jwt).workflow().regenerate(actor(jwt),key,id,input.expectedRevision(),input.expectedContentSha(),input.input(),input.preparedManifestHash());}
    @GetMapping("/{id}/candidates") public List<Candidate> candidates(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam long revision){return context(jwt).workflow().candidates(actor(jwt),id,revision);}
    @PostMapping("/{id}/adopt") public Receipt adopt(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody Adopt input){return context(jwt).workflow().adopt(actor(jwt),key,id,input.expectedRevision(),input.expectedContentSha(),input.candidateId());}
    @PostMapping("/{id}/submit-review") public Receipt submit(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody ReviewInput input){return context(jwt).workflow().submit(actor(jwt),key,id,input.revision(),input.contentSha());}
    @PostMapping("/{id}/reviews") public Receipt review(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody ReviewInput input){return context(jwt).workflow().review(actor(jwt),key,id,input.revision(),input.contentSha(),input.outcome(),input.reason());}
    @GetMapping("/{id}/reviews") public List<Review> reviews(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam long revision){return context(jwt).workflow().reviews(actor(jwt),id,revision);}
    Context context(Jwt jwt) {
        actor(jwt);
        if(jwt.getIssuer()==null||!issuer.equals(jwt.getIssuer().toString())||jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(java.time.Instant.now()))throw new SecurityException("Current identity required");
        long generation=watermark.currentGeneration();
        Integer session=db.queryForObject("SELECT COUNT(*) FROM id2_session s JOIN id2_subject u ON u.issuer=s.issuer AND u.subject_id=s.subject_id WHERE s.issuer=? AND s.subject_id=? AND s.sid=? AND s.jti=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'",
                Integer.class,issuer,actor(jwt),jwt.getClaimAsString("sid"),jwt.getId());
        watermark.assertUnchanged(generation);if(session==null||session!=1)throw new SecurityException("Current identity session required");
        var access=new ProductEnforcementAccess(db,watermark.independentDatabase(),watermark,jwt,issuer,Clock.systemUTC());
        var source=new RuntimeDocumentDraftInputs(rules,templates,templateFiles,legal,conduct,penalty,materials,originals,access,heads,json,Path.of(caseFile),Path.of(catalog),profiles);
        Access own=(actor,organization,id,revision,action)->access.require(actor,organization,"DOCUMENT_DRAFT",id,revision,action,"DOCUMENT_DRAFT");
        var drafts=new DocumentDraftService(source,own,store,Clock.systemUTC());
        var exports=new DocumentDraftExportService(drafts,source,own,store,new DocumentDraftDocxRenderer(templates,templateFiles,json),Clock.systemUTC());
        return new Context(drafts,new DocumentDraftWorkflow(drafts,source,own,store,Clock.systemUTC(),json),source,own,access,exports);
    }
    record Context(DocumentDraftService drafts,DocumentDraftWorkflow workflow,RuntimeDocumentDraftInputs source,Access access,ProductEnforcementAccess productAccess,DocumentDraftExportService exports) { }
    static String actor(Jwt jwt){if(jwt==null||jwt.getSubject()==null||jwt.getSubject().isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return jwt.getSubject();}
    @ExceptionHandler(SecurityException.class) public ResponseEntity<Problem> forbidden(){return ResponseEntity.status(403).body(new Problem("ACCESS_UNAVAILABLE","当前授权不可用，请重新登录",UUID.randomUUID().toString()));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Problem> conflict(IllegalArgumentException failure){return ResponseEntity.status(409).body(new Problem("VERSION_OR_INPUT_CONFLICT",failure.getMessage(),UUID.randomUUID().toString()));}
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class}) public ResponseEntity<Problem> unavailable(){return ResponseEntity.status(503).body(new Problem("DEPENDENCY_UNAVAILABLE","上游版本或原件不可用，未生成成功结果",UUID.randomUUID().toString()));}
}
