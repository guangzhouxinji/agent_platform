package cn.xinji.agentplatform.enforcement.material;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.web.ReactFrontendController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnforcementMaterialFrontendRouteTest {
    @Test void productMaterialOauthReturnOpensExistingWorkbench() throws Exception {
        MockMvcBuilders.standaloneSetup(new ReactFrontendController()).build()
                .perform(get("/app/enforcement/material"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }
}
