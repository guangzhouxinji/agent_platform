package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptFailure;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Values only. The selected server policy supplies all provenance and rendering authority. */
record ProductPromptPreviewCommand(String contentSha,String sourceId,List<Input> values) {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Input(String name,VariableDefinition.Type type,Object value){
        Input{ProductPromptPreviewValues.name(name);Objects.requireNonNull(type);ProductPromptPreviewValues.wire(type,value);
            if(value instanceof List<?> list)value=List.copyOf(list);}
    }
    ProductPromptPreviewCommand {
        sha(contentSha);uuid(sourceId);if(values==null)throw invalid();
        if(values.size()>64)throw new ProductPromptPreviewLimit();values=List.copyOf(values);
        if(values.stream().map(Input::name).distinct().count()!=values.size())throw invalid();
    }
    static ProductPromptPreviewCommand decode(byte[] bytes){
        if(bytes==null||bytes.length==0)throw invalid();if(bytes.length>1048576)throw new ProductPromptPreviewLimit();
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root=JSON.readTree(text);fields(root,Set.of("contentSha","sourceId","values"));
            if(!root.get("contentSha").isTextual()||!root.get("sourceId").isTextual()||!root.get("values").isArray())throw invalid();
            if(root.get("values").size()>64)throw new ProductPromptPreviewLimit();
            var values=new ArrayList<Input>();
            for(JsonNode row:root.get("values")){
                fields(row,Set.of("name","type","value"));
                if(!row.get("name").isTextual()||!row.get("type").isTextual())throw invalid();
                var type=VariableDefinition.Type.valueOf(row.get("type").textValue());
                values.add(new Input(row.get("name").textValue(),type,value(type,row.get("value"))));
            }
            return new ProductPromptPreviewCommand(root.get("contentSha").textValue(),root.get("sourceId").textValue(),values);
        }catch(ProductPromptPreviewLimit limit){throw limit;}
        catch(Exception malformed){throw invalid();}
    }
    Map<String,TemplateRenderer.Value> supplied(PromptRevision revision,Map<String,String> evidenceIds){
        var definitions=new HashMap<String,VariableDefinition>();revision.variables().forEach(v->definitions.put(v.name(),v));
        var result=new LinkedHashMap<String,TemplateRenderer.Value>();var issues=new ArrayList<PromptFailure.Issue>();
        for(var input:values){
            var definition=definitions.get(input.name());
            if(definition==null){issues.add(new PromptFailure.Issue("UNDEFINED_VARIABLE",input.name()));continue;}
            if(definition.type()!=input.type()){issues.add(new PromptFailure.Issue("VARIABLE_TYPE_MISMATCH",input.name()));continue;}
            String evidence=evidenceIds.get(input.name());if(evidence==null||evidence.isBlank())throw unavailable();
            result.put(input.name(),new TemplateRenderer.Value(input.value(),definition.sourceRef(),evidence));
        }
        if(!issues.isEmpty())throw new PromptFailure(issues);return Collections.unmodifiableMap(result);
    }
    private static Object value(VariableDefinition.Type type,JsonNode node){
        if(node.isNull())return null;
        return switch(type){
            case STRING->{if(!node.isTextual())throw invalid();yield node.textValue();}
            case BOOLEAN->{if(!node.isBoolean())throw invalid();yield node.booleanValue();}
            case INTEGER,DECIMAL->{if(!node.isTextual())throw invalid();yield ProductPromptPreviewValues.number(type,node.textValue());}
            case STRING_LIST->{
                if(!node.isArray())throw invalid();if(node.size()>256)throw new ProductPromptPreviewLimit();
                var list=new ArrayList<String>();for(JsonNode item:node){if(!item.isTextual())throw invalid();list.add(item.textValue());}yield list;
            }
        };
    }
    private static void fields(JsonNode node,Set<String> expected){
        if(node==null||!node.isObject())throw invalid();var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected))throw invalid();
    }
}
