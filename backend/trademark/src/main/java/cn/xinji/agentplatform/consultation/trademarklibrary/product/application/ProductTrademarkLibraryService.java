package cn.xinji.agentplatform.consultation.trademarklibrary.product.application;

import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;

/** Source-neutral product service. Runtime authentication, persistence and source adapters are external ports. */
public final class ProductTrademarkLibraryService {
    public static final String SORT = "REGISTRATION_DATE_DESC_NULLS_LAST_SOURCE_RECORD_ASC";
    private static final Comparator<Observation> ORDER = Comparator.comparing(
            (Observation item) -> day(item.registered()), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.ref().sourceId()).thenComparing(item -> item.ref().recordId());
    private final ProductTrademarkReadBoundaryPort boundary;
    private final ProductTrademarkLibrarySourcePort source;
    private final Clock clock;

    public ProductTrademarkLibraryService(ProductTrademarkReadBoundaryPort boundary,
            ProductTrademarkLibrarySourcePort source, Clock clock) {
        this.boundary = Objects.requireNonNull(boundary);
        this.source = Objects.requireNonNull(source); this.clock = Objects.requireNonNull(clock);
    }

    public Page query(ProductTrademarkReadBoundaryPort.Request request, Query query) {
        require(query != null, Code.INVALID_REQUEST);
        return read(request, Operation.QUERY, scope -> {
            for (SourceScope sourceScope : scope.sources()) {
                Set<Field> fields = sourceScope.fields();
                require(blank(query.registrationNumber()) || fields.contains(Field.NUMBER), Code.ACCESS_DENIED);
                require(blank(query.name()) || fields.contains(Field.NAME), Code.ACCESS_DENIED);
                require(blank(query.applicant()) || fields.contains(Field.APPLICANTS), Code.ACCESS_DENIED);
                require(query.categories().isEmpty() || fields.contains(Field.CLASSES), Code.ACCESS_DENIED);
                require(query.statuses().isEmpty() || fields.contains(Field.STATUS), Code.ACCESS_DENIED);
                require(query.registeredFrom() == null && query.registeredTo() == null && query.validFrom() == null
                        && query.validTo() == null || fields.contains(Field.DATES), Code.ACCESS_DENIED);
            }
            Page result = source.query(scope, query);
            require(result != null && SORT.equals(result.sort()), Code.DATA_PARSE_ERROR);
            page(result.pagination(), query.page(), result.items().size());
            Set<RecordRef> unique = new HashSet<>();
            for (Observation item : result.items()) {
                observation(scope, item); require(unique.add(item.ref()), Code.DATA_PARSE_ERROR);
                require(matches(item, query), Code.DATA_PARSE_ERROR);
                require(result.provenance().stream().anyMatch(p -> item.ref().sourceId().equals(p.source().sourceId())), Code.DATA_PARSE_ERROR);
            }
            for (int i = 1; i < result.items().size(); i++) {
                require(ORDER.compare(result.items().get(i - 1), result.items().get(i)) <= 0, Code.DATA_PARSE_ERROR);
            }
            require(!result.provenance().isEmpty(), Code.DATA_PARSE_ERROR);
            for (Provenance provenance : result.provenance()) provenance(scope, provenance, query.refresh());
            return result;
        });
    }

    public Detail detail(ProductTrademarkReadBoundaryPort.Request request, boolean refresh) {
        return read(request, Operation.DETAIL, scope -> {
            Detail result = source.detail(scope, request.ref(), refresh);
            require(result != null && result.item() != null && request.ref().equals(result.item().ref()), Code.DATA_PARSE_ERROR);
            observation(scope, result.item()); provenance(scope, result.provenance(), refresh);
            require(request.ref().sourceId().equals(result.provenance().source().sourceId()), Code.DATA_PARSE_ERROR);
            require(result.capabilities().keySet().equals(Set.of(HistoryType.values())), Code.DATA_PARSE_ERROR);
            return result;
        });
    }

    public History history(ProductTrademarkReadBoundaryPort.Request request, HistoryType type,
            PageRequest pagination, boolean refresh) {
        require(type != null && pagination != null, Code.INVALID_REQUEST);
        return read(request, Operation.HISTORY, scope -> {
            field(scope, request.ref(), Field.HISTORY);
            History result = source.history(scope, request.ref(), type, pagination, refresh);
            require(result != null && result.capability() != null, Code.DATA_PARSE_ERROR);
            provenance(scope, result.provenance(), refresh);
            require(request.ref().sourceId().equals(result.provenance().source().sourceId()), Code.DATA_PARSE_ERROR);
            if (result.capability() != Capability.AVAILABLE) {
                require(result.items().isEmpty() && result.pagination() == null, Code.DATA_PARSE_ERROR);
            } else {
                page(result.pagination(), pagination, result.items().size());
                Set<String> unique = new HashSet<>();
                for (Event event : result.items()) {
                    event(scope, request.ref(), type, event);
                    require(unique.add(event.id()), Code.DATA_PARSE_ERROR);
                }
                Comparator<Event> order = Comparator.comparing((Event event) -> day(event.businessDate()),
                        Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Event::id);
                for (int i = 1; i < result.items().size(); i++) {
                    require(type != HistoryType.CHANGES || order.compare(result.items().get(i - 1), result.items().get(i)) <= 0,
                            Code.DATA_PARSE_ERROR);
                }
            }
            return result;
        });
    }

