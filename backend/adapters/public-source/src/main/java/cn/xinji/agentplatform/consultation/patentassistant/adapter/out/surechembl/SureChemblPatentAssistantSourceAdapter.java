package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.surechembl;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentSourceException;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SureChemblPatentAssistantSourceAdapter implements PatentAssistantSourcePort {
    private static final int MAX_RESULTS = 10;
    private static final int MAX_RESPONSE_BYTES = 5_000_000;
    private static final Set<String> STOP_WORDS = Set.of("一种", "技术", "方案", "相关", "专利", "方法", "system", "method", "patent");
    private final String apiBaseUrl;
    private final Duration requestTimeout;
    private final int configuredLimit;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public SureChemblPatentAssistantSourceAdapter(String apiBaseUrl, int connectTimeoutMs, int requestTimeoutMs,
                                                   int configuredLimit, ObjectMapper mapper) {
        this(apiBaseUrl, requestTimeoutMs, configuredLimit, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(bounded(connectTimeoutMs, 100, 60_000))).build());
    }

    SureChemblPatentAssistantSourceAdapter(String apiBaseUrl, int requestTimeoutMs, int configuredLimit,
                                            ObjectMapper mapper, HttpClient client) {
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.requestTimeout = Duration.ofMillis(bounded(requestTimeoutMs, 100, 60_000));
        this.configuredLimit = bounded(configuredLimit, 1, MAX_RESULTS);
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public SearchPage search(SearchRequest searchRequest) {
        List<String> terms = buildTerms(searchRequest.question());
        int page = Math.max(1, searchRequest.page());
        int limit = Math.min(Math.min(Math.max(searchRequest.pageSize(), 1), configuredLimit), MAX_RESULTS);
        if (terms.isEmpty()) return new SearchPage(List.of(), List.of(), page, limit, 0L, false);
        String textQuery = String.join(" OR ", terms.stream().map(this::quoted).toList());
        String query = searchRequest.country() == null ? textQuery : "(" + textQuery + ") AND pnctry:" + searchRequest.country();
        JsonNode payload = request(searchUri(query, page, limit), "POST");
        JsonNode documents = payload.path("data").path("results").path("documents");
        if (!"OK".equals(payload.path("status").asText()) || !documents.isArray()) {
            throw new PatentSourceException(PatentSourceException.Code.MALFORMED_RESPONSE);
        }

        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (JsonNode document : documents) {
            Candidate candidate = normalize(document, terms);
            if (candidate != null) unique.putIfAbsent(candidate.publicationNumber(), candidate);
            if (unique.size() >= limit) break;
        }
        List<Candidate> candidates = new ArrayList<>(unique.values());
        List<PatentAssistantSource> sources = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            sources.add(new PatentAssistantSource("P" + (index + 1), candidate.sourceRecordId(),
                    candidate.publicationNumber(), null, candidate.title(), candidate.applicant(),
                    candidate.publicationDate(), candidate.sourceUrl(),
                    candidate.excerpt() == null ? "SOURCE_NOT_PROVIDED" : "PARTIAL",
                    candidate.matchBasis(), candidate.excerpt()));
        }
        JsonNode totalNode = payload.path("data").path("results").path("total_hits");
        Long totalHits = totalNode.canConvertToLong() ? totalNode.longValue() : null;
        boolean hasNext = totalHits == null ? documents.size() >= limit : (long) page * limit < totalHits;
        return new SearchPage(terms, sources, page, limit, totalHits, hasNext);
    }

    @Override
    public SourceStatus status() {
        return new SourceStatus("surechembl", "PUBLIC_DYNAMIC", "surechembl-live-v1", null, false,
                "NON_CENTER_PUBLIC_SOURCE", "INTERNAL_DEVELOPMENT_PILOT_ONLY");
    }

    @Override
    public SourceDetail detail(String publicationNumber) {
        JsonNode payload = request(detailUri(publicationNumber), "GET");
        if (!"OK".equals(payload.path("status").asText())) {
            throw new PatentSourceException(PatentSourceException.Code.MALFORMED_RESPONSE);
        }
        List<String> content = new ArrayList<>();
        collectContent(payload.path("data").path("patentDocument").path("abstracts"), content);
        String excerpt = truncate(String.join(" ", content), 1_500);
        return new SourceDetail(publicationNumber, excerpt.isBlank() ? "SOURCE_NOT_PROVIDED" : "PARTIAL",
                excerpt.isBlank() ? null : excerpt);
    }

    private JsonNode request(URI uri, String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Origin", "https://www.surechembl.org");
        HttpRequest request = "POST".equals(method) ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 429) throw new PatentSourceException(PatentSourceException.Code.RATE_LIMITED);
                if (response.statusCode() == 401) throw new PatentSourceException(PatentSourceException.Code.UPSTREAM_UNAUTHENTICATED);
                if (response.statusCode() == 403) throw new PatentSourceException(PatentSourceException.Code.UPSTREAM_FORBIDDEN);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new PatentSourceException(PatentSourceException.Code.UPSTREAM_FAILURE);
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new PatentSourceException(PatentSourceException.Code.MALFORMED_RESPONSE);
                try {
                    return mapper.readTree(bytes);
                } catch (IOException exception) {
                    throw new PatentSourceException(PatentSourceException.Code.MALFORMED_RESPONSE, exception);
                }
            }
        } catch (HttpTimeoutException exception) {
            throw new PatentSourceException(PatentSourceException.Code.TIMEOUT, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PatentSourceException(PatentSourceException.Code.UPSTREAM_FAILURE, exception);
        } catch (IOException exception) {
            throw new PatentSourceException(PatentSourceException.Code.UPSTREAM_FAILURE, exception);
        } catch (RuntimeException exception) {
            if (exception instanceof PatentSourceException sourceException) throw sourceException;
            throw new PatentSourceException(PatentSourceException.Code.UPSTREAM_FAILURE, exception);
        }
    }

    private Candidate normalize(JsonNode document, List<String> terms) {
        JsonNode metadata = document.path("metadata");
        String sourceRecordId = text(document, "docId");
        String publicationNumber = firstNonBlank(text(metadata, "pn"), sourceRecordId);
        if (publicationNumber == null) return null;
        String title = preferredTitle(metadata.path("titles"));
        if (title == null) title = publicationNumber;
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        String matches = terms.stream().map(this::unquoted).filter(term -> normalizedTitle.contains(term.toLowerCase(Locale.ROOT)))
                .limit(6).reduce((left, right) -> left + "、" + right).orElse("公开源动态检索命中");
        return new Candidate(sourceRecordId == null ? publicationNumber : sourceRecordId, publicationNumber, title,
                text(document, "pa"), normalizeDate(text(metadata, "pd")),
                "https://www.surechembl.org/patent/" + pathEncode(sourceRecordId == null ? publicationNumber : sourceRecordId),
                matches, null);
    }

    private static List<String> buildTerms(String question) {
        String normalized = String.valueOf(question == null ? "" : question).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ").trim();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String segment : normalized.split("\\s+")) {
            if (segment.isBlank()) continue;
            if (segment.matches("[a-z0-9]+")) {
                if (segment.length() >= 2 && !STOP_WORDS.contains(segment)) terms.add(segment);
                continue;
            }
            if (segment.length() <= 8 && segment.length() >= 2 && !STOP_WORDS.contains(segment)) terms.add(segment);
            int size = Math.min(4, segment.length());
            for (int start = 0; start + size <= segment.length() && terms.size() < 8; start += Math.max(1, size - 1)) {
                String term = segment.substring(start, start + size);
                if (!STOP_WORDS.contains(term)) terms.add(term);
            }
        }
        return terms.stream().limit(8).toList();
    }

    private URI searchUri(String query, int page, int limit) {
        return URI.create(apiBaseUrl + "/search/content?query=" + encode(query) + "&page=" + page + "&itemsPerPage=" + limit);
    }

    private URI detailUri(String publicationNumber) {
        return URI.create(apiBaseUrl + "/document/" + pathEncode(publicationNumber) + "/contents");
    }

    private String quoted(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private String unquoted(String value) { return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String pathEncode(String value) { return encode(value).replace("+", "%20"); }
    private static String stripTrailingSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private static int bounded(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(value, maximum)); }
    private static String text(JsonNode node, String field) { String value = node.path(field).asText("").trim(); return value.isEmpty() ? null : value; }
    private static String firstNonBlank(String first, String second) { return first != null ? first : second; }
    private static String normalizeDate(String value) {
        return value != null && value.matches("\\d{8}")
                ? value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6) : null;
    }
    private static String truncate(String value, int maximum) { return value.length() <= maximum ? value : value.substring(0, maximum) + "..."; }

    private static String preferredTitle(JsonNode titleGroups) {
        if (!titleGroups.isArray()) return null;
        String first = null;
        String english = null;
        for (JsonNode group : titleGroups) {
            JsonNode titles = group.path("titles");
            if (!titles.isArray() || titles.isEmpty()) continue;
            String title = titles.get(0).asText("").trim();
            if (title.isEmpty()) continue;
            if (first == null) first = title;
            String language = group.path("lang").asText();
            if ("zh".equals(language)) return title;
            if ("en".equals(language)) english = title;
        }
        return english == null ? first : english;
    }

    private static void collectContent(JsonNode node, List<String> values) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collectContent(child, values));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("content".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    String text = entry.getValue().asText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    if (!text.isBlank()) values.add(text);
                } else if (!"annotations".equals(entry.getKey())) {
                    collectContent(entry.getValue(), values);
                }
            });
        }
    }

    private static final class Candidate {
        private final String sourceRecordId;
        private final String publicationNumber;
        private final String title;
        private final String applicant;
        private final String publicationDate;
        private final String sourceUrl;
        private final String matchBasis;
        private volatile String excerpt;

        private Candidate(String sourceRecordId, String publicationNumber, String title, String applicant,
                          String publicationDate, String sourceUrl, String matchBasis, String excerpt) {
            this.sourceRecordId = sourceRecordId;
            this.publicationNumber = publicationNumber;
            this.title = title;
            this.applicant = applicant;
            this.publicationDate = publicationDate;
            this.sourceUrl = sourceUrl;
            this.matchBasis = matchBasis;
            this.excerpt = excerpt;
        }
        String sourceRecordId() { return sourceRecordId; }
        String publicationNumber() { return publicationNumber; }
        String title() { return title; }
        String applicant() { return applicant; }
        String publicationDate() { return publicationDate; }
        String sourceUrl() { return sourceUrl; }
        String matchBasis() { return matchBasis; }
        String excerpt() { return excerpt; }
        void excerpt(String value) { excerpt = value; }
    }
}
