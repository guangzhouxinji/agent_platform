package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fixed finite registration scalars. A record or its digest is never a maintenance authority. */
final class ProductPromptRegisterRecords {
    static final String OPERATION="REGISTER";
    private static final String ZERO="0".repeat(64);
    private ProductPromptRegisterRecords() { }

    record Key(String workspaceId,String subject,String certificateSha,String keySha) {
        Key {uuid(workspaceId);bounded(subject,200);sha(certificateSha);sha(keySha);}
        String operation(){return OPERATION;}
    }
    record Evidence(String operatorSubject,String operatorOrganization,long maintainerVersion,String certificateSha,
            String primaryDatabaseIdentitySha,String authorityDatabaseIdentitySha,String approvalRef,String approvalSha,
            Instant approvedReceiptUntil) {
        Evidence {
            bounded(operatorSubject,200);uuid(operatorOrganization);sha(certificateSha);
            sha(primaryDatabaseIdentitySha);sha(authorityDatabaseIdentitySha);reference(approvalRef);sha(approvalSha);
            time(approvedReceiptUntil);
            if(maintainerVersion<1||primaryDatabaseIdentitySha.equals(authorityDatabaseIdentitySha))throw invalid();
        }
    }
    record Receipt(String requestId,String receiptId,Key key,String operatorOrganization,long maintainerVersion,
            String requestSha,String workspaceSha,String resultSha,Instant preparedAt,Instant receiptUntil) {
        Receipt {
            uuid(requestId);uuid(receiptId);Objects.requireNonNull(key);uuid(operatorOrganization);
            sha(requestSha);sha(workspaceSha);sha(resultSha);time(preparedAt);time(receiptUntil);
            if(requestId.equals(receiptId)||maintainerVersion<1||!receiptUntil.isAfter(preparedAt)
                    ||receiptUntil.isAfter(preparedAt.plus(Duration.ofDays(180))))throw invalid();
        }
        Map<String,Object> columns(){
            var values=new LinkedHashMap<String,Object>();values.put("request_id",requestId);values.put("receipt_id",receiptId);
            values.put("workspace_id",key.workspaceId());values.put("operation",OPERATION);values.put("subject_id",key.subject());
            values.put("operator_organization",operatorOrganization);values.put("maintainer_version",maintainerVersion);
            values.put("certificate_sha",key.certificateSha());values.put("key_sha",key.keySha());values.put("request_sha",requestSha);
            values.put("workspace_sha",workspaceSha);values.put("result_sha",resultSha);values.put("prepared_at",preparedAt);
            values.put("receipt_until",receiptUntil);return values;
        }
    }
    record Request(Workspace target,Evidence evidence,Receipt receipt,Instant intentUntil,String preparedSha,String state) {
        Request {
            Objects.requireNonNull(target);Objects.requireNonNull(evidence);Objects.requireNonNull(receipt);
            uuid(target.organization());bounded(target.policyRef(),200);time(intentUntil);sha(preparedSha);
            if(!"ACTIVE".equals(target.state())||!("PENDING".equals(state)||"READY".equals(state))
                    ||!target.id().equals(receipt.key().workspaceId())||!target.organization().equals(evidence.operatorOrganization())
                    ||!evidence.operatorOrganization().equals(receipt.operatorOrganization())
                    ||!evidence.operatorSubject().equals(receipt.key().subject())
                    ||!evidence.certificateSha().equals(receipt.key().certificateSha())
                    ||evidence.maintainerVersion()!=receipt.maintainerVersion()
                    ||!intentUntil.isAfter(receipt.preparedAt())||intentUntil.isAfter(receipt.preparedAt().plusSeconds(300))
                    ||intentUntil.isAfter(receipt.receiptUntil())||receipt.receiptUntil().isAfter(target.validUntil())
                    ||receipt.receiptUntil().isAfter(evidence.approvedReceiptUntil()))throw invalid();
        }
        Key key(){return receipt.key();}
        String requestId(){return receipt.requestId();}
        String receiptId(){return receipt.receiptId();}
        String requestSha(){return receipt.requestSha();}
        String workspaceSha(){return receipt.workspaceSha();}
        Instant preparedAt(){return receipt.preparedAt();}
        Instant receiptUntil(){return receipt.receiptUntil();}
        void requireDigest(ProductComparisonJson json){
            if(!workspaceSha().equals(json.sha(workspaceCanonical(target)))
                    ||!requestSha().equals(json.sha(commandCanonical(target,evidence)))
                    ||!receipt.resultSha().equals(json.sha(resultCanonical(receipt)))
                    ||!preparedSha.equals(json.sha(preparedCanonical(this))))throw unavailable();
        }
        Request ready(){return new Request(target,evidence,receipt,intentUntil,preparedSha,"READY");}
        Map<String,Object> columns(){
            var values=new LinkedHashMap<>(receipt.columns());
            values.put("organization",target.organization());values.put("scope_version",target.scopeVersion());
            values.put("workspace_state",target.state());values.put("policy_ref",target.policyRef());values.put("policy_sha",target.policySha());
            values.put("registry_sha",target.registrySha());values.put("valid_until",target.validUntil());
            values.put("primary_database_sha",evidence.primaryDatabaseIdentitySha());values.put("authority_database_sha",evidence.authorityDatabaseIdentitySha());
            values.put("approval_ref",evidence.approvalRef());values.put("approval_sha",evidence.approvalSha());
            values.put("approved_receipt_until",evidence.approvedReceiptUntil());values.put("intent_until",intentUntil);
            values.put("prepared_sha",preparedSha);values.put("state",state);return values;
        }
    }
    static Key key(Lease lease,String rawKey){
        var approved=lease.approval();approved.requireCurrent();
        if(rawKey==null||rawKey.length()<1||rawKey.length()>64||rawKey.chars().anyMatch(c->c<33||c>126))throw invalid();
        var evidence=approved.evidence();return new Key(approved.target().id(),evidence.operatorSubject(),evidence.certificateSha(),ProductComparisonJson.hash(rawKey));
    }
    static String requestSha(Lease lease,ProductComparisonJson json){
        var approved=lease.approval();approved.requireCurrent();return json.sha(commandCanonical(approved.target(),approved.evidence()));
    }
    static Request prepare(Lease lease,String rawKey,Instant preparedAt,ProductComparisonJson json){
        time(preparedAt);var approved=lease.approval();approved.requireCurrent();var evidence=approved.evidence();
        Instant until=min(preparedAt.plus(Duration.ofDays(180)),evidence.approvedReceiptUntil(),approved.expiresAt(),approved.target().validUntil());
        Instant intent=min(preparedAt.plusSeconds(300),approved.expiresAt(),approved.target().validUntil(),until);
        String workspaceSha=json.sha(workspaceCanonical(approved.target())),requestSha=requestSha(lease,json);
        var unsigned=new Receipt(UUID.randomUUID().toString(),UUID.randomUUID().toString(),key(lease,rawKey),
                evidence.operatorOrganization(),evidence.maintainerVersion(),requestSha,workspaceSha,ZERO,preparedAt,until);
        var receipt=new Receipt(unsigned.requestId(),unsigned.receiptId(),unsigned.key(),unsigned.operatorOrganization(),
                unsigned.maintainerVersion(),requestSha,workspaceSha,json.sha(resultCanonical(unsigned)),preparedAt,until);
        var request=new Request(approved.target(),evidence,receipt,intent,ZERO,"PENDING");
        return new Request(request.target(),evidence,receipt,intent,json.sha(preparedCanonical(request)),"PENDING");
    }
    private static Map<String,Object> target(Workspace workspace){
        var values=new LinkedHashMap<String,Object>();values.put("workspaceId",workspace.id());values.put("organization",workspace.organization());
        values.put("scopeVersion",workspace.scopeVersion());values.put("state",workspace.state());values.put("policyRef",workspace.policyRef());
        values.put("policySha",workspace.policySha());values.put("registrySha",workspace.registrySha());values.put("validUntil",workspace.validUntil());return values;
    }
    private static Map<String,Object> workspaceCanonical(Workspace workspace){
        return Map.of("format","PROMPT_WORKSPACE_REGISTER_TARGET/v1","target",target(workspace));
    }
    private static Map<String,Object> commandCanonical(Workspace workspace,Evidence evidence){
        var values=new LinkedHashMap<String,Object>();values.put("format","PROMPT_WORKSPACE_REGISTER_COMMAND/v1");values.put("operation",OPERATION);
        values.put("target",target(workspace));values.put("operatorSubject",evidence.operatorSubject());values.put("operatorOrganization",evidence.operatorOrganization());
        values.put("maintainerVersion",evidence.maintainerVersion());values.put("certificateSha",evidence.certificateSha());
        values.put("primaryDatabaseIdentitySha",evidence.primaryDatabaseIdentitySha());values.put("authorityDatabaseIdentitySha",evidence.authorityDatabaseIdentitySha());
        values.put("approvalSha",evidence.approvalSha());values.put("approvedReceiptUntil",evidence.approvedReceiptUntil());return values;
    }
    private static Map<String,Object> keyCanonical(Key key){
        return Map.of("workspaceId",key.workspaceId(),"subject",key.subject(),"certificateSha",key.certificateSha(),"operation",OPERATION,"keySha",key.keySha());
    }
    private static Map<String,Object> resultCanonical(Receipt receipt){
        var values=new LinkedHashMap<String,Object>();values.put("format","PROMPT_WORKSPACE_REGISTER_RESULT/v1");
        values.put("requestId",receipt.requestId());values.put("receiptId",receipt.receiptId());values.put("key",keyCanonical(receipt.key()));
        values.put("requestSha",receipt.requestSha());values.put("workspaceSha",receipt.workspaceSha());
        values.put("preparedAt",receipt.preparedAt());values.put("receiptUntil",receipt.receiptUntil());return values;
    }
    private static Map<String,Object> preparedCanonical(Request request){
        var values=new LinkedHashMap<String,Object>();values.put("format","PROMPT_WORKSPACE_REGISTER_PREPARED/v1");
        values.put("target",target(request.target()));values.put("evidence",request.evidence());values.put("receipt",resultCanonical(request.receipt()));
        values.put("resultSha",request.receipt().resultSha());values.put("intentUntil",request.intentUntil());return values;
    }
    private static Instant min(Instant... values){return java.util.Arrays.stream(values).min(Instant::compareTo).orElseThrow();}
    private static void bounded(String value,int maximum){if(value==null||value.isBlank()||value.length()>maximum)throw invalid();}
    private static void reference(String value){
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||value.contains(".."))throw invalid();
    }
}
