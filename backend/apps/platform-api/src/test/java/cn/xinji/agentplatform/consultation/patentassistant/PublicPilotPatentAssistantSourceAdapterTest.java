package cn.xinji.agentplatform.consultation.patentassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.patentassistant.adapter.out.publicpilot.PublicPilotPatentAssistantSourceAdapter;
import cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot.PublicPilotPatentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PublicPilotPatentAssistantSourceAdapterTest {
    private final PublicPilotPatentAssistantSourceAdapter source =
            new PublicPilotPatentAssistantSourceAdapter(new PublicPilotPatentLoader(new ObjectMapper().findAndRegisterModules()));

    @Test
    void returnsStableCitedPublicCandidates() {
        var result = source.search(new cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort.SearchRequest(
                "基于知识图谱进行专利检索并重排候选", 1, 5, null));
        assertFalse(result.sources().isEmpty());
        assertEquals("P1", result.sources().getFirst().citation());
        assertTrue(result.sources().getFirst().sourceUrl().startsWith("https://"));
        assertFalse(result.searchTerms().isEmpty());
        assertEquals("PUBLIC_PILOT", source.status().dataMode());
    }

    @Test
    void returnsNoCandidateForUnrelatedInput() {
        assertTrue(source.search(new cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort.SearchRequest(
                "量子星际航行推进器", 1, 5, null)).sources().isEmpty());
    }
}
