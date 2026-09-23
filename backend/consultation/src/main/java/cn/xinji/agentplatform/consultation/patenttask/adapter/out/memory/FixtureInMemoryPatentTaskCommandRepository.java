package cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory;

import cn.xinji.agentplatform.consultation.patenttask.application.PatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException;
import cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandReceipt;
import cn.xinji.agentplatform.consultation.patenttask.application.TaskConflict;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Fixture-only store for deterministic component/contract verification. */
public final class FixtureInMemoryPatentTaskCommandRepository implements PatentTaskCommandRepository {
    private final Map<String, PatentTask> tasks = new HashMap<>();
    private final Map<String, StoredCommand> commands = new HashMap<>();
    private final Map<String, StoredCommand> idempotencyKeys = new HashMap<>();
    private final Map<String, TaskCommandReceipt> arrivals = new HashMap<>();

    @Override
    public synchronized <T> T atomically(Function<Transaction, T> work) {
        return work.apply(new Tx());
    }

    @Override public synchronized void recordArrival(TaskCommandReceipt receipt) {
        if (arrivals.putIfAbsent(receipt.receiptId(), receipt) != null) {
            throw new IllegalStateException("receipt identity cannot be overwritten");
        }
    }

    @Override public synchronized void completeArrival(TaskCommandReceipt receipt) {
        if (!arrivals.containsKey(receipt.receiptId())) throw new IllegalStateException("arrival does not exist");
        arrivals.put(receipt.receiptId(), receipt);
    }

    @Override public synchronized List<TaskCommandReceipt> findArrivals(String taskId) {
        return arrivals.values().stream().filter(receipt -> receipt.taskId().equals(taskId))
                .sorted(java.util.Comparator.comparing(TaskCommandReceipt::receivedAt)
                        .thenComparing(TaskCommandReceipt::receiptId)).toList();
    }

    private final class Tx implements Transaction {
        @Override public Optional<PatentTaskSnapshot> findTask(String taskId) {
            return Optional.ofNullable(tasks.get(taskId)).map(PatentTask::current);
        }

        @Override public Optional<PatentTaskSnapshot> findSnapshot(String taskId, long version) {
            return Optional.ofNullable(tasks.get(taskId)).flatMap(task -> task.snapshot(version));
        }

        @Override public Optional<StoredCommand> findByCommandIdentity(String taskId, String commandIdentity) {
            return Optional.ofNullable(commands.get(key(taskId, commandIdentity)));
        }

        @Override public Optional<StoredCommand> findByIdempotencyKey(String taskId, String idempotencyKey) {
            return Optional.ofNullable(idempotencyKeys.get(key(taskId, idempotencyKey)));
        }

        @Override public void create(PatentTaskSnapshot snapshot) {
            if (tasks.putIfAbsent(snapshot.taskId(), new PatentTask(snapshot)) != null) {
                throw new TaskCommandException(TaskCommandException.Code.TASK_ALREADY_EXISTS,
                        "task already exists", tasks.get(snapshot.taskId()).current().version());
            }
        }

        @Override public void replace(long expectedVersion, PatentTaskSnapshot snapshot) {
            PatentTask task = tasks.get(snapshot.taskId());
            PatentTaskSnapshot current = task == null ? null : task.current();
            if (current == null || current.version() != expectedVersion) {
                Long actualVersion = current == null ? null : current.version();
                TaskConflict conflict = new TaskConflict(TaskConflict.Type.CONCURRENT_MODIFICATION,
                        snapshot.taskId(), actualVersion, snapshot.taskId(), expectedVersion, actualVersion,
                        actualVersion == null ? "task:" + snapshot.taskId()
                                : "task:" + snapshot.taskId() + "/snapshot:" + actualVersion);
                throw new TaskCommandException(TaskCommandException.Code.VERSION_CONFLICT,
                        "concurrent modification", actualVersion, conflict);
            }
            task.append(expectedVersion, snapshot);
        }

        @Override public void record(StoredCommand command) {
            commands.put(key(command.taskId(), command.commandIdentity()), command);
            idempotencyKeys.put(key(command.taskId(), command.idempotencyKey()), command);
        }

        private String key(String taskId, String value) { return taskId + "\n" + value; }
    }
}
