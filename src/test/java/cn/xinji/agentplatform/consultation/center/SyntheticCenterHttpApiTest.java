package cn.xinji.agentplatform.consultation.center;

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
class SyntheticCenterHttpApiTest {
    @Autowired MockMvc mvc;

    @Test void exposesExplicitDevelopmentMetadataAndReadOnlyCatalogs() throws Exception {
        mvc.perform(get("/api/v0/synthetic-center"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC_V0"))
                .andExpect(jsonPath("$.limitation").value(org.hamcrest.Matchers.containsString("不代表真实中心")));
        mvc.perform(get("/api/v0/synthetic-center/patents").param("query", "在线测厚").param("type", "实用新型").param("region", "CN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC_V0"))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].sourceId").value("SYN-PAT-002"));
        mvc.perform(get("/api/v0/synthetic-center/trademarks").param("query", "qing yuan").param("category", "3").param("status", "有效"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sourceId").value("TM-SYN-001"));
    }

    @Test void readsStableDetailsAndHasNoWriteRoute() throws Exception {
        mvc.perform(get("/api/v0/synthetic-center/patents/SYN-PAT-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC_V0"))
                .andExpect(jsonPath("$.item.version").value("synthetic-v1"));
        mvc.perform(get("/api/v0/synthetic-center/trademarks/TM-SYN-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.dataSource").value("SYNTHETIC_TRADEMARK_CATALOG"));
        mvc.perform(get("/api/v0/synthetic-center/patents/NOT-FOUND")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v0/synthetic-center/patents")).andExpect(status().isMethodNotAllowed());
    }

    @Test void exposesEnforcementAndAdjudicationSourcesWithoutMergingTheirSemantics() throws Exception {
        mvc.perform(get("/api/v0/synthetic-center/enforcement/clues"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.catalogVersion").value("synthetic-enforcement-source-v2"))
                .andExpect(jsonPath("$.items[0].source.sourceId").value("V0-SYN-CLUE-001"));
        mvc.perform(get("/api/v0/synthetic-center/enforcement/evidence/V0-SYN-EVD-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.relatedClueId").value("V0-SYN-CLUE-001"));
        mvc.perform(get("/api/v0/synthetic-center/adjudication/cases/V0-SYN-ADJ-CASE-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.version").value("v1"));
        mvc.perform(get("/api/v0/synthetic-center/adjudication/materials/V0-SYN-ADJ-MAT-002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.item.caseRef.id").value("V0-SYN-ADJ-CASE-001"))
                .andExpect(jsonPath("$.item.caseRef.version").value("v1"))
                .andExpect(jsonPath("$.item.data.conflicts[0]").value("发生日期存在两种记载"));
        mvc.perform(get("/api/v0/synthetic-center/adjudication/legal-candidates"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].code").value("V0-SYN-ADJ-LAW-001"));
        mvc.perform(get("/api/v0/synthetic-center/adjudication/materials/MISSING")).andExpect(status().isNotFound());
    }

    @Test void exposesRealisticDeterministicDatasetSizes() throws Exception {
        mvc.perform(get("/api/v0/synthetic-center/patents")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(120));
        mvc.perform(get("/api/v0/synthetic-center/trademarks")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(120));
        mvc.perform(get("/api/v0/synthetic-center/enforcement/clues")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(30));
        mvc.perform(get("/api/v0/synthetic-center/enforcement/evidence")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(119));
        mvc.perform(get("/api/v0/synthetic-center/adjudication/cases")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20));
        mvc.perform(get("/api/v0/synthetic-center/adjudication/materials")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(80));
    }
}
