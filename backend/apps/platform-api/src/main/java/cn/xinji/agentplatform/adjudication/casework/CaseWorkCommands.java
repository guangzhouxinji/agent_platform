package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import java.util.function.UnaryOperator;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.*;

/** Routes typed product commands to the approved 01A core; no client-supplied actor or policy. */
public final class CaseWorkCommands {
    private final CaseWorkRepository repository;
    private final CaseWorkCodec codec;
    private final Policy policy;
    private final Clock clock;
    public CaseWorkCommands(CaseWorkRepository repository, CaseWorkCodec codec, Policy policy, Clock clock) {
        this.repository=repository;this.codec=codec;this.policy=policy;this.clock=clock;
    }
    public CaseWorkRepository.Receipt create(JsonNode body,String key,CaseWorkRepository.Access access) {
        fields(body,"input","originalText"); var input=codec.request(required(body,"input"),CaseInput.class);
        validId(input.id());
        return repository.create(input,string(body,"originalText"),key,fingerprint("CREATE",input.id(),body,access),access);
    }
    public CaseWorkRepository.Receipt execute(String id,String operation,Long reportRevision,JsonNode body,String key,
                                               CaseWorkRepository.Access access) {
        validId(id);long version=version(body);var incoming=new ArrayList<Source>();cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Original caseOriginal=null;UnaryOperator<State> mutation;
        String actor=access.actor();var now=clock.instant();
        switch(operation) {
            case "CONTEXT" -> {
                fields(body,"expectedVersion","input","originalText");var input=codec.request(required(body,"input"),CaseInput.class);
                String original=string(body,"originalText");if(!sha256(original).equals(input.source().sha256()))throw new IllegalArgumentException("CASE_ORIGINAL_REQUIRED_OR_DRIFTED");
                incoming.add(input.source());caseOriginal=new cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Original(input.source(),original);mutation=state->context(state,input,actor,now);
            }
            case "MATERIAL" -> {
                fields(body,"expectedVersion","material");var material=codec.request(required(body,"material"),Material.class);
                incoming.add(material.source());mutation=state->addMaterial(state,version,material,actor,now);
            }
            case "CLAIM" -> {
                fields(body,"expectedVersion","claim");var claim=codec.request(required(body,"claim"),Claim.class);
                incoming.add(claim.source());mutation=state->selectClaim(state,version,claim,actor,now);
            }
            case "CITATION" -> {
                fields(body,"expectedVersion","citation");var citation=codec.request(required(body,"citation"),Citation.class);
                incoming.add(citation.source());mutation=state->addCitation(state,version,citation,actor,now);
            }
            case "FINDING" -> {
                fields(body,"expectedVersion","finding");var finding=codec.request(required(body,"finding"),Finding.class);
                mutation=state->addFinding(state,version,finding,actor,now);
            }
            case "GENERATE_REPORT" -> {fields(body,"expectedVersion");mutation=state->generateReport(state,version,policy,actor,now);}
            case "REVISE_REPORT" -> {
                fields(body,"expectedVersion","reportSha256","sectionId","content","reason");positive(reportRevision);
                String hash=string(body,"reportSha256"),section=string(body,"sectionId"),content=string(body,"content"),reason=string(body,"reason");
                mutation=state->reviseReport(state,version,reportRevision,hash,section,content,reason,policy,actor,now);
            }
            case "SUBMIT" -> {
                fields(body,"expectedVersion","reportSha256");positive(reportRevision);String hash=string(body,"reportSha256");
                mutation=state->{requireCurrentPolicy(state,reportRevision);return submit(state,version,reportRevision,hash,actor,now);};
            }
            case "REVIEW" -> {
                fields(body,"expectedVersion","reportSha256","outcome","reason");positive(reportRevision);
                String hash=string(body,"reportSha256"),reason=string(body,"reason");var outcome=ReviewState.valueOf(string(body,"outcome"));
                mutation=state->{requireCurrentPolicy(state,reportRevision);return review(state,version,reportRevision,hash,outcome,reason,actor,now);};
            }
            default -> throw new IllegalArgumentException("UNKNOWN_CASE_COMMAND");
        }
        String hash=fingerprint(operation+"/"+reportRevision,id,body,access);
        return repository.mutate(id,version,key,hash,operation,incoming,caseOriginal,access,mutation);
    }
    public String currentPolicyVersion(){return policy.version();}
    private void requireCurrentPolicy(State state,Long revision){if(!state.report(revision).policyVersion().equals(policy.version()))throw new IllegalArgumentException("REPORT_POLICY_CONFLICT");}
    private State context(State state,CaseInput input,String actor,java.time.Instant now) {
        if(!CaseWorkRepository.Scope.of(state.caseInput()).equals(CaseWorkRepository.Scope.of(input)))throw new IllegalArgumentException("CASE_SCOPE_IMMUTABLE");
        if(!state.citations().isEmpty())throw new IllegalArgumentException("CONTEXT_IN_USE");
        if(input.source().id().equals(state.caseInput().source().id()) && input.source().version().equals(state.caseInput().source().version())
                && input.source().locator().equals(state.caseInput().source().locator()))throw new IllegalArgumentException("NEW_CASE_SOURCE_VERSION_REQUIRED");
        if(input.equals(state.caseInput()) || input.source().equals(state.caseInput().source()))throw new IllegalArgumentException("NEW_CASE_SOURCE_VERSION_REQUIRED");
        var contributors=new HashSet<>(state.inputContributors());contributors.add(actor);var audits=new ArrayList<>(state.audits());
        audits.add(new Audit(state.version()+1,"REVISE_CASE_CONTEXT",actor,input.id(),now));
        return new State(state.version()+1,state.inputVersion()+1,input,state.materials(),state.claims(),state.findings(),state.citations(),state.reports(),contributors,audits);
    }
    private String fingerprint(String action,String id,JsonNode body,CaseWorkRepository.Access access) {
        return sha256(codec.encode(List.of("ADJ_COMMAND/v1",access.identity(),id,action,body)));
    }
    private void fields(JsonNode body,String... allowed) {
        if(body==null || !body.isObject())throw new IllegalArgumentException("OBJECT_BODY_REQUIRED");
        var names=Set.of(allowed);body.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw new IllegalArgumentException("UNKNOWN_COMMAND_FIELD");});
    }
    private long version(JsonNode body) {
        var value=required(body,"expectedVersion");if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<1)
            throw new IllegalArgumentException("INVALID_EXPECTED_VERSION");return value.asLong();
    }
    private JsonNode required(JsonNode body,String key) {
        if(body==null || !body.hasNonNull(key))throw new IllegalArgumentException("REQUIRED_COMMAND_FIELD_MISSING");return body.get(key);
    }
    private String string(JsonNode body,String key) {
        var value=required(body,key);if(!value.isTextual() || value.asText().isBlank())throw new IllegalArgumentException("NONBLANK_COMMAND_TEXT_REQUIRED");return value.asText();
    }
    private void positive(Long value){if(value==null || value<1)throw new IllegalArgumentException("REPORT_VERSION_REQUIRED");}
    public static void validId(String id){if(id==null || !id.matches("[A-Za-z0-9_-]{1,120}"))throw new IllegalArgumentException("INVALID_CASE_ID");}
}
