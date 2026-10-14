package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Owned decision bytes for approval parsing; no actual maintenance or deletion permission. */
final class ProductPromptBodyClearApprovalFixture {
    final Path root;
    final String workspace=UUID.randomUUID().toString(),organization=UUID.randomUUID().toString();
    final String prompt=UUID.randomUUID().toString(),revision=UUID.randomUUID().toString();
    Instant now=Instant.parse("2026-10-14T00:00:00Z");
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}};
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Map<String,Object> clearance=new TreeMap<>();
    ProductPromptBindingRegistry registry;
    ProductPromptBodyClearApproval.Pin pin;

    ProductPromptBodyClearApprovalFixture(Path root)throws Exception {
        this.root=root;clearance.putAll(base());clearance.remove("contentCoverage");
        clearance.put("format","PROMPT_BODY_CLEAR_APPROVAL/v1");clearance.put("decisionId","clear-component");
        clearance.put("validUntil",now.plusSeconds(300));clearance.put("operation","BODY_CLEAR");
        clearance.put("operatorSubject","component-maintainer");clearance.put("operatorOrganization",organization);
        clearance.put("certificateSha","c".repeat(64));clearance.put("maintainerVersion",3);
        clearance.put("primaryDatabaseIdentitySha","a".repeat(64));clearance.put("authorityDatabaseIdentitySha","b".repeat(64));
        clearance.put("traceCoverage","MINIMAL_BODY_CLEAR_SCALARS_AND_ORIGINAL_KEY");
        clearance.put("promptId",prompt);clearance.put("revisionId",revision);clearance.put("revisionNumber",2L);
        clearance.put("recordSha","d".repeat(64));clearance.put("bodyUntil",now);clearance.put("auditUntil",now.plusSeconds(1200));
        clearance.put("receiptUntil",now.plusSeconds(1200));scope(3600,3600,3600);install();
    }
    void scope(long policySeconds,long businessSeconds,long workspaceSeconds)throws Exception {
        var policy=base();policy.put("format","PROMPT_DRAFT_RETENTION_POLICY/v1");policy.put("policyRef","policy.json");
        policy.put("validUntil",now.plusSeconds(policySeconds));policy.put("bodyMaxDays",90);policy.put("auditMaxDays",180);policy.put("backupMaxDays",7);
        String policySha=write("policy.json",policy);
        var business=base();business.put("format","PROMPT_WORKSPACE_APPROVAL/v1");business.put("decisionId","business-component");
        business.put("validUntil",now.plusSeconds(businessSeconds));business.put("policyRef","policy.json");business.put("policySha",policySha);
        business.put("operations",List.of("CREATE","READ","SAVE","COPY","PREVIEW"));String businessSha=write("business.json",business);
        var binding=new ProductPromptWorkspaceEvidence.Binding(workspace,organization,1,"business.json",businessSha,"policy.json",policySha);
        String registrySha=write("registry.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.of(binding)));
        registry=new ProductPromptBindingRegistry(files(),"registry.json",registrySha);
        clearance.put("policyRef","policy.json");clearance.put("policySha",policySha);clearance.put("registrySha",businessSha);
        clearance.put("workspaceValidUntil",now.plusSeconds(workspaceSeconds));
    }
    private Map<String,Object> base(){
        var value=new TreeMap<String,Object>();value.put("source","Owned component decision bytes, not runtime permission");
        value.put("decisionVersion","component-v1");value.put("responsibleParty","owned-component");value.put("decisionDate","2026-10-14");
        value.put("status","ACTIVE");value.put("validFrom",now.minusSeconds(1));value.put("validUntil",now.plusSeconds(3600));
        value.put("workspaceId",workspace);value.put("organization",organization);value.put("scopeVersion",1L);
        value.put("contentCoverage","PROJECT_AUTHORED_HUMAN_DRAFT");return value;
    }
    String write(String reference,Object value)throws Exception {
        String raw=mapper.writeValueAsString(value);Files.writeString(root.resolve(reference),raw);return ProductComparisonJson.hash(raw);
    }
    void install()throws Exception{install(mapper.writeValueAsString(clearance));}
    void install(String raw)throws Exception {
        Files.writeString(root.resolve("body-clear.json"),raw);
        pin=new ProductPromptBodyClearApproval.Pin(workspace,prompt,revision,"body-clear.json",ProductComparisonJson.hash(raw));
    }
    ProductSourceEvidenceFiles files(){return new ProductSourceEvidenceFiles(root,1048576);}
    ProductPromptBodyClearApproval adapter(){return new ProductPromptBodyClearApproval(files(),registry,List.of(pin),clock);}
    ProductPromptBodyClearApproval.Witness admit(){return adapter().admit(workspace,prompt,revision);}
}
