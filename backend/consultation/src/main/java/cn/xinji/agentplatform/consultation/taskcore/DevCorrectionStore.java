package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.CommandResult;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;

public interface DevCorrectionStore {
    record Source(String manifestId, String manifestSha, String oldSha, String newSha,
                  String utteranceSha, String oldText, String newText, String utterance) { }

    CommandResult correct(Actor human, String taskId, String key, long expectedVersion,
                          long sourceRevision, String newText, String utterance, Source source, Guard guard);
}
