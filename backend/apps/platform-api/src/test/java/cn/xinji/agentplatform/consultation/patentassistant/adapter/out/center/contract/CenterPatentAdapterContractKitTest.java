package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.center.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.patentassistant.adapter.out.surechembl.SureChemblPatentAssistantSourceAdapter;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Self-test only. The subject is a public adapter, not a center API contract. */
class CenterPatentAdapterContractKitTest extends CenterPatentAdapterConformanceContract {
    @Override
    protected PatentAssistantSourcePort createSubject(String baseUrl, int requestTimeoutMs) {
        return new SureChemblPatentAssistantSourceAdapter(baseUrl, 1_000, requestTimeoutMs, 10,
                new ObjectMapper());
    }

    @Override
    protected String successResponse() {
        return """
                {"status":"OK","data":{"results":{"total_hits":7,"documents":[
                  {"docId":"CN-EXAMPLE-A","metadata":{"pd":"20240102","pn":"CN-EXAMPLE-A",
                    "titles":[{"lang":"zh","titles":["示例公开专利"]}]},"pa":"示例申请人"}
                ]}}}
                """;
    }

    @Test
    void unknownAndExampleProfilesCannotEnableARealCenterAdapter() {
        assertTrue(CenterApiCapabilityProfile.unknown().capabilities().stream()
                .allMatch(value -> value == CenterApiCapabilityProfile.Capability.UNKNOWN));
        assertFalse(CenterApiCapabilityProfile.unknown().readyForRealAdapter());
        assertFalse(CenterApiCapabilityProfile.exampleOnly().readyForRealAdapter());
    }
}
