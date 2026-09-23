package cn.xinji.agentplatform.enforcement.application;

import cn.xinji.agentplatform.enforcement.domain.EnforcementSnapshot;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EnforcementSources {
    private EnforcementSources() {}

    static List<SourceLocator> all(EnforcementSnapshot snapshot) {
        List<SourceLocator> result = new ArrayList<>();
        result.add(snapshot.workItem().clueRef());
        snapshot.clueAnalyses().forEach(value -> {
            result.add(value.inputClueRef());
            value.subjectCandidates().forEach(item -> result.add(item.source()));
            value.suspectedFacts().forEach(item -> result.add(item.source()));
            value.timeCandidates().forEach(item -> result.add(item.source()));
            value.placeCandidates().forEach(item -> result.add(item.source()));
            value.evidenceMentions().forEach(item -> result.add(item.source()));
        });
        snapshot.evidenceReferences().forEach(value -> result.add(value.source()));
        snapshot.relationSets().forEach(value -> {
            result.add(value.ruleRef());
            value.relations().forEach(item -> result.add(item.source()));
            value.timeline().forEach(item -> result.add(item.source()));
        });
        snapshot.auxiliaryAnalyses().forEach(value -> {
            result.add(value.ruleRef());
            if (value.rightStatusRef() != null) result.add(value.rightStatusRef());
            value.items().forEach(item -> result.addAll(item.sources()));
        });
        return result.stream().filter(Objects::nonNull).distinct().toList();
    }

    static String kind(EnforcementSnapshot snapshot, String sourceLinkId) {
        return snapshot.evidenceReferences().stream().anyMatch(value -> value.source().sourceId().equals(sourceLinkId))
                ? "EVIDENCE" : "CLUE";
    }
}
