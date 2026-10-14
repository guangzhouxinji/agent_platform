package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptCreateRequest.CopySource;
import cn.xinji.agentplatform.security.product.ProductPromptCreateRequest.Evidence;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorization.Lease;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Pinned permission for one exact source revision and destination; no client ownership attestation. */
final class ProductPromptCopyInputPermission {
    private record Binding(String sourceId,String permissionRef,String permissionSha) { }
    private record Directory(String format,List<Binding> bindings) { }
    private record Permission(String format,String permissionId,String source,String decisionVersion,String responsibleParty,
            LocalDate decisionDate,String status,Instant validFrom,Instant validUntil,String workspaceId,String organization,
            long scopeVersion,String issuer,String subject,String operation,String contentCoverage,String contentSha256,
            String generationSourceRef,Instant bodyUntil,Instant auditUntil,String policyRef,String policySha,String traceCoverage,
            String sourceWorkspaceId,String sourcePromptId,String sourceRevisionId,long sourceRevisionNumber,String sourceRecordSha,
            String sourceContentSha256,Instant sourceBodyUntil,Instant sourceAuditUntil,long sourceScopeVersion,String sourceRegistrySha,
            String sourcePolicyRef,String sourcePolicySha) {
        CopySource copySource(){return new CopySource(sourceWorkspaceId,sourcePromptId,sourceRevisionId,sourceRevisionNumber,sourceRecordSha,sourceBodyUntil,sourceAuditUntil);}
    }
    private static final Set<String> NUMBERS=Set.of("scopeVersion","sourceScopeVersion","sourceRevisionNumber");
    private static final Set<String> FIELDS=Set.of("format","permissionId","source","decisionVersion","responsibleParty","decisionDate",
            "status","validFrom","validUntil","workspaceId","organization","scopeVersion","issuer","subject","operation","contentCoverage",
            "contentSha256","generationSourceRef","bodyUntil","auditUntil","policyRef","policySha","traceCoverage","sourceWorkspaceId",
            "sourcePromptId","sourceRevisionId","sourceRevisionNumber","sourceRecordSha","sourceContentSha256","sourceBodyUntil","sourceAuditUntil",
            "sourceScopeVersion","sourceRegistrySha","sourcePolicyRef","sourcePolicySha");
    private final ProductSourceEvidenceFiles files;
    private final String directoryRef,directorySha,issuer;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    ProductPromptCopyInputPermission(ProductSourceEvidenceFiles files,String directoryRef,String directorySha,Clock clock,String issuer){
        this.files=Objects.requireNonNull(files);reference(directoryRef);sha(directorySha);text(issuer);
        this.directoryRef=directoryRef;this.directorySha=directorySha;this.clock=Objects.requireNonNull(clock);this.issuer=issuer;directory();
    }
    String sourceIdFor(Evidence original){
        Objects.requireNonNull(original);
        var matches=directory().stream().filter(row->row.permissionRef().equals(original.inputPermissionRef())
                &&row.permissionSha().equals(original.inputPermissionSha())).toList();
        if(matches.size()!=1)throw unavailable();var match=matches.getFirst();
        if(!permission(match).generationSourceRef().equals(original.generationSourceRef()))throw unavailable();return match.sourceId();
    }
    Witness forWrite(Lease lease,ProductPromptCopyCommand command,ProductPromptCopySourceSnapshot.Snapshot snapshot){
        Objects.requireNonNull(command);Objects.requireNonNull(snapshot);snapshot.requireCurrent();
        var source=snapshot.source();var revision=snapshot.revision();
        if(!source.sourceRevisionId().equals(command.sourceRevisionId())
                ||!revision.reference().contentSha256().equals(command.sourceContentSha()))throw unavailable();
        var binding=binding(command.sourceId());
        return new Witness(lease,command.targetWorkspaceId(),binding,permission(binding),command.sourceContentSha(),source,snapshot,null);
    }
    Witness forTrace(Lease lease,ProductPromptCreateRequest original){
        Objects.requireNonNull(original);
        if(original.receipt().operation()!=Operation.COPY||original.copySource()==null)throw unavailable();
        var binding=binding(sourceIdFor(original.evidence()));
        return new Witness(lease,original.receipt().workspaceId(),binding,permission(binding),original.header().contentSha(),original.copySource(),null,original);
    }
    final class Witness {
        private final Lease lease;
        private final Workspace target,sourceWorkspace;
        private final Binding binding;
        private final Permission permission;
        private final CopySource source;
        private final String contentSha;
        private final ProductPromptCopySourceSnapshot.Snapshot snapshot;
        private final ProductPromptCreateRequest original;
        private final Evidence evidence;
        private final Instant bodyLimit,auditLimit;
        private Witness(Lease lease,String targetWorkspaceId,Binding binding,Permission permission,String contentSha,CopySource source,
                        ProductPromptCopySourceSnapshot.Snapshot snapshot,ProductPromptCreateRequest original){
            this.lease=Objects.requireNonNull(lease);this.target=lease.workspace(targetWorkspaceId);this.sourceWorkspace=lease.workspace(source.sourceWorkspaceId());
            this.binding=binding;this.permission=permission;this.contentSha=contentSha;this.source=source;this.snapshot=snapshot;this.original=original;
            var targetEvidence=lease.workspaceEvidence(target.id());var pin=targetEvidence.binding();
            var sourceEvidence=lease.workspaceEvidence(sourceWorkspace.id());
            evidence=new Evidence(target.scopeVersion(),target.registrySha(),pin.approvalRef(),pin.approvalSha(),target.policyRef(),target.policySha(),
                    binding.permissionRef(),binding.permissionSha(),permission.generationSourceRef());
            Instant bound=min(permission.validUntil(),target.validUntil(),sourceWorkspace.validUntil(),targetEvidence.approval().validUntil(),
                    targetEvidence.policy().validUntil(),sourceEvidence.approval().validUntil(),sourceEvidence.policy().validUntil());
            bodyLimit=min(bound,permission.bodyUntil(),source.sourceBodyUntil());auditLimit=min(bound,permission.auditUntil(),source.sourceAuditUntil());
            requireCurrent();
        }
        void requireCurrent(){
            bound();lease.requireCurrent();
            if(!binding.equals(binding(binding.sourceId()))||!permission.equals(permission(binding)))throw unavailable();
            if(!issuer.equals(permission.issuer())||!lease.subject().equals(permission.subject())||!"COPY".equals(permission.operation())
                    ||!target.id().equals(permission.workspaceId())||!target.organization().equals(permission.organization())
                    ||!target.organization().equals(sourceWorkspace.organization())||target.scopeVersion()!=permission.scopeVersion()
                    ||!target.policyRef().equals(permission.policyRef())||!target.policySha().equals(permission.policySha())
                    ||!contentSha.equals(permission.contentSha256())||!contentSha.equals(permission.sourceContentSha256())
                    ||!source.equals(permission.copySource())||sourceWorkspace.scopeVersion()!=permission.sourceScopeVersion()
                    ||!sourceWorkspace.registrySha().equals(permission.sourceRegistrySha())||!sourceWorkspace.policyRef().equals(permission.sourcePolicyRef())
                    ||!sourceWorkspace.policySha().equals(permission.sourcePolicySha()))throw unavailable();
            Instant now=clock.instant();
            if(!"ACTIVE".equals(permission.status())||now.isBefore(permission.validFrom())||!now.isBefore(permission.validUntil())
                    ||!now.isBefore(auditLimit))throw unavailable();
            if(original==null){
                if(!now.isBefore(bodyLimit)||snapshot==null)throw unavailable();snapshot.requireCurrent();
            }else{
                if(original.pending().operation()!=Operation.COPY||!original.evidence().equals(evidence)||!source.equals(original.copySource())
                        ||!target.id().equals(original.receipt().workspaceId())||!issuer.equals(original.receipt().issuer())
                        ||!lease.subject().equals(original.receipt().subject())||original.header().submittedAt().isBefore(permission.validFrom())
                        ||!original.header().submittedAt().isBefore(permission.validUntil())||!original.header().submittedAt().isBefore(bodyLimit)
                        ||original.header().bodyUntil().isAfter(bodyLimit)||original.header().auditUntil().isAfter(auditLimit)
                        ||!now.isBefore(original.header().auditUntil())||!now.isBefore(original.pending().controlUntil()))throw unavailable();
            }
            lease.requireCurrent();
            now=clock.instant();
            if(!now.isBefore(permission.validUntil())||!now.isBefore(auditLimit)
                    ||original==null&&!now.isBefore(bodyLimit)||original!=null&&(!now.isBefore(original.header().auditUntil())
                    ||!now.isBefore(original.pending().controlUntil())))throw unavailable();
        }
        Evidence evidence(){bound();return evidence;}
        Instant bodyLimit(){bound();return bodyLimit;}
        Instant auditLimit(){bound();return auditLimit;}
        private void bound(){
            lease.requireCopySource(sourceWorkspace.id());lease.requireCopyTarget(target.id());
            if(!target.equals(lease.workspace(target.id()))
                    ||!sourceWorkspace.equals(lease.workspace(sourceWorkspace.id())))throw unavailable();
        }
    }
    private Binding binding(String sourceId){
        uuid(sourceId);return directory().stream().filter(row->row.sourceId().equals(sourceId)).findFirst().orElseThrow(ProductPromptRecords::unavailable);
    }
    private List<Binding> directory(){
        try{
            JsonNode tree=tree(directoryRef,directorySha);fields(tree,Set.of("format","bindings"));
            if(!tree.get("format").isTextual()||!tree.get("bindings").isArray()||tree.get("bindings").isEmpty()||tree.get("bindings").size()>128)throw invalid();
            for(var row:tree.get("bindings")){fields(row,Set.of("sourceId","permissionRef","permissionSha"));for(var value:row)if(!value.isTextual())throw invalid();}
            var value=mapper.treeToValue(tree,Directory.class);if(!"PROMPT_INPUT_PERMISSION_BINDINGS/v1".equals(value.format()))throw invalid();
            var ids=new HashSet<String>();var references=new HashSet<List<String>>();
            for(var row:value.bindings()){
                uuid(row.sourceId());reference(row.permissionRef());sha(row.permissionSha());
                if(!ids.add(row.sourceId())||!references.add(List.of(row.permissionRef(),row.permissionSha())))throw invalid();
            }
            return List.copyOf(value.bindings());
        }catch(Exception malformed){throw unavailable();}
    }
    private Permission permission(Binding binding){
        try{
            JsonNode tree=tree(binding.permissionRef(),binding.permissionSha());fields(tree,FIELDS);
            for(String field:FIELDS){
                JsonNode value=tree.get(field);
                if(NUMBERS.contains(field)){
                    if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1)throw invalid();
                }else if(!value.isTextual())throw invalid();
            }
            var p=mapper.treeToValue(tree,Permission.class);
            if(!"PROMPT_INPUT_COPY_PERMISSION/v1".equals(p.format())||!"COPY".equals(p.operation())
                    ||!"EXACT_PROMPT_REVISION_COPY".equals(p.contentCoverage())
                    ||!"MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY".equals(p.traceCoverage()))throw invalid();
            for(String value:List.of(p.permissionId(),p.source(),p.decisionVersion(),p.responsibleParty(),p.issuer(),p.subject()))text(value);
            uuid(p.workspaceId());uuid(p.organization());reference(p.policyRef());sha(p.policySha());sha(p.contentSha256());
            p.copySource();sha(p.sourceContentSha256());sha(p.sourceRegistrySha());reference(p.sourcePolicyRef());sha(p.sourcePolicySha());
            if(!p.contentSha256().equals(p.sourceContentSha256())
                    ||!p.generationSourceRef().equals("PROMPT_REVISION:"+p.sourcePromptId()+":"+p.sourceRevisionId()))throw invalid();
            String date=tree.get("decisionDate").textValue();
            if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!p.decisionDate().toString().equals(date))throw invalid();
            for(String field:List.of("validFrom","validUntil","bodyUntil","auditUntil","sourceBodyUntil","sourceAuditUntil")){
                String raw=tree.get(field).textValue();Instant at=Instant.parse(raw);time(at);if(!at.toString().equals(raw))throw invalid();
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
