package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Exact installation decision, separate from workspace content rights and maintenance identity. */
final class ProductPromptRegisterApproval {
    record Pin(String workspaceId,String approvalRef,String approvalSha) {
        Pin { uuid(workspaceId);reference(approvalRef);sha(approvalSha); }
    }
    private record Decision(String decisionId,String source,String decisionVersion,String responsibleParty,
            LocalDate decisionDate,Instant validFrom,Instant validUntil,Workspace target,
            ProductPromptRegisterRecords.Evidence evidence) { }
    private record Admission(Decision decision,ProductPromptWorkspaceEvidence.Witness workspace,Instant expiresAt) { }
    private static final Set<String> TEXT_FIELDS=Set.of("format","decisionId","source","decisionVersion",
            "responsibleParty","decisionDate","status","validFrom","validUntil","operation","operatorSubject",
            "operatorOrganization","certificateSha","primaryDatabaseIdentitySha","authorityDatabaseIdentitySha",
            "workspaceId","organization","policyRef","policySha","registrySha","workspaceValidUntil","receiptUntil",
            "traceCoverage");
    private final ProductSourceEvidenceFiles files;
    private final ProductPromptBindingRegistry registry;
    private final Map<String,Pin> pins;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    ProductPromptRegisterApproval(ProductSourceEvidenceFiles files,ProductPromptBindingRegistry registry,
            List<Pin> pins,Clock clock) {
        this.files=Objects.requireNonNull(files);this.registry=Objects.requireNonNull(registry);
        this.clock=Objects.requireNonNull(clock);
        if(pins==null||pins.isEmpty()||pins.size()>128)throw invalid();
        var selected=new TreeMap<String,Pin>();var references=new HashSet<String>();var digests=new HashSet<String>();
        for(Pin pin:List.copyOf(pins)) {
            if(selected.put(pin.workspaceId(),pin)!=null||!references.add(pin.approvalRef())||!digests.add(pin.approvalSha()))
                throw invalid();
        }
        this.pins=Map.copyOf(selected);
    }

    Witness admit(String workspaceId) {
        uuid(workspaceId);Pin pin=pins.get(workspaceId);
        if(pin==null)throw new ProductPromptAccessDenied();
        return new Witness(pin,read(pin));
    }

    final class Witness {
        private final Pin pin;
        private final Admission admitted;
        private Witness(Pin pin,Admission admitted){this.pin=pin;this.admitted=admitted;}
        Workspace target(){return admitted.decision().target();}
        ProductPromptRegisterRecords.Evidence evidence(){return admitted.decision().evidence();}
        Instant approvedReceiptUntil(){return evidence().approvedReceiptUntil();}
        // Business limits only: certificate validity and a call's engineering budget belong to the lease.
        Instant expiresAt(){return admitted.expiresAt();}
        void requireCurrent(){if(!admitted.equals(read(pin)))throw unavailable();}
    }

    private Admission read(Pin pin) {
        try {
            registry.requireCurrent();
            JsonNode value=mapper.readTree(files.verified(pin.approvalRef(),pin.approvalSha()));
            exactFields(value);
            if(!"PROMPT_WORKSPACE_REGISTER_APPROVAL/v1".equals(string(value,"format",80))
                    ||!"PROMPT_WORKSPACE_REGISTER".equals(string(value,"operation",80))
                    ||!"MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY".equals(string(value,"traceCoverage",80))
                    ||!"ACTIVE".equals(string(value,"status",16)))throw invalid();
            String workspaceId=string(value,"workspaceId",36),organization=string(value,"organization",36);
            uuid(workspaceId);uuid(organization);
            if(!pin.workspaceId().equals(workspaceId))throw invalid();
            String operator=string(value,"operatorSubject",200),operatorOrganization=string(value,"operatorOrganization",36);
            uuid(operatorOrganization);if(!organization.equals(operatorOrganization))throw invalid();
            Instant from=instant(value,"validFrom"),until=instant(value,"validUntil"),now=clock.instant();
            Instant workspaceUntil=instant(value,"workspaceValidUntil"),approvedReceiptUntil=instant(value,"receiptUntil");
            if(!from.isBefore(until)||now.isBefore(from)||!now.isBefore(until)
                    ||!now.isBefore(workspaceUntil)||!now.isBefore(approvedReceiptUntil))throw invalid();
            String policyRef=string(value,"policyRef",200);reference(policyRef);
            String policySha=digest(value,"policySha"),registrySha=digest(value,"registrySha");
            Workspace target=new Workspace(workspaceId,organization,positive(value,"scopeVersion"),"ACTIVE",
                    policyRef,policySha,registrySha,workspaceUntil);
            String primary=digest(value,"primaryDatabaseIdentitySha"),authority=digest(value,"authorityDatabaseIdentitySha");
            if(primary.equals(authority))throw invalid();
            var evidence=new ProductPromptRegisterRecords.Evidence(operator,operatorOrganization,positive(value,"maintainerVersion"),
                    digest(value,"certificateSha"),primary,authority,pin.approvalRef(),pin.approvalSha(),approvedReceiptUntil);
            String rawDate=string(value,"decisionDate",10);
            if(!rawDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!LocalDate.parse(rawDate).toString().equals(rawDate))throw invalid();
            var decision=new Decision(string(value,"decisionId",200),string(value,"source",2000),
                    string(value,"decisionVersion",200),string(value,"responsibleParty",200),LocalDate.parse(rawDate),from,until,target,evidence);
            var workspace=registry.evidence().current(target,now);
            Instant expires=min(until,workspaceUntil,approvedReceiptUntil,workspace.approval().validUntil(),workspace.policy().validUntil());
            registry.requireCurrent();
            if(!clock.instant().isBefore(expires))throw invalid();
            return new Admission(decision,workspace,expires);
        } catch(RuntimeException failure) {
            throw new IllegalStateException("Prompt registration approval unavailable");
        } catch(java.io.IOException malformed) {
            throw new IllegalStateException("Prompt registration approval unavailable");
        }
    }

    private static void exactFields(JsonNode value) {
        if(value==null||!value.isObject())throw invalid();
        var expected=new HashSet<>(TEXT_FIELDS);expected.add("scopeVersion");expected.add("maintainerVersion");
        var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!expected.equals(actual))throw invalid();
        for(String field:TEXT_FIELDS)if(!value.get(field).isTextual())throw invalid();
    }
    private static String string(JsonNode value,String field,int maximum) {
        JsonNode node=value.get(field);
        if(node==null||!node.isTextual())throw invalid();
        String text=node.textValue();if(text.isBlank()||text.length()>maximum)throw invalid();return text;
    }
    private static String digest(JsonNode value,String field){String result=string(value,field,64);sha(result);return result;}
    private static long positive(JsonNode value,String field) {
        JsonNode node=value.get(field);
        if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<1)throw invalid();
        return node.longValue();
    }
    private static Instant instant(JsonNode value,String field) {
        String raw=string(value,field,24);
        if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z"))throw invalid();
        Instant result=Instant.parse(raw);time(result);if(!result.toString().equals(raw))throw invalid();return result;
    }
    private static void reference(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")||value.contains(".."))throw invalid();
    }
    private static Instant min(Instant...values){Instant result=values[0];for(Instant value:values)if(value.isBefore(result))result=value;return result;}
}
