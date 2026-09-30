package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.util.List;

public interface DevHumanContextStore {
    record Message(long revision, String kind, String text) { }
    record Context(String taskId, long requestSequence, String requestId, long inputRevision,
                   List<Message> messages, String requestText) { }
    interface RequestSource {
        DevHumanRequestStore.Source permitted(Actor human, Instant now);
    }
    Context read(Actor human, String taskId, long sequence, Guard guard, RequestSource requestSource);
}
