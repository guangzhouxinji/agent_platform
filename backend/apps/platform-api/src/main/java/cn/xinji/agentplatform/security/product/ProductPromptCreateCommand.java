package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Human content only. Server identities, timestamps and permission conclusions cannot be supplied here. */
record ProductPromptCreateCommand(long expectedGeneration,PromptRevision.Kind kind,String name,String body,
                                  List<String> scenarioIds,List<String> intentIds,String audienceRef,String language,
                                  int displayOrder,List<VariableDefinition> variables,String sourceId) {
    private static final Set<String> FIELDS=Set.of("expectedGeneration","kind","name","body","scenarioIds","intentIds",
            "audienceRef","language","displayOrder","variables","sourceId");
    private static final Set<String> VARIABLE_FIELDS=Set.of("name","type","required","sourceRef","allowDefault","defaultValue","allowEmpty");
    private static final ObjectMapper MAPPER=new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                    DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static final ProductComparisonJson JSON=new ProductComparisonJson(MAPPER);
    ProductPromptCreateCommand {
        if(expectedGeneration!=0||kind==null)throw invalid();
        bounded(name,128);bounded(language,63);bounded(audienceRef,500);bytes(body,256*1024,true);uuid(sourceId);
        try {new cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer().validateSyntax(body);}
        catch(cn.xinji.agentplatform.consultation.prompt.domain.PromptFailure malformed){throw invalid();}
        scenarioIds=ids(scenarioIds);intentIds=ids(intentIds);
        if(variables==null||variables.size()>64)throw invalid();variables=List.copyOf(variables);
        if(variables.stream().map(VariableDefinition::name).distinct().count()!=variables.size())throw invalid();
        for(var variable:variables){
            bounded(variable.sourceRef(),500);
            Object value=variable.defaultValue();
            if(value instanceof String string)bytes(string,64*1024,false);
            if(value instanceof List<?> list){if(list.size()>256)throw invalid();for(Object item:list)bytes((String)item,64*1024,false);}
        }
    }
    static ProductPromptCreateCommand decode(byte[] utf8){
        if(utf8==null||utf8.length==0||utf8.length>1024*1024)throw invalid();
        try {
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(utf8)).toString();
            JsonNode tree=MAPPER.readTree(raw);fields(tree,FIELDS);
            for(String name:List.of("kind","name","body","audienceRef","language","sourceId"))if(!tree.get(name).isTextual())throw invalid();
            integer(tree.get("expectedGeneration"),true);integer(tree.get("displayOrder"),false);
            for(String name:List.of("scenarioIds","intentIds")){if(!tree.get(name).isArray())throw invalid();for(var item:tree.get(name))if(!item.isTextual())throw invalid();}
            if(!tree.get("variables").isArray())throw invalid();
            for(var variable:tree.get("variables")){
                fields(variable,VARIABLE_FIELDS);
                for(String name:List.of("name","type","sourceRef"))if(!variable.get(name).isTextual())throw invalid();
                for(String name:List.of("required","allowDefault","allowEmpty"))if(!variable.get(name).isBoolean())throw invalid();
            }
            return MAPPER.treeToValue(tree,ProductPromptCreateCommand.class);
        }catch(Exception malformed){throw invalid();}
    }
    static ObjectMapper commandMapper(){return MAPPER.copy();}
    String requestSha(String workspaceId){
        uuid(workspaceId);var value=new LinkedHashMap<String,Object>();value.put("format",ProductPromptCreateRequest.REQUEST_FORMAT);
        value.put("workspaceId",workspaceId);
        JsonNode command=MAPPER.valueToTree(this);command.fields().forEachRemaining(entry->value.put(entry.getKey(),entry.getValue()));
        String canonical=JSON.canonical(value);if(canonical.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw invalid();
        return ProductComparisonJson.hash(canonical);
    }
    String contentSha(){return PromptRevision.contentSha256(kind,name,body,Set.copyOf(scenarioIds),Set.copyOf(intentIds),audienceRef,language,displayOrder,variables);}
    PromptRevision revision(String promptId,String revisionId,PromptRevision.Provenance provenance){
        uuid(promptId);uuid(revisionId);
        return new PromptRevision(new PromptRevision.Reference(promptId,revisionId,1,contentSha()),kind,name,body,
                Set.copyOf(scenarioIds),Set.copyOf(intentIds),audienceRef,language,displayOrder,variables,provenance);
    }
    private static List<String> ids(List<String> values){
        if(values==null||values.size()>64)throw invalid();var sorted=new TreeSet<String>();
        for(String value:values){bounded(value,128);sorted.add(value);}return List.copyOf(sorted);
    }
    private static void bounded(String value,int max){if(value==null||value.isBlank()||value.length()>max||!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw invalid();}
    private static void bytes(String value,int max,boolean required){
        if(value==null||required&&value.isBlank()||!StandardCharsets.UTF_8.newEncoder().canEncode(value)
                ||value.getBytes(StandardCharsets.UTF_8).length>max)throw invalid();
    }
    private static void fields(JsonNode value,Set<String> expected){
        if(value==null||!value.isObject())throw invalid();var names=new HashSet<String>();value.fieldNames().forEachRemaining(names::add);
        if(!names.equals(expected))throw invalid();
    }
    private static void integer(JsonNode value,boolean wide){if(!value.isIntegralNumber()||!(wide?value.canConvertToLong():value.canConvertToInt()))throw invalid();}
}
