package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Complete human input and exact old revision coordinates; server provenance is never a command field. */
record ProductPromptSaveCommand(long expectedGeneration,String expectedRevisionId,PromptRevision.Kind kind,String name,String body,
                                List<String> scenarioIds,List<String> intentIds,String audienceRef,String language,int displayOrder,
                                List<VariableDefinition> variables,String sourceId) {
    private static final ObjectMapper MAPPER=ProductPromptCreateCommand.commandMapper();
    private static final ProductComparisonJson JSON=new ProductComparisonJson(MAPPER);
    ProductPromptSaveCommand {
        if(expectedGeneration<1||expectedGeneration==Long.MAX_VALUE)throw invalid();uuid(expectedRevisionId);
        var content=new ProductPromptCreateCommand(0,kind,name,body,scenarioIds,intentIds,audienceRef,language,displayOrder,variables,sourceId);
        try{new TemplateRenderer().validateSyntax(body);}catch(PromptFailure malformed){throw invalid();}
        scenarioIds=content.scenarioIds();intentIds=content.intentIds();variables=content.variables();
    }
    static ProductPromptSaveCommand decode(byte[] utf8){
        if(utf8==null||utf8.length==0||utf8.length>1024*1024)throw invalid();
        try{
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
            JsonNode tree=MAPPER.readTree(raw);
            if(!(tree instanceof ObjectNode object)||!object.has("expectedRevisionId")||!object.get("expectedRevisionId").isTextual()
                    ||!object.has("expectedGeneration")||!object.get("expectedGeneration").isIntegralNumber()
                    ||!object.get("expectedGeneration").canConvertToLong())throw invalid();
            long generation=object.get("expectedGeneration").longValue();String revision=object.get("expectedRevisionId").textValue();
            ObjectNode content=object.deepCopy();content.remove("expectedRevisionId");content.put("expectedGeneration",0);
            var c=ProductPromptCreateCommand.decode(MAPPER.writeValueAsBytes(content));
            return new ProductPromptSaveCommand(generation,revision,c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),
                    c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        }catch(Exception malformed){throw invalid();}
    }
    String requestSha(String workspaceId,String promptId){
        uuid(workspaceId);uuid(promptId);var value=new LinkedHashMap<String,Object>();
        value.put("format","PROMPT_SAVE_COMMAND/v1");value.put("workspaceId",workspaceId);value.put("promptId",promptId);
        JsonNode command=MAPPER.valueToTree(this);command.fields().forEachRemaining(entry->value.put(entry.getKey(),entry.getValue()));
        String canonical=JSON.canonical(value);if(canonical.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw invalid();
        return ProductComparisonJson.hash(canonical);
    }
    String contentSha(){return PromptRevision.contentSha256(kind,name,body,Set.copyOf(scenarioIds),Set.copyOf(intentIds),audienceRef,language,displayOrder,variables);}
    PromptRevision revision(String promptId,String revisionId,PromptRevision.Provenance provenance){
        uuid(promptId);uuid(revisionId);
        return new PromptRevision(new PromptRevision.Reference(promptId,revisionId,expectedGeneration+1,contentSha()),kind,name,body,
                Set.copyOf(scenarioIds),Set.copyOf(intentIds),audienceRef,language,displayOrder,variables,provenance);
    }
}
