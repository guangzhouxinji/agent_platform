package cn.xinji.agentplatform.consultation;

import cn.xinji.agentplatform.consultation.application.ConsultationApplicationService;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.adapter.out.synthetic.SyntheticPatentSearchAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class ConsultationHttpApiTest {
    @Autowired MockMvc mvc;
    @Autowired ConsultationApplicationService service;
    @Autowired SyntheticPatentSearchAdapter patentSearch;

    @BeforeEach void resetSyntheticSources() { patentSearch.reset(); }

    @Test void returnsCitedAnswerForNormalRequest() throws Exception {
        mvc.perform(submit("v0-authorized-user", "http-normal", "水性粘结剂、双区烘干", "TECHNICAL_REFERENCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("ANSWER_READY"))
                .andExpect(jsonPath("$.candidates.length()").value(2)).andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("不构成")));
    }

    @Test void asksForClarificationWithoutCandidates() throws Exception {
        mvc.perform(submit("v0-authorized-user", "http-clarify", "附件 A 80，附件 B 120", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test void deniesUnauthorizedRequestWithoutCandidates() throws Exception {
        mvc.perform(submit("unknown", "http-denied", "技术方案", "TECHNICAL_REFERENCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test void returnsPartialResultAfterSourceBecomesUnavailable() throws Exception {
        String response = mvc.perform(submit("v0-authorized-user", "http-refresh", "技术方案", "TECHNICAL_REFERENCE"))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("consultationId").asText();
        patentSearch.markSourceUnavailable("V0-SYN-PAT-002");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}", id)
                        .header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("PARTIAL_RESULT"))
                .andExpect(jsonPath("$.candidates.length()").value(1));
    }

    @Test void rejectsIdempotencyConflict() throws Exception {
        mvc.perform(submit("v0-authorized-user", "http-idempotent", "技术方案", "TECHNICAL_REFERENCE")).andExpect(status().isOk());
        mvc.perform(submit("v0-authorized-user", "http-idempotent", "另一方案", "TECHNICAL_REFERENCE")).andExpect(status().isConflict());
    }

    @Test void readsOwnedConsultationAndDoesNotRevealItToAnotherRequester() throws Exception {
        String response = mvc.perform(submit("v0-authorized-user", "http-read", "技术方案", "TECHNICAL_REFERENCE")).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("consultationId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}", id).header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("ANSWER_READY"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}", id).header("X-V0-Requester", "unknown"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test void readsOnlyOwnedSourceLink() throws Exception {
        String response = mvc.perform(submit("v0-authorized-user", "http-source", "技术方案", "TECHNICAL_REFERENCE")).andReturn().getResponse().getContentAsString();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        String id = body.get("consultationId").asText();
        String linkId = body.get("sourceLinks").get(0).get("sourceLinkId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/source-links/{linkId}", id, linkId).header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceLinkId").value(linkId));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/source-links/{linkId}", id, linkId).header("X-V0-Requester", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test void readsExactOwnedAnswer() throws Exception {
        var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(submit("v0-authorized-user", "http-answer", "技术方案", "TECHNICAL_REFERENCE")).andReturn().getResponse().getContentAsString());
        String id=body.get("consultationId").asText(), answerId=body.get("answerId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/answers/{answerId}",id,answerId).header("X-V0-Requester","v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answerId").value(answerId));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/answers/{answerId}",id,answerId).header("X-V0-Requester","unknown"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("ACCESS_DENIED"));
    }

    @Test void supplementsClarificationAndPreservesOriginalAnswer() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var first=mapper.readTree(mvc.perform(submit("v0-authorized-user","http-supplement-initial","帮我查一下涂布工艺相关专利。","")).andReturn().getResponse().getContentAsString());
        String id=first.get("consultationId").asText(), originalAnswerId=first.get("answerId").asText();
        mvc.perform(post("/api/v0/consultations/{id}/inputs",id).header("X-V0-Requester","v0-authorized-user").header("Idempotency-Key","http-supplement")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"水性粘结剂、双区烘干；用于技术参考。\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("ANSWER_READY"))
                .andExpect(jsonPath("$.answerVersion").value(2)).andExpect(jsonPath("$.candidates.length()").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/answers/{answerId}",id,originalAnswerId).header("X-V0-Requester","v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("NEEDS_CLARIFICATION"));
    }

    @Test void classifiesOutOfScopeNoResultAndToolFailure() throws Exception {
        mvc.perform(submit("v0-authorized-user", "http-out-of-scope", "水性粘结剂技术方案", "LEGAL_OPINION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("OUT_OF_SCOPE"));
        assertEquals(0, patentSearch.calls());

        patentSearch.force(PatentSearchPort.SearchResult.Type.NO_RESULT);
        mvc.perform(submit("v0-authorized-user", "http-no-result", "水性粘结剂技术方案", "TECHNICAL_REFERENCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("NO_RESULT"))
                .andExpect(jsonPath("$.candidates.length()").value(0));

        patentSearch.force(PatentSearchPort.SearchResult.Type.TOOL_FAILURE);
        mvc.perform(submit("v0-authorized-user", "http-tool-failure", "水性粘结剂技术方案", "TECHNICAL_REFERENCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("TOOL_FAILURE"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test void returnsSourceUnavailableWhenNoUsableSourceRemains() throws Exception {
        patentSearch.markSourceUnavailable("V0-SYN-PAT-001");
        patentSearch.markSourceUnavailable("V0-SYN-PAT-002");
        mvc.perform(submit("v0-authorized-user", "http-source-unavailable", "水性粘结剂技术方案", "TECHNICAL_REFERENCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test void rejectsEmptyRequestWithoutCreatingConsultation() throws Exception {
        mvc.perform(post("/api/v0/consultations").header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "http-empty").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, patentSearch.calls());
    }

    @Test void rejectsSupplementIdempotencyConflictAndKeepsOriginalResult() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(mvc.perform(submit("v0-authorized-user", "http-supplement-conflict-initial", "帮我查一下涂布工艺相关专利。", ""))
                .andReturn().getResponse().getContentAsString());
        String id = first.get("consultationId").asText();
        var request = post("/api/v0/consultations/{id}/inputs", id).header("X-V0-Requester", "v0-authorized-user")
                .header("Idempotency-Key", "http-supplement-conflict").contentType(MediaType.APPLICATION_JSON);
        mvc.perform(request.content("{\"content\":\"水性粘结剂、双区烘干；用于技术参考。\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answerVersion").value(2));
        mvc.perform(post("/api/v0/consultations/{id}/inputs", id).header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "http-supplement-conflict").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"另一项补充。\"}"))
                .andExpect(status().isConflict());
    }

    @Test void refusesUnavailableSourceLinkOnCurrentRead() throws Exception {
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mvc.perform(submit("v0-authorized-user", "http-source-recheck", "水性粘结剂技术方案", "TECHNICAL_REFERENCE"))
                        .andReturn().getResponse().getContentAsString());
        String id = body.get("consultationId").asText();
        String linkId = body.get("sourceLinks").get(0).get("sourceLinkId").asText();
        patentSearch.markSourceUnavailable("V0-SYN-PAT-001");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/source-links/{linkId}", id, linkId)
                        .header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isNotFound());
    }

    @Test void removesAllUnavailableSourcesWhenReadingExistingAnswer() throws Exception {
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mvc.perform(submit("v0-authorized-user", "http-answer-source-recheck", "水性粘结剂技术方案", "TECHNICAL_REFERENCE"))
                        .andReturn().getResponse().getContentAsString());
        String id = body.get("consultationId").asText();
        String answerId = body.get("answerId").asText();
        patentSearch.markSourceUnavailable("V0-SYN-PAT-001");
        patentSearch.markSourceUnavailable("V0-SYN-PAT-002");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v0/consultations/{id}/answers/{answerId}", id, answerId)
                        .header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.candidates.length()").value(0))
                .andExpect(jsonPath("$.sourceLinks.length()").value(0));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String requester, String key, String question, String purpose) {
        return post("/api/v0/consultations").header("X-V0-Requester", requester).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + question + "\",\"purpose\":\"" + purpose + "\"}");
    }
}
