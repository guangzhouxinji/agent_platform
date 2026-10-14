package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentTemplateProfile.*;
import java.math.BigDecimal;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract;
import java.time.*;
import java.util.*;

/** Generic deterministic field/condition policy. No current template names are embedded in the engine. */
public final class DocumentProfileEngine {
    public enum Visibility { VISIBLE, HIDDEN, UNKNOWN }
    public record SourceDatum(Object value,String sourceRef,long sourceVersion,String sourceSha256,
            String locator,boolean confirmed) {
        public SourceDatum {
            if(sourceRef==null||sourceRef.isBlank()||sourceVersion<1||sourceSha256==null
                    ||!sourceSha256.matches("[0-9a-f]{64}")||locator==null||locator.isBlank())
                throw new IllegalArgumentException("Exact field source required");
            value=freeze(value);
        }
    }
    public record FieldValue(FieldDefinition definition,SourceDatum source,Object value,boolean missing,boolean needsReview) { }
    public record SectionValue(String id,String title,Visibility visibility,SourceDatum conditionSource,List<FieldValue> fields) {
        public SectionValue {fields=List.copyOf(fields);}
    }
    public record Issue(String field,String code,boolean blocking) { }
    public record Content(int schemaVersion,DocumentTemplateProfile profile,DocumentRuleContract.Rule rule,boolean reviewBlocked,
            boolean reviewRequired,List<SectionValue> sections,List<Issue> issues,List<DocumentContentEditor.Correction> corrections) {
        public Content(int schemaVersion,DocumentTemplateProfile profile,DocumentRuleContract.Rule rule,boolean reviewBlocked,
                boolean reviewRequired,List<SectionValue> sections,List<Issue> issues) {
            this(schemaVersion,profile,rule,reviewBlocked,reviewRequired,sections,issues,List.of());
        }
        public Content {
            rule=new DocumentRuleContract.Rule(rule.id(),rule.revision(),rule.organization(),rule.state(),rule.templateId(),rule.templateRevision(),rule.templateSha256(),rule.schemaId(),rule.schemaVersion(),List.copyOf(rule.mappings()));
            sections=List.copyOf(sections);issues=List.copyOf(issues);
            corrections=corrections==null?List.of():List.copyOf(corrections);
        }
    }
    public Content compose(DocumentTemplateProfile profile,Instant generatedAt,String region,String stage,DocumentRuleContract.Rule rule,Map<String,SourceDatum> sources) {
        return compose(profile,generatedAt,region,stage,rule,sources,null);
    }
    /** Consume the exact mapping trial, while retaining original source values as provenance. */
    public Content compose(DocumentTemplateProfile profile,Instant generatedAt,String region,String stage,
            DocumentRuleContract.Rule rule,Map<String,SourceDatum> sources,DocumentRuleContract.Trial trial) {
        Objects.requireNonNull(profile);Objects.requireNonNull(generatedAt);Objects.requireNonNull(sources);
        if(rule==null||rule.state()!=DocumentRuleContract.State.PUBLISHED)throw new IllegalArgumentException("Exact published mapping rule required");
        var mappings=new HashMap<String,DocumentRuleContract.Mapping>();
        var fieldIds=profile.fields().stream().map(FieldDefinition::id).collect(java.util.stream.Collectors.toSet());
        for(var mapping:rule.mappings())if(!fieldIds.contains(mapping.fieldId())||mappings.put(mapping.fieldId(),mapping)!=null)throw new IllegalArgumentException("Invalid or duplicate actual rule mapping");
        var mapped=new HashMap<String,DocumentRuleContract.Mapped>();
        if(trial!=null)for(var item:trial.fields()) {
            var binding=mappings.get(item.fieldId());
            var source=binding==null?null:sources.get(binding.sourcePath());
            if(binding==null||source==null||mapped.put(item.fieldId(),item)!=null||item.mode()!=binding.mode()
                    ||!Objects.equals(item.sourceRef(),source.sourceRef())
                    ||!Objects.equals(item.sourceVersion(),Long.toString(source.sourceVersion()))
                    ||!Objects.equals(item.locator(),source.locator()))
                throw new IllegalArgumentException("Mapping trial source coordinate mismatch");
        }
        var issues=new ArrayList<Issue>();
        if(generatedAt.isBefore(profile.effectiveFrom())||(profile.effectiveUntil()!=null&&!generatedAt.isBefore(profile.effectiveUntil())))
            issues.add(new Issue("","TEMPLATE_NOT_EFFECTIVE",true));
        scope(profile.regions(),region,"REGION",issues);scope(profile.stages(),stage,"STAGE",issues);
        var definitions=new HashMap<String,FieldDefinition>();profile.fields().forEach(f->definitions.put(f.id(),f));
        var sections=new ArrayList<SectionValue>();boolean review=false;
        for(var section:profile.sections()) {
            Visibility visible=visibility(section.condition(),sources);
            if(visible==Visibility.UNKNOWN){issues.add(new Issue(section.id(),"DISPLAY_INPUT_UNKNOWN",false));review=true;}
            var fields=new ArrayList<FieldValue>();
            if(visible!=Visibility.HIDDEN)for(String id:section.fields()) {
                var definition=definitions.get(id);var mapping=mappings.get(id);var datum=mapping==null?null:sources.get(mapping.sourcePath());
                var result=mapped.get(id);
                if(trial==null&&mapping!=null&&mapping.mode()!=DocumentRuleContract.Mode.DIRECT)
                    throw new IllegalArgumentException("Non-direct mapping requires its exact trial result");
                Object value=trial==null?(datum==null?null:datum.value()):(result==null?null:result.value());boolean missing=absent(value);
                boolean invalid=!missing&&!typed(value,definition.type(),definition.repeated());
                boolean unconfirmed=datum!=null&&(!datum.confirmed()||mapping.mode()==DocumentRuleContract.Mode.AUXILIARY_DRAFT
                        ||result!=null&&result.reviewRequired());
                if(invalid){issues.add(new Issue(id,"SOURCE_TYPE_INVALID",true));value=null;missing=true;}
                if(unconfirmed&&List.of("DATE","DECIMAL").contains(definition.type())){value=null;missing=true;issues.add(new Issue(id,"PROTECTED_INPUT_UNCONFIRMED",definition.requiredForReview()));}
                if(missing)issues.add(new Issue(id,"MISSING_SOURCE_VALUE",definition.requiredForReview()));
                if(unconfirmed){review=true;issues.add(new Issue(id,"SOURCE_REVIEW_REQUIRED",false));}
                fields.add(new FieldValue(definition,datum,value,missing,unconfirmed||definition.editableText()));
            }
            sections.add(new SectionValue(section.id(),section.title(),visible,section.condition().sourcePath()==null?null:sources.get(section.condition().sourcePath()),fields));
        }
        return new Content(1,profile,rule,issues.stream().anyMatch(Issue::blocking),review,sections,issues);
    }
    private static Visibility visibility(Condition condition,Map<String,SourceDatum> sources) {
        if(condition.operator()==Operator.ALWAYS)return Visibility.VISIBLE;
        var datum=sources.get(condition.sourcePath());
        if(datum==null||!datum.confirmed())return Visibility.UNKNOWN;
        if(condition.operator()==Operator.PRESENT)return absent(datum.value())?Visibility.HIDDEN:Visibility.VISIBLE;
        if(datum.value()==null)return Visibility.HIDDEN;
        if(!(datum.value() instanceof String||datum.value() instanceof Boolean||datum.value() instanceof BigDecimal))return Visibility.UNKNOWN;
        return condition.expected().equals(datum.value().toString())?Visibility.VISIBLE:Visibility.HIDDEN;
    }
    private static void scope(List<String> allowed,String value,String name,List<Issue> issues) {
        if(allowed.contains("*"))return;
        if(value==null||value.isBlank())issues.add(new Issue("",name+"_SCOPE_UNKNOWN",true));
        else if(!allowed.contains(value))issues.add(new Issue("",name+"_SCOPE_MISMATCH",true));
    }
    private static boolean absent(Object value) {
        return value==null||value instanceof String s&&s.isBlank()||value instanceof Collection<?> c&&c.isEmpty();
    }
    private static boolean typed(Object value,String type,boolean repeated) {
        if(repeated)return value instanceof List<?> list&&list.stream().allMatch(item->typed(item,type,false));
        return switch(type) {
            case "STRING","LONG_TEXT" -> value instanceof String;
            case "DATE" -> {try{LocalDate.parse((String)value);yield true;}catch(RuntimeException invalid){yield false;}}
            case "DECIMAL" -> value instanceof BigDecimal||value instanceof String s&&s.matches("-?[0-9]+(?:\\.[0-9]+)?");
            default -> false;
        };
    }
    private static Object freeze(Object value) {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof BigDecimal||value instanceof Integer||value instanceof Long||value instanceof LocalDate)return value;
        if(value instanceof List<?> list){var copy=new ArrayList<Object>();list.forEach(v->copy.add(freeze(v)));return Collections.unmodifiableList(copy);}
        if(value instanceof Map<?,?> map){var copy=new TreeMap<String,Object>();map.forEach((k,v)->{if(!(k instanceof String))throw new IllegalArgumentException("String source keys required");copy.put((String)k,freeze(v));});return Collections.unmodifiableMap(copy);}
        throw new IllegalArgumentException("Unsupported source value; binary floating-point money forbidden");
    }
}
