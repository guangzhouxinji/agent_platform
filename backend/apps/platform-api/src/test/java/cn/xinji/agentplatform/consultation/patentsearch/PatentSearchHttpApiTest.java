package cn.xinji.agentplatform.consultation.patentsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.memory.InMemoryPatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "patent-assistant.model.provider=disabled",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class PatentSearchHttpApiTest {
    private static final String USER = "v0-authorized-user";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired InMemoryPatentSearchTaskRepository repository;
    @Autowired SyntheticPatentCatalogAdapter catalog;

    @BeforeEach void clearTasks() { repository.clear(); }

    @Test void createsAndReadsFilteredSyntheticSearchTask() throws Exception {
        JsonNode created = create("http-search", """
                {"inputMode":"TECHNICAL_SOLUTION","input":"水性粘结剂、双区烘干、在线测厚反馈","purpose":"TECHNICAL_REFERENCE",
                 "confirmedFeatures":["在线测厚","激光测量"],
                 "scope":{"patentTypes":["实用新型"],"regions":["CN"],"classifications":["G01B"],"publicationFrom":"2021-01-01","publicationTo":"2022-12-31"}}
                """);
        assertEquals("SYNTHETIC", created.get("dataMode").asText());
        JsonNode completed = awaitTerminal(created.get("taskId").asText());
        assertEquals("SUCCEEDED", completed.get("status").asText());
        assertEquals("SYN-PAT-002", completed.get("candidates").get(0).get("sourceId").asText());
        assertEquals("SYNTHETIC-RANK-V1", completed.get("scoringVersion").asText());
        assertTrue(completed.get("limitation").asText().contains("synthetic-patent-catalog-v2"));
    }

    @Test void supportsPatentNumberInputHistoryAndIdempotentReplay() throws Exception {
        String body = """
                {"inputMode":"PATENT_NUMBER","input":"SYN202010001","purpose":"TECHNICAL_REFERENCE","confirmedFeatures":[],"scope":{}}
                """;
        JsonNode first = create("http-number", body);
        JsonNode replay = create("http-number", body);
        assertEquals(first.get("taskId").asText(), replay.get("taskId").asText());
        awaitTerminal(first.get("taskId").asText());
        mvc.perform(get("/api/v0/patent-search-tasks").header("X-V0-Requester", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].inputSnapshot.inputMode").value("PATENT_NUMBER"));
    }

    @Test void rejectsIdempotencyConflictInvalidInputAndUnauthorizedRequester() throws Exception {
        create("http-conflict", """
                {"inputMode":"CORE_FEATURES","input":"在线测厚","purpose":"TECHNICAL_REFERENCE","scope":{}}
                """);
        mvc.perform(post("/api/v0/patent-search-tasks").header("X-V0-Requester", USER).header("Idempotency-Key", "http-conflict")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"inputMode":"CORE_FEATURES","input":"双区烘干","purpose":"TECHNICAL_REFERENCE","scope":{}}
                        """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/v0/patent-search-tasks").header("X-V0-Requester", USER).header("Idempotency-Key", "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v0/patent-search-tasks").header("X-V0-Requester", "unknown").header("Idempotency-Key", "denied")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"inputMode":"CORE_FEATURES","input":"在线测厚","purpose":"TECHNICAL_REFERENCE","scope":{}}
                        """))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test void retriesFailedSyntheticTaskWithoutChangingItsIdentity() throws Exception {
        catalog.failNextSearch();
        JsonNode created = create("http-retry", """
                {"inputMode":"CORE_FEATURES","input":"在线测厚","purpose":"TECHNICAL_REFERENCE","scope":{}}
                """);
        JsonNode failed = awaitTerminal(created.get("taskId").asText());
        assertEquals("FAILED", failed.get("status").asText());
        String retriedBody = mvc.perform(post("/api/v0/patent-search-tasks/{id}/retry", created.get("taskId").asText())
                        .header("X-V0-Requester", USER))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(created.get("taskId").asText(), mapper.readTree(retriedBody).get("taskId").asText());
        JsonNode completed = awaitTerminal(created.get("taskId").asText());
        assertEquals("SUCCEEDED", completed.get("status").asText());
        assertEquals(2, completed.get("attempt").asInt());
    }

    @Test void featurePreparationFailsClearlyWhenDeepSeekIsNotConfigured() throws Exception {
        mvc.perform(post("/api/v0/patent-search-tasks/preparations").header("X-V0-Requester", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"inputMode":"TECHNICAL_SOLUTION","input":"水性粘结剂与双区烘干", "purpose":"TECHNICAL_REFERENCE","scope":{}}
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
    }

    private JsonNode create(String key, String body) throws Exception {
        String response = mvc.perform(post("/api/v0/patent-search-tasks").header("X-V0-Requester", USER)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.dataMode").value("SYNTHETIC"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private JsonNode awaitTerminal(String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        JsonNode body;
        do {
            String response = mvc.perform(get("/api/v0/patent-search-tasks/{id}", taskId).header("X-V0-Requester", USER))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            body = mapper.readTree(response);
            if (java.util.Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(body.get("status").asText())) return body;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not reach terminal state: " + body);
    }
}
