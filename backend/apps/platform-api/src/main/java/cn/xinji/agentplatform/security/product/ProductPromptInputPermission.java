package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorization.Lease;
import cn.xinji.agentplatform.security.product.ProductPromptCreateRequest.Evidence;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Deployment-pinned original permission; no client declaration can grant ownership or save rights. */
final class ProductPromptInputPermission {
    record Binding(String sourceId,String permissionRef,String permissionSha) { }
    private record Directory(String format,List<Binding> bindings) { }
    private record Permission(String format,String permissionId,String source,String decisionVersion,String responsibleParty,
                              LocalDate decisionDate,String status,Instant validFrom,Instant validUntil,String workspaceId,
                              String organization,long scopeVersion,String issuer,String subject,String operation,String contentCoverage,
                              String contentSha256,String generationSourceRef,Instant bodyUntil,Instant auditUntil,String policyRef,
                              String policySha,String traceCoverage) { }
    private static final Set<String> PERMISSION_FIELDS=Set.of("format","permissionId","source","decisionVersion","responsibleParty",
            "decisionDate","status","validFrom","validUntil","workspaceId","organization","scopeVersion","issuer","subject",
            "operation","contentCoverage","contentSha256","generationSourceRef","bodyUntil","auditUntil","policyRef","policySha","traceCoverage");
    private final ProductSourceEvidenceFiles files;
    private final String directoryRef,directorySha,issuer;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    ProductPromptInputPermission(ProductSourceEvidenceFiles files,String directoryRef,String directorySha,Clock clock,String issuer){
        this.files=Objects.requireNonNull(files);reference(directoryRef);sha(directorySha);text(issuer);
        this.directoryRef=directoryRef;this.directorySha=directorySha;this.clock=Objects.requireNonNull(clock);this.issuer=issuer;
        directory();
    }
    String sourceIdFor(Evidence original){
        Objects.requireNonNull(original);var matches=directory().stream().filter(value->value.permissionRef().equals(original.inputPermissionRef())
                &&value.permissionSha().equals(original.inputPermissionSha())).toList();
        if(matches.size()!=1)throw unavailable();var binding=matches.getFirst();
        if(!permission(binding).generationSourceRef().equals(original.generationSourceRef()))throw unavailable();
        return binding.sourceId();
    }
    Witness forWrite(Lease lease,String workspaceId,ProductPromptCreateCommand command){
        Objects.requireNonNull(command);var binding=binding(command.sourceId());
        return new Witness(lease,workspaceId,binding,permission(binding),command.contentSha(),null,Operation.CREATE);
    }
    Witness forSaveWrite(Lease lease,String workspaceId,ProductPromptSaveCommand command){
        Objects.requireNonNull(command);var binding=binding(command.sourceId());
        return new Witness(lease,workspaceId,binding,permission(binding),command.contentSha(),null,Operation.SAVE);
    }
    Witness forTrace(Lease lease,String workspaceId,ProductPromptCreateRequest original){
        Objects.requireNonNull(original);var binding=binding(sourceIdFor(original.evidence()));
        return new Witness(lease,workspaceId,binding,permission(binding),original.header().contentSha(),original,original.receipt().operation());
    }
    final class Witness {
        private final Lease lease;
        private final Workspace workspace;
        private final Binding binding;
        private final Permission permission;
        private final String contentSha;
        private final ProductPromptCreateRequest original;
        private final Operation operation;
        private final Evidence evidence;
        private final Instant bodyLimit,auditLimit;
        private Witness(Lease lease,String workspaceId,Binding binding,Permission permission,String contentSha,ProductPromptCreateRequest original,Operation operation){
            this.lease=Objects.requireNonNull(lease);this.workspace=lease.workspace(workspaceId);this.binding=binding;
            this.permission=permission;this.contentSha=contentSha;this.original=original;this.operation=operation;
            if(operation!=Operation.CREATE&&operation!=Operation.SAVE)throw unavailable();
            var workspaceEvidence=lease.workspaceEvidence(workspaceId);var pin=workspaceEvidence.binding();
            this.evidence=new Evidence(workspace.scopeVersion(),workspace.registrySha(),pin.approvalRef(),pin.approvalSha(),
                    workspace.policyRef(),workspace.policySha(),binding.permissionRef(),binding.permissionSha(),permission.generationSourceRef());
            this.bodyLimit=min(permission.bodyUntil(),permission.validUntil(),workspace.validUntil(),workspaceEvidence.policy().validUntil(),workspaceEvidence.approval().validUntil());
            this.auditLimit=min(permission.auditUntil(),permission.validUntil(),workspace.validUntil(),workspaceEvidence.policy().validUntil(),workspaceEvidence.approval().validUntil());
            requireCurrent();
        }
        void requireCurrent(){
            bound();lease.requireCurrent();
            if(!binding.equals(binding(binding.sourceId()))||!permission.equals(permission(binding)))throw unavailable();
            if(!issuer.equals(permission.issuer())||!lease.subject().equals(permission.subject())||!operation.name().equals(permission.operation())
                    ||!workspace.id().equals(permission.workspaceId())||!workspace.organization().equals(permission.organization())
                    ||workspace.scopeVersion()!=permission.scopeVersion()||!workspace.policyRef().equals(permission.policyRef())
                    ||!workspace.policySha().equals(permission.policySha())||!contentSha.equals(permission.contentSha256()))throw unavailable();
            Instant now=clock.instant();
            if(!"ACTIVE".equals(permission.status())||now.isBefore(permission.validFrom())||!now.isBefore(permission.validUntil())
                    ||!now.isBefore(auditLimit)||original==null&&!now.isBefore(bodyLimit))throw unavailable();
            if(original!=null){
                if(original.receipt().operation()!=operation||original.pending().operation()!=operation
                        ||!original.evidence().equals(evidence)||!original.receipt().workspaceId().equals(workspace.id())
                        ||!original.receipt().issuer().equals(issuer)||!original.receipt().subject().equals(lease.subject())
                        ||original.header().submittedAt().isBefore(permission.validFrom())||!original.header().submittedAt().isBefore(permission.validUntil())
                        ||!original.header().submittedAt().isBefore(permission.bodyUntil())
                        ||original.header().bodyUntil().isAfter(bodyLimit)||original.header().auditUntil().isAfter(auditLimit)
                        ||!now.isBefore(original.header().auditUntil())||!now.isBefore(original.pending().controlUntil()))throw unavailable();
            }
        }
        Evidence evidence(){bound();return evidence;}
        Instant bodyLimit(){bound();return bodyLimit;}
        Instant auditLimit(){bound();return auditLimit;}
        String sourceId(){bound();return binding.sourceId();}
        private void bound(){if(!lease.action().name().equals(operation.name())||!workspace.equals(lease.workspace(workspace.id())))throw unavailable();}
    }
    private Binding binding(String sourceId){
        uuid(sourceId);return directory().stream().filter(value->value.sourceId().equals(sourceId)).findFirst().orElseThrow(ProductPromptRecords::unavailable);
    }
    private List<Binding> directory(){
        try {
            JsonNode tree=tree(directoryRef,directorySha);fields(tree,Set.of("format","bindings"));
            if(!tree.get("format").isTextual()||!tree.get("bindings").isArray()||tree.get("bindings").isEmpty()||tree.get("bindings").size()>128)throw invalid();
            for(var row:tree.get("bindings")){fields(row,Set.of("sourceId","permissionRef","permissionSha"));for(var value:row)if(!value.isTextual())throw invalid();}
            var directory=mapper.treeToValue(tree,Directory.class);if(!"PROMPT_INPUT_PERMISSION_BINDINGS/v1".equals(directory.format()))throw invalid();
            var sources=new HashSet<String>();var references=new HashSet<List<String>>();
            for(var binding:directory.bindings()){
                uuid(binding.sourceId());reference(binding.permissionRef());sha(binding.permissionSha());
                if(!sources.add(binding.sourceId())||!references.add(List.of(binding.permissionRef(),binding.permissionSha())))throw invalid();
            }
            return List.copyOf(directory.bindings());
        }catch(Exception malformed){throw unavailable();}
    }
    private Permission permission(Binding binding){
        try {
            JsonNode tree=tree(binding.permissionRef(),binding.permissionSha());fields(tree,PERMISSION_FIELDS);
            for(String name:PERMISSION_FIELDS)if(!name.equals("scopeVersion")&&!tree.get(name).isTextual())throw invalid();
            if(!tree.get("scopeVersion").isIntegralNumber()||!tree.get("scopeVersion").canConvertToLong()||tree.get("scopeVersion").longValue()<1)throw invalid();
            var p=mapper.treeToValue(tree,Permission.class);
            if(!"PROMPT_INPUT_SAVE_PERMISSION/v1".equals(p.format())||!"PROJECT_AUTHORED_FULL_DRAFT".equals(p.contentCoverage())
                    ||!"MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY".equals(p.traceCoverage()))throw invalid();
            for(String value:List.of(p.permissionId(),p.source(),p.decisionVersion(),p.responsibleParty(),p.issuer(),p.subject(),p.generationSourceRef()))text(value);
            uuid(p.workspaceId());uuid(p.organization());reference(p.policyRef());sha(p.policySha());sha(p.contentSha256());
            if(p.generationSourceRef().length()>500||!StandardCharsets.UTF_8.newEncoder().canEncode(p.generationSourceRef()))throw invalid();
            String date=tree.get("decisionDate").textValue();if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!p.decisionDate().toString().equals(date))throw invalid();
            for(String field:List.of("validFrom","validUntil","bodyUntil","auditUntil")){
                String raw=tree.get(field).textValue();Instant at=Instant.parse(raw);time(at);
                if(!at.toString().equals(raw))throw invalid();
            }
            if(!p.validUntil().isAfter(p.validFrom())||!p.bodyUntil().isAfter(p.validFrom())||p.auditUntil().isBefore(p.bodyUntil()))throw invalid();
            return p;
        }catch(Exception malformed){throw unavailable();}
    }
    private JsonNode tree(String ref,String sha)throws Exception{
        byte[] bytes=files.verified(ref,sha);if(bytes.length==0||bytes.length>1024*1024)throw invalid();
        String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        JsonNode tree=mapper.readTree(raw);unicode(tree);return tree;
    }
    private static void unicode(JsonNode tree){if(tree==null)throw invalid();if(tree.isTextual()&&!StandardCharsets.UTF_8.newEncoder().canEncode(tree.textValue()))throw invalid();if(tree.isContainerNode())for(var child:tree)unicode(child);}
    private static void fields(JsonNode tree,Set<String> expected){if(tree==null||!tree.isObject())throw invalid();var names=new HashSet<String>();tree.fieldNames().forEachRemaining(names::add);if(!names.equals(expected))throw invalid();}
    private static void reference(String ref){if(ref==null||!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||ref.contains(".."))throw invalid();}
    private static Instant min(Instant... values){return Arrays.stream(values).min(Instant::compareTo).orElseThrow();}
}
