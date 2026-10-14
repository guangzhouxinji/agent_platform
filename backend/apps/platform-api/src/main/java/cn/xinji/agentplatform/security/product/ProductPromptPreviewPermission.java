package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorization.Lease;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Current, pinned TEST permission for one human and exact revision; never a save or runtime licence. */
final class ProductPromptPreviewPermission {
    private record Binding(String sourceId,String permissionRef,String permissionSha) { }
    private record Directory(String format,List<Binding> bindings) { }
    private record Source(String name,String sourceRef,String evidenceId,String source,String decisionVersion,
            String responsibleParty,LocalDate decisionDate,String status,Instant validFrom,Instant validUntil,String purpose) { }
    private record Permission(String format,String permissionId,String source,String decisionVersion,String responsibleParty,
            LocalDate decisionDate,String status,Instant validFrom,Instant validUntil,String issuer,String subject,
            String workspaceId,String organization,long scopeVersion,String registrySha,String policyRef,String policySha,
            String promptId,String revisionId,long revisionNumber,String recordSha,String contentSha,String schemaSha,
            String operation,String mode,String inputMode,String outputCoverage,List<Source> variables) {
        Permission { variables=List.copyOf(variables); }
    }
    private static final Set<String> NUMBERS=Set.of("scopeVersion","revisionNumber");
    private static final Set<String> FIELDS=Set.of("format","permissionId","source","decisionVersion","responsibleParty",
            "decisionDate","status","validFrom","validUntil","issuer","subject","workspaceId","organization","scopeVersion",
            "registrySha","policyRef","policySha","promptId","revisionId","revisionNumber","recordSha","contentSha","schemaSha",
            "operation","mode","inputMode","outputCoverage","variables");
    private static final Set<String> SOURCE_FIELDS=Set.of("name","sourceRef","evidenceId","source","decisionVersion",
            "responsibleParty","decisionDate","status","validFrom","validUntil","purpose");
    private final ProductSourceEvidenceFiles files;
    private final String directoryRef,directorySha,issuer;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    ProductPromptPreviewPermission(ProductSourceEvidenceFiles files,String directoryRef,String directorySha,Clock clock,String issuer){
        this.files=Objects.requireNonNull(files);reference(directoryRef);sha(directorySha);text(issuer);
        this.directoryRef=directoryRef;this.directorySha=directorySha;this.clock=Objects.requireNonNull(clock);this.issuer=issuer;
        directory();
    }
    Witness admit(Lease lease,String workspaceId,Header header,PromptRevision revision,String sourceId){
        Objects.requireNonNull(lease);Objects.requireNonNull(header);Objects.requireNonNull(revision);
        if(lease.action()!=ProductPromptWorkspaceEvidence.Action.PREVIEW)throw unavailable();
        Workspace workspace=lease.workspace(workspaceId);
        var ref=revision.reference();
        if(!ref.promptId().equals(header.promptId())||!ref.revisionId().equals(header.revisionId())
                ||ref.version()!=header.number()||!ref.contentSha256().equals(header.contentSha())
                ||!revision.provenance().modifiedAt().equals(header.submittedAt())||!"PRESENT".equals(header.bodyState()))throw unavailable();
        sha(header.recordSha());
        // This may reject an operation budget with ProductPromptPreviewLimit, not a policy parse error.
        String schema=ProductPromptPreviewValues.schemaSha(revision.variables());
        Binding selected=binding(sourceId);
        return new Witness(lease,workspace,header,revision,selected,permission(selected),schema);
    }
    final class Witness {
        private final Lease lease;
        private final Workspace workspace;
        private final Header header;
        private final Binding binding;
        private final Permission permission;
        private final String schema;
        private final Map<String,VariableDefinition> definitions;
        private final Map<String,String> evidenceIds;
        private final Instant until;
        private Witness(Lease lease,Workspace workspace,Header header,PromptRevision revision,Binding binding,Permission permission,String schema){
            this.lease=lease;this.workspace=workspace;this.header=header;this.binding=binding;this.permission=permission;this.schema=schema;
            var definitions=new LinkedHashMap<String,VariableDefinition>();
            for(var definition:revision.variables())if(definitions.put(definition.name(),definition)!=null)throw unavailable();
            this.definitions=Map.copyOf(definitions);
            var evidenceIds=new LinkedHashMap<String,String>();Instant limit=permission.validUntil();
            for(Source source:permission.variables()){
                if(evidenceIds.put(source.name(),source.evidenceId())!=null)throw unavailable();
                if(source.validUntil().isBefore(limit))limit=source.validUntil();
            }
            this.evidenceIds=Map.copyOf(evidenceIds);this.until=limit;requireCurrent();
        }
        TemplateRenderer.Policy policy(){
            requireCurrent();return new TemplateRenderer.Policy(permission.permissionId(),binding.permissionSha(),until,definitions);
        }
        Map<String,String> evidenceIds(){requireCurrent();return evidenceIds;}
        String schemaSha(){bound();return schema;}
        String policySha(){bound();return binding.permissionSha();}
        Instant expiresAt(){bound();return until;}
        void requireCurrent(){
            bound();lease.requireCurrent();
            if(!binding.equals(binding(binding.sourceId()))||!permission.equals(permission(binding)))throw unavailable();
            if(!issuer.equals(lease.issuer())||!issuer.equals(permission.issuer())||!lease.subject().equals(permission.subject())
                    ||!workspace.id().equals(permission.workspaceId())||!workspace.organization().equals(permission.organization())
                    ||workspace.scopeVersion()!=permission.scopeVersion()||!workspace.registrySha().equals(permission.registrySha())
                    ||!workspace.policyRef().equals(permission.policyRef())||!workspace.policySha().equals(permission.policySha())
                    ||!header.promptId().equals(permission.promptId())||!header.revisionId().equals(permission.revisionId())
                    ||header.number()!=permission.revisionNumber()||!header.recordSha().equals(permission.recordSha())
                    ||!header.contentSha().equals(permission.contentSha())||!schema.equals(permission.schemaSha())
                    ||!definitions.keySet().equals(evidenceIds.keySet()))throw unavailable();
            Instant now=clock.instant();active(permission.status(),permission.validFrom(),permission.validUntil(),now);
            for(Source source:permission.variables()){
                VariableDefinition definition=definitions.get(source.name());
                if(definition==null||!definition.sourceRef().equals(source.sourceRef()))throw unavailable();
                active(source.status(),source.validFrom(),source.validUntil(),now);
            }
            lease.requireCurrent();
            now=clock.instant();active(permission.status(),permission.validFrom(),permission.validUntil(),now);
            for(Source source:permission.variables())active(source.status(),source.validFrom(),source.validUntil(),now);
        }
        private void bound(){
            if(lease.action()!=ProductPromptWorkspaceEvidence.Action.PREVIEW||!workspace.equals(lease.workspace(workspace.id())))throw unavailable();
        }
    }
    private Binding binding(String sourceId){
        uuid(sourceId);return directory().stream().filter(row->row.sourceId().equals(sourceId)).findFirst().orElseThrow(ProductPromptRecords::unavailable);
    }
    private List<Binding> directory(){
        try{
            JsonNode tree=tree(directoryRef,directorySha);fields(tree,Set.of("format","bindings"));
            if(!tree.get("format").isTextual()||!tree.get("bindings").isArray()||tree.get("bindings").isEmpty()
                    ||tree.get("bindings").size()>128)throw invalid();
            for(var row:tree.get("bindings")){fields(row,Set.of("sourceId","permissionRef","permissionSha"));for(var value:row)if(!value.isTextual())throw invalid();}
            var value=mapper.treeToValue(tree,Directory.class);
            if(!"PROMPT_VARIABLE_TEST_BINDINGS/v1".equals(value.format()))throw invalid();
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
                }else if(!field.equals("variables")&&!value.isTextual())throw invalid();
            }
            if(!tree.get("variables").isArray()||tree.get("variables").size()>64)throw invalid();
            for(var row:tree.get("variables")){
                fields(row,SOURCE_FIELDS);for(var value:row)if(!value.isTextual())throw invalid();
                dates(row);
            }
            dates(tree);var p=mapper.treeToValue(tree,Permission.class);
            if(!"PROMPT_VARIABLE_TEST_PERMISSION/v1".equals(p.format())||!"PREVIEW".equals(p.operation())
                    ||!"TEST".equals(p.mode())||!"MANUAL_TEST_INPUT".equals(p.inputMode())
                    ||!"REQUEST_MEMORY_ONLY".equals(p.outputCoverage()))throw invalid();
            logical(p.permissionId());provenance(p.source(),p.decisionVersion(),p.responsibleParty());text(p.issuer());text(p.subject());
            uuid(p.workspaceId());uuid(p.organization());uuid(p.promptId());uuid(p.revisionId());reference(p.policyRef());
            for(String value:List.of(p.registrySha(),p.policySha(),p.recordSha(),p.contentSha(),p.schemaSha()))sha(value);
            if(!p.validUntil().isAfter(p.validFrom()))throw invalid();
            var names=new HashSet<String>();
            for(Source source:p.variables()){
                if(!source.name().matches("[A-Za-z][A-Za-z0-9_]{0,127}")||!names.add(source.name())
                        ||!"TEST_SAMPLE".equals(source.purpose())||!source.validUntil().isAfter(source.validFrom()))throw invalid();
                text(source.sourceRef());logical(source.evidenceId());provenance(source.source(),source.decisionVersion(),source.responsibleParty());
            }
            return p;
        }catch(Exception malformed){throw unavailable();}
    }
    private JsonNode tree(String ref,String sha)throws Exception{
        byte[] bytes=files.verified(ref,sha);if(bytes.length==0||bytes.length>1024*1024)throw invalid();
        String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        JsonNode tree=mapper.readTree(raw);unicode(tree);return tree;
    }
    private static void dates(JsonNode tree){
        String date=tree.get("decisionDate").textValue();
        if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!LocalDate.parse(date).toString().equals(date))throw invalid();
        for(String name:List.of("validFrom","validUntil")){
            String raw=tree.get(name).textValue();Instant at=Instant.parse(raw);time(at);
            if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z")||!at.toString().equals(raw))throw invalid();
        }
    }
    private static void active(String status,Instant from,Instant until,Instant now){
        if(!"ACTIVE".equals(status)||now.isBefore(from)||!now.isBefore(until))throw unavailable();
    }
    private static void provenance(String source,String version,String owner){text(source);text(version);text(owner);}
    private static void logical(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw invalid();}
    private static void reference(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||value.contains(".."))throw invalid();}
    private static void fields(JsonNode tree,Set<String> expected){
        if(tree==null||!tree.isObject())throw invalid();var actual=new HashSet<String>();tree.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected))throw invalid();
    }
    private static void unicode(JsonNode tree){
        if(tree==null)throw invalid();if(tree.isTextual()&&!StandardCharsets.UTF_8.newEncoder().canEncode(tree.textValue()))throw invalid();
        if(tree.isContainerNode())for(var child:tree)unicode(child);
    }
}
