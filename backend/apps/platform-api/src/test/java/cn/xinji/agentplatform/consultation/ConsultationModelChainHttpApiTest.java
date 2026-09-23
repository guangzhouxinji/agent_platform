package cn.xinji.agentplatform.consultation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.consultation.application.ConsultationAnswerModelPort;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "consultation-agent.runtime.enabled=true",
        "consultation-agent.runtime.model-chain=test:primary",
        "consultation-agent.models.deepseek-chat.model-id=test:primary",
        "consultation-agent.models.deepseek-chat.provider=test-provider",
        "consultation-agent.models.deepseek-chat.model-name=test-primary",
        "consultation-agent.models.deepseek-chat.display-name=Test Primary",
        "consultation-agent.models.deepseek-chat.endpoint=http://127.0.0.1:9/chat",
        "consultation-agent.models.deepseek-chat.api-key=test-secret",
        "consultation-agent.models.deepseek-chat.enabled=true",
        "consultation-agent.models.deepseek-chat.protocol=OPENAI_CHAT_COMPLETIONS",
        "consultation-agent.models.deepseek-chat.data-channel=PUBLIC_LOCAL"})
@AutoConfigureMockMvc
class ConsultationModelChainHttpApiTest {
    @org.springframework.beans.factory.annotation.Autowired MockMvc mvc;

    @Test void returnsModelAssistedAnswerWithRetrievedSourceOverHttp() throws Exception {
        mvc.perform(post("/api/v0/consultations")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "model-chain-http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"水性粘结剂技术\",\"purpose\":\"TECHNICAL_REFERENCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeType").value("ANSWER_READY"))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("模型组织的技术参考答复")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("KB-HTTP-001@v7")))
                .andExpect(jsonPath("$.candidates[0].sourceId").value("KB-HTTP-001"))
                .andExpect(jsonPath("$.sourceLinks[0].sourceId").value("KB-HTTP-001"));
    }

    @TestConfiguration
    static class ControlledAdapters {
        @Bean @Primary PatentSearchPort publicLocalSearch() {
            return new PatentSearchPort() {
                @Override public SearchResult search(String confirmedInput) {
                    var candidate = new ConsultationCandidate("KB-HTTP-001", "v7",
                            "DM8 PUBLIC_LOCAL / title+claims", "标题命中：水性粘结剂");
                    return new SearchResult(SearchResult.Type.SUCCESS, List.of(candidate), "", "DM8 PUBLIC_LOCAL 测试范围");
                }
                @Override public boolean isSourceAvailable(String sourceId, String sourceVersion) { return true; }
            };
        }

        @Bean @Primary ConsultationAnswerModelPort controlledModel() {
            return (modelId, question, candidates, traceId) -> new ConsultationAnswerModelPort.AnswerDraft(
                    "模型组织的技术参考答复。", List.of(candidates.getFirst().sourceId()));
        }
    }
}
