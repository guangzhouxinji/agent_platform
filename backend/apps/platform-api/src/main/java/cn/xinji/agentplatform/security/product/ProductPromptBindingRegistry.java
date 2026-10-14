package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Current deployment pin plus strict immutable workspace bindings; never a registration writer. */
final class ProductPromptBindingRegistry {
    private final ProductSourceEvidenceFiles files;
    private final String reference, digest;
    private final ProductPromptWorkspaceEvidence evidence;
    ProductPromptBindingRegistry(ProductSourceEvidenceFiles files,String reference,String digest){
        this.files=java.util.Objects.requireNonNull(files);text(reference);sha(digest);
        this.reference=reference;this.digest=digest;
        try {
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=mapper.readTree(files.verified(reference,digest));
            fields(root,Set.of("format","bindings"));
            if(!"PROMPT_WORKSPACE_BINDINGS/v1".equals(string(root,"format")))throw invalid();
            JsonNode entries=root.get("bindings");
            if(!entries.isArray()||entries.isEmpty()||entries.size()>128)throw invalid();
            var bindings=new ArrayList<Binding>();
            for(JsonNode value:entries){
                fields(value,Set.of("workspaceId","organization","scopeVersion","approvalRef","approvalSha","policyRef","policySha"));
                JsonNode version=value.get("scopeVersion");
                if(!version.isIntegralNumber()||!version.canConvertToLong())throw invalid();
                bindings.add(new Binding(string(value,"workspaceId"),string(value,"organization"),version.longValue(),
                        string(value,"approvalRef"),string(value,"approvalSha"),string(value,"policyRef"),string(value,"policySha")));
            }
            evidence=new ProductPromptWorkspaceEvidence(bindings,files);
        }catch(Exception failure){throw new IllegalStateException("Prompt managed bindings unavailable",failure);}
    }
    ProductPromptWorkspaceEvidence evidence(){return evidence;}
    void requireCurrent(){files.verified(reference,digest);}
    private static String string(JsonNode value,String name){
        if(!value.get(name).isTextual())throw invalid();return value.get(name).textValue();
    }
    private static void fields(JsonNode value,Set<String> expected){
        if(value==null||!value.isObject())throw invalid();
        var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected))throw invalid();
    }
}
