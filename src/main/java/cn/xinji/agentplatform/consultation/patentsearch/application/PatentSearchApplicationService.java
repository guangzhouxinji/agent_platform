package cn.xinji.agentplatform.consultation.patentsearch.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PatentSearchApplicationService {
    private final PatentSearchTaskRepository repository;
    private final PatentCatalogPort catalog;
    private final ConsultationAuthorizationPort authorization;
    private final Executor executor;
    private final Clock clock;

    public PatentSearchApplicationService(PatentSearchTaskRepository repository, PatentCatalogPort catalog,
                                          ConsultationAuthorizationPort authorization,
                                          @Qualifier("patentSearchExecutor") Executor executor, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.authorization = authorization;
        this.executor = executor;
        this.clock = clock;
    }

    @PostConstruct
    void recoverInterruptedTasks() {
        repository.failInterruptedTasks(clock.instant());
    }

    public synchronized PatentSearchTask create(String requester, String key, PatentSearchCommand command) {
        requireAccess(requester);
        if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key is required");
        String fingerprint = fingerprint(command);
        var existing = repository.findByIdempotency(requester, key);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
            return existing.get();
        }
        Instant now = clock.instant();
        PatentSearchTask task = PatentSearchTask.queued(UUID.randomUUID().toString(), requester, key, fingerprint, command, now);
        repository.save(task);
        schedule(task.taskId());
        return repository.findById(task.taskId()).orElse(task);
    }

    public PatentSearchTask read(String requester, String taskId) {
        requireAccess(requester);
        return owned(requester, taskId);
    }

    public List<PatentSearchTask> history(String requester) {
        requireAccess(requester);
        return repository.findByRequester(requester);
    }

    public PatentSearchTask cancel(String requester, String taskId) {
        requireAccess(requester);
        owned(requester, taskId);
        return repository.update(taskId, current -> {
            if (current.status() == PatentSearchTask.Status.CANCELLED) return current;
            if (current.terminal()) throw new InvalidStateException("completed task cannot be cancelled");
            return current.cancelled(clock.instant());
        });
    }

    public PatentSearchTask retry(String requester, String taskId) {
        requireAccess(requester);
        PatentSearchTask current = owned(requester, taskId);
        if (current.status() != PatentSearchTask.Status.FAILED) throw new InvalidStateException("only failed tasks can be retried");
        PatentSearchTask queued = repository.update(taskId, task -> task.requeued(clock.instant()));
        schedule(taskId);
        return repository.findById(taskId).orElse(queued);
    }

    private void schedule(String taskId) {
        executor.execute(() -> execute(taskId));
    }

    private void execute(String taskId) {
        PatentSearchTask processing = repository.update(taskId, current ->
                current.status() == PatentSearchTask.Status.QUEUED ? current.processing(clock.instant()) : current);
        if (processing.status() != PatentSearchTask.Status.PROCESSING) return;
        try {
            PatentCatalogPort.CatalogResult result = catalog.search(processing.inputSnapshot());
            repository.update(taskId, current -> current.status() == PatentSearchTask.Status.CANCELLED ? current
                    : current.succeeded(result.actualScope(), result.candidates(), result.limitation(), result.partial(), clock.instant()));
        } catch (RuntimeException exception) {
            repository.update(taskId, current -> current.status() == PatentSearchTask.Status.CANCELLED ? current
                    : current.failed("SYNTHETIC_CATALOG_FAILURE", clock.instant()));
        }
    }

    private PatentSearchTask owned(String requester, String taskId) {
        PatentSearchTask task = repository.findById(taskId).orElseThrow(NotFoundException::new);
        if (!task.requester().equals(requester)) throw new NotFoundException();
        return task;
    }

    private void requireAccess(String requester) {
        if (!authorization.maySearch(requester)) throw new AccessDeniedException();
    }

    private static String fingerprint(PatentSearchCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = command.inputMode() + "|" + command.input() + "|" + command.purpose() + "|"
                    + command.confirmedFeatures() + "|" + command.scope();
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static class InvalidRequestException extends RuntimeException { public InvalidRequestException(String message) { super(message); } }
    public static class IdempotencyConflictException extends RuntimeException {}
    public static class InvalidStateException extends RuntimeException { public InvalidStateException(String message) { super(message); } }
    public static class AccessDeniedException extends RuntimeException {}
    public static class NotFoundException extends RuntimeException {}
}
