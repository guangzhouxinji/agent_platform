package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Exact source selection only; callers cannot replace copied content or server provenance. */
record ProductPromptCopyCommand(long expectedGeneration,String sourceRevisionId,String sourceContentSha,
                                String targetWorkspaceId,String sourceId) {
    private static final Set<String> FIELDS=Set.of("expectedGeneration","sourceRevisionId","sourceContentSha","targetWorkspaceId","sourceId");
    private static final ObjectMapper MAPPER=ProductPromptCreateCommand.commandMapper();
    private static final ProductComparisonJson JSON=new ProductComparisonJson(MAPPER);
    ProductPromptCopyCommand {
        if(expectedGeneration!=0)throw invalid();
        uuid(sourceRevisionId);sha(sourceContentSha);uuid(targetWorkspaceId);uuid(sourceId);
    }
    static ProductPromptCopyCommand decode(byte[] utf8){
        if(utf8==null||utf8.length==0||utf8.length>1024*1024)throw invalid();
        try{
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(utf8)).toString();
            JsonNode tree=MAPPER.readTree(raw);
            if(tree==null||!tree.isObject())throw invalid();
            var names=new HashSet<String>();tree.fieldNames().forEachRemaining(names::add);
            if(!names.equals(FIELDS)||!tree.get("expectedGeneration").isIntegralNumber()
                    ||!tree.get("expectedGeneration").canConvertToLong())throw invalid();
            for(String field:FIELDS)if(!field.equals("expectedGeneration")&&!tree.get(field).isTextual())throw invalid();
            return MAPPER.treeToValue(tree,ProductPromptCopyCommand.class);
        }catch(Exception malformed){throw invalid();}
    }
    String requestSha(String sourceWorkspaceId,String sourcePromptId){
        uuid(sourceWorkspaceId);uuid(sourcePromptId);
        var values=new LinkedHashMap<String,Object>();values.put("format","PROMPT_COPY_COMMAND/v1");
        values.put("sourceWorkspaceId",sourceWorkspaceId);values.put("sourcePromptId",sourcePromptId);
        JsonNode command=MAPPER.valueToTree(this);command.fields().forEachRemaining(entry->values.put(entry.getKey(),entry.getValue()));
        return ProductComparisonJson.hash(JSON.canonical(values));
    }
}
