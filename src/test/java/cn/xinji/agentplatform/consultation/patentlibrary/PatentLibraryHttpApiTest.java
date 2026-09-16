package cn.xinji.agentplatform.consultation.patentlibrary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class PatentLibraryHttpApiTest {
    private static final String USER = "v0-authorized-user";
    @Autowired MockMvc mvc;

    @Test void keepsApplicationAndPublicationNumbersSeparate() throws Exception {
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", USER).param("applicationNumber", " SYN202010001 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC_V0"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].sourceRecordId").value("SYN-PAT-001"));
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", USER).param("applicationNumber", "SYNCN110001A"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", USER).param("publicationNumber", "SYNCN110001A"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    @Test void appliesGlobalFiltersClassificationScopeAndStablePaging() throws Exception {
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", USER)
                        .param("patentType", "发明").param("classification", "H01M").param("includeDescendants", "true").param("pageSize", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.items.length()").value(Matchers.lessThanOrEqualTo(10)))
                .andExpect(jsonPath("$.items[0].ipcClassifications[0]").value(Matchers.startsWith("H01M")));
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", USER)
                        .param("applicationFrom", "2025-01-02").param("applicationTo", "2025-01-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", USER).param("pageSize", "100"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void exposesDistinctDocumentVersionsAndContentStates() throws Exception {
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-010").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.versions.length()").value(2))
                .andExpect(jsonPath("$.item.versions[0].versionId").value("synthetic-v1-A"))
                .andExpect(jsonPath("$.item.versions[1].versionId").value("synthetic-v1-B"));
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-017/documents/synthetic-v1").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.descriptionSection.state").value("PARSE_FAILED"))
                .andExpect(jsonPath("$.document.descriptionSection.originalRef").exists());
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-012/documents/synthetic-v1").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.claimsSection.state").value("NOT_APPLICABLE"));
    }

    @Test void keepsInvalidationRequestAndDecisionDistinctAndOrdersEvents() throws Exception {
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-010/events").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.state").value("HAS_RECORDS"))
                .andExpect(jsonPath("$.events.records[0].type").value("INVALIDATION_REQUEST"))
                .andExpect(jsonPath("$.events.records[0].result").value(Matchers.containsString("尚无决定")));
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-007/events").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.records[0].type").value("INVALIDATION_DECISION"));
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-013/events").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.state").value("SOURCE_NOT_PROVIDED"));
    }

    @Test void rejectsUnauthorizedAndProvidesNoWriteRoute() throws Exception {
        mvc.perform(get("/api/v0/patent-library").header("X-V0-Actor", "unknown"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/v0/patent-library/SYN-PAT-001/documents/synthetic-v1").header("X-V0-Actor", "unknown"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v0/patent-library").header("X-V0-Actor", USER)).andExpect(status().isMethodNotAllowed());
    }
}
