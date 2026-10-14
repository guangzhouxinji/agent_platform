package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptBodyClearApprovalTest {
    @TempDir Path root;

    @Test void dueBodyAdmitsExactTargetAndFixedAuditCoverageWithoutUsingExpiredBodyAsDeadline()throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);var witness=f.admit();
        assertThat(witness.workspace().id()).isEqualTo(f.workspace);
        assertThat(witness.target()).isEqualTo(new ProductPromptBodyClearApproval.Target(f.prompt,f.revision,2L,
                "d".repeat(64),f.now,f.now.plusSeconds(1200)));
        assertThat(witness.expiresAt()).isEqualTo(f.now.plusSeconds(300));
        assertThat(witness.evidence()).isEqualTo(new ProductPromptBodyClearApproval.Evidence("component-maintainer",f.organization,
                "c".repeat(64),3,"a".repeat(64),"b".repeat(64),f.pin.approvalRef(),f.pin.approvalSha(),f.now.plusSeconds(1200)));
        f.now=f.now.plusSeconds(121);witness.requireCurrent();
        assertThat(witness.target().bodyUntil()).isBefore(f.now);assertThat(witness.expiresAt()).isAfter(f.now);
    }

    @ParameterizedTest @ValueSource(strings={"body-future","audit-due","audit-before-body","receipt-short","workspace-short",
            "scope-short","policy-short","business-short","expired-decision","future-decision","reversed-decision"})
    void temporalAdmissionCannotShortenTraceCoverageOrClearUndueBody(String fault)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);
        switch(fault){
            case "body-future"->f.clearance.put("bodyUntil",f.now.plusMillis(1));
            case "audit-due"->f.clearance.put("auditUntil",f.now);
            case "audit-before-body"->f.clearance.put("auditUntil",f.now.minusMillis(1));
            case "receipt-short"->f.clearance.put("receiptUntil",f.now.plusSeconds(1199));
            case "workspace-short"->f.clearance.put("workspaceValidUntil",f.now.plusSeconds(1199));
            case "scope-short"->f.scope(1199,1199,1199);
            case "policy-short"->f.scope(1199,3600,1199);
            case "business-short"->f.scope(3600,1199,1199);
            case "expired-decision"->f.clearance.put("validUntil",f.now);
            case "future-decision"->f.clearance.put("validFrom",f.now.plusMillis(1));
            case "reversed-decision"->f.clearance.put("validFrom",f.now.plusSeconds(301));
            default->throw new AssertionError(fault);
        }
        f.install();reject(f);
    }

    @ParameterizedTest @ValueSource(strings={"target-audit","decision"})
    void fixedAdmissionClosesAtEarliestDeadlineWhileReceiptCoverageMayBeLonger(String deadline)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);
        if(deadline.equals("target-audit"))f.clearance.put("validUntil",f.now.plusSeconds(2400));
        f.clearance.put("receiptUntil",f.now.plusSeconds(3000));f.install();var witness=f.admit();
        Instant expected=f.now.plusSeconds(deadline.equals("target-audit")?1200:300);
        assertThat(witness.expiresAt()).isEqualTo(expected);assertThat(witness.evidence().approvedReceiptUntil()).isAfter(expected);
        f.now=expected;assertThatThrownBy(witness::requireCurrent).isInstanceOf(IllegalStateException.class);
    }

    @Test void exactAuditCoverageAndLargeIntegralRevisionRemainValid()throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);f.scope(1200,1200,1200);
        f.clearance.put("revisionNumber",9007199254740993L);f.install();
        var witness=f.admit();assertThat(witness.target().revisionNumber()).isEqualTo(9007199254740993L);
        assertThat(witness.evidence().approvedReceiptUntil()).isEqualTo(witness.target().auditUntil());
    }

    @ParameterizedTest @ValueSource(strings={"unknown","missing","null","number","float-version","string-version","zero-version",
            "float-revision","string-revision","zero-revision","overflow-revision","wrong-workspace","wrong-prompt","wrong-revision",
            "wrong-organization","wrong-operator-organization","register-operation","register-format","wrong-coverage","revoked",
            "same-databases","invalid-certificate","invalid-record","wrong-registry","wrong-policy","oversized-operator",
            "oversized-source","bad-date","submillisecond","offset-time","noncanonical-time","body-field"})
    void matchingRawPinDoesNotAdmitUnsupportedDecisions(String fault)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);
        switch(fault){
            case "unknown"->f.clearance.put("allowAll",true);
            case "missing"->f.clearance.remove("source");
            case "null"->f.clearance.put("responsibleParty",null);
            case "number"->f.clearance.put("operatorSubject",123);
            case "float-version"->f.clearance.put("scopeVersion",1.1);
            case "string-version"->f.clearance.put("maintainerVersion","3");
            case "zero-version"->f.clearance.put("maintainerVersion",0);
            case "float-revision"->f.clearance.put("revisionNumber",2.1);
            case "string-revision"->f.clearance.put("revisionNumber","2");
            case "zero-revision"->f.clearance.put("revisionNumber",0);
            case "overflow-revision"->f.clearance.put("revisionNumber",BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
            case "wrong-workspace"->f.clearance.put("workspaceId",UUID.randomUUID().toString());
            case "wrong-prompt"->f.clearance.put("promptId",UUID.randomUUID().toString());
            case "wrong-revision"->f.clearance.put("revisionId",UUID.randomUUID().toString());
            case "wrong-organization"->f.clearance.put("organization",UUID.randomUUID().toString());
            case "wrong-operator-organization"->f.clearance.put("operatorOrganization",UUID.randomUUID().toString());
            case "register-operation"->f.clearance.put("operation","PROMPT_WORKSPACE_REGISTER");
            case "register-format"->f.clearance.put("format","PROMPT_WORKSPACE_REGISTER_APPROVAL/v1");
            case "wrong-coverage"->f.clearance.put("traceCoverage","MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY");
            case "revoked"->f.clearance.put("status","REVOKED");
            case "same-databases"->f.clearance.put("authorityDatabaseIdentitySha","a".repeat(64));
            case "invalid-certificate"->f.clearance.put("certificateSha","C".repeat(64));
            case "invalid-record"->f.clearance.put("recordSha","d".repeat(63));
            case "wrong-registry"->f.clearance.put("registrySha","f".repeat(64));
            case "wrong-policy"->f.clearance.put("policySha","f".repeat(64));
            case "oversized-operator"->f.clearance.put("operatorSubject","x".repeat(201));
            case "oversized-source"->f.clearance.put("source","x".repeat(2001));
            case "bad-date"->f.clearance.put("decisionDate","2026-02-30");
            case "submillisecond"->f.clearance.put("auditUntil","2026-10-14T00:20:00.000001Z");
            case "offset-time"->f.clearance.put("auditUntil","2026-10-14T08:20:00+08:00");
            case "noncanonical-time"->f.clearance.put("auditUntil","2026-10-14T00:20:00.000Z");
            case "body-field"->f.clearance.put("revisionJson","forbidden text");
            default->throw new AssertionError(fault);
        }
        f.install();reject(f);
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","array"})
    void strictJsonIsRequiredEvenWithMatchingRawSha(String fault)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);String raw=f.mapper.writeValueAsString(f.clearance);
        f.install(switch(fault){case "duplicate"->"{\"operation\":\"BODY_CLEAR\","+raw.substring(1);
            case "trailing"->raw+" {}";default->"["+raw+"]";});reject(f);
    }

    @ParameterizedTest @ValueSource(strings={"body-clear.json","business.json","policy.json","registry.json"})
    void issuedWitnessRejectsChangedPinnedOriginals(String changed)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);var witness=f.admit();Files.writeString(root.resolve(changed),"{}");
        assertThatThrownBy(witness::requireCurrent).isInstanceOf(IllegalStateException.class).hasMessage("Prompt body clear approval unavailable");
    }

    @ParameterizedTest @ValueSource(strings={"workspace","prompt","revision"})
    void targetPinsAreExactAndUnboundCoordinatesCannotAdmit(String coordinate)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);String other=UUID.randomUUID().toString();
        assertThatThrownBy(()->f.adapter().admit(coordinate.equals("workspace")?other:f.workspace,
                coordinate.equals("prompt")?other:f.prompt,coordinate.equals("revision")?other:f.revision)).isInstanceOf(ProductPromptAccessDenied.class);
    }

    @Test void boundedPinsRejectAmbiguityButPermitMultipleExactRevisionsOfOneWorkspace()throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);var other=new ProductPromptBodyClearApproval.Pin(f.workspace,f.prompt,
                UUID.randomUUID().toString(),"other.json","e".repeat(64));
        assertThatCode(()->new ProductPromptBodyClearApproval(f.files(),f.registry,List.of(f.pin,other),f.clock)).doesNotThrowAnyException();
        for(var pins:List.of(List.<ProductPromptBodyClearApproval.Pin>of(),List.of(f.pin,f.pin),Collections.nCopies(129,f.pin)))
            assertThatThrownBy(()->new ProductPromptBodyClearApproval(f.files(),f.registry,pins,f.clock)).isInstanceOf(IllegalArgumentException.class);
        var sameRef=new ProductPromptBodyClearApproval.Pin(f.workspace,f.prompt,other.revisionId(),f.pin.approvalRef(),other.approvalSha());
        var sameSha=new ProductPromptBodyClearApproval.Pin(f.workspace,f.prompt,other.revisionId(),other.approvalRef(),f.pin.approvalSha());
        for(var ambiguous:List.of(sameRef,sameSha))
            assertThatThrownBy(()->new ProductPromptBodyClearApproval(f.files(),f.registry,List.of(f.pin,ambiguous),f.clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProductPromptBodyClearApproval.Pin(f.workspace,f.prompt,f.revision,"../outside.json",f.pin.approvalSha()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings={"workspace","prompt","revision"})
    void malformedUuidCannotBecomeFixedTarget(String coordinate)throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);String bad="AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";
        assertThatThrownBy(()->new ProductPromptBodyClearApproval.Pin(coordinate.equals("workspace")?bad:f.workspace,
                coordinate.equals("prompt")?bad:f.prompt,coordinate.equals("revision")?bad:f.revision,f.pin.approvalRef(),f.pin.approvalSha()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validRegisterAndBodyClearOriginalsCannotAuthorizeEachOther()throws Exception {
        var f=new ProductPromptBodyClearApprovalFixture(root);var registerPin=new ProductPromptRegisterApproval.Pin(f.workspace,f.pin.approvalRef(),f.pin.approvalSha());
        assertThatThrownBy(()->new ProductPromptRegisterApproval(f.files(),f.registry,List.of(registerPin),f.clock).admit(f.workspace))
                .isInstanceOf(IllegalStateException.class);
        var registration=new TreeMap<String,Object>(f.clearance);
        for(String field:List.of("promptId","revisionId","revisionNumber","recordSha","bodyUntil","auditUntil"))registration.remove(field);
        registration.put("format","PROMPT_WORKSPACE_REGISTER_APPROVAL/v1");registration.put("operation","PROMPT_WORKSPACE_REGISTER");
        registration.put("traceCoverage","MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY");f.install(f.mapper.writeValueAsString(registration));
        var validPin=new ProductPromptRegisterApproval.Pin(f.workspace,f.pin.approvalRef(),f.pin.approvalSha());
        assertThatCode(()->new ProductPromptRegisterApproval(f.files(),f.registry,List.of(validPin),f.clock).admit(f.workspace)).doesNotThrowAnyException();
        reject(f);
    }

    private static void reject(ProductPromptBodyClearApprovalFixture f){
        assertThatThrownBy(f::admit).isInstanceOf(IllegalStateException.class).hasMessage("Prompt body clear approval unavailable");
    }
}
