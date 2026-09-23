package cn.xinji.agentplatform.consultation.trademarklibrary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class TrademarkLibraryHttpApiTest {
    private static final String USER = "v0-authorized-user";
    @Autowired MockMvc mvc;

    @Test void queriesByStableFieldsWithGlobalFilteringAndPaging() throws Exception {
        mvc.perform(get("/api/v0/trademark-library").header("X-V0-Actor", USER)
                        .param("registrationNumber", " 0A76810001 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("DEVELOPMENT_MIXED"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].sourceRecordId").value("TM-SYN-001"))
                .andExpect(jsonPath("$.items[0].registrationNumber").value("0A76810001"));
        mvc.perform(get("/api/v0/trademark-library").header("X-V0-Actor", USER)
                        .param("applicant", "瑞衡").param("category", "3,35").param("status", "有效").param("pageSize", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(10)))
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test void rejectsInvalidDateRangesAndOversizedPages() throws Exception {
        mvc.perform(get("/api/v0/trademark-library").header("X-V0-Actor", USER)
                        .param("registeredFrom", "2025-01-02").param("registeredTo", "2025-01-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(get("/api/v0/trademark-library").header("X-V0-Actor", USER).param("pageSize", "100"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void exposesDetailsAndKeepsHistoryCapabilityStatesIndependent() throws Exception {
        mvc.perform(get("/api/v0/trademark-library/TM-SYN-010").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.classes.length()").value(2))
                .andExpect(jsonPath("$.historyCapabilities.oppositions").value("HAS_RECORDS"));
        mvc.perform(get("/api/v0/trademark-library/TM-SYN-010/history/oppositions").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.state").value("HAS_RECORDS"))
                .andExpect(jsonPath("$.history.records[0].matter").value("异议申请"))
                .andExpect(jsonPath("$.history.records[0].result").value("尚无决定结果"));
        mvc.perform(get("/api/v0/trademark-library/TM-SYN-011/history/invalidations").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.state").value("QUERY_FAILED"))
                .andExpect(jsonPath("$.history.records.length()").value(0));
    }

    @Test void includesImportedPublicTrademarkWithoutInventingRegistrationOrHistory() throws Exception {
        mvc.perform(get("/api/v0/trademark-library").header("X-V0-Actor", USER).param("name", "LEAKONOMICS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sourceRecordId").value("50002442"))
                .andExpect(jsonPath("$.items[0].dataMode").value("PUBLIC_PILOT"))
                .andExpect(jsonPath("$.items[0].registrationNumber").doesNotExist())
                .andExpect(jsonPath("$.items[0].displayStatus").value("公报事件：published"));
        mvc.perform(get("/api/v0/trademark-library/50002442/history/oppositions").header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.state").value("SOURCE_NOT_PROVIDED"))
                .andExpect(jsonPath("$.history.records.length()").value(0));
    }

    @Test void rejectsUnauthorizedAndProvidesNoWriteRoute() throws Exception {
        mvc.perform(get("/api/v0/trademark-library").header("X-V0-Actor", "unknown"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v0/trademark-library").header("X-V0-Actor", USER)).andExpect(status().isMethodNotAllowed());
    }
}
