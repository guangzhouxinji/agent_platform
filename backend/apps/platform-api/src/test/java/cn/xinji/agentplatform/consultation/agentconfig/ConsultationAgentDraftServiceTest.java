package cn.xinji.agentplatform.consultation.agentconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService.SaveDraft;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry.RegisteredModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConsultationAgentDraftServiceTest {
    @Test
    void savesAgainstModelsRegisteredAfterTheDraftServiceStarts() {
        AtomicReference<List<RegisteredModel>> registered = new AtomicReference<>(List.of(model("legacy-model")));
        ConsultationAgentModelRegistry registry = registered::get;
        ConsultationAgentDraftService service = new ConsultationAgentDraftService(
                requester -> true, Clock.fixed(Instant.parse("2026-09-20T08:00:00Z"), ZoneOffset.UTC), registry);

        registered.set(List.of(model("saved-model")));

        assertEquals("saved-model", service.catalog("developer").models().getFirst().modelId());
        var saved = service.save("developer", command("saved-model"));
        assertEquals(List.of("saved-model"), saved.modelChain());
    }

    private static SaveDraft command(String modelId) {
        return new SaveDraft(ConsultationAgentDraftService.AGENT_ID, "咨询助手", "开发草稿",
                List.of("PATENT"), List.of(modelId), List.of("TIMEOUT"), List.of("PATENT_INFORMATION"),
                1, "切换为已保存模型");
    }

    private static RegisteredModel model(String id) {
        return new RegisteredModel(id, "test", id, id, true, true, "PUBLIC_LOCAL", "后端配置完整");
    }
}
