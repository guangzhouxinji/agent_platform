package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.runtime.ModelGateway;
import java.time.*;
import java.util.*;

/** Deployment-controlled exact disclosure decision, independent of Task COMPARE. No approval issuer. */
final class ProductComparisonModelAdmission {
    record Approval(String version,String responsibleParty,Instant approvedAt,Instant validFrom,Instant expiresAt,
                    String taskId,String inputSha,Set<String> candidates,int maxCalls,ModelGateway.Configuration configuration) { }
    record Permit(Approval approval,String evidenceSha,Instant expiresAt) {
        Permit(Approval approval,String evidenceSha){this(approval,evidenceSha,approval.expiresAt());}
    }
    record ConfigurationEvidence(ModelGateway.Configuration configuration,String evidenceSha,Instant expiresAt) { }
    record HistoricalReference(String sha256,String reference) { }
    private Map<String,String> historicalReferences=Map.of();
    private final ProductSourceEvidenceFiles files;private final ProductComparisonJson json;
    private final ProductSourceAvailability sources;private final ProductPatentDocumentAccess access;
    private final String reference,sha;private final Clock clock;
    ProductComparisonModelAdmission(ProductSourceEvidenceFiles files,ProductComparisonJson json,ProductSourceAvailability sources,
            ProductPatentDocumentAccess access,String reference,String sha,Clock clock) {
        this.files=files;this.json=json;this.sources=sources;this.access=access;this.reference=reference;this.sha=sha;this.clock=clock;
    }
    ProductComparisonModelAdmission(ProductSourceEvidenceFiles files,ProductComparisonJson json,ProductSourceAvailability sources,
            ProductPatentDocumentAccess access,String reference,String sha,Clock clock,String historicalJson) {
        this(files,json,sources,access,reference,sha,clock);
        var entries=json.stored(historicalJson,HistoricalReference[].class);
        if(entries==null || entries.length>1280)throw denied();
        Map<String,String> refs=new HashMap<>();Set<String> paths=new HashSet<>();
        for(var entry:entries) {
            if(entry==null || entry.sha256()==null || !entry.sha256().matches("[a-f0-9]{64}")
                    || entry.reference()==null || !entry.reference().matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")
                    || entry.reference().contains("..") || refs.putIfAbsent(entry.sha256(),entry.reference())!=null
                    || !paths.add(entry.reference()))throw denied();
        }
        historicalReferences=Map.copyOf(refs);
    }
    ConfigurationEvidence configurationEvidence(ProductComparisonLineageStore.Material material,ProductComparisonInputs.View input) {
        String ref=historicalReferences.get(material.approvalSha());
        if(ref==null)throw denied();
        return configurationEvidence(ref,material,input);
    }
    void validateDeployment() {
        var config=approval().configuration();
        if(!"https".equalsIgnoreCase(config.endpoint().getScheme()) || !"OPENAI_CHAT_COMPLETIONS".equals(config.protocol()))
            throw new IllegalStateException("APPROVED_MODEL_TLS_CONFIGURATION_REQUIRED");
    }
    Permit require(ProductComparisonWorkerMaterials.Loaded loaded) {
        var a=approval();var input=loaded.input();
        if(!a.taskId().equals(input.input().taskId()) || !a.inputSha().equals(input.inputSha())
                || !a.candidates().contains(loaded.candidate().material().key()))throw denied();
        Instant until=source(loaded.input().input().taskId(),loaded.candidate(),loaded.actor());
        if(loaded.targetPatent()!=null)until=min(until,source(a.taskId(),loaded.targetPatent(),loaded.actor()));
        until=min(until,min(a.expiresAt(),loaded.expiresAt()));
        if(!until.isAfter(clock.instant()))throw denied();return new Permit(a,sha,until);
    }
    Approval approval() {
        return approval(reference,sha);
    }
    /** Metadata only. The caller must already authenticate Input, native material and current READ/Source. */
    ConfigurationEvidence configurationEvidence(String historicalReference,ProductComparisonLineageStore.Material material,
            ProductComparisonInputs.View input) {
        if(material==null || !"RESERVATION_MATERIAL_V1".equals(material.version()) || input==null || input.input()==null || !input.inputSha().equals(json.sha(input.input()))
                || !material.taskId().equals(input.input().taskId()) || !material.comparisonId().equals(input.input().comparisonId())
                || !material.inputSha().equals(input.inputSha())
                || !Objects.equals(material.previousComparisonId(),input.input().previousComparisonId()))throw denied();
        Set<String> candidates=new HashSet<>();
        for(var candidate:input.input().candidates())if(!candidates.add(candidate.candidateId()))throw denied();
        if(!candidates.contains(material.candidateId()))throw denied();
        var a=approval(historicalReference,material.approvalSha());
        if(!a.taskId().equals(input.input().taskId()) || !a.inputSha().equals(input.inputSha()) || !a.candidates().equals(candidates)
                || !a.configuration().equals(json.stored(material.configurationJson(),ModelGateway.Configuration.class))
                || !"https".equalsIgnoreCase(a.configuration().endpoint().getScheme())
                || !"OPENAI_CHAT_COMPLETIONS".equals(a.configuration().protocol()))throw denied();
        Instant until=min(a.expiresAt(),input.input().expiresAt());
        if(!until.isAfter(clock.instant()))throw denied();
        return new ConfigurationEvidence(a.configuration(),material.approvalSha(),until);
    }
    private Approval approval(String reference,String sha) {
        var a=json.stored(new String(files.verified(reference,sha),java.nio.charset.StandardCharsets.UTF_8),Approval.class);
        if(a==null || blank(a.version()) || blank(a.responsibleParty()) || a.approvedAt()==null || a.validFrom()==null
                || a.expiresAt()==null || a.approvedAt().isAfter(clock.instant()) || a.validFrom().isAfter(clock.instant())
                || !a.expiresAt().isAfter(clock.instant()) || !a.expiresAt().isAfter(a.validFrom())
                || blank(a.taskId()) || a.inputSha()==null || !a.inputSha().matches("[a-f0-9]{64}")
                || a.candidates()==null || a.candidates().isEmpty() || a.candidates().size()>20
                || a.maxCalls()<1 || a.configuration()==null
                || !a.configuration().purposes().contains("PATENT_COMPARISON")
                || !a.configuration().dataClasses().contains("APPROVED_COMPARISON_ORIGINALS"))throw denied();return a;
    }
    private Instant source(String task,ProductComparisonDocuments.Loaded document,ProductComparisonWorkerTaskAuthority.Authorized actor) {
        var active=sources.require(document.reference().sourceId(),"CLAIMS","MODEL");
        if(!active.equals(document.authority().source()) || !active.equals(sources.require(active.sourceId(),"CLAIMS","SAVE")))throw denied();
        access.requireDelegatedModelInput(task,active,actor);
        return min(access.modelInputUntil(task,active,actor),min(sources.useUntil(active.sourceId(),"CLAIMS","MODEL"),sources.useUntil(active.sourceId(),"CLAIMS","SAVE")));
    }
    private static Instant min(Instant a,Instant b){if(a==null||b==null)throw denied();return a.isBefore(b)?a:b;}
    private static boolean blank(String text){return text==null||text.isBlank();}
    private static IllegalStateException denied(){return new IllegalStateException("COMPARISON_MODEL_DISCLOSURE_NOT_APPROVED");}
}