    public Event event(ProductTrademarkReadBoundaryPort.Request request, HistoryType type,
            String id, String version, boolean refresh) {
        require(type != null, Code.INVALID_REQUEST); identifier(id); identifier(version);
        return read(request, Operation.EVENT, scope -> {
            field(scope, request.ref(), Field.HISTORY);
            Event result = source.event(scope, request.ref(), type, id, version, refresh);
            event(scope, request.ref(), type, result);
            require(id.equals(result.id()) && version.equals(result.version()), Code.DATA_PARSE_ERROR);
            return result;
        });
    }

    public Image image(ProductTrademarkReadBoundaryPort.Request request, boolean refresh) {
        return read(request, Operation.IMAGE, scope -> {
            field(scope, request.ref(), Field.IMAGE);
            Image result = source.image(scope, request.ref(), refresh);
            require(result != null && request.ref().equals(result.parent()), Code.DATA_PARSE_ERROR);
            provenance(scope, result.provenance(), refresh);
            require(request.ref().sourceId().equals(result.provenance().source().sourceId()), Code.DATA_PARSE_ERROR);
            return result;
        });
    }

    private <T> T read(ProductTrademarkReadBoundaryPort.Request request, Operation operation,
            Function<AuthorizedScope, T> work) {
        require(request != null && request.operation() == operation, Code.INVALID_REQUEST);
        return boundary.read(request, scope -> {
            live(scope, request);
            if (request.ref() != null) sourceScope(scope, request.ref().sourceId());
            T result;
            try { result = work.apply(scope); }
            catch (Failure failure) { throw failure; }
            catch (RuntimeException malformedOrUnavailable) { throw new Failure(Code.SOURCE_UNAVAILABLE); }
            live(scope, request);
            return result;
        });
    }
    private void live(AuthorizedScope scope, ProductTrademarkReadBoundaryPort.Request request) {
        require(scope != null && request.taskId().equals(scope.taskId()) && scope.validUntil().isAfter(clock.instant()), Code.ACCESS_DENIED);
    }
    private static SourceScope sourceScope(AuthorizedScope scope, String sourceId) {
        return scope.sources().stream().filter(value -> value.source().sourceId().equals(sourceId)).findFirst()
                .orElseThrow(() -> new Failure(Code.ACCESS_DENIED));
    }
    private static void field(AuthorizedScope scope, RecordRef ref, Field field) {
        require(sourceScope(scope, ref.sourceId()).fields().contains(field), Code.ACCESS_DENIED);
    }
    private static void observation(AuthorizedScope scope, Observation item) {
        require(item != null, Code.DATA_PARSE_ERROR);
        sourceScope(scope, item.ref().sourceId());
        if (item.registrationNumber() != null) field(scope, item.ref(), Field.NUMBER);
        if (item.name() != null) field(scope, item.ref(), Field.NAME);
        if (item.imageRef() != null) field(scope, item.ref(), Field.IMAGE);
        if (!item.applicants().isEmpty()) field(scope, item.ref(), Field.APPLICANTS);
        if (item.address() != null) field(scope, item.ref(), Field.ADDRESS);
        if (!item.classes().isEmpty()) field(scope, item.ref(), Field.CLASSES);
        if (item.registered() != null || item.validUntil() != null) field(scope, item.ref(), Field.DATES);
        if (item.sourceStatus() != null || item.displayStatus() != null) field(scope, item.ref(), Field.STATUS);
        if (item.sourceRef() != null) field(scope, item.ref(), Field.SOURCE_REFERENCE);
    }
    private static void event(AuthorizedScope scope, RecordRef ref, HistoryType type, Event event) {
        require(event != null && ref.equals(event.parent()) && type == event.type(), Code.DATA_PARSE_ERROR);
        field(scope, ref, Field.HISTORY);
        if (event.sourceRef() != null) field(scope, ref, Field.SOURCE_REFERENCE);
    }
    private static void provenance(AuthorizedScope scope, Provenance provenance, boolean refresh) {
        require(provenance != null, Code.DATA_PARSE_ERROR);
        require(sourceScope(scope, provenance.source().sourceId()).source().equals(provenance.source()), Code.ACCESS_DENIED);
        require(!refresh || !"HIT".equals(provenance.cacheState()), Code.DATA_PARSE_ERROR);
    }
    private static void page(Pagination result, PageRequest request, int count) {
        require(result != null && request.equals(result.request()) && count <= request.size(), Code.DATA_PARSE_ERROR);
        if (result.reliableTotal() != null) {
            long remaining = Math.max(0, result.reliableTotal() - request.offset());
            require(count == Math.min(request.size(), remaining)
                    && result.hasNext() == (remaining > request.size()), Code.DATA_PARSE_ERROR);
        }
    }
    private static boolean matches(Observation item, Query query) {
        return (blank(query.registrationNumber()) || query.registrationNumber().equals(item.registrationNumber()))
                && contains(item.name(), query.name())
                && (blank(query.applicant()) || item.applicants().stream().anyMatch(a -> contains(a, query.applicant())))
                && (query.categories().isEmpty() || item.classes().stream().anyMatch(c -> query.categories().contains(c.code())))
                && (query.statuses().isEmpty() || query.statuses().contains(item.displayStatus()))
                && range(day(item.registered()), query.registeredFrom(), query.registeredTo())
                && range(day(item.validUntil()), query.validFrom(), query.validTo());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean contains(String value, String needle) { return blank(needle) || value != null && value.contains(needle); }
    private static LocalDate day(DateValue value) { return value == null ? null : value.day(); }
    private static boolean range(LocalDate value, LocalDate from, LocalDate to) {
        return from == null && to == null || value != null && (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
    }
}
