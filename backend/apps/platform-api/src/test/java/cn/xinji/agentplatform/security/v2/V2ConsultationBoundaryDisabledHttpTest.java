package cn.xinji.agentplatform.security.v2;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "consultation-v2.development.enabled=false"
})
@ActiveProfiles("test-synthetic")
@AutoConfigureMockMvc
class V2ConsultationBoundaryDisabledHttpTest {
    @Autowired MockMvc mvc;

    @Test void disabledV2PrefixFailsClosedEvenWithoutCredentials() throws Exception {
        mvc.perform(get("/v2/consultation/_boundary/identity")
                        .header("Authorization", DevelopmentBoundaryPolicyTest.token("human-a")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v2/consultation/tasks"))
                .andExpect(status().isNotFound());
    }
}
