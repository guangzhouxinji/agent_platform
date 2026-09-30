package cn.xinji.agentplatform.security.v2;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "consultation-v2.development.enabled=true",
        "consultation-v2.development.credentials[0].key-id=human-a",
        "consultation-v2.development.credentials[0].secret-sha256=05c8803d79d80e0bd886261c24c0ea30838d7d0047c558c23367dc910c974b5c",
        "consultation-v2.development.credentials[0].principal-ref=dev-con-a",
        "consultation-v2.development.credentials[0].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[0].kind=HUMAN",
        "consultation-v2.development.credentials[0].expires-at=2099-01-01T00:00:00Z",
        "consultation-v2.development.credentials[1].key-id=human-b",
        "consultation-v2.development.credentials[1].secret-sha256=0e0d6b169e418021fc74cbf168abc8ab822819f9f03c1aa63968256286d3d272",
        "consultation-v2.development.credentials[1].principal-ref=dev-con-b",
        "consultation-v2.development.credentials[1].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[1].kind=HUMAN",
        "consultation-v2.development.credentials[1].expires-at=2099-01-01T00:00:00Z",
        "consultation-v2.development.credentials[2].key-id=agent",
        "consultation-v2.development.credentials[2].secret-sha256=d0c5f1263ab7a8473a3135bbc5f391689660c2ce3bc0f783df20b86f307308f5",
        "consultation-v2.development.credentials[2].principal-ref=dev-con-agent",
        "consultation-v2.development.credentials[2].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[2].kind=AGENT",
        "consultation-v2.development.credentials[2].expires-at=2099-01-01T00:00:00Z",
        "consultation-v2.development.credentials[3].key-id=job",
        "consultation-v2.development.credentials[3].secret-sha256=93b3e3315ec272c5ef812cd9f78147a815fc45a5b7e646ea0095aa4c5cdf8b03",
        "consultation-v2.development.credentials[3].principal-ref=dev-con-job",
        "consultation-v2.development.credentials[3].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[3].kind=JOB",
        "consultation-v2.development.credentials[3].expires-at=2099-01-01T00:00:00Z"
})
@ActiveProfiles("test-synthetic")
@AutoConfigureMockMvc
class V2ConsultationBoundaryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired DevelopmentSubjectPolicy subjects;

    @DynamicPropertySource
    static void manifest(DynamicPropertyRegistry registry) {
        registry.add("consultation-v2.development.manifest-path",
                () -> DevelopmentBoundaryPolicyTest.manifest().toString());
    }

    @Test void onlyTrustedHumanReachesTheTestOnlyProbe() throws Exception {
        String probe = "/v2/consultation/_boundary/identity";
        mvc.perform(get(probe)).andExpect(status().isUnauthorized());
        mvc.perform(get(probe).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isForbidden());
        mvc.perform(get(probe).header("Authorization", DevelopmentBoundaryPolicyTest.token("human-a"))
                        .header("X-V0-Requester", "dev-con-b").header("X-V0-Organization", "other-org"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ALLOW"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/v2/consultation/tasks").header("Authorization",
                DevelopmentBoundaryPolicyTest.token("human-a"))).andExpect(status().isNotFound());
        mvc.perform(get("/v2/consultation/tasks/00000000-0000-0000-0000-000000000001/feature-confirmations/1")
                .header("Authorization", DevelopmentBoundaryPolicyTest.token("human-a")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v2/consultation/tasks/00000000-0000-0000-0000-000000000001/human-requests/1/context")
                .header("Authorization", DevelopmentBoundaryPolicyTest.token("human-a")))
                .andExpect(status().isNotFound());
        subjects.revoke("human-a");
        mvc.perform(get(probe).header("Authorization", DevelopmentBoundaryPolicyTest.token("human-a")))
                .andExpect(status().isUnauthorized());
    }

}
