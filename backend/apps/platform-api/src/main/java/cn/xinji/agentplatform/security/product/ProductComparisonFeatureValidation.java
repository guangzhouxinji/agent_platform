package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonPreparationLoader.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic validation of explicit user inputs, never automatic feature confirmation. */
final class ProductComparisonFeatureValidation {
    static List<Feature> features(Confirm command,Loaded loaded,int maxBytes) {
        if(!command.confirmed() || command.features()==null || command.features().isEmpty() || command.features().size()>200)
            throw invalid("EXPLICIT_FEATURE_CONFIRMATION_REQUIRED");
        if(!loaded.targetReady()) throw invalid("TARGET_CLAIM_DEPENDENCY_UNRESOLVED");
        if(loaded.candidates().stream().mapToLong(d->d.material().branches().size()).sum()*command.features().size()>4000)
            throw invalid("MATRIX_BUDGET_EXCEEDED");
        Map<String,FeatureInput> inputs=new LinkedHashMap<>(); long bytes=command.userStatement()==null?0:
                command.userStatement().getBytes(StandardCharsets.UTF_8).length;
        for(var input:command.features()) {
            if(input==null || input.featureId()==null || !input.featureId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
                    || blank(input.userText()) || inputs.putIfAbsent(input.featureId(),input)!=null)
                throw invalid("FEATURE_INPUT_INVALID");
            bytes+=input.userText().getBytes(StandardCharsets.UTF_8).length;
            if(bytes>maxBytes) throw invalid("CONFIRMATION_TEXT_BUDGET_EXCEEDED");
        }
        for(var input:inputs.values()) {
            Set<String> chain=new HashSet<>(); FeatureInput cursor=input;
            while(cursor!=null) {
                if(!chain.add(cursor.featureId())) throw invalid("FEATURE_PARENT_CYCLE");
                String parent=cursor.parentFeatureId();
                if(parent!=null && !inputs.containsKey(parent)) throw invalid("FEATURE_PARENT_MISSING");
                cursor=parent==null?null:inputs.get(parent);
            }
        }
        List<Feature> result=new ArrayList<>();
        for(var input:inputs.values()) {
            Span evidence;
            try { evidence=Span.of(loaded.target().evidence(input.characterStart(),input.characterEnd())); }
            catch(IllegalArgumentException problem) { throw invalid("FEATURE_ORIGINAL_SPAN_INVALID"); }
            Integer claim=null;
            if(loaded.targetPatent()!=null) {
                var matches=loaded.targetPatent().material().branches().stream().flatMap(branch->branch.inheritedClaims().stream())
                        .filter(item->item.span().ref().equals(evidence.ref()) && item.span().byteStart()<=evidence.byteStart()
                                && item.span().byteEnd()>=evidence.byteEnd()).map(Claim::number).distinct().toList();
                if(matches.size()!=1) throw invalid("FEATURE_OUTSIDE_SELECTED_TARGET_CLAIMS");
                claim=matches.getFirst();
            }
            result.add(new Feature(input.featureId(),result.size(),input.characterStart(),input.characterEnd(),input.userText(),
                    input.parentFeatureId(),claim,evidence));
        }
        return List.copyOf(result);
    }
    private ProductComparisonFeatureValidation() { }
}
