package cn.xinji.agentplatform.consultation.trademarksearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.memory.InMemoryTrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.synthetic.SyntheticTrademarkCatalogAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class TrademarkSearchHttpApiTest {
    private static final String USER = "v0-authorized-user";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired InMemoryTrademarkSearchTaskRepository repository;
    @Autowired SyntheticTrademarkCatalogAdapter catalog;

    @BeforeEach void clearTasks() { repository.clear(); }

    @Test void createsHistoryAndReturnsDimensionExecutionStates() throws Exception {
        var created = create("text-search", request("TEXT", "清源", "[\"TEXT\",\"PHONETIC\",\"SEMANTIC\"]", "{}"));
        var completed = awaitTerminal(created.get("taskId").asText());
        assertEquals("SUCCEEDED", completed.get("status").asText());
        assertEquals("NOT_ANALYZED", completed.get("candidates").get(0).get("dimensions").get(2).get("state").asText());
        mvc.perform(get("/api/v0/trademark-search-tasks").header("X-V0-Requester", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void rejectsMissingClassificationAndImageOrCombinedWithoutCreatingTasks() throws Exception {
        mvc.perform(post("/api/v0/trademark-search-tasks").header("X-V0-Requester", USER).header("Idempotency-Key", "missing")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inputMode\":\"TEXT\",\"name\":\"清源\",\"categories\":[],\"goodsServices\":[],\"dimensions\":[\"TEXT\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String mode : new String[] {"IMAGE", "COMBINED"}) {
            mvc.perform(post("/api/v0/trademark-search-tasks").header("X-V0-Requester", USER).header("Idempotency-Key", mode)
                            .contentType(MediaType.APPLICATION_JSON).content(request(mode, "清源", "[\"TEXT\"]", "{}")))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DIMENSION_UNAVAILABLE"));
        }
    }

    @Test void supportsConflictFailureRetryAndSyntheticCandidateRetrieval() throws Exception {
        var created = create("status", request("TEXT", "qing yuan", "[\"PHONETIC\"]", "{}"));
        assertEquals("SUCCEEDED", awaitTerminal(created.get("taskId").asText()).get("status").asText());
        mvc.perform(post("/api/v0/trademark-search-tasks").header("X-V0-Requester", USER).header("Idempotency-Key", "status")
                        .contentType(MediaType.APPLICATION_JSON).content(request("TEXT", "青源", "[\"TEXT\"]", "{}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        catalog.failNextSearch();
        var failed = create("retry", request("TEXT", "清源", "[\"TEXT\"]", "{}"));
        assertEquals("FAILED", awaitTerminal(failed.get("taskId").asText()).get("status").asText());
        mvc.perform(post("/api/v0/trademark-search-tasks/{id}/retry", failed.get("taskId").asText()).header("X-V0-Requester", USER))
                .andExpect(status().isAccepted());
        assertEquals("SUCCEEDED", awaitTerminal(failed.get("taskId").asText()).get("status").asText());
    }

    private JsonNode create(String key, String body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v0/trademark-search-tasks").header("X-V0-Requester", USER)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    private static String request(String mode, String name, String dimensions, String scope) {
        return "{\"inputMode\":\"%s\",\"name\":\"%s\",\"categories\":[\"3\"],\"goodsServices\":[\"化妆品\"],\"dimensions\":%s,\"scope\":%s}".formatted(mode, name, dimensions, scope);
    }

    private JsonNode awaitTerminal(String taskId) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        JsonNode body;
        do {
            body = mapper.readTree(mvc.perform(get("/api/v0/trademark-search-tasks/{id}", taskId).header("X-V0-Requester", USER))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (body.get("status").asText().matches("SUCCEEDED|PARTIAL|FAILED|CANCELLED")) return body;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not reach terminal state: " + body);
    }
}
