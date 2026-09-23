package cn.xinji.agentplatform.consultation.patentsearch.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantService.ModelUnavailableException;
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
    private final PatentAnalysisPort analysis;
    private final Executor executor;
    private final Clock clock;

    public PatentSearchApplicationService(PatentSearchTaskRepository repository,
                                          @Qualifier("patentSearchCatalog") PatentCatalogPort catalog,
                                          ConsultationAuthorizationPort authorization, PatentAnalysisPort analysis,
                                          @Qualifier("patentSearchExecutor") Executor executor, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.authorization = authorization;
        this.analysis = analysis;
        this.executor = executor;
        this.clock = clock;
    }

    @PostConstruct
    void recoverInterruptedTasks() {
        repository.failInterruptedTasks(clock.instant());
    }

    public PatentSearchTask create(String requester, String key, PatentSearchCommand command) {
        return create(requester, key, command, true);
    }

    /**
     * Consultation V0 needs a completed immutable task before composing a
     * sourced answer. It deliberately reuses the ordinary task repository and
     * catalog, rather than creating a second consultation-only search path.
     */
    public PatentSearchTask createForConsultation(String requester, String key, PatentSearchCommand command) {
        PatentSearchTask task = create(requester, key, command, false);
        if (!task.terminal()) execute(task.taskId());
        return repository.findById(task.taskId()).orElse(task);
    }

    public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        return catalog.isSourceAvailable(sourceId, sourceVersion);
    }

    private PatentSearchTask create(String requester, String key, PatentSearchCommand command, boolean scheduleTask) {
        requireAccess(requester);
        if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key is required");
        String fingerprint = fingerprint(command);
        var existing = repository.findByIdempotency(requester, key);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
            return existing.get();
        }
        Instant now = clock.instant();
        PatentCatalogPort.CatalogDescriptor descriptor = catalog.descriptor();
        PatentSearchTask task = PatentSearchTask.queued(UUID.randomUUID().toString(), requester, key, fingerprint,
                command, descriptor.dataMode(), descriptor.scoringVersion(), descriptor.limitation(), now);
        PatentSearchTask persisted = repository.saveIfAbsent(task);
        if (!persisted.requestFingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
        if (scheduleTask && persisted.taskId().equals(task.taskId())) schedule(task.taskId());
        return repository.findById(persisted.taskId()).orElse(persisted);
    }

    public PatentSearchTask read(String requester, String taskId) {
        requireAccess(requester);
        return owned(requester, taskId);
    }

    public List<PatentSearchTask> history(String requester) {
        requireAccess(requester);
        return repository.findByRequester(requester);
    }

    public Preparation prepare(String requester, PatentSearchCommand command) {
        requireAccess(requester);
        String traceId = UUID.randomUUID().toString();
        PatentAnalysisPort.ModelStatus status = analysis.status();
        if (!status.configured()) throw new ModelUnavailableException("MODEL_NOT_CONFIGURED");
        PatentAnalysisPort.FeatureExtraction extraction = analysis.extractFeatures(
                command.input(), command.purpose(), traceId);
        return new Preparation(command.inputMode().name(), command.input(), command.purpose(), command.scope(),
                extraction.features(), status.provider(), status.model(), traceId,
                "特征由模型从本次用户输入中提取；必须由用户编辑或确认后才能创建检索任务。");
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
        owned(requester, taskId);
        PatentSearchTask queued;
        try {
            queued = repository.requeueFailed(taskId, clock.instant());
        } catch (PatentSearchTaskRepository.StateConflictException exception) {
            throw new InvalidStateException("only failed tasks can be retried");
        }
        schedule(taskId);
        return repository.findById(taskId).orElse(queued);
    }

    private void schedule(String taskId) {
        try {
            executor.execute(() -> execute(taskId));
        } catch (RuntimeException exception) {
            repository.update(taskId, current -> current.status() == PatentSearchTask.Status.QUEUED
                    ? current.failed("TASK_SCHEDULING_FAILED", clock.instant()) : current);
        }
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
                    : current.failed("PATENT_CATALOG_FAILURE_" + rootFailureName(exception), clock.instant()));
        }
    }

    private static String rootFailureName(RuntimeException exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName();
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
    public record Preparation(String inputMode, String input, String purpose,
                              PatentSearchCommand.SearchScope scope, List<String> suggestedFeatures,
                              String modelProvider, String modelName, String traceId, String limitation) {}
}
