package cn.xinji.agentplatform.consultation.trademarkcomparison;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"consultation.storage=memory","spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class TrademarkComparisonHttpApiTest {
    private static final String ACTOR="v0-authorized-user";
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;

    @Test void preparesAndCreatesIndependentDimensionEvidenceWithoutLegalScore() throws Exception {
        String prepare="{\"purpose\":\"AUXILIARY_COMPARISON\",\"target\":{\"sourceId\":\"TM-SYN-001\",\"version\":\"synthetic-v1\"},\"candidates\":[{\"sourceId\":\"TM-SYN-002\",\"version\":\"synthetic-v1\"}],\"dimensions\":[\"TEXT\",\"PHONETIC\",\"SEMANTIC\",\"IMAGE\",\"OVERALL_VISUAL\",\"GOODS_SERVICES\"]}";
        mvc.perform(post("/api/v0/trademark-comparisons/preparations").header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content(prepare))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC_V0"))
                .andExpect(jsonPath("$.suggestedCategories[0]").value("3")).andExpect(jsonPath("$.candidates[0].status").value("AVAILABLE"));

        JsonNode created=json.readTree(mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","tc-main")
                .contentType(MediaType.APPLICATION_JSON).content(command("TM-SYN-002","synthetic-v1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.reportCapability").value("EVIDENCE_TABLE_ONLY")).andExpect(jsonPath("$.reviewStatus").value("UNREVIEWED"))
                .andExpect(jsonPath("$.pairs[0].dimensions[0].state").value("ANALYZED"))
                .andExpect(jsonPath("$.pairs[0].dimensions[1].observation").value("目录受控读音字符串相同"))
                .andExpect(jsonPath("$.pairs[0].dimensions[2].state").value("NOT_ANALYZED"))
                .andExpect(jsonPath("$.pairs[0].dimensions[3].state").value("MATERIAL_UNAVAILABLE"))
                .andExpect(jsonPath("$.pairs[0].dimensions[5].reason").value(org.hamcrest.Matchers.containsString("STANDARD_UNAVAILABLE")))
                .andReturn().getResponse().getContentAsString());
        assertFalse(created.toString().contains("percentage")); assertFalse(created.toString().contains("注册成功率："));
        mvc.perform(get("/api/v0/trademark-comparisons/tasks/{id}",created.get("taskId").asText()).header("X-V0-Actor",ACTOR)).andExpect(status().isOk()).andExpect(jsonPath("$.inputSnapshot.target.version").value("synthetic-v1"));
        mvc.perform(get("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR)).andExpect(status().isOk()).andExpect(jsonPath("$[0].taskId").value(created.get("taskId").asText()));
    }

    @Test void replaysSameInputRejectsChangedInputAndKeepsCandidateFailureIndependent() throws Exception {
        String body=commandWithCandidates("[{\"sourceId\":\"TM-SYN-002\",\"version\":\"synthetic-v1\"},{\"sourceId\":\"MISSING\",\"version\":\"v1\"}]");
        JsonNode first=json.readTree(mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","tc-partial").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.pairs[0].status").value("PARTIAL")).andExpect(jsonPath("$.pairs[1].status").value("FAILED"))
                .andExpect(jsonPath("$.pairs[1].missingItems[0]").value("DOCUMENT_NOT_FOUND")).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","tc-partial").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(first.get("taskId").asText()));
        mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","tc-partial").contentType(MediaType.APPLICATION_JSON).content(command("TM-SYN-003","synthetic-v1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test void rejectsMissingConfirmedScopeWrongVersionAndUnauthorizedAccess() throws Exception {
        String missing="{\"purpose\":\"AUXILIARY_COMPARISON\",\"target\":{\"sourceId\":\"TM-SYN-001\",\"version\":\"synthetic-v1\"},\"candidates\":[{\"sourceId\":\"TM-SYN-002\",\"version\":\"synthetic-v1\"}],\"confirmedCategories\":[],\"confirmedGoodsServices\":[\"化妆品\"],\"dimensions\":[\"TEXT\"]}";
        mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","missing").contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORIES_REQUIRED"));
        mvc.perform(post("/api/v0/trademark-comparisons/preparations").header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"AUXILIARY_COMPARISON\",\"target\":{\"sourceId\":\"TM-SYN-001\",\"version\":\"wrong\"},\"candidates\":[{\"sourceId\":\"TM-SYN-002\",\"version\":\"synthetic-v1\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor","unknown").header("Idempotency-Key","denied").contentType(MediaType.APPLICATION_JSON).content(command("TM-SYN-002","synthetic-v1")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private static String command(String candidate,String version){return commandWithCandidates("[{\"sourceId\":\""+candidate+"\",\"version\":\""+version+"\"}]");}
    private static String commandWithCandidates(String candidates){return "{\"purpose\":\"AUXILIARY_COMPARISON\",\"target\":{\"sourceId\":\"TM-SYN-001\",\"version\":\"synthetic-v1\"},\"candidates\":"+candidates+",\"confirmedCategories\":[\"3\"],\"confirmedGoodsServices\":[\"化妆品\",\"洗发液\"],\"dimensions\":[\"TEXT\",\"PHONETIC\",\"SEMANTIC\",\"IMAGE\",\"OVERALL_VISUAL\",\"GOODS_SERVICES\"]}";}
}
