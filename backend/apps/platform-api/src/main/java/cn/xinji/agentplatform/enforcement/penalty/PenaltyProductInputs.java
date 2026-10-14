package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.enforcement.legalreference.*;
import cn.xinji.agentplatform.enforcement.conduct.ConductStore;
import cn.xinji.agentplatform.enforcement.procedure.ProcedureStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads exact existing V2 upstream product revisions and authorized case source, never request-supplied review flags. */
public final class PenaltyProductInputs implements Inputs {
    private final ProductEnforcementAccess access;
    private final LegalReferenceStore legal;
    private final LegalKnowledgePort source;
    private final LegalReferenceService.Target targets;
    private final ConductStore conduct;
    private final ProcedureStore procedure;
    private final EvidenceOrganizationStore evidence;
    private final ObjectMapper json;
    private final Path caseSource;
    private final Store current;
    private final cn.xinji.agentplatform.enforcement.material.MaterialRepository materials;
    private final cn.xinji.agentplatform.enforcement.material.MaterialFiles files;
    public PenaltyProductInputs(ProductEnforcementAccess access,LegalReferenceStore legal,LegalKnowledgePort source,
            LegalReferenceService.Target targets,ConductStore conduct,ProcedureStore procedure,EvidenceOrganizationStore evidence,
            ObjectMapper json,Path caseSource,Store current,
            cn.xinji.agentplatform.enforcement.material.MaterialRepository materials,cn.xinji.agentplatform.enforcement.material.MaterialFiles files) {
        this.access=access;this.legal=legal;this.source=source;this.targets=targets;this.conduct=conduct;
        this.procedure=procedure;this.evidence=evidence;this.json=json;this.caseSource=caseSource;this.current=current;this.materials=materials;this.files=files;
    }
    @Override public void assertCurrentAccess(String actor,PenaltyPublishChecks.Input input) {
        access.require(actor,input.organization(),"LEGAL_REFERENCE",input.basisRef(),input.basisRevision(),"READ","LEGAL_BASIS");
    }
    @Override public PenaltyPublishChecks.Basis legalBasis(String actor,String org,String id,long revision) {
        access.require(actor,org,"LEGAL_REFERENCE",id,revision,"READ","LEGAL_BASIS");
        var ref=legal.reference(id,revision).orElseThrow(()->new SecurityException("Legal reference unavailable"));
        if(!org.equals(ref.organization()))throw new SecurityException("Cross-organization legal reference");
        access.require(actor,org,"LEGAL_SOURCE",ref.sourceId(),ref.sourceRevision(),"READ_VERBATIM","LEGAL_BASIS");
        access.require(actor,org,ref.targetType(),ref.targetId(),ref.targetRevision(),"READ_LEGAL_BASIS","LEGAL_BASIS");
        var exact=source.exact(ref.sourceId(),ref.sourceVersion(),ref.candidateId());
        if(!ref.verbatimSha256().equals(exact.verbatimSha256()))throw new IllegalStateException("Legal original changed");
        var gaps=new ArrayList<>(ref.gaps());
        var latest=source.latest(ref.sourceId(),ref.articleId());
        if(!latest.sourceVersion().equals(ref.sourceVersion())||!latest.verbatimSha256().equals(ref.verbatimSha256()))gaps.add("LEGAL_SOURCE_UPDATED");
        if(legal.latestReferenceVersion(id)!=revision)gaps.add("LEGAL_REFERENCE_VERSION_CHANGED");
        String digest=targets.require(actor,org,ref.targetType(),ref.targetId(),ref.targetRevision(),ref.targetAnchor(),"LEGAL_BASIS");
        if(!ref.targetDigest().equals(digest))gaps.add("LEGAL_TARGET_CHANGED");
        if(!"VERIFIED".equals(ref.effectStatus())||!"CLEAR".equals(ref.transitionStatus()))gaps.add("LEGAL_APPLICABILITY_UNCONFIRMED");
        assertCurrentAccess(actor,new PenaltyPublishChecks.Input(org,true,id,revision,null,null,null,List.of(),List.of()));
        return new PenaltyPublishChecks.Basis(id,revision,ref.reviewState().name(),ref.bindingState().name(),List.copyOf(gaps));
    }
    @Override public void assertRuleCurrent(String actor,Rule rule) {
        if(rule.calculation()==null)throw new IllegalArgumentException("Calculation required");
        assertCurrentAccess(actor,rule.calculation());
        // On recovery this is configured against the live source, not the restored database.
        var head=current.latestRule(rule.id());
        if(rule.state()==RuleState.ACTIVE&&(head.isEmpty()||head.get().revision()!=rule.revision()||head.get().state()!=RuleState.ACTIVE))
            throw new IllegalStateException("Current rule head changed; recovered permission cannot be reused");
    }
    @Override public void assertCaseCurrent(String actor,CaseInput input) {
        access.require(actor,input.organization(),"CASE",input.caseId(),input.caseRevision(),"READ","ANALYZE");
        readCase(input);
        for(Ref r:input.dependencies()) {
            String action="PROCEDURE".equals(r.kind())?"READ_REPORT":"READ";
            String purpose="PROCEDURE".equals(r.kind())?"PROCEDURE_CHECK":"DISPLAY";
            access.require(actor,input.organization(),resourceType(r.kind()),r.id(),r.revision(),action,purpose);
        }
        for(Ref r:input.factors())access.require(actor,input.organization(),r.kind(),r.id(),r.revision(),"READ","ANALYZE");
        for(var amount:input.amounts().values()) {
            if(amount==null||amount.sourceRef()==null||amount.sourceVersion()==null)throw new IllegalArgumentException("Amount provenance required");
            access.require(actor,input.organization(),"CASE",amount.sourceRef(),Long.parseLong(amount.sourceVersion()),"READ","ANALYZE");
        }
    }
    @Override public void assertRuleTransitionCompleted(String actor,Rule successor) {
        assertCurrentAccess(actor,successor.calculation());
        var head=current.latestRule(successor.id()).orElseThrow(()->new IllegalStateException("Current transitioned rule unavailable"));
        if(!head.equals(successor))throw new IllegalStateException("Current transitioned rule head changed; recovered transition cannot be reused");
    }
    @Override public CaseStatus caseStatus(String actor,CaseInput input) {
        assertCaseCurrent(actor,input);var snapshot=readCase(input);var gaps=new ArrayList<String>();boolean facts=false,authority=false,procedureApplicable=false;
        if(!input.conductType().equals(snapshot.path("conductType").asText())||!input.procedure().equals(snapshot.path("procedure").asText())
            ||!input.region().equals(snapshot.path("region").asText())||!input.applicableDate().toString().equals(snapshot.path("applicableAt").asText()))gaps.add("CASE_SCOPE_INPUT_MISSING_OR_CHANGED");
        var resolved=new ArrayList<Ref>();
        for(Ref r:input.dependencies()) {
            Object payload;
            switch(r.kind()) {
                case "CONDUCT" -> {
                    var report=conduct.report(r.id(),r.revision()).orElseThrow(()->new SecurityException("Conduct unavailable"));payload=report;
                    sameCase(input,report.organization(),report.caseRef(),report.caseVersion());
                    if(conduct.latestReportVersion(r.id())!=r.revision())gaps.add("CONDUCT_VERSION_CHANGED");
                    var rule=conduct.latestRule(report.ruleId()).orElseThrow(()->new SecurityException("Conduct rule unavailable"));
                    if(rule.version()!=report.ruleVersion()||!"ACTIVE".equals(rule.state()))gaps.add("CONDUCT_RULE_CHANGED");
                    if(input.dependencies().stream().noneMatch(d->"EVIDENCE".equals(d.kind())&&d.id().equals(report.evidenceOrganizationId())&&d.revision()==report.evidenceOrganizationVersion()))gaps.add("CONDUCT_EVIDENCE_COORDINATE_MISMATCH");
                    // A machine SUSPECTED outcome is not confirmation. Independently named case-source
                    // facts must be exactly present and confirmed; review alone cannot synthesize them.
                    facts="REVIEWED".equals(report.reviewState())&&report.reviewedBy()!=null&&report.gaps().isEmpty()
                        &&confirmedFacts(input,snapshot,report.facts());
                    if(report.outcome()==cn.xinji.agentplatform.enforcement.conduct.ConductContract.Outcome.EXCLUSION_INDICATED)gaps.add("CONDUCT_EXCLUSION_INDICATED");
                    authority=report.scope()!=null&&report.scope().state()==cn.xinji.agentplatform.enforcement.conduct.ConductContract.Applicability.MATCH;
                    gaps.addAll(report.gaps());
                }
                case "PROCEDURE" -> {
                    var report=procedure.report(r.id(),r.revision()).orElseThrow(()->new SecurityException("Procedure unavailable"));payload=report;
                    sameCase(input,report.organization(),report.caseRef(),report.caseVersion());
                    if(procedure.latestReport(r.id()).orElseThrow().version()!=r.revision())gaps.add("PROCEDURE_VERSION_CHANGED");
                    var rule=procedure.latestRule(report.ruleId()).orElseThrow(()->new SecurityException("Procedure rule unavailable"));
                    if(rule.version()!=report.ruleVersion()||!"ACTIVE".equals(rule.state()))gaps.add("PROCEDURE_RULE_CHANGED");
                    procedureApplicable="COMPLETE".equals(report.executionState())&&report.unprocessed().isEmpty()&&!report.items().isEmpty()&&report.items().stream().allMatch(i->i.state()==cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.State.PASS);
                }
                case "EVIDENCE" -> {
                    var report=evidence.revision(r.id(),r.revision()).orElseThrow(()->new SecurityException("Evidence unavailable"));payload=report;
                    sameCase(input,report.organization(),report.caseRef(),report.caseVersion());
                    if(evidence.latest(r.id())!=r.revision())gaps.add("EVIDENCE_VERSION_CHANGED");
                    new cn.xinji.agentplatform.enforcement.evidence.VerifiedEvidenceSource(caseSource,json,materials,files,access,java.time.Clock.systemUTC())
                        .read(input.caseId(),Long.toString(input.caseRevision()),report.evidenceSet());
                    if(!"CONFIRMED".equals(report.state())||report.reviewedBy()==null||!report.unprocessed().isEmpty()||report.matters().isEmpty()||report.matters().stream().anyMatch(m->!"PRESENT".equals(m.coverage())))gaps.add("EVIDENCE_REVIEW_OR_GAP");
                }
                default -> throw new IllegalArgumentException("Unsupported dependency kind");
            }
            String sha=sha(payload);if(!sha.equals(r.sha256()))throw new IllegalArgumentException("Dependency digest mismatch");
            resolved.add(new Ref(r.kind(),r.id(),r.revision(),sha));
        }
        // Numeric provenance must match the retained authoritative case input, not the browser's confirmed flag.
        var amounts=snapshot.path("amounts");
        for(var entry:input.amounts().entrySet()) {
            var original=amounts.path(entry.getKey());
            if(!sameAmountSource(json,entry.getValue(),original))gaps.add("AMOUNT_SOURCE_MISMATCH:"+entry.getKey());
        }
        assertCaseCurrent(actor,input);return new CaseStatus(authority,procedureApplicable,facts,List.copyOf(gaps),List.copyOf(resolved));
    }
    static boolean sameAmountSource(ObjectMapper json,PenaltyFormula.Amount amount,com.fasterxml.jackson.databind.JsonNode original) {
        com.fasterxml.jackson.databind.JsonNode submitted=json.valueToTree(amount);
        // JSON numbers have no IntNode/DecimalNode business distinction. Only normalize an
        // exactly equal numeric value; every provenance field and confirmation stays exact.
        if(amount!=null&&amount.value()!=null&&original.isObject()&&original.path("value").isNumber()
                &&amount.value().compareTo(original.path("value").decimalValue())==0) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)submitted).set("value",original.get("value"));
        }
        return submitted.equals(original);
    }
    private com.fasterxml.jackson.databind.JsonNode readCase(CaseInput input) {
        if(caseSource==null)throw new IllegalStateException("Authorized case source not configured");
        try {
            var value=json.readTree(Files.readAllBytes(caseSource));
            if(!input.caseId().equals(value.path("caseRef").asText())||!Long.toString(input.caseRevision()).equals(value.path("version").asText())||!input.organization().equals(value.path("organization").asText()))throw new SecurityException("Case source coordinate mismatch");
            return value;
        }catch(java.io.IOException e){throw new IllegalStateException("Case source unavailable",e);}
    }
    private String sha(Object value) {try{return PenaltyArtifacts.sha(json.writeValueAsBytes(value));}catch(java.io.IOException e){throw new IllegalStateException("Upstream serialization failed",e);}}
    private boolean confirmedFacts(CaseInput input,com.fasterxml.jackson.databind.JsonNode source,
            List<cn.xinji.agentplatform.enforcement.conduct.ConductContract.Fact> facts) {
        if(input.confirmedFacts()==null||input.confirmedFacts().isEmpty()||facts==null||facts.isEmpty())return false;
        if(!json.valueToTree(input.confirmedFacts()).equals(source.path("confirmedFacts")))return false;
        if(input.confirmedFacts().stream().anyMatch(f->f==null||!"CONFIRMED".equals(f.state())||blank(f.confirmedBy())||blank(f.confirmationRef())
            ||blank(f.ref())||blank(f.sourceRef())||blank(f.sourceVersion())||blank(f.locator())))return false;
        return facts.stream().allMatch(f->input.confirmedFacts().stream().anyMatch(c->c.ref().equals(f.ref())&&c.sourceRef().equals(f.sourceRef())&&c.locator().equals(f.locator())));
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String resourceType(String kind){return switch(kind){case "CONDUCT"->"CONDUCT_ANALYSIS";case "PROCEDURE"->"PROCEDURE_REPORT";case "EVIDENCE"->"EVIDENCE_ORGANIZATION";default->throw new IllegalArgumentException("Unsupported dependency kind");};}
    private static void sameCase(CaseInput i,String org,String ref,String revision) {if(!i.organization().equals(org)||!i.caseId().equals(ref)||!Long.toString(i.caseRevision()).equals(revision))throw new SecurityException("Dependency case mismatch");}
}
