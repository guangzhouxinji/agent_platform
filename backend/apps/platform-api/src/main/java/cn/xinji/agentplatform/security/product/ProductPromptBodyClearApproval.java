package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact body-clear decision; maintenance identity and current database rows are authenticated separately. */
final class ProductPromptBodyClearApproval {
    record Pin(String workspaceId,String promptId,String revisionId,String approvalRef,String approvalSha) {
        Pin { uuid(workspaceId);uuid(promptId);uuid(revisionId);reference(approvalRef);sha(approvalSha); }
    }
    record Target(String promptId,String revisionId,long revisionNumber,String recordSha,Instant bodyUntil,Instant auditUntil) {
        Target {
            uuid(promptId);uuid(revisionId);sha(recordSha);time(bodyUntil);time(auditUntil);
            if(revisionNumber<1||!bodyUntil.isBefore(auditUntil))throw invalid();
        }
    }
    record Evidence(String operatorSubject,String operatorOrganization,String certificateSha,long maintainerVersion,
                    String primaryDatabaseIdentitySha,String authorityDatabaseIdentitySha,
                    String approvalRef,String approvalSha,Instant approvedReceiptUntil) {
        Evidence {
            text(operatorSubject);uuid(operatorOrganization);sha(certificateSha);sha(primaryDatabaseIdentitySha);
            sha(authorityDatabaseIdentitySha);reference(approvalRef);sha(approvalSha);time(approvedReceiptUntil);
            if(operatorSubject.length()>200||maintainerVersion<1||primaryDatabaseIdentitySha.equals(authorityDatabaseIdentitySha))throw invalid();
        }
    }
    private record Coordinate(String workspaceId,String promptId,String revisionId) { }
    private record Decision(String decisionId,String source,String decisionVersion,String responsibleParty,
            LocalDate decisionDate,Instant validFrom,Instant validUntil,Workspace workspace,Target target,Evidence evidence) { }
    private record Admission(Decision decision,ProductPromptWorkspaceEvidence.Witness workspace,Instant expiresAt) { }
    private static final Set<String> TEXT_FIELDS=Set.of("format","decisionId","source","decisionVersion",
            "responsibleParty","decisionDate","status","validFrom","validUntil","operation","operatorSubject",
            "operatorOrganization","certificateSha","primaryDatabaseIdentitySha","authorityDatabaseIdentitySha",
            "workspaceId","organization","policyRef","policySha","registrySha","workspaceValidUntil","receiptUntil",
            "traceCoverage","promptId","revisionId","recordSha","bodyUntil","auditUntil");
    private final ProductSourceEvidenceFiles files;
    private final ProductPromptBindingRegistry registry;
    private final Map<Coordinate,Pin> pins;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    ProductPromptBodyClearApproval(ProductSourceEvidenceFiles files,ProductPromptBindingRegistry registry,List<Pin> pins,Clock clock) {
        this.files=Objects.requireNonNull(files);this.registry=Objects.requireNonNull(registry);this.clock=Objects.requireNonNull(clock);
        if(pins==null||pins.isEmpty()||pins.size()>128)throw invalid();
        var selected=new HashMap<Coordinate,Pin>();var references=new HashSet<String>();var digests=new HashSet<String>();
        for(Pin pin:List.copyOf(pins))
            if(selected.put(coordinate(pin),pin)!=null||!references.add(pin.approvalRef())||!digests.add(pin.approvalSha()))throw invalid();
        this.pins=Map.copyOf(selected);
    }
    Witness admit(String workspaceId,String promptId,String revisionId) {
        uuid(workspaceId);uuid(promptId);uuid(revisionId);Pin pin=pins.get(new Coordinate(workspaceId,promptId,revisionId));
        if(pin==null)throw new ProductPromptAccessDenied();return new Witness(pin,read(pin));
    }
    final class Witness {
        private final Pin pin;
        private final Admission admitted;
        private Witness(Pin pin,Admission admitted){this.pin=pin;this.admitted=admitted;}
        Workspace workspace(){return admitted.decision().workspace();}
        Target target(){return admitted.decision().target();}
        Evidence evidence(){return admitted.decision().evidence();}
        Instant expiresAt(){return admitted.expiresAt();}
        void requireCurrent(){if(!admitted.equals(read(pin)))throw unavailable();}
    }
    private Admission read(Pin pin) {
        try {
            registry.requireCurrent();JsonNode value=mapper.readTree(files.verified(pin.approvalRef(),pin.approvalSha()));
            exactFields(value);
            if(!"PROMPT_BODY_CLEAR_APPROVAL/v1".equals(string(value,"format",80))
                    ||!"BODY_CLEAR".equals(string(value,"operation",80))
                    ||!"MINIMAL_BODY_CLEAR_SCALARS_AND_ORIGINAL_KEY".equals(string(value,"traceCoverage",80))
                    ||!"ACTIVE".equals(string(value,"status",16)))throw invalid();
            String workspaceId=string(value,"workspaceId",36),organization=string(value,"organization",36);
            uuid(workspaceId);uuid(organization);if(!pin.workspaceId().equals(workspaceId))throw invalid();
            Target target=new Target(string(value,"promptId",36),string(value,"revisionId",36),positive(value,"revisionNumber"),
                    digest(value,"recordSha"),instant(value,"bodyUntil"),instant(value,"auditUntil"));
            if(!pin.promptId().equals(target.promptId())||!pin.revisionId().equals(target.revisionId()))throw invalid();
            String operator=string(value,"operatorSubject",200),operatorOrganization=string(value,"operatorOrganization",36);
            uuid(operatorOrganization);if(!organization.equals(operatorOrganization))throw invalid();
            Instant from=instant(value,"validFrom"),until=instant(value,"validUntil"),now=clock.instant();
            Instant workspaceUntil=instant(value,"workspaceValidUntil"),approvedReceiptUntil=instant(value,"receiptUntil");
            if(!from.isBefore(until)||now.isBefore(from)||!now.isBefore(until)||now.isBefore(target.bodyUntil())
                    ||!now.isBefore(target.auditUntil())||workspaceUntil.isBefore(target.auditUntil())
                    ||approvedReceiptUntil.isBefore(target.auditUntil()))throw invalid();
            String policyRef=string(value,"policyRef",200);reference(policyRef);
            Workspace workspace=new Workspace(workspaceId,organization,positive(value,"scopeVersion"),"ACTIVE",
                    policyRef,digest(value,"policySha"),digest(value,"registrySha"),workspaceUntil);
            Evidence evidence=new Evidence(operator,operatorOrganization,digest(value,"certificateSha"),positive(value,"maintainerVersion"),
                    digest(value,"primaryDatabaseIdentitySha"),digest(value,"authorityDatabaseIdentitySha"),
                    pin.approvalRef(),pin.approvalSha(),approvedReceiptUntil);
            String rawDate=string(value,"decisionDate",10);
            if(!rawDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!LocalDate.parse(rawDate).toString().equals(rawDate))throw invalid();
            Decision decision=new Decision(string(value,"decisionId",200),string(value,"source",2000),
                    string(value,"decisionVersion",200),string(value,"responsibleParty",200),LocalDate.parse(rawDate),from,until,workspace,target,evidence);
            var current=registry.evidence().current(workspace,now);
            if(current.approval().validUntil().isBefore(target.auditUntil())||current.policy().validUntil().isBefore(target.auditUntil()))throw invalid();
            Instant expires=min(until,workspaceUntil,current.approval().validUntil(),current.policy().validUntil(),target.auditUntil());
            registry.requireCurrent();
            if(clock.instant().isBefore(target.bodyUntil())||!clock.instant().isBefore(expires))throw invalid();
            return new Admission(decision,current,expires);
        }catch(RuntimeException|java.io.IOException failed){throw new IllegalStateException("Prompt body clear approval unavailable");}
    }
    private static void exactFields(JsonNode value) {
        if(value==null||!value.isObject())throw invalid();
        var expected=new HashSet<>(TEXT_FIELDS);expected.addAll(List.of("scopeVersion","maintainerVersion","revisionNumber"));
        var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!expected.equals(actual))throw invalid();for(String field:TEXT_FIELDS)if(!value.get(field).isTextual())throw invalid();
    }
    private static String string(JsonNode value,String field,int maximum) {
        JsonNode node=value.get(field);if(node==null||!node.isTextual())throw invalid();
        String text=node.textValue();if(text.isBlank()||text.length()>maximum)throw invalid();return text;
    }
    private static String digest(JsonNode value,String field){String result=string(value,field,64);sha(result);return result;}
    private static long positive(JsonNode value,String field) {
        JsonNode node=value.get(field);
        if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<1)throw invalid();return node.longValue();
    }
    private static Instant instant(JsonNode value,String field) {
        String raw=string(value,field,24);
        if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z"))throw invalid();
        Instant result=Instant.parse(raw);time(result);if(!result.toString().equals(raw))throw invalid();return result;
    }
    private static void reference(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||value.contains(".."))throw invalid();
    }
    private static Coordinate coordinate(Pin pin){return new Coordinate(pin.workspaceId(),pin.promptId(),pin.revisionId());}
    private static Instant min(Instant...values){Instant result=values[0];for(Instant value:values)if(value.isBefore(result))result=value;return result;}
}
