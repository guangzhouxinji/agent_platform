package cn.xinji.agentplatform.adjudication.materials;

import java.time.*;
import java.util.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.Source;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;
import static cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.*;

/** Material organization only. Application ports must authorize and bind persisted candidates before calling. */
public final class MaterialCandidateDecisions {
    public enum Action { ACCEPT, EDIT, REJECT }
    public enum Semantic { HUMAN_REVIEWED_MATERIAL_STATEMENT, REJECTED_CANDIDATE }
    public enum Scope { SELECTED_MATERIALS }
    public record Limits(int textLength, int reasonLength) {
        public Limits { if(textLength<1||textLength>100000||reasonLength<1||reasonLength>100000)throw invalid("MATERIAL_DECISION_LIMIT_INVALID"); }
    }
    /** Server-derived state, never an HTTP request or permission assertion. */
    public record Current(String caseId, long workVersion, long organizationVersion, long inputVersion,
                          Instant now, Map<String,String> decidedCandidates) {
        public Current { decidedCandidates=Map.copyOf(Objects.requireNonNull(decidedCandidates)); }
    }
    public record Run(String id, String caseId, long inputVersion, Instant createdAt, Instant expiresAt) { }
    public record Request(long expectedWorkVersion, long expectedOrganizationVersion, Action action,
                          String humanText, String reason) { }
    public record AuditReference(String versionId,String runId,String candidateId,long organizationVersion,Action action) { }
    /** Transient plaintext; repositories must encrypt the entire payload under the inherited deadline. */
    public record Decision(String versionId,String baseVersionId,String caseId,String runId,String candidateId,
                           long workVersion,long organizationVersion,long inputVersion,Action action,Semantic semantic,
                           Kind kind,Side providerSide,String machineStatement,String humanText,String reason,
                           Source source,String exactQuote,int start,int end,String sourceTag,Scope scope,
                           String actor,Instant createdAt,Instant expiresAt) {
        public AuditReference auditReference(){return new AuditReference(versionId,runId,candidateId,organizationVersion,action);}
    }
    private final Limits limits;
    public MaterialCandidateDecisions(Limits limits){this.limits=Objects.requireNonNull(limits);}

