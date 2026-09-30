package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.Evidence;

public interface JobPackEvidenceStore {
    void register(String packageId, Evidence evidence, String actor);
    boolean contains(String packageId, Evidence evidence);
    java.util.Optional<Evidence> find(String packageId, String evidenceRef);
}
