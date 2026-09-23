package cn.xinji.agentplatform.consultation.patentlibrary.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.DocumentVersion;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.EventResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PatentLibraryService {
    public static final String MODE = "DEVELOPMENT_MIXED";
    public static final String LIMITATION = "开发期合成数据与公开网页测试样本；不代表真实中心记录、法律状态、检索质量或业务验收。";
    private final PatentLibraryPort source;
    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;

    public PatentLibraryService(PatentLibraryPort source, ConsultationAuthorizationPort authorization, Clock clock) {
        this.source = source;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Page query(String actor, Query query, String requestId) {
        requireAccess(actor, requestId);
        validate(query, requestId);
        List<PatentLibraryRecord> matches = source.records().stream()
                .filter(value -> exact(value.applicationNumber(), query.applicationNumber()))
                .filter(value -> exact(value.publicationNumber(), query.publicationNumber()))
                .filter(value -> contains(value.title(), query.title()))
                .filter(value -> blank(query.applicant()) || value.applicants().stream().anyMatch(item -> contains(item, query.applicant())))
                .filter(value -> blank(query.inventor()) || value.inventors().stream().anyMatch(item -> contains(item, query.inventor())))
                .filter(value -> query.patentTypes().isEmpty() || query.patentTypes().contains(value.patentType()))
                .filter(value -> query.statuses().isEmpty() || query.statuses().contains(value.mappedLegalStatus()))
                .filter(value -> classification(value, query.classification(), query.includeDescendants()))
                .filter(value -> inRange(value.applicationDate(), query.applicationFrom(), query.applicationTo()))
                .filter(value -> inRange(value.grantDate(), query.grantFrom(), query.grantTo()))
                .sorted(Comparator.comparing(PatentLibraryRecord::applicationDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PatentLibraryRecord::sourceRecordId))
                .toList();
        int from = Math.min((query.page() - 1) * query.pageSize(), matches.size());
        int to = Math.min(from + query.pageSize(), matches.size());
        return new Page(source.dataMode(), source.sourceVersion(), limitation(), "DISABLED", clock.instant().toString(), requestId,
                query.page(), query.pageSize(), matches.size(), List.copyOf(matches.subList(from, to)));
    }

    public Detail detail(String actor, String sourceId, String requestId) {
        requireAccess(actor, requestId);
        PatentLibraryRecord record = find(sourceId, requestId);
        return new Detail(source.dataMode(), source.sourceVersion(), limitation(), "DISABLED", clock.instant().toString(), requestId, record);
    }

    public Document document(String actor, String sourceId, String versionId, String requestId) {
        requireAccess(actor, requestId);
        PatentLibraryRecord record = find(sourceId, requestId);
        DocumentVersion version = record.versions().stream().filter(value -> value.versionId().equals(versionId)).findFirst()
                .orElseThrow(() -> error("DOCUMENT_NOT_FOUND", "文献版本不存在", HttpStatus.NOT_FOUND, requestId));
        return new Document(source.dataMode(), source.sourceVersion(), limitation(), clock.instant().toString(), requestId,
                record.sourceRecordId(), version);
    }

    public Events events(String actor, String sourceId, String requestId) {
        requireAccess(actor, requestId);
        find(sourceId, requestId);
        return new Events(source.dataMode(), source.sourceVersion(), limitation(), clock.instant().toString(), requestId, source.events(sourceId));
    }

    private PatentLibraryRecord find(String sourceId, String requestId) {
        return source.find(sourceId).orElseThrow(() -> error("RECORD_NOT_FOUND", "专利记录不存在", HttpStatus.NOT_FOUND, requestId));
    }

    private String limitation() {
        return "PUBLIC_LOCAL".equals(source.dataMode())
                ? "本地保存的公开专利开发样本；字段可能不完整，不代表权威全库、当前法律状态、专业检索质量或业务验收。"
                : LIMITATION;
    }

    private void requireAccess(String actor, String requestId) {
        if (!authorization.maySearch(actor)) throw error("ACCESS_DENIED", "当前身份无权查询专利库", HttpStatus.FORBIDDEN, requestId);
    }

    private static void validate(Query query, String requestId) {
        if (query.page() < 1 || !Set.of(10, 20, 50).contains(query.pageSize())) throw error("INVALID_REQUEST", "分页参数无效", HttpStatus.BAD_REQUEST, requestId);
        range(query.applicationFrom(), query.applicationTo(), "申请日期", requestId);
        range(query.grantFrom(), query.grantTo(), "授权日期", requestId);
    }

    private static boolean classification(PatentLibraryRecord value, String query, boolean descendants) {
        if (blank(query)) return true;
        String expected = query.trim().toUpperCase();
        return value.ipcClassifications().stream().anyMatch(item -> descendants ? item.toUpperCase().startsWith(expected) : item.equalsIgnoreCase(expected))
                || value.cpcClassifications().stream().anyMatch(item -> descendants ? item.toUpperCase().startsWith(expected) : item.equalsIgnoreCase(expected))
                || (value.designClassification() != null && (descendants ? value.designClassification().toUpperCase().startsWith(expected) : value.designClassification().equalsIgnoreCase(expected)));
    }

    private static void range(LocalDate from, LocalDate to, String label, String requestId) {
        if (from != null && to != null && from.isAfter(to)) throw error("INVALID_REQUEST", label + "起始日期不能晚于结束日期", HttpStatus.BAD_REQUEST, requestId);
    }
    private static boolean inRange(LocalDate value, LocalDate from, LocalDate to) { return from == null && to == null || value != null && (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to)); }
    private static boolean exact(String value, String query) { return blank(query) || value != null && value.equals(query.trim()); }
    private static boolean contains(String value, String query) { return blank(query) || value != null && value.contains(query.trim()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static LibraryException error(String code, String message, HttpStatus status, String requestId) { return new LibraryException(code, message, status, requestId); }

    public record Query(String applicationNumber, String publicationNumber, String title, String applicant, String inventor,
                        List<String> patentTypes, String classification, boolean includeDescendants, List<String> statuses,
                        LocalDate applicationFrom, LocalDate applicationTo, LocalDate grantFrom, LocalDate grantTo, int page, int pageSize) {
        public Query { patentTypes = patentTypes == null ? List.of() : List.copyOf(patentTypes); statuses = statuses == null ? List.of() : List.copyOf(statuses); }
    }
    public record Page(String dataMode, String sourceVersion, String limitation, String cacheState, String fetchedAt,
                       String requestId, int page, int pageSize, int total, List<PatentLibraryRecord> items) {}
    public record Detail(String dataMode, String sourceVersion, String limitation, String cacheState, String fetchedAt,
                         String requestId, PatentLibraryRecord item) {}
    public record Document(String dataMode, String sourceVersion, String limitation, String fetchedAt, String requestId,
                           String sourceRecordId, DocumentVersion document) {}
    public record Events(String dataMode, String sourceVersion, String limitation, String fetchedAt, String requestId, EventResult events) {}
    public static final class LibraryException extends RuntimeException {
        private final String code; private final HttpStatus status; private final String requestId;
        public LibraryException(String code, String message, HttpStatus status, String requestId) { super(message); this.code=code;this.status=status;this.requestId=requestId; }
        public String code(){return code;} public HttpStatus status(){return status;} public String requestId(){return requestId;}
    }
}
