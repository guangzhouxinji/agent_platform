package cn.xinji.agentplatform.adjudication.casework;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class MaterialOrganizationErrorsTest {
 @Test void unknownCaseAndRunDoNotMasqueradeAsMalformedInputs(){var e=new MaterialOrganizationErrors();for(String c:new String[]{"CASE_NOT_FOUND","CASE_VERSION_NOT_FOUND","MODEL_RUN_NOT_FOUND"})assertEquals(404,e.invalid(new IllegalArgumentException(c)).getStatusCode().value());}
 @Test void staleVersionAndBudgetRetainTheirSpecificStatuses(){var e=new MaterialOrganizationErrors();assertEquals(409,e.invalid(new IllegalArgumentException("WORK_VERSION_CONFLICT")).getStatusCode().value());assertEquals(429,e.invalid(new IllegalArgumentException("MODEL_BUDGET_EXHAUSTED")).getStatusCode().value());}
 @Test void permissionErrorsRemainBackendRefusalsWithoutLeakingUntrustedText(){var r=new MaterialOrganizationErrors().denied(new SecurityException("untrusted private text"));assertEquals(403,r.getStatusCode().value());assertEquals("MODEL_OPERATION_FAILED",r.getBody().get("reason"));}
}
