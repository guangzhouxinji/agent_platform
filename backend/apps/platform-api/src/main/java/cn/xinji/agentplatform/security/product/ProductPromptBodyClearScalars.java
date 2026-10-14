package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearApproval.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Exact nested scalar maps; these maps do not define the business closure algorithm. */
final class ProductPromptBodyClearScalars {
    private static final DateTimeFormatter UTC=new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private ProductPromptBodyClearScalars() { }
    static Map<String,Object> workspace(Workspace w){return map("id",w.id(),"organization",w.organization(),"scopeVersion",w.scopeVersion(),
            "state",w.state(),"policyRef",w.policyRef(),"policySha",w.policySha(),"registrySha",w.registrySha(),"validUntil",w.validUntil());}
    static Map<String,Object> evidence(Evidence e){return map("operatorSubject",e.operatorSubject(),"operatorOrganization",e.operatorOrganization(),
            "certificateSha",e.certificateSha(),"maintainerVersion",e.maintainerVersion(),"primaryDatabaseIdentitySha",e.primaryDatabaseIdentitySha(),
            "authorityDatabaseIdentitySha",e.authorityDatabaseIdentitySha(),"approvalRef",e.approvalRef(),"approvalSha",e.approvalSha(),
            "approvedReceiptUntil",e.approvedReceiptUntil());}
    static Map<String,Object> head(Head h){return map("promptId",h.promptId(),"workspaceId",h.workspaceId(),"kind",h.kind().name(),
            "revisionId",h.revisionId(),"generation",h.generation(),"scalarSha",h.scalarSha(),"containerUntil",h.containerUntil(),"controlUntil",h.controlUntil());}
    static Map<String,Object> header(Header h){return map("revisionId",h.revisionId(),"promptId",h.promptId(),"number",h.number(),
            "contentSha",h.contentSha(),"recordSha",h.recordSha(),"submittedAt",h.submittedAt(),"bodyUntil",h.bodyUntil(),"auditUntil",h.auditUntil(),"bodyState",h.bodyState());}
    static Map<String,Object> original(ProductPromptRecords.Receipt r){return map("id",r.id(),"workspaceId",r.workspaceId(),"issuer",r.issuer(),
            "subject",r.subject(),"operation",r.operation().name(),"keySha",r.keySha(),"requestSha",r.requestSha(),"promptId",r.promptId(),
            "revisionId",r.revisionId(),"generation",r.generation(),"contentSha",r.contentSha(),"resultSha",r.resultSha(),"submittedAt",r.submittedAt(),"auditUntil",r.auditUntil());}
    static Map<String,Object> key(Key k){return map("workspaceId",k.workspaceId(),"promptId",k.promptId(),"revisionId",k.revisionId(),
            "subject",k.subject(),"certificateSha",k.certificateSha(),"operation",k.operation(),"keySha",k.keySha());}
    static Map<String,Object> material(Manifest m){return map("workspace",workspace(m.workspace()),"evidence",evidence(m.evidence()),
            "before",head(m.before()),"target",header(m.target()),"originalReceipt",original(m.originalReceipt()),
            "requestId",m.requestId(),"receiptId",m.receiptId(),"key",key(m.key()),"requestSha",m.requestSha(),"afterSha",m.afterSha(),
            "resultSha",m.resultSha(),"firstAttempt",m.firstAttempt(),"intentUntil",m.intentUntil(),"receiptUntil",m.receiptUntil(),"closureScopeUntil",m.closureScopeUntil());}
    static Workspace workspace(JsonNode n){fields(n,"id","organization","scopeVersion","state","policyRef","policySha","registrySha","validUntil");
        return new Workspace(text(n,"id"),text(n,"organization"),positive(n,"scopeVersion"),text(n,"state"),text(n,"policyRef"),
                text(n,"policySha"),text(n,"registrySha"),at(n,"validUntil"));}
    static Evidence evidence(JsonNode n){fields(n,"operatorSubject","operatorOrganization","certificateSha","maintainerVersion", "primaryDatabaseIdentitySha",
            "authorityDatabaseIdentitySha","approvalRef","approvalSha","approvedReceiptUntil");
        return new Evidence(text(n,"operatorSubject"),text(n,"operatorOrganization"),text(n,"certificateSha"),positive(n,"maintainerVersion"),
                text(n,"primaryDatabaseIdentitySha"),text(n,"authorityDatabaseIdentitySha"),text(n,"approvalRef"),text(n,"approvalSha"),at(n,"approvedReceiptUntil"));}
    static Head head(JsonNode n){fields(n,"promptId","workspaceId","kind","revisionId","generation","scalarSha","containerUntil","controlUntil");
        return new Head(text(n,"promptId"),text(n,"workspaceId"),PromptRevision.Kind.valueOf(text(n,"kind")),text(n,"revisionId"),
                positive(n,"generation"),text(n,"scalarSha"),at(n,"containerUntil"),at(n,"controlUntil"));}
    static Header header(JsonNode n){fields(n,"revisionId","promptId","number","contentSha","recordSha","submittedAt","bodyUntil","auditUntil","bodyState");
        return new Header(text(n,"revisionId"),text(n,"promptId"),positive(n,"number"),text(n,"contentSha"),text(n,"recordSha"),
                at(n,"submittedAt"),at(n,"bodyUntil"),at(n,"auditUntil"),text(n,"bodyState"));}
    static ProductPromptRecords.Receipt original(JsonNode n){fields(n,"id","workspaceId","issuer","subject","operation","keySha","requestSha","promptId",
            "revisionId","generation","contentSha","resultSha","submittedAt","auditUntil");
        return new ProductPromptRecords.Receipt(text(n,"id"),text(n,"workspaceId"),text(n,"issuer"),text(n,"subject"),Operation.valueOf(text(n,"operation")),
                text(n,"keySha"),text(n,"requestSha"),text(n,"promptId"),text(n,"revisionId"),positive(n,"generation"),text(n,"contentSha"),text(n,"resultSha"),
                at(n,"submittedAt"),at(n,"auditUntil"));}
    static Key key(JsonNode n){fields(n,"workspaceId","promptId","revisionId","subject","certificateSha","operation","keySha");
        if(!OPERATION.equals(text(n,"operation")))throw invalid();
        return new Key(text(n,"workspaceId"),text(n,"promptId"),text(n,"revisionId"),text(n,"subject"),text(n,"certificateSha"),text(n,"keySha"));}
    static Manifest manifest(JsonNode n){fields(n,"format","workspace","evidence","before","target","originalReceipt","requestId","receiptId","key",
            "requestSha","afterSha","resultSha","preparedSha","firstAttempt","intentUntil","receiptUntil","closureScopeUntil");
        if(!"PROMPT_BODY_CLEAR_MANIFEST/v1".equals(text(n,"format")))throw invalid();
        return new Manifest(workspace(n.get("workspace")),evidence(n.get("evidence")),head(n.get("before")),header(n.get("target")),
                original(n.get("originalReceipt")),text(n,"requestId"),text(n,"receiptId"),key(n.get("key")),text(n,"requestSha"),text(n,"afterSha"),
                text(n,"resultSha"),text(n,"preparedSha"),at(n,"firstAttempt"),at(n,"intentUntil"),at(n,"receiptUntil"),at(n,"closureScopeUntil"));}
    static Map<String,Object> map(Object... pairs){
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2){Object value=pairs[i+1];result.put((String)pairs[i],value instanceof Instant at?UTC.format(at):value);}
        return result;
    }
    private static void fields(JsonNode n,String... names){
        if(n==null||!n.isObject()||n.size()!=names.length)throw invalid();
        Set<String> expected=Set.of(names);n.fieldNames().forEachRemaining(name->{if(!expected.contains(name))throw invalid();});
    }
    private static String text(JsonNode n,String name){JsonNode v=n.get(name);if(v==null||!v.isTextual())throw invalid();return v.textValue();}
    private static long positive(JsonNode n,String name){
        JsonNode v=n.get(name);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw invalid();return v.longValue();
    }
    private static Instant at(JsonNode n,String name){String value=text(n,name);Instant at=Instant.parse(value);
        if(!UTC.format(at).equals(value))throw invalid();return at;}
}