    public Decision decide(Run run,Candidate candidate,Input original,Current current,Request request,
                           String actor,String versionId) {
        validate(run,candidate,original,current,request,actor,versionId);
        if(current.decidedCandidates().containsKey(key(run.id(),candidate.id())))throw invalid("MATERIAL_CANDIDATE_ALREADY_DECIDED");
        return result(null,run,candidate,original,current,request,actor,versionId);
    }
    public Decision revise(Decision prior,Run run,Input original,Current current,Request request,
                           String actor,String versionId) {
        if(prior==null||run==null||current==null||request==null)throw invalid("MATERIAL_DECISION_REQUIRED");
        if(prior.semantic()!=Semantic.HUMAN_REVIEWED_MATERIAL_STATEMENT||prior.scope()!=Scope.SELECTED_MATERIALS
                ||(prior.action()!=Action.ACCEPT&&prior.action()!=Action.EDIT)||request.action()!=Action.EDIT)
            throw invalid("MATERIAL_DECISION_REVISION_NOT_ALLOWED");
        if(!Objects.equals(prior.caseId(),run.caseId())||!Objects.equals(prior.runId(),run.id())
                ||prior.inputVersion()!=run.inputVersion()||!Objects.equals(prior.expiresAt(),run.expiresAt())
                ||!Objects.equals(current.decidedCandidates().get(key(run.id(),prior.candidateId())),prior.versionId()))
            throw invalid("MATERIAL_DECISION_BASE_VERSION_CONFLICT");
        if(Objects.equals(prior.versionId(),versionId))throw invalid("MATERIAL_DECISION_VERSION_REUSE");
        Candidate candidate=new Candidate(prior.candidateId(),prior.kind(),prior.providerSide(),prior.machineStatement(),
                prior.exactQuote(),prior.start(),prior.end(),prior.source().id(),prior.source().version(),
                prior.source().sha256(),prior.source().locator(),prior.sourceTag());
        validate(run,candidate,original,current,request,actor,versionId);
        return result(prior.versionId(),run,candidate,original,current,request,actor,versionId);
    }
    private void validate(Run run,Candidate c,Input original,Current current,Request r,String actor,String versionId) {
        if(run==null||c==null||original==null||current==null||r==null||r.action()==null)throw invalid("MATERIAL_DECISION_REQUIRED");
        uuid(run.id());uuid(versionId);text(actor,200,"MATERIAL_DECISION_ACTOR_INVALID");
        if(versionId.equals(run.id())||current.decidedCandidates().containsValue(versionId))throw invalid("MATERIAL_DECISION_VERSION_REUSE");
        text(c.sourceTag(),20,"MATERIAL_DECISION_SOURCE_DRIFTED");
        text(c.id(),20,"MATERIAL_CANDIDATE_INVALID");if(!c.id().matches("c[1-9][0-9]{0,3}")||c.kind()==null||c.providerSide()==null)throw invalid("MATERIAL_CANDIDATE_INVALID");
        if(run.caseId()==null||!run.caseId().matches("[A-Za-z0-9_-]{1,120}")||!run.caseId().equals(current.caseId()))throw invalid("MATERIAL_DECISION_CASE_CONFLICT");
        if(current.workVersion()<1||current.organizationVersion()<0||current.inputVersion()<1||current.inputVersion()>current.workVersion()
                ||r.expectedWorkVersion()<1||r.expectedOrganizationVersion()<0)throw invalid("MATERIAL_DECISION_VERSION_INVALID");
        if(r.expectedWorkVersion()!=current.workVersion()||r.expectedOrganizationVersion()!=current.organizationVersion())throw invalid("MATERIAL_DECISION_VERSION_CONFLICT");
        if(run.inputVersion()!=current.inputVersion())throw invalid("MATERIAL_MODEL_RUN_STALE");
        if(current.now()==null||run.createdAt()==null||run.expiresAt()==null||!run.expiresAt().isAfter(run.createdAt())
                ||Duration.between(run.createdAt(),run.expiresAt()).compareTo(Duration.ofDays(30))>0)throw invalid("MATERIAL_DECISION_RETENTION_INVALID");
        if(current.now().isBefore(run.createdAt()))throw new SecurityException("MATERIAL_DECISION_CLOCK_INVALID");
        if(!run.expiresAt().isAfter(current.now()))throw new SecurityException("MATERIAL_DECISION_EXPIRED");
        Source source=original.source();
        if(source==null||original.text()==null||!Objects.equals(c.sourceTag(),original.tag())||c.providerSide()!=original.side()
                ||!Objects.equals(c.sourceId(),source.id())||!Objects.equals(c.sourceVersion(),source.version())
                ||!Objects.equals(c.sourceSha256(),source.sha256())||!Objects.equals(c.originalLocator(),source.locator())
                ||!sha256(original.text()).equals(source.sha256()))throw invalid("MATERIAL_DECISION_SOURCE_DRIFTED");
        text(c.exactQuote(),16000,"MATERIAL_DECISION_QUOTE_INVALID");text(c.statement(),2000,"MATERIAL_CANDIDATE_INVALID");
        if(c.start()<0||c.end()<=c.start()||c.end()>original.text().length()||!original.text().substring(c.start(),c.end()).equals(c.exactQuote())
                ||(c.start()>0&&Character.isLowSurrogate(original.text().charAt(c.start())))
                ||(c.end()<original.text().length()&&Character.isLowSurrogate(original.text().charAt(c.end()))))throw invalid("MATERIAL_DECISION_QUOTE_DRIFTED");
        if(r.action()==Action.EDIT){text(r.humanText(),limits.textLength(),"MATERIAL_HUMAN_TEXT_REQUIRED");text(r.reason(),limits.reasonLength(),"MATERIAL_DECISION_REASON_REQUIRED");}
        else {if(r.humanText()!=null)throw invalid("MATERIAL_DECISION_HUMAN_TEXT_NOT_ALLOWED");
            if(r.action()==Action.ACCEPT)text(c.statement(),limits.textLength(),"MATERIAL_HUMAN_TEXT_TOO_LONG");
            if(r.action()==Action.REJECT)text(r.reason(),limits.reasonLength(),"MATERIAL_DECISION_REASON_REQUIRED");
            else if(r.reason()!=null)text(r.reason(),limits.reasonLength(),"MATERIAL_DECISION_REASON_INVALID");}
    }
    private Decision result(String base,Run run,Candidate c,Input original,Current current,Request r,String actor,String versionId) {
        long work,organization;try{work=Math.addExact(current.workVersion(),1);organization=Math.addExact(current.organizationVersion(),1);}
        catch(ArithmeticException overflow){throw invalid("MATERIAL_DECISION_VERSION_INVALID");}
        Semantic semantic=r.action()==Action.REJECT?Semantic.REJECTED_CANDIDATE:Semantic.HUMAN_REVIEWED_MATERIAL_STATEMENT;
        String human=r.action()==Action.REJECT?null:r.action()==Action.EDIT?r.humanText():c.statement();
        return new Decision(versionId,base,run.caseId(),run.id(),c.id(),work,organization,current.inputVersion(),r.action(),semantic,
                c.kind(),c.providerSide(),c.statement(),human,r.reason(),original.source(),c.exactQuote(),c.start(),c.end(),c.sourceTag(),
                Scope.SELECTED_MATERIALS,actor,current.now(),run.expiresAt());
    }
    private static String key(String runId,String candidateId){return runId+":"+candidateId;}
    private static void uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw invalid("MATERIAL_DECISION_ID_INVALID");}
    private static void text(String value,int max,String code){
        if(value==null||value.isBlank()||value.length()>max)throw invalid(code);
        for(int i=0;i<value.length();i++){char ch=value.charAt(i);if(Character.isHighSurrogate(ch)){
            if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw invalid(code);
        }else if(Character.isLowSurrogate(ch))throw invalid(code);}
    }
    private static IllegalArgumentException invalid(String code){return new IllegalArgumentException(code);}
}
