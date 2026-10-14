package cn.xinji.agentplatform.enforcement.documentdraft;

import java.time.Instant;
import java.util.*;
import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.Type;

/** Immutable content configuration, not a second template lifecycle or legal approval model. */
public record DocumentTemplateProfile(String id,long revision,String classification,Type documentType,
        String title,Instant effectiveFrom,Instant effectiveUntil,List<String> regions,List<String> stages,
        List<FieldDefinition> fields,List<cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Mapping> defaultMappings,List<Section> sections) {
    public enum Operator { ALWAYS, PRESENT, EQUALS }
    public record Condition(Operator operator,String sourcePath,String expected) {
        public Condition {
            Objects.requireNonNull(operator);
            if(operator!=Operator.ALWAYS && (sourcePath==null||sourcePath.isBlank()))
                throw new IllegalArgumentException("Conditional source path required");
            if(operator==Operator.EQUALS && expected==null)throw new IllegalArgumentException("Expected value required");
        }
    }
    public record FieldDefinition(String id,String label,String type,
            boolean requiredForReview,boolean repeated,boolean editableText) {
        public FieldDefinition {
            if(id==null||!id.matches("[A-Za-z0-9_.\\[\\]]{1,120}")||label==null||label.isBlank()
                    ||!List.of("STRING","LONG_TEXT","DATE","DECIMAL").contains(type))
                throw new IllegalArgumentException("Invalid profile field");
            if(repeated!=id.contains("[]"))throw new IllegalArgumentException("Repeat marker mismatch");
            if(editableText&&!"LONG_TEXT".equals(type))throw new IllegalArgumentException("Source identity/date/amount cannot be freely edited");
        }
    }
    public record Section(String id,String title,Condition condition,List<String> fields) {
        public Section {Objects.requireNonNull(condition);fields=List.copyOf(fields);if(id==null||id.isBlank()||title==null||title.isBlank()||fields.isEmpty())throw new IllegalArgumentException("Section definition required");}
    }
    public DocumentTemplateProfile {
        if(id==null||id.isBlank()||revision<1||title==null||title.isBlank()||documentType==null
                ||!List.of("NON_OFFICIAL","PROVIDER_SUPPLIED").contains(classification)||effectiveFrom==null
                ||(effectiveUntil!=null&&!effectiveUntil.isAfter(effectiveFrom)))throw new IllegalArgumentException("Profile metadata required");
        regions=List.copyOf(regions);stages=List.copyOf(stages);fields=List.copyOf(fields);defaultMappings=List.copyOf(defaultMappings);sections=List.copyOf(sections);
        if(regions.isEmpty()||stages.isEmpty()||fields.isEmpty()||sections.isEmpty())throw new IllegalArgumentException("Profile scope and content required");
        var ids=new HashSet<String>();for(var field:fields)if(!ids.add(field.id()))throw new IllegalArgumentException("Duplicate field");
        var used=new HashSet<String>();var sectionIds=new HashSet<String>();
        for(var section:sections){if(!sectionIds.add(section.id()))throw new IllegalArgumentException("Duplicate section");for(String field:section.fields())if(!ids.contains(field)||!used.add(field))throw new IllegalArgumentException("Unknown or repeated section field");}
        if(!used.equals(ids))throw new IllegalArgumentException("Unplaced field");
    }
}
