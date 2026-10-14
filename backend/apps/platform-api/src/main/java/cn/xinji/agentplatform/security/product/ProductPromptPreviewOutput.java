package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptFailure;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.*;

/** Complete bounded JSON only; never serializes domain objects or streams a partial HTTP response. */
final class ProductPromptPreviewOutput {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> PUBLIC_ISSUES=Set.of("MISSING_VARIABLE","UNDEFINED_VARIABLE","VARIABLE_TYPE_MISMATCH","VARIABLE_EMPTY_NOT_ALLOWED");
    private ProductPromptPreviewOutput() { }
    static byte[] success(String workspaceId,PromptRevision revision,ProductPromptRecords.Header header,
            String schemaSha,String policySha,Instant expiresAt,TemplateRenderer.Result rendered){
        if(rendered.mode()!=TemplateRenderer.Mode.TEST||!rendered.revision().equals(revision.reference())
                ||!rendered.policySha256().equals(policySha))throw ProductPromptRecords.unavailable();
        if(rendered.segments().size()>1024)throw new ProductPromptPreviewLimit();
        var cache=new IdentityHashMap<TemplateRenderer.Segment,Map<String,Object>>();
        var segments=rendered.segments().stream().map(segment->cache.computeIfAbsent(segment,ProductPromptPreviewOutput::segment)).toList();
        var body=new LinkedHashMap<String,Object>();body.put("status","OK");body.put("mode","TEST");body.put("workspaceId",workspaceId);
        body.put("promptId",revision.reference().promptId());body.put("revisionId",revision.reference().revisionId());
        body.put("revisionNumber",Long.toString(header.number()));body.put("contentSha",header.contentSha());
        body.put("schemaSha",schemaSha);body.put("policySha",policySha);body.put("expiresAt",expiresAt.toString());body.put("segments",segments);
        return encode(body);
    }
    static byte[] diagnostics(List<PromptFailure.Issue> issues,Instant expiresAt){
        if(issues==null||issues.isEmpty())throw ProductPromptRecords.unavailable();
        if(issues.size()>2048)throw new ProductPromptPreviewLimit();
        var rows=issues.stream().map(issue->{
            if(!PUBLIC_ISSUES.contains(issue.code()))throw ProductPromptRecords.unavailable();
            try{ProductPromptPreviewValues.name(issue.variable());}catch(IllegalArgumentException malformed){throw ProductPromptRecords.unavailable();}
            return Map.of("code",issue.code(),"variable",issue.variable());
        }).toList();
        return encode(Map.of("code","PROMPT_PREVIEW_INVALID_VALUES","expiresAt",expiresAt.toString(),"issues",rows));
    }
    private static Map<String,Object> segment(TemplateRenderer.Segment segment){
        if(segment instanceof TemplateRenderer.LiteralSegment literal)return Map.of("kind","LITERAL","text",literal.text());
        if(!(segment instanceof TemplateRenderer.DataSegment data))throw ProductPromptRecords.unavailable();
        if(data.sourceEvidenceRef()==null||!data.sourceEvidenceRef().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw ProductPromptRecords.unavailable();
        return Map.of("kind","DATA","name",data.name(),"type",data.type().name(),"sourceEvidenceId",data.sourceEvidenceRef(),
                "value",ProductPromptPreviewValues.wire(data.type(),data.value()));
    }
    private static byte[] encode(Object value){
        var output=new LimitedOutput();
        try{JSON.writeValue(output,value);return output.bytes.toByteArray();}
        catch(IOException failure){
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof ProductPromptPreviewLimit limit)throw limit;
            throw ProductPromptRecords.unavailable();
        }
    }
    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream(8192);
        @Override public void write(int value){budget(1);bytes.write(value);}
        @Override public void write(byte[] value,int offset,int length){Objects.checkFromIndexSize(offset,length,value.length);budget(length);bytes.write(value,offset,length);}
        private void budget(int length){if(length>1048576-bytes.size())throw new ProductPromptPreviewLimit();}
    }
}
