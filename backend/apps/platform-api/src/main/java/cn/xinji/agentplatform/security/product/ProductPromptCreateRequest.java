package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Finite immutable persistence scalars, never evidence of permission or successful main commit. */
record ProductPromptCreateRequest(Pending pending, Receipt receipt, Header header, Evidence evidence,
                                  String preparedFormat, String requestFormat, String state, String preparedSha, CopySource copySource) {
    static final String PREPARED_FORMAT = "PROMPT_PREPARED_SCALARS/v1";
    static final String REQUEST_FORMAT = "PROMPT_CREATE_COMMAND/v1";
    static final String SAVE_PREPARED_FORMAT = "PROMPT_SAVE_PREPARED_SCALARS/v1";
    static final String SAVE_REQUEST_FORMAT = "PROMPT_SAVE_COMMAND/v1";
    static final String COPY_PREPARED_FORMAT = "PROMPT_COPY_PREPARED_SCALARS/v1";
    static final String COPY_REQUEST_FORMAT = "PROMPT_COPY_COMMAND/v1";
    private static final String ZERO = "0".repeat(64);

    record Key(String workspaceId, String issuer, String subject, Operation operation, String keySha) {
        Key { uuid(workspaceId); bounded(issuer,300); bounded(subject,200); sha(keySha);
            if(operation!=Operation.CREATE&&operation!=Operation.SAVE&&operation!=Operation.COPY)throw invalid(); }
        Key(String workspaceId,String issuer,String subject,String keySha){this(workspaceId,issuer,subject,Operation.CREATE,keySha);}
    }
    record Evidence(long scopeVersion, String registrySha, String approvalRef, String approvalSha,
                    String policyRef, String policySha, String inputPermissionRef, String inputPermissionSha,
                    String generationSourceRef) {
        Evidence {
            if(scopeVersion<1)throw invalid();
            sha(registrySha);sha(approvalSha);sha(policySha);sha(inputPermissionSha);
            reference(approvalRef);reference(policyRef);reference(inputPermissionRef);bounded(generationSourceRef,500);
            if(!registrySha.equals(approvalSha))throw invalid();
        }
    }
    record CopySource(String sourceWorkspaceId, String sourcePromptId, String sourceRevisionId, long sourceRevisionNumber,
                      String sourceRecordSha, Instant sourceBodyUntil, Instant sourceAuditUntil) {
        CopySource {
            uuid(sourceWorkspaceId);uuid(sourcePromptId);uuid(sourceRevisionId);sha(sourceRecordSha);
            time(sourceBodyUntil);time(sourceAuditUntil);
            if(sourceRevisionNumber<1||sourceBodyUntil.isAfter(sourceAuditUntil))throw invalid();
        }
    }
    ProductPromptCreateRequest(Pending pending, Receipt receipt, Header header, Evidence evidence,
                               String preparedFormat, String requestFormat, String state, String preparedSha) {
        this(pending,receipt,header,evidence,preparedFormat,requestFormat,state,preparedSha,null);
    }
    ProductPromptCreateRequest {
        Objects.requireNonNull(pending);Objects.requireNonNull(receipt);Objects.requireNonNull(header);Objects.requireNonNull(evidence);
        boolean first=pending.operation()!=Operation.SAVE;
        if(!preparedFormat(pending.operation()).equals(preparedFormat)
                ||!requestFormat(pending.operation()).equals(requestFormat)
                ||!("PENDING".equals(state)||"READY".equals(state)))throw invalid();
        if((pending.operation()==Operation.COPY)!=(copySource!=null))throw invalid();
        sha(preparedSha);uuid(receipt.id());uuid(receipt.promptId());uuid(receipt.revisionId());
        new Key(receipt.workspaceId(),receipt.issuer(),receipt.subject(),receipt.operation(),receipt.keySha());
        sha(receipt.requestSha());sha(receipt.contentSha());sha(receipt.resultSha());sha(header.recordSha());
        time(receipt.submittedAt());time(receipt.auditUntil());time(header.bodyUntil());time(header.auditUntil());time(header.submittedAt());
        if(copySource!=null&&(copySource.sourcePromptId().equals(pending.promptId())
                ||copySource.sourceRevisionId().equals(pending.targetRevisionId())
                ||header.bodyUntil().isAfter(copySource.sourceBodyUntil())
                ||header.auditUntil().isAfter(copySource.sourceAuditUntil())
                ||!evidence.generationSourceRef().equals("PROMPT_REVISION:"+copySource.sourcePromptId()+":"+copySource.sourceRevisionId())))throw invalid();
        if(receipt.operation()!=pending.operation()
                ||(first?(pending.expectedGeneration()!=0||pending.expectedRevisionId()!=null||!ZERO.equals(pending.beforeSha()))
                    :(pending.expectedGeneration()<1||pending.expectedRevisionId()==null))
                ||receipt.generation()!=pending.expectedGeneration()+1||header.number()!=receipt.generation()||!"PRESENT".equals(header.bodyState())
                ||!pending.promptId().equals(receipt.promptId())||!receipt.promptId().equals(header.promptId())
                ||!pending.targetRevisionId().equals(receipt.revisionId())||!receipt.revisionId().equals(header.revisionId())
                ||!pending.workspaceId().equals(receipt.workspaceId())||!pending.keySha().equals(receipt.keySha())
                ||!pending.requestSha().equals(receipt.requestSha())||!receipt.contentSha().equals(header.contentSha())
                ||!receipt.submittedAt().equals(header.submittedAt())||!receipt.auditUntil().equals(header.auditUntil())
                ||(first?(!pending.containerUntil().equals(header.bodyUntil())||!pending.controlUntil().equals(header.auditUntil()))
                    :(pending.containerUntil().isBefore(header.bodyUntil())||pending.controlUntil().isBefore(header.auditUntil())))
                ||!header.bodyUntil().isAfter(header.submittedAt())||header.auditUntil().isBefore(header.bodyUntil())
                ||header.bodyUntil().isAfter(header.submittedAt().plus(Duration.ofDays(90)))
                ||header.auditUntil().isAfter(header.submittedAt().plus(Duration.ofDays(180)))
                ||!pending.intentUntil().isAfter(header.submittedAt())||pending.intentUntil().isAfter(header.bodyUntil()))throw invalid();
    }
    static ProductPromptCreateRequest prepared(Pending pending, PreparedWrite prepared, Evidence evidence,
                                               ProductComparisonJson json) {
        return prepared(pending,prepared,evidence,null,json);
    }
    static ProductPromptCreateRequest prepared(Pending pending, PreparedWrite prepared, Evidence evidence,
                                               CopySource copySource, ProductComparisonJson json) {
        String preparedFormat=preparedFormat(pending.operation());
        String requestFormat=requestFormat(pending.operation());
        var unsigned=new ProductPromptCreateRequest(pending,prepared.receipt(),prepared.header(),evidence,
                preparedFormat,requestFormat,"PENDING",ZERO,copySource);
        unsigned.requirePrepared(prepared);
        if(!unsigned.header().recordSha().equals(json.sha(prepared.write().revision())))throw unavailable();
        return new ProductPromptCreateRequest(pending,prepared.receipt(),prepared.header(),evidence,
                preparedFormat,requestFormat,"PENDING",json.sha(unsigned.canonical()),copySource);
    }
    Key key(){return new Key(receipt.workspaceId(),receipt.issuer(),receipt.subject(),receipt.operation(),receipt.keySha());}
    Head after(){return new Head(pending.promptId(),pending.workspaceId(),pending.kind(),pending.targetRevisionId(),receipt.generation(),
            pending.afterSha(),pending.containerUntil(),pending.controlUntil());}
    void requireDigest(ProductComparisonJson json){if(!preparedSha.equals(json.sha(canonical())))throw unavailable();}
    void requirePrepared(PreparedWrite prepared){
        Objects.requireNonNull(prepared);var write=prepared.write();var value=write.revision();var origin=value.provenance();
        boolean first=pending.operation()!=Operation.SAVE;
        if(first){if(prepared.before()!=null)throw unavailable();}
        else{
            Head before=prepared.before();
            if(!matchesBefore(before)||!maximum(before.containerUntil(),header.bodyUntil()).equals(pending.containerUntil())
                    ||!maximum(before.controlUntil(),header.auditUntil()).equals(pending.controlUntil()))throw unavailable();
        }
        if(!after().equals(prepared.after())||!header.equals(prepared.header())||!receipt.equals(prepared.receipt())
                ||write.operation()!=pending.operation()||write.expectedGeneration()!=pending.expectedGeneration()||!write.workspaceId().equals(pending.workspaceId())
                ||!write.issuer().equals(receipt.issuer())||!write.subject().equals(receipt.subject())
                ||!write.keySha().equals(receipt.keySha())||!write.requestSha().equals(receipt.requestSha())
                ||!write.bodyUntil().equals(header.bodyUntil())||!write.auditUntil().equals(header.auditUntil())
                ||!value.reference().promptId().equals(pending.promptId())||!value.reference().revisionId().equals(pending.targetRevisionId())
                ||value.reference().version()!=receipt.generation()||value.kind()!=pending.kind()||!value.reference().contentSha256().equals(header.contentSha())
                ||first&&(!origin.createdBy().equals(receipt.subject())||!origin.createdAt().equals(header.submittedAt()))
                ||!origin.modifiedBy().equals(receipt.subject())||!origin.modifiedAt().equals(header.submittedAt())
                ||!origin.generationSourceRef().equals(evidence.generationSourceRef())||!changeDescription(pending.operation()).equals(origin.changeDescription())
                ||!origin.testRecordRefs().isEmpty()||!Objects.equals(origin.contentApprovalRef(),evidence.inputPermissionRef())
                ||!Objects.equals(origin.retentionPolicyRef(),evidence.policyRef()))throw unavailable();
    }
    Map<String,Object> columns(){
        var c=new LinkedHashMap<String,Object>();
        c.put("request_id",pending.intentId());c.put("workspace_id",pending.workspaceId());c.put("issuer",receipt.issuer());
        c.put("subject_id",receipt.subject());c.put("operation",pending.operation().name());c.put("key_sha",pending.keySha());
        c.put("prompt_id",pending.promptId());c.put("kind",pending.kind().name());c.put("target_revision_id",pending.targetRevisionId());
        c.put("expected_generation",pending.expectedGeneration());c.put("expected_revision_id",pending.expectedRevisionId());c.put("target_generation",receipt.generation());c.put("receipt_id",receipt.id());
        c.put("prepared_format",preparedFormat);c.put("request_format",requestFormat);c.put("request_sha",pending.requestSha());
        c.put("content_sha",header.contentSha());c.put("record_sha",header.recordSha());c.put("result_sha",receipt.resultSha());
        c.put("before_sha",pending.beforeSha());c.put("expected_after_sha",pending.afterSha());c.put("prepared_sha",preparedSha);
        c.put("prepared_at",header.submittedAt());c.put("body_until",header.bodyUntil());c.put("audit_until",header.auditUntil());
        c.put("intent_until",pending.intentUntil());c.put("container_until",pending.containerUntil());c.put("control_until",pending.controlUntil());
        c.put("scope_version",evidence.scopeVersion());c.put("registry_sha",evidence.registrySha());c.put("approval_ref",evidence.approvalRef());
        c.put("approval_sha",evidence.approvalSha());c.put("policy_ref",evidence.policyRef());c.put("policy_sha",evidence.policySha());
        c.put("input_permission_ref",evidence.inputPermissionRef());c.put("input_permission_sha",evidence.inputPermissionSha());
        c.put("generation_source_ref",evidence.generationSourceRef());
        if(copySource!=null){
            c.put("source_workspace_id",copySource.sourceWorkspaceId());c.put("source_prompt_id",copySource.sourcePromptId());
            c.put("source_revision_id",copySource.sourceRevisionId());c.put("source_revision_number",copySource.sourceRevisionNumber());
            c.put("source_record_sha",copySource.sourceRecordSha());c.put("source_body_until",copySource.sourceBodyUntil());
            c.put("source_audit_until",copySource.sourceAuditUntil());
        }
        c.put("state",state);return c;
    }
    private Map<String,Object> canonical(){
        var values=columns();values.remove("state");values.remove("prepared_sha");
        values.put("before",pending.operation()!=Operation.SAVE?null:Map.of("promptId",pending.promptId(),"workspaceId",pending.workspaceId(),
                "kind",pending.kind(),"generation",pending.expectedGeneration(),"revisionId",pending.expectedRevisionId(),"scalarSha",pending.beforeSha()));
        values.put("after",after());values.put("header",header);values.put("receipt",receipt);return values;
    }
    boolean matchesBefore(Head head){
        return head!=null&&head.promptId().equals(pending.promptId())&&head.workspaceId().equals(pending.workspaceId())
                &&head.kind()==pending.kind()&&head.generation()==pending.expectedGeneration()
                &&head.revisionId().equals(pending.expectedRevisionId())&&head.scalarSha().equals(pending.beforeSha());
    }
    boolean matchesPending(Control control){
        var p=pending;
        return control!=null&&control.cleanupRequestId()==null&&control.promptId().equals(p.promptId())&&"PENDING".equals(control.state())&&Objects.equals(control.intentId(),p.intentId())
                &&control.workspaceId().equals(p.workspaceId())&&control.kind()==p.kind()
                &&control.generation()==p.expectedGeneration()&&Objects.equals(control.revisionId(),p.expectedRevisionId())
                &&control.scalarSha().equals(p.beforeSha())&&control.operation()==p.operation()
                &&Objects.equals(control.keySha(),p.keySha())&&Objects.equals(control.requestSha(),p.requestSha())
                &&Objects.equals(control.beforeSha(),p.beforeSha())&&Objects.equals(control.afterSha(),p.afterSha())
                &&Objects.equals(control.targetGeneration(),receipt.generation())&&Objects.equals(control.targetRevisionId(),p.targetRevisionId())
                &&Objects.equals(control.intentUntil(),p.intentUntil())&&control.containerUntil().equals(p.containerUntil())
                &&control.controlUntil().equals(p.controlUntil());
    }
    private static java.time.Instant maximum(java.time.Instant one,java.time.Instant two){return one.isAfter(two)?one:two;}
    private static String preparedFormat(Operation operation){return switch(operation){case CREATE->PREPARED_FORMAT;case SAVE->SAVE_PREPARED_FORMAT;case COPY->COPY_PREPARED_FORMAT;};}
    private static String requestFormat(Operation operation){return switch(operation){case CREATE->REQUEST_FORMAT;case SAVE->SAVE_REQUEST_FORMAT;case COPY->COPY_REQUEST_FORMAT;};}
    private static String changeDescription(Operation operation){return switch(operation){case CREATE->"CREATE_DRAFT";case SAVE->"SAVE_DRAFT";case COPY->"COPY_DRAFT";};}
    private static void bounded(String value,int max){text(value);if(value.length()>max)throw invalid();}
    private static void reference(String value){
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||value.contains(".."))throw invalid();
    }
}
