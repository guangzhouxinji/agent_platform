package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptRegisterApprovalTest {
    @TempDir Path root;

    @Test void exactInstallationDecisionRemainsDistinctFromWorkspaceRightsAndEngineeringDeadline()throws Exception {
        var f=new Fixture(root);var witness=f.adapter().admit(f.workspace);
        assertThat(witness.target().id()).isEqualTo(f.workspace);
        assertThat(witness.target().validUntil()).isEqualTo(f.now.plusSeconds(100*86400L));
        assertThat(witness.expiresAt()).isEqualTo(f.now.plusSeconds(2*86400L));
        assertThat(witness.approvedReceiptUntil()).isEqualTo(f.now.plusSeconds(90*86400L));
        assertThat(witness.evidence().operatorSubject()).isEqualTo("component-maintainer");
        assertThat(witness.evidence().maintainerVersion()).isEqualTo(3);
        assertThat(witness.evidence().approvalSha()).isEqualTo(f.pin.approvalSha());
        f.now=f.now.plusSeconds(121);witness.requireCurrent();
    }

    @ParameterizedTest @ValueSource(strings={"unknown","missing","null","number","float-version","string-version",
            "zero-version","wrong-workspace","wrong-organization","wrong-operator-organization","wrong-operation",
            "wrong-coverage","revoked","future","expired","expired-receipt","expired-workspace","same-databases",
            "invalid-sha","wrong-registry","wrong-policy","oversized-operator","oversized-policy","bad-date","submillisecond","offset-time"})
    void repinnedButUnsupportedDecisionsAreStillRejected(String fault)throws Exception {
        var f=new Fixture(root);
        switch(fault) {
            case "unknown"->f.registration.put("allowAll",true);
            case "missing"->f.registration.remove("source");
            case "null"->f.registration.put("responsibleParty",null);
            case "number"->f.registration.put("operatorSubject",123);
            case "float-version"->f.registration.put("scopeVersion",1.1);
            case "string-version"->f.registration.put("maintainerVersion","3");
            case "zero-version"->f.registration.put("maintainerVersion",0);
            case "wrong-workspace"->f.registration.put("workspaceId",UUID.randomUUID().toString());
            case "wrong-organization"->f.registration.put("organization",UUID.randomUUID().toString());
            case "wrong-operator-organization"->f.registration.put("operatorOrganization",UUID.randomUUID().toString());
            case "wrong-operation"->f.registration.put("operation","GRANTS");
            case "wrong-coverage"->f.registration.put("traceCoverage","ALL_MAINTENANCE");
            case "revoked"->f.registration.put("status","REVOKED");
            case "future"->f.registration.put("validFrom",f.now.plusSeconds(1));
            case "expired"->f.registration.put("validUntil",f.now);
            case "expired-receipt"->f.registration.put("receiptUntil",f.now);
            case "expired-workspace"->f.registration.put("workspaceValidUntil",f.now);
            case "same-databases"->f.registration.put("authorityDatabaseIdentitySha","a".repeat(64));
            case "invalid-sha"->f.registration.put("certificateSha","c".repeat(63));
            case "wrong-registry"->f.registration.put("registrySha","f".repeat(64));
            case "wrong-policy"->f.registration.put("policySha","f".repeat(64));
            case "oversized-operator"->f.registration.put("operatorSubject","x".repeat(201));
            case "oversized-policy"->f.registration.put("policyRef","x".repeat(201));
            case "bad-date"->f.registration.put("decisionDate","2026-02-30");
            case "submillisecond"->f.registration.put("validUntil","2026-10-16T00:00:00.000001Z");
            case "offset-time"->f.registration.put("validUntil","2026-10-16T08:00:00+08:00");
            default->throw new AssertionError(fault);
        }
        f.install(f.mapper.writeValueAsString(f.registration));
        assertThatThrownBy(()->f.adapter().admit(f.workspace)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Prompt registration approval unavailable");
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","array"})
    void nonStrictJsonCannotBecomeAuthorityEvenWithMatchingPin(String fault)throws Exception {
        var f=new Fixture(root);String raw=f.mapper.writeValueAsString(f.registration);
        f.install(switch(fault){case "duplicate"->"{\"operation\":\"GRANTS\","+raw.substring(1);
            case "trailing"->raw+" {}";default->"["+raw+"]";});
        assertThatThrownBy(()->f.adapter().admit(f.workspace)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"registration","business","policy","registry","time"})
    void issuedWitnessReauthenticatesOriginalBytesAndFixedBusinessDeadline(String changed)throws Exception {
        var f=new Fixture(root);var witness=f.adapter().admit(f.workspace);
        if(changed.equals("time"))f.now=witness.expiresAt();
        else Files.writeString(root.resolve(switch(changed){case "registration"->"register.json";case "business"->"business.json";
            case "policy"->"policy.json";default->"registry.json";}),"{}");
        assertThatThrownBy(witness::requireCurrent).isInstanceOf(IllegalStateException.class)
                .hasMessage("Prompt registration approval unavailable");
    }

    @Test void unboundWorkspaceAndDuplicateOrAmbiguousPinsAreRejected()throws Exception {
        var f=new Fixture(root);var files=new ProductSourceEvidenceFiles(root,1048576);
        assertThatThrownBy(()->f.adapter().admit(UUID.randomUUID().toString())).isInstanceOf(ProductPromptAccessDenied.class);
        assertThatThrownBy(()->new ProductPromptRegisterApproval(files,f.registry,List.of(f.pin,f.pin),f.clock))
                .isInstanceOf(IllegalArgumentException.class);
        var other=new ProductPromptRegisterApproval.Pin(UUID.randomUUID().toString(),"other.json",f.pin.approvalSha());
        assertThatThrownBy(()->new ProductPromptRegisterApproval(files,f.registry,List.of(f.pin,other),f.clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProductPromptRegisterApproval.Pin(f.workspace,"../outside.json",f.pin.approvalSha()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class Fixture {
        final Path root;
        final String workspace=UUID.randomUUID().toString(),organization=UUID.randomUUID().toString();
        Instant now=Instant.parse("2026-10-14T00:00:00Z");
        final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now;}};
        final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        final Map<String,Object> registration=new TreeMap<>();
        final ProductPromptBindingRegistry registry;
        ProductPromptRegisterApproval.Pin pin;
        Fixture(Path root)throws Exception {
            this.root=root;
            var policy=base();policy.put("format","PROMPT_DRAFT_RETENTION_POLICY/v1");policy.put("policyRef","policy.json");
            policy.put("bodyMaxDays",90);policy.put("auditMaxDays",180);policy.put("backupMaxDays",7);
            String policySha=write("policy.json",policy);
            var business=base();business.put("format","PROMPT_WORKSPACE_APPROVAL/v1");business.put("decisionId","business-component");
            business.put("policyRef","policy.json");business.put("policySha",policySha);business.put("operations",List.of("CREATE","READ","SAVE","COPY","PREVIEW"));
            String businessSha=write("business.json",business);
            var binding=new ProductPromptWorkspaceEvidence.Binding(workspace,organization,1,"business.json",businessSha,"policy.json",policySha);
            String registrySha=write("registry.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.of(binding)));
            registry=new ProductPromptBindingRegistry(new ProductSourceEvidenceFiles(root,1048576),"registry.json",registrySha);
            registration.putAll(base());registration.remove("contentCoverage");registration.put("format","PROMPT_WORKSPACE_REGISTER_APPROVAL/v1");
            registration.put("decisionId","register-component");registration.put("validUntil",now.plusSeconds(2*86400L));
            registration.put("operation","PROMPT_WORKSPACE_REGISTER");registration.put("operatorSubject","component-maintainer");
            registration.put("operatorOrganization",organization);registration.put("certificateSha","c".repeat(64));
            registration.put("maintainerVersion",3);registration.put("primaryDatabaseIdentitySha","a".repeat(64));
            registration.put("authorityDatabaseIdentitySha","b".repeat(64));registration.put("policyRef","policy.json");
            registration.put("policySha",policySha);registration.put("registrySha",businessSha);
            registration.put("workspaceValidUntil",now.plusSeconds(100*86400L));registration.put("receiptUntil",now.plusSeconds(90*86400L));
            registration.put("traceCoverage","MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY");install(mapper.writeValueAsString(registration));
        }
        Map<String,Object> base(){
            var value=new TreeMap<String,Object>();value.put("source","Owned component decision bytes, not runtime permission");
            value.put("decisionVersion","component-v1");value.put("responsibleParty","owned-component");value.put("decisionDate","2026-10-14");
            value.put("status","ACTIVE");value.put("validFrom",now.minusSeconds(1));value.put("validUntil",now.plusSeconds(120*86400L));
            value.put("workspaceId",workspace);value.put("organization",organization);value.put("scopeVersion",1);
            value.put("contentCoverage","PROJECT_AUTHORED_HUMAN_DRAFT");return value;
        }
        String write(String reference,Object value)throws Exception {
            String raw=mapper.writeValueAsString(value);Files.writeString(root.resolve(reference),raw);return ProductComparisonJson.hash(raw);
        }
        void install(String raw)throws Exception {
            Files.writeString(root.resolve("register.json"),raw);pin=new ProductPromptRegisterApproval.Pin(workspace,"register.json",ProductComparisonJson.hash(raw));
        }
        ProductPromptRegisterApproval adapter(){return new ProductPromptRegisterApproval(new ProductSourceEvidenceFiles(root,1048576),registry,List.of(pin),clock);}
    }
}
