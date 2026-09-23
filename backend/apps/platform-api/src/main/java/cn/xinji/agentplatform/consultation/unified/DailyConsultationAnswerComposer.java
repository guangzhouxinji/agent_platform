package cn.xinji.agentplatform.consultation.unified;

import cn.xinji.agentplatform.consultation.unified.DailyConsultationResponse.Candidate;
import java.util.List;
import java.util.stream.Collectors;

final class DailyConsultationAnswerComposer {
    String withEvidence(String lead, List<Candidate> candidates) {
        if (candidates.isEmpty()) return lead;
        String evidence = candidates.stream().limit(3).map(this::evidenceLine)
                .collect(Collectors.joining("\n"));
        return lead + "\n\n当前命中依据：\n" + evidence;
    }

    private String evidenceLine(Candidate candidate) {
        String title = candidate.title().isBlank() ? candidate.sourceId() : candidate.title();
        String basis = candidate.matchBasis().isBlank() ? "当前记录已命中" : candidate.matchBasis();
        return "- " + title + "（" + candidate.identifier() + "）：" + basis
                + "【" + candidate.sourceId() + "@" + candidate.version() + "】";
    }
}
