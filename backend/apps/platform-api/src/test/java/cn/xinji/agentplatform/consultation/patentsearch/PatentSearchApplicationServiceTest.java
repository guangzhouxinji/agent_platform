package cn.xinji.agentplatform.consultation.patentsearch;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.memory.InMemoryPatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.consultation.TaskBackedConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchApplicationService;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PatentSearchApplicationServiceTest {
    private static final String USER = "v0-authorized-user";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);

    @Test void createsTraceableSyntheticTaskAndReplaysSameIdempotentRequest() {
        var fixture = fixture(Runnable::run);
        var first = fixture.service.create(USER, "same-key", command());
        var replay = fixture.service.create(USER, "same-key", command());
        assertEquals(PatentSearchTask.Status.SUCCEEDED, first.status());
        assertEquals(first.taskId(), replay.taskId());
        assertEquals("SYNTHETIC", first.dataMode());
        assertEquals("SYNTHETIC-RANK-V1", first.scoringVersion());
        assertFalse(first.candidates().isEmpty());
        assertEquals(1, fixture.repository.findByRequester(USER).size());
    }

    @Test void consultationAdapterReturnsThePersistedTaskSnapshot() {
        var fixture = fixture(Runnable::run);
        var adapter = new TaskBackedConsultationPatentSearchAdapter(fixture.service());

        var result = adapter.search(USER, "consultation-1:answer:1", "水性粘结剂、双区烘干");

        assertTrue(result.candidates().stream().noneMatch(candidate -> candidate.location().startsWith("DM8 PUBLIC_LOCAL / ")));

        assertEquals(PatentSearchTask.Status.SUCCEEDED, fixture.repository.findById(result.retrievalTaskId()).orElseThrow().status());
        assertEquals(1, fixture.repository.findByRequester(USER).size());
        assertFalse(result.candidates().isEmpty());
        assertEquals(fixture.repository.findById(result.retrievalTaskId()).orElseThrow().candidates().getFirst().sourceId(),
                result.candidates().getFirst().sourceId());
        assertTrue(result.candidates().getFirst().evidenceText().contains("匹配片段："));
        assertTrue(result.candidates().getFirst().evidenceText().contains("公开（公告）号："));
    }

    @Test void rejectsSameIdempotencyKeyForDifferentInput() {
        var fixture = fixture(Runnable::run);
        fixture.service.create(USER, "conflict-key", command());
        var changed = new PatentSearchCommand(PatentSearchCommand.InputMode.CORE_FEATURES, "在线测厚",
                "TECHNICAL_REFERENCE", List.of(), PatentSearchCommand.SearchScope.empty());
        assertThrows(PatentSearchApplicationService.IdempotencyConflictException.class,
                () -> fixture.service.create(USER, "conflict-key", changed));
    }

    @Test void rejectsUnimplementedLegalAssistancePurposesBeforeCreatingATask() {
        var fixture = fixture(Runnable::run);

        assertThrows(IllegalArgumentException.class, () -> new PatentSearchCommand(
                PatentSearchCommand.InputMode.CORE_FEATURES, "水性粘结剂", "NOVELTY_PREPARATION",
                List.of(), PatentSearchCommand.SearchScope.empty()));
        assertTrue(fixture.repository.findByRequester(USER).isEmpty());
    }

    @Test void cancellationPreventsQueuedLateResultFromPublishing() {
        var queuedExecutor = new QueuedExecutor();
        var fixture = fixture(queuedExecutor);
        var created = fixture.service.create(USER, "cancel-key", command());
        assertEquals(PatentSearchTask.Status.QUEUED, created.status());
        assertEquals(PatentSearchTask.Status.CANCELLED, fixture.service.cancel(USER, created.taskId()).status());
        queuedExecutor.runNext();
        assertEquals(PatentSearchTask.Status.CANCELLED, fixture.service.read(USER, created.taskId()).status());
        assertTrue(fixture.service.read(USER, created.taskId()).candidates().isEmpty());
    }

    @Test void failedTaskCanRetryWithIncrementedAttempt() {
        var fixture = fixture(Runnable::run);
        fixture.catalog.failNextSearch();
        var failed = fixture.service.create(USER, "retry-key", command());
        assertEquals(PatentSearchTask.Status.FAILED, failed.status());
        var retried = fixture.service.retry(USER, failed.taskId());
        assertEquals(PatentSearchTask.Status.SUCCEEDED, retried.status());
        assertEquals(2, retried.attempt());
    }

    @Test void deniesUnknownRequesterWithoutCreatingTask() {
        var fixture = fixture(Runnable::run);
        assertThrows(PatentSearchApplicationService.AccessDeniedException.class,
                () -> fixture.service.create("unknown", "denied-key", command()));
        assertTrue(fixture.repository.findByRequester("unknown").isEmpty());
    }

    @Test void preparesEditableFeaturesBeforeTaskCreation() {
        var fixture = fixture(Runnable::run);
        var prepared = fixture.service.prepare(USER, command());
        assertEquals(List.of("水性粘结剂", "双区烘干"), prepared.suggestedFeatures());
        assertEquals("deepseek", prepared.modelProvider());
        assertFalse(prepared.traceId().isBlank());
        assertTrue(fixture.repository.findByRequester(USER).isEmpty());
    }

    @Test void concurrentServiceInstancesReuseOneIdempotentTaskAndScheduleOnce() throws Exception {
        var repository = new InMemoryPatentSearchTaskRepository();
        var catalog = new SyntheticPatentCatalogAdapter();
        var executor = new QueuedExecutor();
        var firstService = service(repository, catalog, executor);
        var secondService = service(repository, catalog, executor);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<PatentSearchTask> create = () -> { start.await(); return firstService.create(USER, "shared-key", command()); };
            Future<PatentSearchTask> first = callers.submit(create);
            Future<PatentSearchTask> second = callers.submit(() -> { start.await(); return secondService.create(USER, "shared-key", command()); });
            start.countDown();
            PatentSearchTask left = first.get(5, TimeUnit.SECONDS);
            PatentSearchTask right = second.get(5, TimeUnit.SECONDS);
            assertEquals(left.taskId(), right.taskId());
            assertEquals(1, repository.findByRequester(USER).size());
            assertEquals(1, executor.size());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test void schedulerRejectionBecomesRetryableFailureInsteadOfStuckQueue() {
        var repository = new InMemoryPatentSearchTaskRepository();
        var catalog = new SyntheticPatentCatalogAdapter();
        var service = service(repository, catalog, command -> { throw new RejectedExecutionException("busy"); });

        PatentSearchTask created = service.create(USER, "rejected-key", command());
        assertEquals(PatentSearchTask.Status.FAILED, created.status());
        assertEquals("TASK_SCHEDULING_FAILED", created.errorCode());
        PatentSearchTask retried = service.retry(USER, created.taskId());
        assertEquals(PatentSearchTask.Status.FAILED, retried.status());
        assertEquals(2, retried.attempt());
    }

    @Test void concurrentRetryAdvancesAttemptAndSchedulesOnlyOnce() throws Exception {
        var repository = new InMemoryPatentSearchTaskRepository();
        var catalog = new SyntheticPatentCatalogAdapter();
        PatentSearchTask failed = PatentSearchTask.queued("failed-task", USER, "retry-once", "fingerprint",
                command(), "SYNTHETIC", "SYNTHETIC-RANK-V1", "test", CLOCK.instant())
                .failed("PATENT_CATALOG_FAILURE", CLOCK.instant());
        repository.saveIfAbsent(failed);
        var executor = new QueuedExecutor();
        var firstService = service(repository, catalog, executor);
        var secondService = service(repository, catalog, executor);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Callable<Void>> retries = List.of(
                    () -> { start.await(); retry(firstService, accepted, rejected); return null; },
                    () -> { start.await(); retry(secondService, accepted, rejected); return null; });
            List<Future<Void>> futures = retries.stream().map(callers::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
            assertEquals(2, repository.findById("failed-task").orElseThrow().attempt());
            assertEquals(1, executor.size());
        } finally {
            callers.shutdownNow();
        }
    }

    private static Fixture fixture(Executor executor) {
        var repository = new InMemoryPatentSearchTaskRepository();
        var catalog = new SyntheticPatentCatalogAdapter();
        return new Fixture(service(repository, catalog, executor), repository, catalog);
    }

    private static PatentSearchApplicationService service(InMemoryPatentSearchTaskRepository repository,
                                                           SyntheticPatentCatalogAdapter catalog, Executor executor) {
        PatentAnalysisPort analysis = new PatentAnalysisPort() {
            @Override public ModelStatus status() { return new ModelStatus("deepseek", "test-model", true); }
            @Override public String analyze(String question, List<PatentAssistantSource> sources, String traceId) { return ""; }
            @Override public FeatureExtraction extractFeatures(String input, String purpose, String traceId) {
                return new FeatureExtraction(List.of("水性粘结剂", "双区烘干"));
            }
        };
        return new PatentSearchApplicationService(repository, catalog, USER::equals, analysis, executor, CLOCK);
    }

    private static void retry(PatentSearchApplicationService service, AtomicInteger accepted, AtomicInteger rejected) {
        try { service.retry(USER, "failed-task"); accepted.incrementAndGet(); }
        catch (PatentSearchApplicationService.InvalidStateException expected) { rejected.incrementAndGet(); }
    }

    private static PatentSearchCommand command() {
        return new PatentSearchCommand(PatentSearchCommand.InputMode.TECHNICAL_SOLUTION,
                "水性粘结剂、双区烘干、在线测厚反馈", "TECHNICAL_REFERENCE", List.of(), PatentSearchCommand.SearchScope.empty());
    }

    private record Fixture(PatentSearchApplicationService service, InMemoryPatentSearchTaskRepository repository,
                           SyntheticPatentCatalogAdapter catalog) {}

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable command) { tasks.add(command); }
        void runNext() { tasks.remove().run(); }
        synchronized int size() { return tasks.size(); }
    }
}
