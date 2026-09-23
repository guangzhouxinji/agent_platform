package cn.xinji.agentplatform.consultation.agentconfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "consultation-agent.models.deepseek-chat.model-id=deepseek:deepseek-chat",
        "consultation-agent.models.deepseek-chat.provider=deepseek",
        "consultation-agent.models.deepseek-chat.model-name=deepseek-chat",
        "consultation-agent.models.deepseek-chat.display-name=DeepSeek Chat",
        "consultation-agent.models.deepseek-chat.endpoint=https://api.deepseek.com/chat/completions",
        "consultation-agent.models.deepseek-chat.api-key=chat-secret",
        "consultation-agent.models.deepseek-reasoner.model-id=deepseek:deepseek-reasoner",
        "consultation-agent.models.deepseek-reasoner.provider=deepseek",
        "consultation-agent.models.deepseek-reasoner.model-name=deepseek-reasoner",
        "consultation-agent.models.deepseek-reasoner.display-name=DeepSeek Reasoner",
        "consultation-agent.models.deepseek-reasoner.endpoint=https://api.deepseek.com/chat/completions",
        "consultation-agent.models.deepseek-reasoner.api-key=reasoner-secret",
        "consultation-agent.models.qwen-max.model-id=qwen:qwen-max",
        "consultation-agent.models.qwen-max.provider=qwen",
        "consultation-agent.models.qwen-max.model-name=qwen-max",
        "consultation-agent.models.qwen-max.display-name=Qwen Max",
        "consultation-agent.models.qwen-max.endpoint=https://example.invalid/chat/completions"})
@AutoConfigureMockMvc
class ConsultationAgentDraftHttpApiTest {
    @Autowired MockMvc mvc;

    @Test void catalogExposesApprovedReferencesWithoutSecrets() throws Exception {
        mvc.perform(get("/api/v0/consultation-agent-draft/catalog").header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.models.length()").value(3))
                .andExpect(jsonPath("$.models[0].modelId").value("deepseek:deepseek-chat"))
                .andExpect(jsonPath("$.models[1].modelId").value("deepseek:deepseek-reasoner"))
                .andExpect(jsonPath("$.models[0].configured").value(true))
                .andExpect(jsonPath("$.models[1].configured").value(true))
                .andExpect(jsonPath("$.models[2].configured").value(false))
                .andExpect(jsonPath("$.models[2].availability").value("服务端密钥尚未配置"))
                .andExpect(jsonPath("$.models[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.models[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$.switchableFailures.length()").value(3));
    }

    @Test void savesOrderedModelChainAndRejectsStaleVersion() throws Exception {
        String body = validBody(1);
        mvc.perform(put("/api/v0/consultation-agent-draft").header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.modelChain[0]").value("deepseek:deepseek-chat"))
                .andExpect(jsonPath("$.modelChain[1]").value("deepseek:deepseek-reasoner"));
        mvc.perform(put("/api/v0/consultation-agent-draft").header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test void rejectsUnknownModelsDuplicatesAndUnsafeSwitchConditions() throws Exception {
        String body = validBody(1).replace("deepseek:deepseek-reasoner", "deepseek:deepseek-chat")
                .replace("UPSTREAM_UNAVAILABLE", "ACCESS_DENIED");
        mvc.perform(put("/api/v0/consultation-agent-draft").header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DRAFT"))
                .andExpect(jsonPath("$.issues[?(@.code == 'DUPLICATE_MODEL')]").exists())
                .andExpect(jsonPath("$.issues[?(@.code == 'UNSAFE_SWITCH_CONDITION')]").exists());
    }

    @Test void validationRemainsNotPublishReadyAndUnauthorizedAccessIsRejected() throws Exception {
        mvc.perform(post("/api/v0/consultation-agent-draft/validate").header("X-V0-Requester", "v0-authorized-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publishReady").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("FORMAL_DEPENDENCIES_PENDING"));
        mvc.perform(get("/api/v0/consultation-agent-draft").header("X-V0-Requester", "unknown"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private static String validBody(long version) {
        return """
                {"agentId":"CONSULTATION-AGENT-001","name":"知识产权咨询助手","description":"内部开发草稿",
                 "scenarioIds":["PATENT","TRADEMARK"],
                 "modelChain":["deepseek:deepseek-chat","deepseek:deepseek-reasoner"],
                 "switchOn":["TIMEOUT","RATE_LIMITED","UPSTREAM_UNAVAILABLE"],
                 "toolIds":["PATENT_INFORMATION","TRADEMARK_INFORMATION"],
                 "expectedVersion":%d,"changeNote":"配置主备模型"}
                """.formatted(version);
    }
}
