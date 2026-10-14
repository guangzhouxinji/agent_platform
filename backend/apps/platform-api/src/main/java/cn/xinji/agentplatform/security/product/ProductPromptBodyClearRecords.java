package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearApproval.Evidence;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Finite immutable conversion material, never maintenance authority or a commit proof. */
final class ProductPromptBodyClearRecords {
    static final String OPERATION="BODY_CLEAR";
    private ProductPromptBodyClearRecords() { }

    record Key(String workspaceId,String promptId,String revisionId,String subject,String certificateSha,String keySha) {
        Key {uuid(workspaceId);uuid(promptId);uuid(revisionId);bounded(subject,200);sha(certificateSha);sha(keySha);}
        String operation(){return OPERATION;}
    }
    record Manifest(Workspace workspace,Evidence evidence,Head before,Header target,
            ProductPromptRecords.Receipt originalReceipt,String requestId,String receiptId,Key key,
            String requestSha,String afterSha,String resultSha,String preparedSha,
            Instant firstAttempt,Instant intentUntil,Instant receiptUntil,Instant closureScopeUntil) {
        Manifest {
            Objects.requireNonNull(workspace);Objects.requireNonNull(evidence);Objects.requireNonNull(before);
            Objects.requireNonNull(target);Objects.requireNonNull(originalReceipt);Objects.requireNonNull(key);
            uuid(requestId);uuid(receiptId);sha(requestSha);sha(afterSha);sha(resultSha);sha(preparedSha);
            time(firstAttempt);time(intentUntil);time(receiptUntil);time(closureScopeUntil);
            uuid(workspace.organization());bounded(workspace.policyRef(),200);
            validate(before);validate(target);validate(originalReceipt);
            if(!"ACTIVE".equals(workspace.state())||requestId.equals(receiptId)
                    ||!workspace.organization().equals(evidence.operatorOrganization())
                    ||!workspace.id().equals(key.workspaceId())||!workspace.id().equals(before.workspaceId())
                    ||!key.promptId().equals(before.promptId())||!key.promptId().equals(target.promptId())
                    ||!key.revisionId().equals(target.revisionId())||target.number()>before.generation()
                    ||target.number()==before.generation()&&!target.revisionId().equals(before.revisionId())
                    ||!key.subject().equals(evidence.operatorSubject())||!key.certificateSha().equals(evidence.certificateSha())
                    ||!"PRESENT".equals(target.bodyState())||before.scalarSha().equals(afterSha)
                    ||!workspace.id().equals(originalReceipt.workspaceId())||!key.promptId().equals(originalReceipt.promptId())
                    ||!key.revisionId().equals(originalReceipt.revisionId())||target.number()!=originalReceipt.generation()
                    ||!target.contentSha().equals(originalReceipt.contentSha())
                    ||!target.submittedAt().equals(originalReceipt.submittedAt())||!target.auditUntil().equals(originalReceipt.auditUntil())
                    ||target.bodyUntil().isAfter(firstAttempt)||!firstAttempt.isBefore(receiptUntil)
                    ||!receiptUntil.equals(target.auditUntil())||receiptUntil.isAfter(firstAttempt.plus(Duration.ofDays(180)))
                    ||receiptUntil.isAfter(evidence.approvedReceiptUntil())||receiptUntil.isAfter(workspace.validUntil())
                    ||!firstAttempt.isBefore(intentUntil)||intentUntil.isAfter(firstAttempt.plusSeconds(300))
                    ||intentUntil.isAfter(receiptUntil)||intentUntil.isAfter(closureScopeUntil)
                    ||closureScopeUntil.isAfter(receiptUntil)||closureScopeUntil.isAfter(before.controlUntil())
                    ||closureScopeUntil.isAfter(workspace.validUntil()))throw invalid();
        }
        Head afterHead(){return new Head(before.promptId(),before.workspaceId(),before.kind(),before.revisionId(),
                before.generation(),afterSha,before.containerUntil(),before.controlUntil());}
        Header afterHeader(){return new Header(target.revisionId(),target.promptId(),target.number(),target.contentSha(),
                target.recordSha(),target.submittedAt(),target.bodyUntil(),target.auditUntil(),"CLEARED");}
        void requireDigest(ProductComparisonJson json){new ProductPromptBodyClearCodec(json).requireDigest(this);}
    }
    record Request(Manifest manifest,String state) {
        Request {Objects.requireNonNull(manifest);if(!"PENDING".equals(state)&&!"READY".equals(state))throw invalid();}
        Request ready(){return new Request(manifest,"READY");}
        Receipt receipt(){return new Receipt(manifest);}
        Key key(){return manifest.key();}
        Workspace workspace(){return manifest.workspace();}
        Evidence evidence(){return manifest.evidence();}
        Head before(){return manifest.before();}
        Header target(){return manifest.target();}
        ProductPromptRecords.Receipt originalReceipt(){return manifest.originalReceipt();}
        String requestId(){return manifest.requestId();}
        String receiptId(){return manifest.receiptId();}
        String requestSha(){return manifest.requestSha();}
        String afterSha(){return manifest.afterSha();}
        String resultSha(){return manifest.resultSha();}
        String preparedSha(){return manifest.preparedSha();}
        Instant firstAttempt(){return manifest.firstAttempt();}
        Instant intentUntil(){return manifest.intentUntil();}
        Instant receiptUntil(){return manifest.receiptUntil();}
        Instant closureScopeUntil(){return manifest.closureScopeUntil();}
        Head afterHead(){return manifest.afterHead();}
        Header afterHeader(){return manifest.afterHeader();}
        void requireDigest(ProductComparisonJson json){manifest.requireDigest(json);}
        boolean sameMaterial(Request other){return other!=null&&manifest.equals(other.manifest);}
    }
    record Receipt(Manifest manifest) {
        Receipt {Objects.requireNonNull(manifest);}
        Key key(){return manifest.key();}
        Workspace workspace(){return manifest.workspace();}
        Evidence evidence(){return manifest.evidence();}
        Head before(){return manifest.before();}
        Header target(){return manifest.target();}
        ProductPromptRecords.Receipt originalReceipt(){return manifest.originalReceipt();}
        String requestId(){return manifest.requestId();}
        String receiptId(){return manifest.receiptId();}
        String requestSha(){return manifest.requestSha();}
        String afterSha(){return manifest.afterSha();}
        String resultSha(){return manifest.resultSha();}
        String preparedSha(){return manifest.preparedSha();}
        Instant firstAttempt(){return manifest.firstAttempt();}
        Instant intentUntil(){return manifest.intentUntil();}
        Instant receiptUntil(){return manifest.receiptUntil();}
        Instant closureScopeUntil(){return manifest.closureScopeUntil();}
        Head afterHead(){return manifest.afterHead();}
        Header afterHeader(){return manifest.afterHeader();}
        void requireDigest(ProductComparisonJson json){manifest.requireDigest(json);}
        boolean sameMaterial(Receipt other){return other!=null&&manifest.equals(other.manifest);}
    }
    static Key key(Workspace workspace,Evidence evidence,String promptId,String revisionId,String rawKey){
        Objects.requireNonNull(workspace);Objects.requireNonNull(evidence);
        if(rawKey==null||rawKey.length()<1||rawKey.length()>64||rawKey.chars().anyMatch(c->c<33||c>126))throw invalid();
        return new Key(workspace.id(),promptId,revisionId,evidence.operatorSubject(),evidence.certificateSha(),ProductComparisonJson.hash(rawKey));
    }
    private static void validate(Head h){
        uuid(h.promptId());uuid(h.workspaceId());uuid(h.revisionId());sha(h.scalarSha());time(h.containerUntil());time(h.controlUntil());
        if(h.kind()==null||h.generation()<1||h.containerUntil().isAfter(h.controlUntil()))throw invalid();
    }
    private static void validate(Header h){
        uuid(h.revisionId());uuid(h.promptId());sha(h.contentSha());sha(h.recordSha());
        time(h.submittedAt());time(h.bodyUntil());time(h.auditUntil());
        if(h.number()<1||!h.submittedAt().isBefore(h.bodyUntil())||!h.bodyUntil().isBefore(h.auditUntil())
                ||h.bodyUntil().isAfter(h.submittedAt().plus(Duration.ofDays(90)))
                ||h.auditUntil().isAfter(h.submittedAt().plus(Duration.ofDays(180))))throw invalid();
    }
    private static void validate(ProductPromptRecords.Receipt r){
        uuid(r.id());uuid(r.workspaceId());uuid(r.promptId());uuid(r.revisionId());bounded(r.issuer(),300);bounded(r.subject(),200);
        sha(r.keySha());sha(r.requestSha());sha(r.contentSha());sha(r.resultSha());time(r.submittedAt());time(r.auditUntil());
        if(r.operation()==null||r.generation()<1||!r.submittedAt().isBefore(r.auditUntil())
                ||(r.operation()==Operation.SAVE)!=(r.generation()>1))throw invalid();
    }
    private static void bounded(String value,int max){text(value);if(value.length()>max)throw invalid();}
}
