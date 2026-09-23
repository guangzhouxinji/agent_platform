package cn.xinji.agentplatform.consultation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import cn.xinji.agentplatform.consultation.application.ConsultationApplicationService;
import cn.xinji.agentplatform.consultation.application.ConsultationAnswerGenerator;
import cn.xinji.agentplatform.consultation.application.ConsultationStore;
import cn.xinji.agentplatform.consultation.adapter.out.memory.InMemoryConsultationStore;
import cn.xinji.agentplatform.consultation.adapter.out.synthetic.SyntheticPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.adapter.out.synthetic.V0AuthorizationAdapter;
import org.junit.jupiter.api.Test;

class ConsultationServiceTest {
    private Fixture fixture() {
        var search = new SyntheticPatentSearchAdapter();
        return new Fixture(new ConsultationApplicationService(new InMemoryConsultationStore(), new V0AuthorizationAdapter(), search,
                ConsultationAnswerGenerator.deterministic()), search);
    }
    @Test void normalScenarioReturnsCitedCandidates() {
        var service = fixture().service();
        var result = service.submit("v0-authorized-user", "水性粘结剂、双区烘干、在线测厚反馈", "TECHNICAL_REFERENCE", "k1");
        assertEquals("ANSWER_READY", result.type()); assertEquals(2, result.candidates().size()); assertTrue(result.content().contains("可参考"));
    }
    @Test void genericPatentTitleDoesNotRequireSampleKeywords() {
        var fixture = fixture();
        var result = fixture.service().submit("v0-authorized-user", "基于 CPC 分类的专利相似度判断方法", "TECHNICAL_REFERENCE", "generic-title");
        assertEquals("ANSWER_READY", result.type());
        assertEquals(1, fixture.search().calls());
    }
    @Test void incompleteOrConflictingInputStillSearchesCurrentText() {
        var fixture = fixture();
        var service = fixture.service();
        var result = service.submit("v0-authorized-user", "附件 A 80，附件 B 120", "", "k2");
        assertEquals("ANSWER_READY", result.type()); assertEquals(2, result.candidates().size()); assertEquals(1, fixture.search().calls());
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
        assertEquals("ANSWER_READY", first.type()); assertEquals("ANSWER_READY",completed.type());
        assertEquals(2,completed.answerVersion()); assertEquals(2,completed.candidates().size());
        assertEquals(first.answerId(),service.readAnswer("v0-authorized-user",first.consultationId(),first.answerId()).answerId());
        assertEquals(completed.answerId(),service.supplement("v0-authorized-user",first.consultationId(),"水性粘结剂、双区烘干；用于技术参考。","supplement-answer").answerId());
        assertThrows(ConsultationApplicationService.ConflictException.class,()->service.supplement("v0-authorized-user",first.consultationId(),"另一补充","supplement-answer"));
    }
    @Test void supplementingOnlyMissingPurposeKeepsGenericTechnicalInput() {
        var service = fixture().service();
        var first = service.submit("v0-authorized-user", "基于 CPC 分类的专利相似度判断方法", "", "generic-purpose-initial");
        var completed = service.supplement("v0-authorized-user", first.consultationId(), "用于技术参考。", "generic-purpose-supplement");
        assertEquals("ANSWER_READY", first.type());
        assertEquals("ANSWER_READY", completed.type());
    }
    @Test void blankPurposeDoesNotBlockCurrentInput() {
        var fixture = fixture();
        var first = fixture.service().submit("v0-authorized-user", "基于 CPC 分类的专利相似度判断方法", "", "continue-initial");
        assertEquals("ANSWER_READY", first.type());
        assertEquals(1, first.answerVersion());
        assertEquals(1, fixture.search().calls());
    }
    @Test void conflictDoesNotBlockCurrentInput() {
        var fixture = fixture();
        var first = fixture.service().submit("v0-authorized-user", "附件 A 80，附件 B 120", "", "continue-conflict-initial");
        assertEquals("ANSWER_READY", first.type());
        assertEquals(1, fixture.search().calls());
    }

    private record Fixture(ConsultationApplicationService service, SyntheticPatentSearchAdapter search) {}
}
