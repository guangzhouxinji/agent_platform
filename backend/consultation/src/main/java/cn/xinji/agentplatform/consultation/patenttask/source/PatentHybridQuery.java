package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reciprocal-rank fusion of independently executed lexical and semantic retrieval. */
public final class PatentHybridQuery {
    public static final String METHOD_VERSION = "HYBRID_RRF/v1";
    private static final int RRF_K = 60;

    public record SemanticMatch(String sourceId, String documentId, String documentVersion,
                                String sourceVersion, String rawSha, String locatorRef,
                                double semanticScore, Set<String> semanticFields) {
        public SemanticMatch { semanticFields = Set.copyOf(semanticFields); }
        public SemanticMatch(String sourceId, String documentId, String documentVersion,
                String sourceVersion, String rawSha, String locatorRef, double semanticScore) {
            this(sourceId,documentId,documentVersion,sourceVersion,rawSha,locatorRef,semanticScore,Set.of());
        }
    }
    public record Match(String sourceId, String documentId, String documentVersion,
                        String sourceVersion, String rawSha, String locatorRef,
                        Set<String> matchFields, List<String> matchedTerms,
                        Double lexicalScore, Map<String, Double> fieldScores,
                        Integer lexicalRank, double semanticScore, Integer semanticRank,
                        double fusionScore) { }
    public record Result(String methodVersion, String normalizedQuery, List<Match> matches,
                         int candidateCount, boolean truncated) { }

    public Result fuse(PatentBm25fQuery.Result lexical, List<SemanticMatch> semantic, int maxResults) {
        return fuse(lexical,semantic,maxResults,false);
    }
    public Result fuseFields(PatentBm25fQuery.Result lexical, List<SemanticMatch> semantic, int maxResults) {
        return fuse(lexical,semantic,maxResults,true);
    }
    private Result fuse(PatentBm25fQuery.Result lexical, List<SemanticMatch> semantic,
            int maxResults, boolean fieldMode) {
        if (lexical == null || semantic == null || (!fieldMode && semantic.isEmpty()) || maxResults < 1)
            throw new IllegalArgumentException("Both retrieval paths and a result bound are required");
        Map<String, Mutable> union = new HashMap<>();
        for (int index = 0; index < lexical.matches().size(); index++) {
            var match = lexical.matches().get(index);
            Mutable item = union.computeIfAbsent(key(match.sourceId(), match.documentId(),
                    match.documentVersion()), ignored -> new Mutable(match));
            item.lexical = match;
            item.lexicalRank = index + 1;
        }
        for (int index = 0; index < semantic.size(); index++) {
            var match = semantic.get(index);
            if (fieldMode == match.semanticFields().isEmpty())
                throw new IllegalArgumentException("Semantic field provenance does not match hybrid method");
            if (fieldMode && !Set.of("TITLE","ABSTRACT","CLAIMS","DESCRIPTION").containsAll(match.semanticFields()))
                throw new IllegalArgumentException("Unsupported semantic field provenance");
            if (!Double.isFinite(match.semanticScore()))
                throw new IllegalArgumentException("Finite semantic score required");
            Mutable item = union.computeIfAbsent(key(match.sourceId(), match.documentId(),
                    match.documentVersion()), ignored -> new Mutable(match));
            item.assertSame(match);
            item.semantic = match;
            item.semanticRank = index + 1;
        }
        List<Match> matches = new ArrayList<>();
        for (Mutable item : union.values()) matches.add(item.freeze());
        matches.sort(Comparator.comparingDouble(Match::fusionScore).reversed()
                .thenComparing(Match::lexicalScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Match::sourceId).thenComparing(Match::documentId)
                .thenComparing(Match::documentVersion));
        boolean truncated = matches.size() > maxResults;
        return new Result(fieldMode ? "HYBRID_FIELD_RRF/v1" : METHOD_VERSION, lexical.normalizedQuery(),
                List.copyOf(matches.subList(0, Math.min(maxResults, matches.size()))),
                matches.size(), truncated);
    }

    private static String key(String source, String document, String version) {
        if (blank(source) || blank(document) || blank(version))
            throw new IllegalArgumentException("Versioned semantic document required");
        return source + '\0' + document + '\0' + version;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static final class Mutable {
        private final String sourceId;
        private final String documentId;
        private final String documentVersion;
        private final String sourceVersion;
        private final String rawSha;
        private final String locatorRef;
        private PatentBm25fQuery.Match lexical;
        private SemanticMatch semantic;
        private Integer lexicalRank;
        private Integer semanticRank;

        private Mutable(PatentBm25fQuery.Match match) {
            this(match.sourceId(), match.documentId(), match.documentVersion(), match.sourceVersion(),
                    match.rawSha(), match.locatorRef());
        }
        private Mutable(SemanticMatch match) {
            this(match.sourceId(), match.documentId(), match.documentVersion(), match.sourceVersion(),
                    match.rawSha(), match.locatorRef());
        }
        private Mutable(String sourceId, String documentId, String documentVersion,
                String sourceVersion, String rawSha, String locatorRef) {
            key(sourceId, documentId, documentVersion);
            if (blank(sourceVersion) || rawSha == null || !rawSha.matches("[0-9a-fA-F]{64}")
                    || blank(locatorRef)) throw new IllegalArgumentException("Semantic evidence required");
            this.sourceId = sourceId;
            this.documentId = documentId;
            this.documentVersion = documentVersion;
            this.sourceVersion = sourceVersion;
            this.rawSha = rawSha;
            this.locatorRef = locatorRef;
        }
        private void assertSame(SemanticMatch match) {
            if (!sourceVersion.equals(match.sourceVersion()) || !rawSha.equals(match.rawSha())
                    || !locatorRef.equals(match.locatorRef()))
                throw new IllegalStateException("Lexical and semantic evidence versions differ");
        }
        private Match freeze() {
            double fusion = (lexicalRank == null ? 0 : 1.0 / (RRF_K + lexicalRank))
                    + (semanticRank == null ? 0 : 1.0 / (RRF_K + semanticRank));
            Set<String> fields = lexical == null ? Set.of() : new LinkedHashSet<>(lexical.matchFields());
            if (semantic != null && !semantic.semanticFields().isEmpty()) {
                fields = new LinkedHashSet<>(fields); fields.addAll(semantic.semanticFields());
            }
            return new Match(sourceId, documentId, documentVersion, sourceVersion, rawSha, locatorRef,
                    Set.copyOf(fields), lexical == null ? List.of() : lexical.matchedTerms(),
                    lexical == null ? null : lexical.lexicalScore(),
                    lexical == null ? Map.of() : lexical.fieldScores(), lexicalRank,
                    semantic == null ? Double.NaN : semantic.semanticScore(), semanticRank, fusion);
        }
    }
}
