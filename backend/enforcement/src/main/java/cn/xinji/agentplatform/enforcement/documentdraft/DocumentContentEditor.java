package cn.xinji.agentplatform.enforcement.documentdraft;

import java.time.Instant;
import java.util.*;
import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentProfileEngine.*;

/** Explicit text correction; immutable original source is never rewritten or confirmed by an edit. */
public final class DocumentContentEditor {
    public record Correction(String field,String before,String after,String actor,String reason,Instant at) { }
    public Content revise(Content original,Map<String,String> changes,String actor,String reason,Instant at) {
        if(changes==null||changes.isEmpty()||actor==null||actor.isBlank()||reason==null||reason.isBlank()||at==null)
            throw new IllegalArgumentException("Explicit correction, actor, reason and time required");
        var remaining=new HashSet<>(changes.keySet());
        var corrections=new ArrayList<>(original.corrections());
        var sections=new ArrayList<SectionValue>();
        for(var section:original.sections()) {
            var fields=new ArrayList<FieldValue>();
            for(var field:section.fields()) {
                String id=field.definition().id();
                if(changes.containsKey(id)) {
                    remaining.remove(id);
                    String text=changes.get(id);
                    if(!field.definition().editableText()||field.definition().repeated()
                            ||!"LONG_TEXT".equals(field.definition().type())||section.visibility()!=Visibility.VISIBLE
                            ||text==null||text.isBlank())
                        throw new IllegalArgumentException("Only visible editable narrative text may be corrected");
                    if(Objects.equals(field.value(),text))throw new IllegalArgumentException("Unchanged correction");
                    corrections.add(new Correction(id,(String)field.value(),text,actor,reason,at));
                    field=new FieldValue(field.definition(),field.source(),text,false,true);
                }
                fields.add(field);
            }
            sections.add(new SectionValue(section.id(),section.title(),section.visibility(),section.conditionSource(),fields));
        }
        if(!remaining.isEmpty())throw new IllegalArgumentException("Unknown or hidden field correction");
        // A correction never silently closes a missing-source diagnostic or validates business truth.
        return new Content(original.schemaVersion(),original.profile(),original.rule(),original.reviewBlocked(),
                true,sections,original.issues(),corrections);
    }
}
