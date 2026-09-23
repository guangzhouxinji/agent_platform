package cn.xinji.agentplatform.consultation.trademarksearch.domain;

import java.util.List;
import java.util.Locale;

public record TrademarkSearchCommand(
        InputMode inputMode,
        String originalName,
        String normalizedName,
        List<String> categories,
        List<String> goodsServices,
        List<Dimension> dimensions,
        SearchScope scope,
        int page,
        int pageSize) {

    public TrademarkSearchCommand {
        if (inputMode == null) throw new IllegalArgumentException("inputMode is required");
        originalName = clean(originalName);
        normalizedName = normalize(originalName);
        categories = values(categories);
        goodsServices = values(goodsServices);
        dimensions = dimensions == null ? List.of() : dimensions.stream().distinct().toList();
        scope = scope == null ? SearchScope.empty() : scope;
        if (inputMode != InputMode.TEXT) throw new UnsupportedOperationException("IMAGE_AND_COMBINED_SEARCH_UNAVAILABLE");
        if (originalName.isBlank()) throw new IllegalArgumentException("name is required for text search");
        if (categories.isEmpty()) throw new IllegalArgumentException("at least one category is required");
        if (goodsServices.isEmpty()) throw new IllegalArgumentException("at least one goods or services item is required");
        if (dimensions.isEmpty()) throw new IllegalArgumentException("at least one search dimension is required");
        if (dimensions.contains(Dimension.IMAGE)) throw new UnsupportedOperationException("IMAGE_SEARCH_UNAVAILABLE");
        if (page < 1) throw new IllegalArgumentException("page must be positive");
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize must be between 1 and 100");
    }

    public enum InputMode { TEXT, IMAGE, COMBINED }
    public enum Dimension { TEXT, PHONETIC, SEMANTIC, IMAGE }

    public record SearchScope(List<String> statuses, List<String> regions, List<String> dataSources) {
        public SearchScope { statuses = values(statuses); regions = values(regions); dataSources = values(dataSources); }
        public static SearchScope empty() { return new SearchScope(List.of(), List.of(), List.of()); }
    }

    private static String normalize(String value) { return clean(value).replaceAll("\\s+", "").toUpperCase(Locale.ROOT); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static List<String> values(List<String> input) {
        return input == null ? List.of() : input.stream().map(TrademarkSearchCommand::clean).filter(value -> !value.isBlank()).distinct().toList();
    }
}
