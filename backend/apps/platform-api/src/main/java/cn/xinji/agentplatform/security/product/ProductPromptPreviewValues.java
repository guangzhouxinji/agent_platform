package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/** Lossless numeric projection and a separate versioned TEST schema fingerprint. */
final class ProductPromptPreviewValues {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ProductPromptPreviewValues() { }
    static void name(String value){
        if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))throw ProductPromptRecords.invalid();
    }
    static void string(String value){
        if(value==null||!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw ProductPromptRecords.invalid();
        if(value.getBytes(StandardCharsets.UTF_8).length>64*1024)throw new ProductPromptPreviewLimit();
    }
    static Object number(VariableDefinition.Type type,String lexical){
        if(lexical==null)throw ProductPromptRecords.invalid();
        if(lexical.length()>1024)throw new ProductPromptPreviewLimit();
        try {
            Object result;
            if(type==VariableDefinition.Type.INTEGER){
                if(!lexical.matches("-?(0|[1-9][0-9]*)"))throw ProductPromptRecords.invalid();
                result=new BigInteger(lexical);
            }else{
                if(type!=VariableDefinition.Type.DECIMAL||!lexical.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw ProductPromptRecords.invalid();
                result=new BigDecimal(lexical);
            }
            wire(type,result);return result;
        }catch(NumberFormatException invalid){throw ProductPromptRecords.invalid();}
    }
    static Object wire(VariableDefinition.Type type,Object value){
        if(value==null)return null;
        return switch(type){
            case STRING->{if(!(value instanceof String text))throw ProductPromptRecords.invalid();string(text);yield text;}
            case BOOLEAN->{if(!(value instanceof Boolean))throw ProductPromptRecords.invalid();yield value;}
            case INTEGER->{
                if(!(value instanceof BigInteger||value instanceof Long||value instanceof Integer||value instanceof Short||value instanceof Byte))throw ProductPromptRecords.invalid();
                String text=value.toString();if(text.length()>1024)throw new ProductPromptPreviewLimit();yield text;
            }
            case DECIMAL->{
                if(!(value instanceof BigDecimal number))throw ProductPromptRecords.invalid();
                if(number.precision()>1024||number.scale() < -4096||number.scale()>4096)throw new ProductPromptPreviewLimit();
                String text=number.toString();if(text.length()>1024)throw new ProductPromptPreviewLimit();yield text;
            }
            case STRING_LIST->{
                if(!(value instanceof List<?> items))throw ProductPromptRecords.invalid();
                if(items.size()>256)throw new ProductPromptPreviewLimit();
                for(Object item:items){if(!(item instanceof String text))throw ProductPromptRecords.invalid();string(text);}
                yield List.copyOf(items);
            }
        };
    }
    static String schemaSha(List<VariableDefinition> variables){
        if(variables==null)throw ProductPromptRecords.invalid();
        if(variables.size()>64)throw new ProductPromptPreviewLimit();
        if(variables.stream().map(VariableDefinition::name).distinct().count()!=variables.size())throw ProductPromptRecords.invalid();
        var definitions=variables.stream().map(variable->{
            if(variable.name()!=null&&variable.name().length()>128)throw new ProductPromptPreviewLimit();
            name(variable.name());var item=new LinkedHashMap<String,Object>();
            item.put("name",variable.name());item.put("type",variable.type().name());item.put("required",variable.required());
            item.put("sourceRef",variable.sourceRef());item.put("allowDefault",variable.allowDefault());
            item.put("defaultValue",wire(variable.type(),variable.defaultValue()));item.put("allowEmpty",variable.allowEmpty());return item;
        }).toList();
        var schema=new LinkedHashMap<String,Object>();schema.put("format","PROMPT_PREVIEW_SCHEMA/v1");schema.put("variables",definitions);
        try{return ProductComparisonJson.hash(JSON.writeValueAsString(schema));}
        catch(java.io.IOException failure){throw ProductPromptRecords.unavailable();}
    }
}
