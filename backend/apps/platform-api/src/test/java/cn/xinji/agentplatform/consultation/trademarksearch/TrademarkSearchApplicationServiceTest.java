package cn.xinji.agentplatform.consultation.trademarksearch;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.memory.InMemoryTrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.synthetic.SyntheticTrademarkCatalogAdapter;
import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkSearchApplicationService;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class TrademarkSearchApplicationServiceTest {
    private static final String USER = "v0-authorized-user";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);

    @Test void createsSyntheticTaskAndReplaysIdenticalIdempotencyRequest() {
        var fixture = fixture(Runnable::run);
        var first = fixture.service.create(USER, "same-key", command());
        var replay = fixture.service.create(USER, "same-key", command());
        assertEquals(TrademarkSearchTask.Status.SUCCEEDED, first.status());
        assertEquals(first.taskId(), replay.taskId());
        assertEquals("SYNTHETIC", first.dataMode());
        assertTrue(first.candidates().stream().anyMatch(value -> value.name().equals("清源")));
        assertTrue(first.candidates().stream().allMatch(value -> value.dimensions().stream()
                .anyMatch(dimension -> dimension.state() == TrademarkSearchCandidate.State.NOT_ANALYZED)));
    }

    @Test void rejectsChangedRequestForExistingIdempotencyKeyAndUnknownRequester() {
        var fixture = fixture(Runnable::run);
        fixture.service.create(USER, "same-key", command());
        var changed = new TrademarkSearchCommand(TrademarkSearchCommand.InputMode.TEXT, "青源", null,
                List.of("3"), List.of("洗涤剂"), List.of(TrademarkSearchCommand.Dimension.TEXT),
                TrademarkSearchCommand.SearchScope.empty(), 1, 20);
        assertThrows(TrademarkSearchApplicationService.IdempotencyConflictException.class,
                () -> fixture.service.create(USER, "same-key", changed));
        assertThrows(TrademarkSearchApplicationService.AccessDeniedException.class,
                () -> fixture.service.create("unknown", "denied", command()));
    }

    @Test void cancellationPreventsQueuedLateResultAndFailureCanRetry() {
        var queued = new QueuedExecutor();
        var fixture = fixture(queued);
        var created = fixture.service.create(USER, "cancel", command());
        assertEquals(TrademarkSearchTask.Status.CANCELLED, fixture.service.cancel(USER, created.taskId()).status());
        queued.runNext();
        assertEquals(TrademarkSearchTask.Status.CANCELLED, fixture.service.read(USER, created.taskId()).status());

        var retryFixture = fixture(Runnable::run);
        retryFixture.catalog.failNextSearch();
        var failed = retryFixture.service.create(USER, "retry", command());
        assertEquals(TrademarkSearchTask.Status.FAILED, failed.status());
        var retried = retryFixture.service.retry(USER, failed.taskId());
        assertEquals(TrademarkSearchTask.Status.SUCCEEDED, retried.status());
        assertEquals(2, retried.attempt());
    }

    @Test void catalogKeepsEmptyStatusUnfilteredAndAppliesExplicitStatus() {
        var catalog = new SyntheticTrademarkCatalogAdapter();
        var all = catalog.search(command());
        assertTrue(all.candidates().stream().anyMatch(value -> value.status().equals("待审")));
        var effective = new TrademarkSearchCommand(TrademarkSearchCommand.InputMode.TEXT, "清源", null,
                List.of("3"), List.of("化妆品"), List.of(TrademarkSearchCommand.Dimension.TEXT),
                new TrademarkSearchCommand.SearchScope(List.of("有效"), List.of(), List.of()), 1, 20);
        assertTrue(catalog.search(effective).candidates().stream().allMatch(value -> value.status().equals("有效")));
    }

    @Test void realisticCatalogHasStableUniqueRecordsAndDiverseBusinessFields() {
        var documents = new SyntheticTrademarkCatalogAdapter().documents();
        assertEquals(120, documents.size());
        assertEquals(120, documents.stream().map(value -> value.sourceId()).distinct().count());
        assertEquals(120, documents.stream().map(value -> value.registrationNumber()).distinct().count());
        assertTrue(documents.stream().flatMap(value -> value.categories().stream()).distinct().count() >= 10);
        assertEquals(java.util.Set.of("有效", "待审", "已失效"), documents.stream().map(value -> value.status()).collect(java.util.stream.Collectors.toSet()));
    }

    private static Fixture fixture(Executor executor) {
        var repository = new InMemoryTrademarkSearchTaskRepository();
        var catalog = new SyntheticTrademarkCatalogAdapter();
        return new Fixture(new TrademarkSearchApplicationService(repository, catalog, USER::equals, executor, CLOCK), catalog);
    }

    private static TrademarkSearchCommand command() {
        return new TrademarkSearchCommand(TrademarkSearchCommand.InputMode.TEXT, "清源", null,
                List.of("3"), List.of("化妆品"),
                List.of(TrademarkSearchCommand.Dimension.TEXT, TrademarkSearchCommand.Dimension.PHONETIC,
                        TrademarkSearchCommand.Dimension.SEMANTIC), TrademarkSearchCommand.SearchScope.empty(), 1, 20);
    }

    private record Fixture(TrademarkSearchApplicationService service, SyntheticTrademarkCatalogAdapter catalog) {}
    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runNext() { tasks.remove().run(); }
    }
}
