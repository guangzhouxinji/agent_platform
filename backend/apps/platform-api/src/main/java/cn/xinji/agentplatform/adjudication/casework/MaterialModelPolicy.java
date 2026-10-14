package cn.xinji.agentplatform.adjudication.casework;

import java.time.*;
import java.util.Set;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Approved immutable single-case scope. Changing its configuration does not reset its allowance. */
public record MaterialModelPolicy(String approvalRef,String approvalSha256,String supplierApprovalSha256,
                                  String caseId,Instant validUntil,Set<String> permittedTextHashes,String modelVersion) {
    public MaterialModelPolicy {
        if(approvalRef==null || approvalRef.isBlank() || caseId==null || !caseId.matches("[A-Za-z0-9_-]{1,120}")
                || approvalSha256==null || !approvalSha256.matches("[a-f0-9]{64}") || supplierApprovalSha256==null || !supplierApprovalSha256.matches("[a-f0-9]{64}")
                || validUntil==null || permittedTextHashes==null || permittedTextHashes.isEmpty()
                || permittedTextHashes.stream().anyMatch(s->!s.matches("[a-f0-9]{64}")) || !"deepseek-flash".equals(modelVersion))throw new IllegalArgumentException("EXPLICIT_ADJ_MODEL_APPROVAL_REQUIRED");
        validUntil=validUntil.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);permittedTextHashes=Set.copyOf(permittedTextHashes);
    }
    public String approvalKey(){return sha256(approvalRef);}
    public String key(){return sha256(approvalRef+"|"+approvalSha256+"|"+supplierApprovalSha256+"|"+caseId);}
    public String configurationSha(){return sha256(key()+"|"+validUntil+"|"+modelVersion+"|"+String.join(",",permittedTextHashes.stream().sorted().toList()));}
    public void require(String exactCase,java.util.List<cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.Input> inputs,Clock clock) {
        if(!caseId.equals(exactCase) || !validUntil.isAfter(clock.instant()) || inputs.isEmpty()
                || inputs.size()>20 || inputs.stream().anyMatch(i->i==null || i.text()==null || i.source()==null || i.side()==null || !sha256(i.text()).equals(i.source().sha256()) || !permittedTextHashes.contains(sha256(i.text()))))throw new SecurityException("MODEL_APPROVED_SCOPE_REQUIRED");
    }
}
