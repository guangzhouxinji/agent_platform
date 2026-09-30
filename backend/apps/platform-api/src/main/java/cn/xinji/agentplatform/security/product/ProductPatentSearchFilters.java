package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Versioned, source-authorized metadata filters for patent retrieval. */
public record ProductPatentSearchFilters(LocalDate publicationFrom, LocalDate publicationTo,
        List<String> ipcPrefixes, List<String> cpcPrefixes) {
    private static final String VERSION = "PATENT_FILTER/v1";

    static ProductPatentSearchFilters none() {
        return new ProductPatentSearchFilters(null, null, List.of(), List.of());
    }

    static ProductPatentSearchFilters parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return none();
        try {
            Wire value = mapper.readValue(json, Wire.class);
            return validated(date(value.publicationFrom()), date(value.publicationTo()),
                    normalized(value.ipcPrefixes()), normalized(value.cpcPrefixes()));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid patent search filters", invalid);
        }
    }

    static ProductPatentSearchFilters validated(LocalDate from, LocalDate to,
            List<String> ipc, List<String> cpc) {
        if (from != null && to != null && from.isAfter(to))
            throw new IllegalArgumentException("Publication date range is reversed");
        return new ProductPatentSearchFilters(from, to, normalized(ipc), normalized(cpc));
    }

    boolean matches(LocalDate publicationDate, List<String> ipc, List<String> cpc) {
        if ((publicationFrom != null || publicationTo != null) && publicationDate == null) return false;
        if (publicationFrom != null && publicationDate.isBefore(publicationFrom)) return false;
        if (publicationTo != null && publicationDate.isAfter(publicationTo)) return false;
        return matchesPrefix(ipcPrefixes, ipc) && matchesPrefix(cpcPrefixes, cpc);
    }

    Set<String> requiredSourceFields() {
        var fields = new java.util.LinkedHashSet<String>();
        if (publicationFrom != null || publicationTo != null) fields.add("PUBLICATION_DATE");
        if (!ipcPrefixes.isEmpty()) fields.add("IPC");
        if (!cpcPrefixes.isEmpty()) fields.add("CPC");
        return Set.copyOf(fields);
    }

    void requireAuthorized(ProductSourceAvailability availability,
            ProductSourceAvailability.Active title) {
        for (String field : requiredSourceFields()) {
            ProductSourceAvailability.Active metadata = availability.require(title.sourceId(), field, "QUERY");
            if (!title.sourceId().equals(metadata.sourceId())
                    || !title.contractVersion().equals(metadata.contractVersion())
                    || !title.decisionId().equals(metadata.decisionId()) || title.epoch() != metadata.epoch()
                    || title.rowVersion() != metadata.rowVersion())
                throw new IllegalStateException("Filter field escaped current source approval");
        }
    }

    String version() { return VERSION; }

    Map<String, Object> evidence() {
        return Map.of("publicationFrom", publicationFrom == null ? "" : publicationFrom.toString(),
                "publicationTo", publicationTo == null ? "" : publicationTo.toString(),
                "ipcPrefixes", ipcPrefixes, "cpcPrefixes", cpcPrefixes);
    }

    private static boolean matchesPrefix(List<String> expected, List<String> actual) {
        if (expected.isEmpty()) return true;
        return actual != null && actual.stream().map(ProductPatentSearchFilters::fold)
                .anyMatch(value -> expected.stream().anyMatch(value::startsWith));
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) return List.of();
        List<String> result = values.stream().map(ProductPatentSearchFilters::fold).distinct().sorted().toList();
        if (result.size() != values.size() || result.stream().anyMatch(value -> value.isBlank()
                || !value.matches("[A-Z0-9][A-Z0-9 /.-]{0,39}")))
            throw new IllegalArgumentException("Invalid or duplicate classification prefix");
        return result;
    }

    private static String fold(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid publication date", invalid); }
    }

    private record Wire(String publicationFrom, String publicationTo,
                        List<String> ipcPrefixes, List<String> cpcPrefixes) { }
}
