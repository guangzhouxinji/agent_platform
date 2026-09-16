package cn.xinji.agentplatform.consultation.patentsearch;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.memory.InMemoryPatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchApplicationService;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
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

    @Test void rejectsSameIdempotencyKeyForDifferentInput() {
        var fixture = fixture(Runnable::run);
        fixture.service.create(USER, "conflict-key", command());
        var changed = new PatentSearchCommand(PatentSearchCommand.InputMode.CORE_FEATURES, "在线测厚",
                "TECHNICAL_REFERENCE", List.of(), PatentSearchCommand.SearchScope.empty());
        assertThrows(PatentSearchApplicationService.IdempotencyConflictException.class,
                () -> fixture.service.create(USER, "conflict-key", changed));
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

    private static Fixture fixture(Executor executor) {
        var repository = new InMemoryPatentSearchTaskRepository();
        var catalog = new SyntheticPatentCatalogAdapter();
        var service = new PatentSearchApplicationService(repository, catalog, USER::equals, executor, CLOCK);
        return new Fixture(service, repository, catalog);
    }

    private static PatentSearchCommand command() {
        return new PatentSearchCommand(PatentSearchCommand.InputMode.TECHNICAL_SOLUTION,
                "水性粘结剂、双区烘干、在线测厚反馈", "TECHNICAL_REFERENCE", List.of(), PatentSearchCommand.SearchScope.empty());
    }

    private record Fixture(PatentSearchApplicationService service, InMemoryPatentSearchTaskRepository repository,
                           SyntheticPatentCatalogAdapter catalog) {}

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runNext() { tasks.remove().run(); }
    }
}
