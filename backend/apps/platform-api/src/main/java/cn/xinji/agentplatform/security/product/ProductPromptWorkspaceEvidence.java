package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Authenticates deployment-bound original bytes and their exact approved workspace scope. */
final class ProductPromptWorkspaceEvidence {
    enum Action { CREATE, READ, SAVE, COPY, PREVIEW }
    record Binding(String workspaceId, String organization, long scopeVersion, String approvalRef,
                   String approvalSha, String policyRef, String policySha) {
        Binding {
            uuid(workspaceId);uuid(organization);sha(approvalSha);sha(policySha);
            reference(approvalRef);reference(policyRef);
            if(scopeVersion<1)throw invalid();
        }
    }
    record Approval(String format,String decisionId,String source,String decisionVersion,String responsibleParty,
                    LocalDate decisionDate,String status,Instant validFrom,Instant validUntil,
                    String workspaceId,String organization,long scopeVersion,String policyRef,String policySha,
                    String contentCoverage,List<Action> operations) {
        Approval { if(operations!=null)operations=List.copyOf(operations); }
    }
    record Policy(String format,String policyRef,String source,String decisionVersion,String responsibleParty,
                  LocalDate decisionDate,String status,Instant validFrom,Instant validUntil,String workspaceId,
                  String organization,long scopeVersion,String contentCoverage,int bodyMaxDays,int auditMaxDays,int backupMaxDays) { }
    record Witness(Binding binding,Approval approval,Policy policy) { }
    private final Map<String,Binding> bindings;
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;

    ProductPromptWorkspaceEvidence(List<Binding> bindings,ProductSourceEvidenceFiles files){
        var exact=new TreeMap<String,Binding>();
        for(Binding binding:List.copyOf(bindings))
            if(exact.put(binding.workspaceId(),binding)!=null)throw invalid();
        if(exact.isEmpty())throw invalid();
        this.bindings=Map.copyOf(exact);this.files=Objects.requireNonNull(files);
        this.mapper=new ObjectMapper().registerModule(new JavaTimeModule()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
    void requireBound(String workspaceId){
        uuid(workspaceId);if(!bindings.containsKey(workspaceId))throw denied();
    }
    Witness current(Workspace workspace,Instant now){
        Binding binding=bindings.get(workspace.id());
        if(binding==null)throw denied();
        Approval approval=parse(original(binding.approvalRef(),binding.approvalSha()),Approval.class);
        Policy policy=parse(original(binding.policyRef(),binding.policySha()),Policy.class);
        try {
            if(!"PROMPT_WORKSPACE_APPROVAL/v1".equals(approval.format())
                    ||!"PROMPT_DRAFT_RETENTION_POLICY/v1".equals(policy.format()))throw invalid();
            provenance(approval.decisionId(),approval.source(),approval.decisionVersion(),approval.responsibleParty(),approval.decisionDate());
            provenance(policy.policyRef(),policy.source(),policy.decisionVersion(),policy.responsibleParty(),policy.decisionDate());
            active(approval.status(),approval.validFrom(),approval.validUntil(),now);
            active(policy.status(),policy.validFrom(),policy.validUntil(),now);
            scope(binding,approval.workspaceId(),approval.organization(),approval.scopeVersion());
            scope(binding,policy.workspaceId(),policy.organization(),policy.scopeVersion());
            scope(binding,workspace.id(),workspace.organization(),workspace.scopeVersion());
            if(!"PROJECT_AUTHORED_HUMAN_DRAFT".equals(approval.contentCoverage())
                    ||!approval.contentCoverage().equals(policy.contentCoverage())
                    ||approval.operations()==null||approval.operations().isEmpty()
                    ||Set.copyOf(approval.operations()).size()!=approval.operations().size()
                    ||policy.bodyMaxDays()!=90||policy.auditMaxDays()!=180||policy.backupMaxDays()!=7
                    ||!binding.policyRef().equals(approval.policyRef())||!binding.policyRef().equals(policy.policyRef())
                    ||!binding.policySha().equals(approval.policySha())||!binding.policySha().equals(workspace.policySha())
                    ||!binding.policyRef().equals(workspace.policyRef())||!binding.approvalSha().equals(workspace.registrySha())
                    ||workspace.validUntil().isAfter(approval.validUntil())||workspace.validUntil().isAfter(policy.validUntil()))throw invalid();
        }catch(RuntimeException invalid){throw unavailable();}
        return new Witness(binding,approval,policy);
    }
    void requireAction(Witness witness,Action action){
        if(!witness.approval().operations().contains(action))throw denied();
    }
    private byte[] original(String reference,String sha){
        try{return files.verified(reference,sha);}catch(RuntimeException unreadable){throw unavailable();}
    }
    private <T>T parse(byte[] bytes,Class<T> type){
        try {
            JsonNode tree=mapper.readTree(bytes);tokens(tree,type==Approval.class);
            T value=mapper.treeToValue(tree,type);if(value==null)throw invalid();return value;
        }
        catch(Exception malformed){throw unavailable();}
    }
    private static void tokens(JsonNode tree,boolean approval){
        if(tree==null||!tree.isObject())throw invalid();
        var strings=new java.util.HashSet<>(List.of("format","source","decisionVersion","responsibleParty","decisionDate",
                "status","validFrom","validUntil","workspaceId","organization","policyRef","contentCoverage"));
        if(approval)strings.addAll(List.of("decisionId","policySha"));
        var fields=new java.util.HashSet<>(strings);fields.add("scopeVersion");
        if(approval)fields.add("operations");else fields.addAll(List.of("bodyMaxDays","auditMaxDays","backupMaxDays"));
        var actual=new java.util.HashSet<String>();tree.fieldNames().forEachRemaining(actual::add);
        if(!fields.equals(actual))throw invalid();
        for(String name:strings)if(!tree.get(name).isTextual())throw invalid();
        integer(tree.get("scopeVersion"),true);
        if(approval){
            if(!tree.get("operations").isArray())throw invalid();
            for(JsonNode value:tree.get("operations"))if(!value.isTextual())throw invalid();
        }else for(String name:List.of("bodyMaxDays","auditMaxDays","backupMaxDays"))integer(tree.get(name),false);
        String date=tree.get("decisionDate").textValue();
        if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!LocalDate.parse(date).toString().equals(date))throw invalid();
        for(String name:List.of("validFrom","validUntil")){
            String raw=tree.get(name).textValue();
            if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z")
                    ||!Instant.parse(raw).toString().equals(raw))throw invalid();
        }
    }
    private static void integer(JsonNode value,boolean big){
        if(!value.isIntegralNumber()||!(big?value.canConvertToLong():value.canConvertToInt()))throw invalid();
    }
    private static void active(String status,Instant from,Instant until,Instant now){
        time(from);time(until);
        if(!"ACTIVE".equals(status)||!until.isAfter(from)||now.isBefore(from)||!now.isBefore(until))throw invalid();
    }
    private static void scope(Binding binding,String workspace,String organization,long version){
        uuid(workspace);uuid(organization);
        if(!binding.workspaceId().equals(workspace)||!binding.organization().equals(organization)||binding.scopeVersion()!=version)throw invalid();
    }
    private static void provenance(String id,String source,String version,String owner,LocalDate date){
        text(id);text(source);text(version);text(owner);if(date==null)throw invalid();
    }
    private static void reference(String value){
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||value.contains(".."))throw invalid();
    }
    static ProductPromptAccessDenied denied(){return new ProductPromptAccessDenied();}
}
