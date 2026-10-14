package cn.xinji.agentplatform.security.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.xinji.agentplatform.web.ReactFrontendController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductConsultationWorkspacePageControllerTest {
    @Test void finalPageHasOneRouteAndCannotFallBackToOldIndex() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ProductConsultationWorkspacePageController(),
                new ReactFrontendController()).build();
        mvc.perform(get("/app/consultation")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/consultation.html"));
    }
}
