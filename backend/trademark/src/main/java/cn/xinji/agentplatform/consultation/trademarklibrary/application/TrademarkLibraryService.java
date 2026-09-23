package cn.xinji.agentplatform.consultation.trademarklibrary.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord.HistoryResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TrademarkLibraryService {
    public static final String MODE = "DEVELOPMENT_MIXED";
    public static final String LIMITATION = "开发期合成记录与公开公报测试样本；不代表真实中心记录、当前法律状态、近似判断或业务验收。";
    private final TrademarkLibraryPort source;
    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;

    public TrademarkLibraryService(TrademarkLibraryPort source, ConsultationAuthorizationPort authorization, Clock clock) {
        this.source = source;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Page query(String actor, Query query, String requestId) {
        requireAccess(actor, requestId);
        validate(query, requestId);
        List<TrademarkLibraryRecord> matches = source.records().stream()
                .filter(value -> blank(query.registrationNumber()) || value.registrationNumber() != null && value.registrationNumber().equals(query.registrationNumber().trim()))
                .filter(value -> contains(value.name(), query.name()))
                .filter(value -> blank(query.applicant()) || value.applicants().stream().anyMatch(item -> contains(item, query.applicant())))
                .filter(value -> query.categories().isEmpty() || value.classes().stream().map(TrademarkLibraryRecord.ClassDetail::code).anyMatch(query.categories()::contains))
                .filter(value -> query.statuses().isEmpty() || query.statuses().contains(value.displayStatus()))
                .filter(value -> inRange(value.registrationDate(), query.registeredFrom(), query.registeredTo()))
                .filter(value -> inRange(value.validUntil(), query.validFrom(), query.validTo()))
                .sorted(Comparator.comparing(TrademarkLibraryRecord::registrationDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TrademarkLibraryRecord::sourceRecordId))
                .toList();
        int from = Math.min((query.page() - 1) * query.pageSize(), matches.size());
        int to = Math.min(from + query.pageSize(), matches.size());
        return new Page(source.dataMode(), source.sourceVersion(), limitation(), "DISABLED", clock.instant().toString(), requestId,
                query.page(), query.pageSize(), matches.size(), List.copyOf(matches.subList(from, to)));
    }

    public Detail detail(String actor, String sourceId, String requestId) {
        requireAccess(actor, requestId);
        TrademarkLibraryRecord record = source.find(sourceId).orElseThrow(() -> error("RECORD_NOT_FOUND", "商标记录不存在", HttpStatus.NOT_FOUND, requestId));
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (String type : List.of("changes", "oppositions", "invalidations")) capabilities.put(type, source.history(sourceId, type).state());
        return new Detail(source.dataMode(), source.sourceVersion(), limitation(), "DISABLED", clock.instant().toString(), requestId, record, capabilities);
    }

    public History history(String actor, String sourceId, String type, String requestId) {
        requireAccess(actor, requestId);
        if (!Set.of("changes", "oppositions", "invalidations").contains(type)) throw error("INVALID_REQUEST", "未知历史类型", HttpStatus.BAD_REQUEST, requestId);
        if (source.find(sourceId).isEmpty()) throw error("RECORD_NOT_FOUND", "商标记录不存在", HttpStatus.NOT_FOUND, requestId);
        return new History(source.dataMode(), source.sourceVersion(), limitation(), clock.instant().toString(), requestId, source.history(sourceId, type));
    }

    private void requireAccess(String actor, String requestId) {
        if (!authorization.maySearch(actor)) throw error("ACCESS_DENIED", "当前身份无权查询商标库", HttpStatus.FORBIDDEN, requestId);
    }

    private String limitation() {
        return "PUBLIC_LOCAL".equals(source.dataMode())
                ? "本地保存的 USPTO 派生公开商标开发样本；不是中国商标库，不代表权威状态、近似判断、注册成功率或业务验收。"
                : LIMITATION;
    }

    private static void validate(Query query, String requestId) {
        if (query.page() < 1 || !Set.of(10, 20, 50).contains(query.pageSize())) throw error("INVALID_REQUEST", "分页参数无效", HttpStatus.BAD_REQUEST, requestId);
        range(query.registeredFrom(), query.registeredTo(), "注册日期", requestId);
        range(query.validFrom(), query.validTo(), "有效期", requestId);
    }

    private static void range(LocalDate from, LocalDate to, String label, String requestId) {
        if (from != null && to != null && from.isAfter(to)) throw error("INVALID_REQUEST", label + "起始日期不能晚于结束日期", HttpStatus.BAD_REQUEST, requestId);
    }

    private static boolean inRange(LocalDate value, LocalDate from, LocalDate to) {
        return from == null && to == null || value != null && (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
    }

    private static boolean contains(String value, String query) { return blank(query) || (value != null && value.contains(query.trim())); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static LibraryException error(String code, String message, HttpStatus status, String requestId) { return new LibraryException(code, message, status, requestId); }

    public record Query(String registrationNumber, String name, String applicant, List<String> categories, List<String> statuses,
                        LocalDate registeredFrom, LocalDate registeredTo, LocalDate validFrom, LocalDate validTo, int page, int pageSize) {
        public Query { categories = categories == null ? List.of() : List.copyOf(categories); statuses = statuses == null ? List.of() : List.copyOf(statuses); }
    }
    public record Page(String dataMode, String sourceVersion, String limitation, String cacheState, String fetchedAt,
                       String requestId, int page, int pageSize, int total, List<TrademarkLibraryRecord> items) {}
    public record Detail(String dataMode, String sourceVersion, String limitation, String cacheState, String fetchedAt,
                         String requestId, TrademarkLibraryRecord item, Map<String, String> historyCapabilities) {}
    public record History(String dataMode, String sourceVersion, String limitation, String fetchedAt, String requestId, HistoryResult history) {}
    public static final class LibraryException extends RuntimeException {
        private final String code; private final HttpStatus status; private final String requestId;
        public LibraryException(String code, String message, HttpStatus status, String requestId) { super(message); this.code=code;this.status=status;this.requestId=requestId; }
        public String code(){return code;} public HttpStatus status(){return status;} public String requestId(){return requestId;}
    }
}
