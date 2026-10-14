package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptBodyClearScalars.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearApproval.Evidence;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Receipt;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fixed strict storage codec. No database access, current authorization or proof construction. */
final class ProductPromptBodyClearCodec {
    static final String COLUMNS="request_id,receipt_id,operation,workspace_id,prompt_id,revision_id,subject_id,certificate_sha,key_sha,request_sha,"
            +"generation,head_revision_id,before_sha,after_sha,result_sha,prepared_sha,first_attempt,intent_until,receipt_until,closure_scope_until,manifest_json";
    static final String REQUEST_COLUMNS=COLUMNS+",state";
    private static final String ZERO="0".repeat(64);
    private final ProductComparisonJson json;
    private final ObjectMapper parser=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    ProductPromptBodyClearCodec(ProductComparisonJson json){this.json=Objects.requireNonNull(json);}

    Request prepare(Workspace workspace,Evidence evidence,Head before,Header target,ProductPromptRecords.Receipt originalReceipt,
            Key key,String afterSha,Instant firstAttempt,Instant closureScopeUntil,Instant approvalExpiresAt){
        time(approvalExpiresAt);
        Instant until=target.auditUntil(),intent=min(firstAttempt.plusSeconds(300),until,closureScopeUntil,approvalExpiresAt);
        var unsigned=new Manifest(workspace,evidence,before,target,originalReceipt,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                key,ZERO,afterSha,ZERO,ZERO,firstAttempt,intent,until,closureScopeUntil);
        var command=withDigests(unsigned,json.sha(command(unsigned)),ZERO,ZERO);
        var result=withDigests(command,command.requestSha(),json.sha(result(command)),ZERO);
        var prepared=withDigests(result,result.requestSha(),result.resultSha(),json.sha(prepared(result)));
        encode(prepared);return new Request(prepared,"PENDING");
    }
    String encode(Manifest manifest){
        requireDigest(manifest);var values=material(manifest);values.put("format","PROMPT_BODY_CLEAR_MANIFEST/v1");
        values.put("preparedSha",manifest.preparedSha());String raw=json.canonical(values);capacity(raw);return raw;
    }
    Manifest decode(String raw){
        try {capacity(raw);Manifest manifest=manifest(parser.readTree(raw));requireDigest(manifest);return manifest;}
        catch(Exception failure){throw new IllegalStateException("Invalid body-clear manifest",failure);}
    }
    void requireDigest(Manifest m){
        if(!m.requestSha().equals(json.sha(command(m)))||!m.resultSha().equals(json.sha(result(m)))
                ||!m.preparedSha().equals(json.sha(prepared(m))))throw unavailable();
    }
    Map<String,Object> receiptColumns(Receipt receipt){return columns(receipt.manifest());}
    Map<String,Object> requestColumns(Request request){var values=columns(request.manifest());values.put("state",request.state());return values;}
    Request readRequest(ResultSet rs)throws SQLException{return new Request(readManifest(rs),rs.getString("state"));}
    Receipt readReceipt(ResultSet rs)throws SQLException{return new Receipt(readManifest(rs));}
    private Manifest readManifest(ResultSet rs)throws SQLException{
        Manifest manifest=decode(rs.getString("manifest_json"));
        for(var entry:columns(manifest).entrySet()){
            if("manifest_json".equals(entry.getKey()))continue;
            Object expected=entry.getValue(),actual;
            if(expected instanceof Instant){Timestamp at=rs.getTimestamp(entry.getKey());actual=at==null?null:at.toInstant();}
            else if(expected instanceof Long){long number=rs.getLong(entry.getKey());actual=rs.wasNull()?null:number;}
            else actual=rs.getString(entry.getKey());
            if(!Objects.equals(expected,actual))throw unavailable();
        }
        return manifest;
    }
    private Map<String,Object> columns(Manifest m){
        var values=new LinkedHashMap<String,Object>();values.put("request_id",m.requestId());values.put("receipt_id",m.receiptId());
        values.put("operation",OPERATION);values.put("workspace_id",m.key().workspaceId());values.put("prompt_id",m.key().promptId());
        values.put("revision_id",m.key().revisionId());values.put("subject_id",m.key().subject());values.put("certificate_sha",m.key().certificateSha());
        values.put("key_sha",m.key().keySha());values.put("request_sha",m.requestSha());values.put("generation",m.before().generation());
        values.put("head_revision_id",m.before().revisionId());values.put("before_sha",m.before().scalarSha());values.put("after_sha",m.afterSha());
        values.put("result_sha",m.resultSha());values.put("prepared_sha",m.preparedSha());values.put("first_attempt",m.firstAttempt());
        values.put("intent_until",m.intentUntil());values.put("receipt_until",m.receiptUntil());values.put("closure_scope_until",m.closureScopeUntil());
        values.put("manifest_json",encode(m));return values;
    }
    private static Map<String,Object> command(Manifest m){return map("format","PROMPT_BODY_CLEAR_COMMAND/v1","operation",OPERATION,
            "workspace",workspace(m.workspace()),"evidence",evidence(m.evidence()),"before",head(m.before()),
            "target",header(m.target()),"originalReceipt",original(m.originalReceipt()));}
    private static Map<String,Object> result(Manifest m){return map("format","PROMPT_BODY_CLEAR_RESULT/v1","operation",OPERATION,
            "requestId",m.requestId(),"receiptId",m.receiptId(),"key",key(m.key()),"requestSha",m.requestSha(),
            "before",head(m.before()),"after",head(m.afterHead()),"target",header(m.target()),"clearedTarget",header(m.afterHeader()),
            "firstAttempt",m.firstAttempt(),"intentUntil",m.intentUntil(),"receiptUntil",m.receiptUntil(),"closureScopeUntil",m.closureScopeUntil());}
    private static Map<String,Object> prepared(Manifest m){var values=material(m);values.put("format","PROMPT_BODY_CLEAR_PREPARED/v1");return values;}
    private static Manifest withDigests(Manifest m,String request,String result,String prepared){
        return new Manifest(m.workspace(),m.evidence(),m.before(),m.target(),m.originalReceipt(),m.requestId(),m.receiptId(),m.key(),
                request,m.afterSha(),result,prepared,m.firstAttempt(),m.intentUntil(),m.receiptUntil(),m.closureScopeUntil());
    }
    private static Instant min(Instant... values){return Arrays.stream(values).min(Instant::compareTo).orElseThrow();}
    private static void capacity(String raw){
        if(raw==null||raw.length()>16384||raw.getBytes(StandardCharsets.UTF_8).length>65536)throw invalid();
    }
}
