package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.util.List;

public interface DevComparisonInputPreviewStore {
    record Preview(String taskId, long taskRevision, long featureRevision, String featureManifestId,
                   String targetText, List<DevFeatureConfirmationStore.Candidate> features,
                   long focusSequence, String setId, int ordinal, String recordId, String sourceVersion,
                   String contentDigest, String locator, String claimText, String analysisPurpose,
                   String technicalRelation, List<String> missing) { }

    interface Sources {
        DevFeatureConfirmationStore.Source features(Actor human, Instant now);
        DevTextResultStore.Source claim(Actor human, String recordId, String sourceVersion,
                                        String digest, Instant now, boolean comparisonUse);
    }

    Preview read(Actor human, String taskId, long featureRevision, long focusSequence,
                 Guard guard, Sources sources);
}
