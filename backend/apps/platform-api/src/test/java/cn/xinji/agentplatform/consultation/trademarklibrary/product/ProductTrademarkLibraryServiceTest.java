package cn.xinji.agentplatform.consultation.trademarklibrary.product;

import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductTrademarkLibraryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final RecordRef REF = new RecordRef("source", "record", "v1");
    private final StubSource source = new StubSource();
    private final Boundary boundary = new Boundary();
    private final ProductTrademarkLibraryService service = new ProductTrademarkLibraryService(
            boundary, source, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void numberAndAllClassesSurviveQueryAndDetail() {
        Observation record = observation(REF, "00012A", date("2026-01-01"));
        source.page = page(List.of(record), 1L);
        Page actual = service.query(request(Operation.QUERY), query(" 00012A ", Set.of("09")));
        assertEquals("00012A", actual.items().getFirst().registrationNumber());
        assertEquals(2, actual.items().getFirst().classes().size());
        assertEquals(List.of("first", "second"), actual.items().getFirst().classes().getFirst().items());
        source.detail = new Detail(record, capabilities(), provenance("BYPASSED"));
        assertEquals(record, service.detail(request(Operation.DETAIL), false).item());
    }
    @Test void rangePageAndCursorInvalidityRejectBeforeAnySourceRead() {
        failure(Code.INVALID_REQUEST, () -> new PageRequest(0, 20, null));
        failure(Code.INVALID_REQUEST, () -> new PageRequest(1, 51, null));
        failure(Code.INVALID_REQUEST, () -> new PageRequest(2, 20, "cursor"));
        failure(Code.INVALID_REQUEST, () -> new Query(null, null, null, null, null,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null, null, PageRequest.defaults(), false));
        assertEquals(0, source.calls);
        assertEquals(107374182300L, new PageRequest(Integer.MAX_VALUE, 50, null).offset());
    }
    @Test void literalInternalCharactersAreNotChangedAndDatesAreInclusive() {
        Observation record = observation(REF, "00 012A", date("2026-01-01"));
        source.page = page(List.of(record), 1L);
        Query query = new Query(" 00 012A ", null, "甲", Set.of("09"), Set.of("UNMAPPED"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), null, null, PageRequest.defaults(), false);
        assertEquals("00 012A", service.query(request(Operation.QUERY), query).items().getFirst().registrationNumber());
    }
    @Test void wrongPageOrderOrDuplicateRecordsAreRejected() {
        Observation older = observation(REF, "00012A", date("2025-01-01"));
        Observation newer = observation(new RecordRef("source", "second", "v1"), "other", date("2026-01-01"));
        source.page = page(List.of(older, newer), 2L);
        failure(Code.DATA_PARSE_ERROR, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
        source.page = page(List.of(older, older), 2L);
        failure(Code.DATA_PARSE_ERROR, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
    }
    @Test void missingDatesSortLastAndWrongSourceCannotBeSilentlyFiltered() {
        Observation noDate = observation(REF, "00012A", null);
        Observation dated = observation(new RecordRef("source", "second", "v1"), "other", date("2026-01-01"));
        source.page = page(List.of(dated, noDate), 2L);
        assertEquals(2, service.query(request(Operation.QUERY), query(null, Set.of())).items().size());
        source.page = page(List.of(observation(new RecordRef("foreign", "record", "v1"), "other", null)), 1L);
        failure(Code.ACCESS_DENIED, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
    }
    @Test void unreliableTotalRemainsAbsentAndContradictoryTotalIsRejected() {
        source.page = page(List.of(), null);
        assertNull(service.query(request(Operation.QUERY), query(null, Set.of())).pagination().reliableTotal());
        source.page = page(List.of(), 1L);
        failure(Code.DATA_PARSE_ERROR, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
        failure(Code.DATA_PARSE_ERROR, () -> new Pagination(new PageRequest(1, 20, "c"), 0L, false, null));
    }
    @Test void source503AndUnhandledFailureDoNotBecomeNoRecords() {
        source.failure = new Failure(Code.SOURCE_UNAVAILABLE);
        failure(Code.SOURCE_UNAVAILABLE, () -> service.history(request(Operation.HISTORY), HistoryType.OPPOSITIONS, PageRequest.defaults(), false));
        assertEquals(1, source.calls);
        source.failure = new IllegalStateException("credential-secret");
        Failure thrown = failure(Code.SOURCE_UNAVAILABLE, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
        assertFalse(thrown.getMessage().contains("secret"));
        assertNull(thrown.getCause());
    }
    @Test void historyUnknownUnsupportedAndSuccessfulEmptyStayDistinct() {
        for (Capability capability : List.of(Capability.UNKNOWN, Capability.NOT_PROVIDED)) {
            source.history = new History(capability, List.of(), null, provenance("BYPASSED"));
            History actual = service.history(request(Operation.HISTORY), HistoryType.OPPOSITIONS, PageRequest.defaults(), false);
            assertEquals(capability, actual.capability()); assertNull(actual.pagination());
        }
        source.history = new History(Capability.AVAILABLE, List.of(), new Pagination(PageRequest.defaults(), 0L, false, null), provenance("BYPASSED"));
        assertEquals(0L, service.history(request(Operation.HISTORY), HistoryType.OPPOSITIONS, PageRequest.defaults(), false).pagination().reliableTotal());
        source.history = new History(Capability.UNKNOWN, List.of(), new Pagination(PageRequest.defaults(), 0L, false, null), provenance("BYPASSED"));
        failure(Code.DATA_PARSE_ERROR, () -> service.history(request(Operation.HISTORY), HistoryType.OPPOSITIONS, PageRequest.defaults(), false));
    }
    @Test void historyRequiresExactParentAndSingleEventVersion() {
        source.event = event(new RecordRef("source", "other", "v1"), "e1", "e-v1", HistoryType.OPPOSITIONS);
        failure(Code.DATA_PARSE_ERROR, () -> service.event(request(Operation.EVENT), HistoryType.OPPOSITIONS, "e1", "e-v1", false));
        source.event = event(REF, "e1", "e-v2", HistoryType.OPPOSITIONS);
        failure(Code.DATA_PARSE_ERROR, () -> service.event(request(Operation.EVENT), HistoryType.OPPOSITIONS, "e1", "e-v1", false));
        source.event = event(REF, "e1", "e-v1", HistoryType.OPPOSITIONS);
        assertNull(service.event(request(Operation.EVENT), HistoryType.OPPOSITIONS, "e1", "e-v1", false).result());
        assertEquals("PENDING", source.event.progress());
    }
    @Test void deniedFieldAndExactExpiryRejectWithoutLeakingOutput() {
        boundary.scope = scope(Set.of(Field.NUMBER), NOW.plusSeconds(30));
        source.page = page(List.of(observation(REF, "00012A", null)), 1L);
        failure(Code.ACCESS_DENIED, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
        boundary.scope = scope(Set.of(Field.values()), NOW);
        int previous = source.calls;
        failure(Code.ACCESS_DENIED, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
        assertEquals(previous, source.calls);
    }
    @Test void filteringOnDeniedFieldCannotLeakEvenAnEmptyCount() {
        boundary.scope = scope(Set.of(Field.NUMBER), NOW.plusSeconds(30));
        source.page = page(List.of(), 0L);
        Query byName = new Query(null, "sensitive", null, null, null, null, null, null, null, PageRequest.defaults(), false);
        failure(Code.ACCESS_DENIED, () -> service.query(request(Operation.QUERY), byName));
        assertEquals(0, source.calls);
    }
    @Test void boundaryRevocationAfterReadRejectsAndCredentialsAreNotPrinted() {
        boundary.revokedAfterRead = true;
        source.page = page(List.of(), 0L);
        failure(Code.ACCESS_DENIED, () -> service.query(request(Operation.QUERY), query(null, Set.of())));
        assertEquals(1, source.calls);
        assertFalse(request(Operation.QUERY).toString().contains("secret"));
    }
    @Test void refreshRejectsCachedReplyAndImageBytesAreImmutable() {
        source.detail = new Detail(observation(REF, "00012A", null), capabilities(), provenance("HIT"));
        failure(Code.DATA_PARSE_ERROR, () -> service.detail(request(Operation.DETAIL), true));
        byte[] original = { 1, 2, 3 };
        source.image = new Image(REF, "image/png", "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", original, provenance("BYPASSED"));
        original[0] = 9;
        Image actual = service.image(request(Operation.IMAGE), false);
        actual.content()[0] = 8;
        assertArrayEquals(new byte[]{1, 2, 3}, actual.content());
        failure(Code.DATA_PARSE_ERROR, () -> new Image(REF, "image/png", "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                new byte[]{1, 2, 4}, provenance("BYPASSED")));
        failure(Code.DATA_PARSE_ERROR, () -> new Image(REF, "image/png", "digest", new byte[]{1}, provenance("BYPASSED")));
    }
    @Test void recordVersionAndSourceVersionAreIndependentButProvenanceMustBeExact() {
        RecordRef record = new RecordRef("source", "record", "record-r7");
        SourceVersion sourceVersion = new SourceVersion("source", "source-s3");
        boundary.scope = new AuthorizedScope("task", 1, "issuer", "actor", "org", 1, 1, NOW.plusSeconds(30),
                List.of(new SourceScope(sourceVersion, Set.of(Field.values()))));
        Provenance provenance = new Provenance(sourceVersion, "REGISTERED_LOCAL", "evidence", "scope", null, NOW, "BYPASSED", null);
        source.detail = new Detail(observation(record, "00012A", null), capabilities(), provenance);
        var request = new ProductTrademarkReadBoundaryPort.Request("secret", "task", Operation.DETAIL, record, "r");
        assertEquals("record-r7", service.detail(request, false).item().ref().version());
        source.detail = new Detail(source.detail.item(), capabilities(), provenance("BYPASSED"));
        failure(Code.ACCESS_DENIED, () -> service.detail(request, false));
    }
    @Test void imageAndSourceCoordinatesCannotBeCredentialUrls() {
        failure(Code.DATA_PARSE_ERROR, () -> new Observation(REF, "number", null, "https://host/?token=secret", List.of(), null,
                List.of(), null, null, null, null, null, null));
        failure(Code.DATA_PARSE_ERROR, () -> new Observation(REF, "number", null, null, List.of(), null,
                List.of(), null, null, null, null, null, "https://host"));
    }
    @Test void wrongOperationWrongTaskAndFilteredMismatchReject() {
        failure(Code.INVALID_REQUEST, () -> service.detail(request(Operation.QUERY), false));
        ProductTrademarkReadBoundaryPort.Request wrong = new ProductTrademarkReadBoundaryPort.Request("secret", "wrong", Operation.QUERY, null, "r");
        failure(Code.ACCESS_DENIED, () -> service.query(wrong, query(null, Set.of())));
        source.page = page(List.of(observation(REF, "other", null)), 1L);
        failure(Code.DATA_PARSE_ERROR, () -> service.query(request(Operation.QUERY), query("00012A", Set.of())));
    }

    private static Failure failure(Code code, org.junit.jupiter.api.function.Executable executable) {
        Failure failure = assertThrows(Failure.class, executable); assertEquals(code, failure.code()); return failure;
    }
    private static DateValue date(String day) { return new DateValue(day, "DAY", LocalDate.parse(day)); }
    private static Observation observation(RecordRef ref, String number, DateValue date) {
        return new Observation(ref, number, "名称", null, List.of("甲公司", "乙公司"), "地址",
                List.of(new ClassDetail("09", "电子", List.of("first", "second"), List.of("0901")),
                        new ClassDetail("42", "服务", List.of("third"), List.of("4209"))), date, null,
                "SOURCE_UNKNOWN", "UNMAPPED", null, "source-reference");
    }
    private static Event event(RecordRef ref, String id, String version, HistoryType type) {
        return new Event(ref, id, version, type, null, date("2026-01-01"), null,
                "number", "application", "authority", "PENDING", null, null, null, "reference");
    }
    private static Query query(String number, Set<String> categories) {
        return new Query(number, null, null, categories, Set.of(), null, null, null, null, PageRequest.defaults(), false);
    }
    private static Provenance provenance(String cache) {
        return new Provenance(new SourceVersion("source", "v1"), "REGISTERED_LOCAL", "evidence", "scope", null,
                NOW, cache, "HIT".equals(cache) ? NOW.minusSeconds(5) : null);
    }
    private static Page page(List<Observation> items, Long total) {
        return new Page(items, new Pagination(PageRequest.defaults(), total, false, null),
                ProductTrademarkLibraryService.SORT, List.of(provenance("BYPASSED")));
    }
    private static Map<HistoryType, Capability> capabilities() {
        return Map.of(HistoryType.CHANGES, Capability.UNKNOWN, HistoryType.OPPOSITIONS, Capability.AVAILABLE,
                HistoryType.INVALIDATIONS, Capability.NOT_PROVIDED);
    }
    private static AuthorizedScope scope(Set<Field> fields, Instant until) {
        return new AuthorizedScope("task", 1, "issuer", "actor", "org", 1, 1, until,
                List.of(new SourceScope(new SourceVersion("source", "v1"), fields)));
    }
    private static ProductTrademarkReadBoundaryPort.Request request(Operation operation) {
        return new ProductTrademarkReadBoundaryPort.Request("secret", "task", operation,
                operation == Operation.QUERY ? null : REF, "request");
    }
    private static class Boundary implements ProductTrademarkReadBoundaryPort {
        AuthorizedScope scope = scope(Set.of(Field.values()), NOW.plusSeconds(30));
        boolean revokedAfterRead;
        @Override public <T> T read(Request request, Function<AuthorizedScope, T> callback) {
            T result = callback.apply(scope);
            if (revokedAfterRead) throw new Failure(Code.ACCESS_DENIED);
            return result;
        }
    }
    private static class StubSource implements ProductTrademarkLibrarySourcePort {
        Page page; Detail detail; History history; Event event; Image image; RuntimeException failure; int calls;
        private void called() { calls++; if (failure != null) throw failure; }
        @Override public Page query(AuthorizedScope scope, Query query) { called(); return page; }
        @Override public Detail detail(AuthorizedScope scope, RecordRef ref, boolean refresh) { called(); return detail; }
        @Override public History history(AuthorizedScope scope, RecordRef ref, HistoryType type, PageRequest page, boolean refresh) { called(); return history; }
        @Override public Event event(AuthorizedScope scope, RecordRef ref, HistoryType type, String id, String version, boolean refresh) { called(); return event; }
        @Override public Image image(AuthorizedScope scope, RecordRef ref, boolean refresh) { called(); return image; }
    }
}
