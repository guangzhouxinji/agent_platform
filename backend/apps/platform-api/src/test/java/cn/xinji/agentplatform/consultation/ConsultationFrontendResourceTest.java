package cn.xinji.agentplatform.consultation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class ConsultationFrontendResourceTest {
    @Autowired MockMvc mvc;

    @Test void servesReactWorkbenchFromTheOnlyFrontendEntry() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/consultation"));
        mvc.perform(get("/app/consultation/flow"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
        mvc.perform(get("/app/consultation"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
        mvc.perform(get("/app/consultation/intents"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
        mvc.perform(get("/app/consultation/agent-configuration"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
        for (String route : new String[] {
                "/app/enforcement", "/app/enforcement/rules",
                "/app/enforcement/discovery", "/app/enforcement/parsing", "/app/enforcement/evidence"
        }) {
            mvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/app/index.html"));
        }
        for (String route : new String[] {"/app/development/synthetic-center", "/app/enforcement/sources"}) {
            mvc.perform(get(route)).andExpect(status().isNotFound());
        }
    }
}
