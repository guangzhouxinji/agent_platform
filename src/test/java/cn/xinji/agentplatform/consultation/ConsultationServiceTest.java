package cn.xinji.agentplatform.consultation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import cn.xinji.agentplatform.consultation.application.ConsultationApplicationService;
import cn.xinji.agentplatform.consultation.application.ConsultationStore;
import cn.xinji.agentplatform.consultation.adapter.out.memory.InMemoryConsultationStore;
import cn.xinji.agentplatform.consultation.adapter.out.synthetic.SyntheticPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.adapter.out.synthetic.V0AuthorizationAdapter;
import org.junit.jupiter.api.Test;

class ConsultationServiceTest {
    private Fixture fixture() {
        var search = new SyntheticPatentSearchAdapter();
        return new Fixture(new ConsultationApplicationService(new InMemoryConsultationStore(), new V0AuthorizationAdapter(), search), search);
    }
    @Test void normalScenarioReturnsCitedCandidates() {
        var service = fixture().service();
        var result = service.submit("v0-authorized-user", "水性粘结剂、双区烘干、在线测厚反馈", "TECHNICAL_REFERENCE", "k1");
        assertEquals("ANSWER_READY", result.type()); assertEquals(2, result.candidates().size()); assertTrue(result.content().contains("不构成"));
    }
    @Test void conflictRequiresClarificationWithoutSearch() {
        var fixture = fixture();
        var service = fixture.service();
        var result = service.submit("v0-authorized-user", "附件 A 80，附件 B 120", "", "k2");
        assertEquals("NEEDS_CLARIFICATION", result.type()); assertEquals(0, result.candidates().size()); assertEquals(0, fixture.search().calls());
    }
    @Test void deniedRequestDoesNotSearchOrExposeCandidates() {
        var fixture = fixture();
        var service = fixture.service();
        var result = service.submit("unknown", "技术方案", "TECHNICAL_REFERENCE", "k3");
        assertEquals("ACCESS_DENIED", result.type()); assertTrue(result.candidates().isEmpty()); assertEquals(0, fixture.search().calls());
    }
    @Test void unavailableSourceIsExcludedOnRefresh() {
        var fixture = fixture();
        var service = fixture.service();
        var result = service.submit("v0-authorized-user", "技术方案", "TECHNICAL_REFERENCE", "k4");
        fixture.search().markSourceUnavailable("V0-SYN-PAT-002");
        var refreshed = service.refresh("v0-authorized-user", result.consultationId());
        assertEquals("PARTIAL_RESULT", refreshed.type()); assertEquals(1, refreshed.candidates().size()); assertEquals("V0-SYN-PAT-001", refreshed.candidates().get(0).sourceId());
    }
    @Test void idempotencyPreventsDuplicateSearchAndRejectsConflict() {
        var fixture = fixture();
        var service = fixture.service();
        service.submit("v0-authorized-user", "技术方案", "TECHNICAL_REFERENCE", "k5");
        service.submit("v0-authorized-user", "技术方案", "TECHNICAL_REFERENCE", "k5");
        assertEquals(1, fixture.search().calls());
        assertThrows(ConsultationApplicationService.ConflictException.class, () -> service.submit("v0-authorized-user", "另一方案", "TECHNICAL_REFERENCE", "k5"));
    }
    @Test void supplementCompletesClarificationWithoutReplacingOriginalAnswer() {
        var service=fixture().service();
        var first=service.submit("v0-authorized-user","帮我查一下涂布工艺相关专利。","","supplement-initial");
        var completed=service.supplement("v0-authorized-user",first.consultationId(),"水性粘结剂、双区烘干；用于技术参考。","supplement-answer");
        assertEquals(List.of("检索目的","具体技术条件"),first.issues()); assertEquals("ANSWER_READY",completed.type());
        assertEquals(2,completed.answerVersion()); assertEquals(2,completed.candidates().size());
        assertEquals(first.answerId(),service.readAnswer("v0-authorized-user",first.consultationId(),first.answerId()).answerId());
        assertEquals(completed.answerId(),service.supplement("v0-authorized-user",first.consultationId(),"水性粘结剂、双区烘干；用于技术参考。","supplement-answer").answerId());
        assertThrows(ConsultationApplicationService.ConflictException.class,()->service.supplement("v0-authorized-user",first.consultationId(),"另一补充","supplement-answer"));
    }

    private record Fixture(ConsultationApplicationService service, SyntheticPatentSearchAdapter search) {}
}
