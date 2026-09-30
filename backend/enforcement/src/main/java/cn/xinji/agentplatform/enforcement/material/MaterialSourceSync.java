package cn.xinji.agentplatform.enforcement.material;

import static cn.xinji.agentplatform.enforcement.material.MaterialDigests.hash;

import java.time.Clock;
import java.util.UUID;

/** Processes one externally fetched page; each contiguous position commits with its observation. */
public final class MaterialSourceSync {
    public record Result(String attemptId, String sourceId, String startCursor, String cursor, int processed,
                         String state, String failureCode) { }

    private final MaterialRepository repository;
    private final MaterialPageSource sourcePages;
    private final MaterialProductService materials;
    private final MaterialAccess access;
    private final Clock clock;

    public MaterialSourceSync(MaterialRepository repository, MaterialPageSource sourcePages,
                              MaterialProductService materials, MaterialAccess access, Clock clock) {
        this.repository = repository;
        this.sourcePages = sourcePages;
        this.materials = materials;
        this.access = access;
        this.clock = clock;
    }

    public Result onePage(String actor, String sourceId) {
        var source = repository.source(sourceId).orElseThrow(() -> new IllegalStateException("Source unavailable"));
        current(actor, source);
        String start = repository.syncCursor(sourceId);
        String attempt = UUID.randomUUID().toString();
        repository.beginSyncAttempt(attempt, sourceId, actor, start);
        Result result;
        try {
            result = processPage(actor, sourceId, source, start, attempt);
        } catch (RuntimeException failed) {
            String cursor = repository.syncCursor(sourceId);
            repository.finishSyncAttempt(attempt, cursor, repository.successfulSyncRecords(attempt), "FAILED",
                    failed instanceof MaterialPageSource.Failure sourceFailure
                            ? sourceFailure.code() : "SOURCE_OR_AUTHORITY_UNAVAILABLE", cursor);
            throw failed;
        }
        repository.finishSyncAttempt(attempt, result.cursor(), result.processed(), result.state(),
                result.failureCode(), "PARTIAL_FAILED".equals(result.state()) ? result.cursor() : null);
        return result;
    }

    public java.util.List<MaterialRepository.SyncRecord> records(String actor, String sourceId, String attemptId) {
        var source = repository.source(sourceId).orElseThrow(() -> new IllegalArgumentException("Source not found"));
        access.require(actor, source.organization(), "SOURCE", source.id(), 1, "SYNC", "QUERY");
        access.requireSource(sourceId, "QUERY", source.epoch());
        return repository.syncRecords(attemptId, sourceId);
    }

    private Result processPage(String actor, String sourceId, MaterialWork.Source source, String start,
                               String attempt) {
        var page = sourcePages.fetch(sourceId, start, source.credentialRef());
        if (page == null || page.entries() == null) throw new IllegalStateException("Source page unavailable");
        if (page.failureCode() != null && !page.failureCode().matches("[A-Z][A-Z0-9_]{0,79}"))
            throw new MaterialPageSource.Failure("PAGE_MALFORMED", "Source failure code malformed");
        String cursor = start;
        int processed = 0;
        for (int ordinal = 0; ordinal < page.entries().size(); ordinal++) {
            var entry = page.entries().get(ordinal);
            int position = ordinal;
            if (entry == null || entry.observation() == null || entry.nextCursor() == null
                    || !sourceId.equals(entry.observation().sourceId())) {
                repository.recordSyncRecord(record(attempt, ordinal, entry, cursor, "FAILED",
                        "INVALID_SOURCE_RECORD"));
                return new Result(attempt, sourceId, start, cursor, processed, "PARTIAL_FAILED",
                        "INVALID_SOURCE_RECORD");
            }
            String expected = cursor;
            try {
                current(actor, source);
                var input = entry.observation();
                repository.atSyncCursor(sourceId, expected, entry.nextCursor(), () -> {
                    var receipt = materials.observe(actor,
                            "SYNC:" + hash(sourceId, input.recordId(), input.sourceVersion()), input);
                    repository.recordSyncRecord(record(attempt, position, entry, expected, "COMMITTED", null));
                    return receipt;
                });
            } catch (IllegalArgumentException failed) {
                repository.recordSyncRecord(record(attempt, ordinal, entry, cursor, "FAILED",
                        "INVALID_SOURCE_RECORD"));
                return new Result(attempt, sourceId, start, cursor, processed, "PARTIAL_FAILED",
                        "INVALID_SOURCE_RECORD");
            } catch (RuntimeException failed) {
                repository.recordSyncRecord(record(attempt, ordinal, entry, cursor, "FAILED",
                        failed instanceof MaterialPageSource.Failure sourceFailure ? sourceFailure.code()
                                : "SOURCE_RECORD_FAILURE"));
                throw failed;
            }
            cursor = entry.nextCursor();
            processed++;
        }
        current(actor, source);
        if (page.failureCode() != null)
            return new Result(attempt, sourceId, start, cursor, processed, "PARTIAL_FAILED", page.failureCode());
        return new Result(attempt, sourceId, start, cursor, processed,
                page.complete() ? "SUCCEEDED" : "CONTINUE", null);
    }

    private static MaterialRepository.SyncRecord record(String attempt, int ordinal, MaterialPageSource.Entry entry,
                                                        String before, String state, String code) {
        var observation = entry == null ? null : entry.observation();
        return new MaterialRepository.SyncRecord(attempt, ordinal + 1,
                bounded(observation == null ? null : observation.recordId(), 120),
                bounded(observation == null ? null : observation.sourceVersion(), 120),
                before, bounded(entry == null ? null : entry.nextCursor(), 200), state, code);
    }

    private static String bounded(String value, int limit) {
        return value == null ? null : value.substring(0, Math.min(value.length(), limit));
    }

    private void current(String actor, MaterialWork.Source source) {
        var current = repository.source(source.id()).orElseThrow(() -> new IllegalStateException("Source unavailable"));
        if (!current.active()) throw new MaterialPageSource.Failure("SOURCE_DISABLED", "Source disabled");
        if (!source.equals(current))
            throw new MaterialPageSource.Failure("CONFIGURATION_CHANGED", "Source configuration changed");
        if (!current.expiresAt().isAfter(clock.instant()) || !current.uses().contains("QUERY")
                || !current.uses().contains("SAVE"))
            throw new IllegalStateException("Source use unavailable");
        access.require(actor, source.organization(), "SOURCE", source.id(), 1, "SYNC", "QUERY");
        access.require(actor, source.organization(), "SOURCE", source.id(), 1, "SYNC", "SAVE");
        access.requireSource(source.id(), "QUERY", source.epoch());
        access.requireSource(source.id(), "SAVE", source.epoch());
    }
}
