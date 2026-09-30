package cn.xinji.agentplatform.enforcement.jobpack;

import java.util.List;
import java.util.Optional;

public interface RealBusinessCollectionTaskStore {
    boolean seed(RealBusinessCollectionTask.Seed seed);
    List<RealBusinessCollectionTask> list();
    Optional<RealBusinessCollectionTask> find(String taskId);
    RealBusinessCollectionTask claim(String taskId, String actor, String organization,
                                     String requestKey, String fingerprint);
}
