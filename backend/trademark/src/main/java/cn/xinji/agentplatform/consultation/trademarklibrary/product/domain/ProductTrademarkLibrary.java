package cn.xinji.agentplatform.consultation.trademarklibrary.product.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ProductTrademarkLibrary {
    private ProductTrademarkLibrary() { }
    public enum Operation { QUERY, DETAIL, HISTORY, EVENT, IMAGE }
    public enum HistoryType { CHANGES, OPPOSITIONS, INVALIDATIONS }
    public enum Capability { UNKNOWN, AVAILABLE, NOT_PROVIDED }
    public enum Field { NUMBER, NAME, IMAGE, APPLICANTS, ADDRESS, CLASSES, DATES, STATUS, SOURCE_REFERENCE, HISTORY }
    public enum Code {
        INVALID_REQUEST(400), UNAUTHENTICATED(401), ACCESS_DENIED(403), RECORD_NOT_FOUND(404),
        CAPABILITY_UNAVAILABLE(503), QUERY_CONDITIONS_REQUIRED(400), SOURCE_AUTH_FAILED(503),
        SOURCE_RATE_LIMITED(429), SOURCE_TIMEOUT(504), SOURCE_UNAVAILABLE(503), DATA_PARSE_ERROR(502);
        private final int status;
        Code(int status) { this.status = status; }
        public int status() { return status; }
    }
    public static final class Failure extends RuntimeException {
        private final Code code;
        public Failure(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
    public static void require(boolean condition, Code code) { if (!condition) throw new Failure(code); }
    public static String identifier(String value) {
        require(value != null && !value.isBlank(), Code.INVALID_REQUEST);
        return value;
    }
    public static void controlledRef(String value) {
        require(value == null || value.matches("[\\p{L}\\p{N}._-]+"), Code.DATA_PARSE_ERROR);
    }
    public record RecordRef(String sourceId, String recordId, String version) {
        public RecordRef { identifier(sourceId); identifier(recordId); identifier(version); }
    }
    public record SourceVersion(String sourceId, String version) {
        public SourceVersion { identifier(sourceId); identifier(version); }
    }
    public record DateValue(String raw, String precision, LocalDate day) {
        public DateValue {
            identifier(raw); identifier(precision);
            require(day == null || "DAY".equals(precision) && day.toString().equals(raw), Code.DATA_PARSE_ERROR);
            require(!"DAY".equals(precision) || day != null, Code.DATA_PARSE_ERROR);
        }
    }
    public record ClassDetail(String code, String name, List<String> items, List<String> similarGroups) {
        public ClassDetail { identifier(code); items = List.copyOf(items); similarGroups = List.copyOf(similarGroups); }
    }
    public record Observation(RecordRef ref, String registrationNumber, String name, String imageRef,
            List<String> applicants, String address, List<ClassDetail> classes, DateValue registered,
            DateValue validUntil, String sourceStatus, String displayStatus, String sourceUpdatedAt, String sourceRef) {
        public Observation {
            require(ref != null, Code.DATA_PARSE_ERROR);
            applicants = List.copyOf(applicants); classes = List.copyOf(classes);
            require(sourceStatus == null || displayStatus != null, Code.DATA_PARSE_ERROR);
            controlledRef(imageRef); controlledRef(sourceRef);
        }
    }
    public record PageRequest(int page, int size, String cursor) {
        public PageRequest {
            require(page >= 1 && Set.of(10, 20, 50).contains(size), Code.INVALID_REQUEST);
            require(cursor == null || !cursor.isBlank() && page == 1, Code.INVALID_REQUEST);
        }
        public long offset() { return ((long) page - 1) * size; }
        public static PageRequest defaults() { return new PageRequest(1, 20, null); }
    }
    public record Query(String registrationNumber, String name, String applicant, Set<String> categories,
            Set<String> statuses, LocalDate registeredFrom, LocalDate registeredTo,
            LocalDate validFrom, LocalDate validTo, PageRequest page, boolean refresh) {
        public Query {
            registrationNumber = registrationNumber == null ? null : registrationNumber.trim();
            name = name == null ? null : name.trim(); applicant = applicant == null ? null : applicant.trim();
            categories = categories == null ? Set.of() : Set.copyOf(categories);
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            require(page != null, Code.INVALID_REQUEST);
            require(registeredFrom == null || registeredTo == null || !registeredFrom.isAfter(registeredTo), Code.INVALID_REQUEST);
            require(validFrom == null || validTo == null || !validFrom.isAfter(validTo), Code.INVALID_REQUEST);
        }
    }
    public record Pagination(PageRequest request, Long reliableTotal, boolean hasNext, String nextCursor) {
        public Pagination {
            require(request != null && (reliableTotal == null || reliableTotal >= 0), Code.DATA_PARSE_ERROR);
            require(request.cursor() == null || reliableTotal == null, Code.DATA_PARSE_ERROR);
            require(nextCursor == null || hasNext && !nextCursor.isBlank(), Code.DATA_PARSE_ERROR);
        }
    }
    public record Provenance(SourceVersion source, String sourceType, String evidenceRef,
            String actualScope, String sourceUpdatedAt, Instant fetchedAt, String cacheState, Instant cachedAt) {
        public Provenance {
            require(source != null && fetchedAt != null, Code.DATA_PARSE_ERROR);
            identifier(sourceType); identifier(evidenceRef); identifier(actualScope);
            require(Set.of("BYPASSED", "MISS", "HIT").contains(cacheState), Code.DATA_PARSE_ERROR);
            require(!"HIT".equals(cacheState) || cachedAt != null, Code.DATA_PARSE_ERROR);
        }
    }
    public record Page(List<Observation> items, Pagination pagination, String sort, List<Provenance> provenance) {
        public Page { items = List.copyOf(items); provenance = List.copyOf(provenance); }
    }
    public record Detail(Observation item, Map<HistoryType, Capability> capabilities, Provenance provenance) {
        public Detail { capabilities = Map.copyOf(capabilities); }
    }
    public record Event(RecordRef parent, String id, String version, HistoryType type, DateValue businessDate,
            DateValue applicationDate, DateValue decisionDate, String number, String matter, String authority,
            String progress, String result, String beforeValue, String afterValue, String sourceRef) {
        public Event { require(parent != null && type != null, Code.DATA_PARSE_ERROR); identifier(id); identifier(version); controlledRef(sourceRef); }
    }
    public record History(Capability capability, List<Event> items, Pagination pagination, Provenance provenance) {
        public History { items = List.copyOf(items); }
    }
    public record Image(RecordRef parent, String mediaType, String digest, byte[] content, Provenance provenance) {
        public Image {
            require(parent != null && content != null, Code.DATA_PARSE_ERROR); identifier(mediaType);
            content = content.clone();
            require(digest != null && digest.matches("[0-9a-fA-F]{64}"), Code.DATA_PARSE_ERROR);
            try {
                require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equalsIgnoreCase(digest), Code.DATA_PARSE_ERROR);
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
        @Override public byte[] content() { return content.clone(); }
    }
    public record SourceScope(SourceVersion source, Set<Field> fields) {
        public SourceScope { require(source != null, Code.ACCESS_DENIED); fields = Set.copyOf(fields); }
    }
    public record AuthorizedScope(String taskId, long taskVersion, String issuer, String actorId, String organization,
            long authorizationEpoch, long sourceEpoch, Instant validUntil, List<SourceScope> sources) {
        public AuthorizedScope {
            identifier(taskId); identifier(issuer); identifier(actorId); identifier(organization);
            require(taskVersion > 0 && authorizationEpoch >= 0 && sourceEpoch >= 0 && validUntil != null, Code.ACCESS_DENIED);
            sources = List.copyOf(sources);
            require(sources.stream().map(s -> s.source().sourceId()).distinct().count() == sources.size(), Code.ACCESS_DENIED);
        }
    }
}
