package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface PatentTaskCommandRepository {
    <T> T atomically(Function<Transaction, T> work);
    void recordArrival(TaskCommandReceipt receipt);
    void completeArrival(TaskCommandReceipt receipt);
    List<TaskCommandReceipt> findArrivals(String taskId);

    interface Transaction {
        Optional<PatentTaskSnapshot> findTask(String taskId);
        Optional<PatentTaskSnapshot> findSnapshot(String taskId, long version);
        Optional<StoredCommand> findByCommandIdentity(String taskId, String commandIdentity);
        Optional<StoredCommand> findByIdempotencyKey(String taskId, String idempotencyKey);
        void create(PatentTaskSnapshot snapshot);
        void replace(long expectedVersion, PatentTaskSnapshot snapshot);
        void record(StoredCommand command);
    }

    record StoredCommand(
            String taskId,
            String commandIdentity,
            String idempotencyKey,
            String payloadFingerprint,
            PatentTaskSnapshot result) {}
}
