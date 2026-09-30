package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.*;

import java.util.Optional;
import java.util.function.Supplier;

public interface JobPackStore {
    Optional<Snapshot> latest(String id);
    Optional<Snapshot> snapshot(String id, long revision);
    Receipt command(String key, String fingerprint, String actor, String operation, Supplier<Receipt> action);
    void add(Snapshot snapshot);
}
