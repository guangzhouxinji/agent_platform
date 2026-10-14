package cn.xinji.agentplatform.enforcement.deployment.entry;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
class ProductEnforcementWorkspacePageControllerTest {
    @Test void normalRootsAndAdmittedPagesForwardOnlyOwnedHtml() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ProductEnforcementWorkspacePageController()).addFilters(new ProductEnforcementEntryFilter(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of()))).build();
        for(String path:List.of("/","/app","/app/","/app/enforcement","/app/enforcement/job-pack"))mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/app/enforcement.html"));
        for(String path:List.of("/app/enforcement/material","/app/enforcement/document-templates","/app/adjudication"))mvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
