package cn.xinji.agentplatform.consultation.patentcomparison;

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
class PatentComparisonHttpApiTest {
    private static final String ACTOR="v0-authorized-user";
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;

    @Test void preparesAndCreatesEvidenceTableWithoutLegalScore()throws Exception{
        String preparation="{\"purpose\":\"TECHNICAL_SIMILARITY\",\"target\":{\"sourceId\":\"SYN-PAT-001\",\"version\":\"synthetic-v1\"},\"candidates\":[{\"sourceId\":\"SYN-PAT-003\",\"version\":\"synthetic-v1\"}]}";
        mvc.perform(post("/api/v0/patent-comparisons/preparations").header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content(preparation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC_V0")).andExpect(jsonPath("$.suggestedFeatures.length()").value(3)).andExpect(jsonPath("$.candidates[0].status").value("AVAILABLE"));
        String body=command("TECHNICAL_SIMILARITY","SYN-PAT-003","synthetic-v1");
        JsonNode created=json.readTree(mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","pc-main").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.scoreCapability").value("NOT_CALIBRATED")).andExpect(jsonPath("$.reviewStatus").value("UNREVIEWED")).andExpect(jsonPath("$.pairs[0].mappings[0].relation").value("CORRESPONDS")).andExpect(jsonPath("$.pairs[0].mappings[1].relation").value("NOT_FOUND")).andReturn().getResponse().getContentAsString());
        assertFalse(created.toString().contains("percentage"));assertFalse(created.toString().contains("侵权成立"));
        String taskId=created.get("taskId").asText();mvc.perform(get("/api/v0/patent-comparisons/tasks/{id}",taskId).header("X-V0-Actor",ACTOR)).andExpect(status().isOk()).andExpect(jsonPath("$.inputSnapshot.target.version").value("synthetic-v1"));
        mvc.perform(get("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR)).andExpect(status().isOk()).andExpect(jsonPath("$[0].taskId").value(taskId));
    }

    @Test void replaysSameInputRejectsChangedInputAndKeepsCandidateFailureIndependent()throws Exception{
        String body=commandWithCandidates("CLAIM_COMPARISON","[{\"sourceId\":\"SYN-PAT-003\",\"version\":\"synthetic-v1\"},{\"sourceId\":\"MISSING\",\"version\":\"v1\"}]");
        JsonNode first=json.readTree(mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","pc-partial").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.pairs[0].status").value("COMPLETED")).andExpect(jsonPath("$.pairs[1].status").value("FAILED")).andExpect(jsonPath("$.pairs[1].unexecutedItems[0]").value("DOCUMENT_NOT_FOUND")).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","pc-partial").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(first.get("taskId").asText()));
        mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","pc-partial").contentType(MediaType.APPLICATION_JSON).content(command("CLAIM_COMPARISON","SYN-PAT-004","synthetic-v1"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test void blocksUnavailablePurposesVersionsAndUnauthorizedAccess()throws Exception{
        mvc.perform(post("/api/v0/patent-comparisons/preparations").header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"DESIGN_ASSISTANCE\",\"target\":{\"sourceId\":\"SYN-PAT-011\",\"version\":\"synthetic-v1\"},\"candidates\":[{\"sourceId\":\"SYN-PAT-012\",\"version\":\"synthetic-v1\"}]}" )).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DESIGN_CAPABILITY_UNAVAILABLE"));
        mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","novelty").contentType(MediaType.APPLICATION_JSON).content(command("NOVELTY_ASSISTANCE","SYN-PAT-003","synthetic-v1"))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LEGAL_RULE_UNAVAILABLE"));
        mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","version").contentType(MediaType.APPLICATION_JSON).content(command("TECHNICAL_SIMILARITY","SYN-PAT-003","wrong"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.pairs[0].unexecutedItems[0]").value("VERSION_CONFLICT"));
        mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor","unknown").header("Idempotency-Key","denied").contentType(MediaType.APPLICATION_JSON).content(command("TECHNICAL_SIMILARITY","SYN-PAT-003","synthetic-v1"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private static String command(String purpose,String candidate,String version){return commandWithCandidates(purpose,"[{\"sourceId\":\""+candidate+"\",\"version\":\""+version+"\"}]");}
    private static String commandWithCandidates(String purpose,String candidates){return "{\"purpose\":\""+purpose+"\",\"target\":{\"sourceId\":\"SYN-PAT-001\",\"version\":\"synthetic-v1\"},\"candidates\":"+candidates+",\"confirmedFeatures\":[{\"featureId\":\"F1\",\"text\":\"双区烘干\",\"sourceLocation\":\"目标权利要求1\"},{\"featureId\":\"F2\",\"text\":\"水性粘结剂\",\"sourceLocation\":\"目标权利要求1\"}]}";}
}
