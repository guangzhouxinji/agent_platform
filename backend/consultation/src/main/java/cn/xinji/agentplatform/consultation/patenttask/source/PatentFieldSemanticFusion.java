package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.*;

/** Field-local rank fusion with immutable per-field model and original-location evidence. */
public final class PatentFieldSemanticFusion {
    public static final String METHOD_VERSION = "FIELD_SEMANTIC_RRF_K60/v1";
    private static final Set<String> FIELDS = Set.of("TITLE","ABSTRACT","CLAIMS","DESCRIPTION");
    private static final int BOUND = 1000;
    public record DocumentEvidence(PatentLexicalQuery.Document document, Map<String,String> fieldLocators) {
        public DocumentEvidence {
            if (document == null || document.fields() == null) throw new IllegalArgumentException("Original document required");
            document = new PatentLexicalQuery.Document(document.sourceId(),document.documentId(),document.documentVersion(),
                    document.sourceVersion(),document.rawSha(),document.locatorRef(),immutable(document.fields()));
            fieldLocators = immutable(fieldLocators);
        }
    }
    public record FieldScore(String sourceId, String documentId, String documentVersion, String sourceVersion,
                             String rawSha, String field, String locatorRef, String modelVersion, double cosine) { }
    public record Candidate(PatentHybridQuery.SemanticMatch match, Map<String,Double> fieldScores,
                            Map<String,Integer> fieldRanks, Map<String,String> fieldLocators) {
        public Candidate {
            fieldScores = immutable(fieldScores); fieldRanks = immutable(fieldRanks); fieldLocators = immutable(fieldLocators);
        }
    }
    public record Result(String methodVersion, Set<String> fields, Map<String,String> modelVersions,
                         List<Candidate> candidates, int candidateCount, boolean truncated) {
        public Result {
            fields = Set.copyOf(fields); modelVersions = immutable(modelVersions); candidates = List.copyOf(candidates);
        }
    }

    public Result fuse(List<DocumentEvidence> pool, Set<String> selected, Map<String,String> modelVersions,
            Map<String,List<FieldScore>> fieldResults, int bound) {
        if (pool == null || selected == null || selected.isEmpty()
                || selected.stream().anyMatch(field -> field == null || !FIELDS.contains(field))
                || modelVersions == null || !modelVersions.keySet().equals(selected)
                || modelVersions.values().stream().anyMatch(PatentFieldSemanticFusion::blank)
                || fieldResults == null || !fieldResults.keySet().equals(selected) || bound < 1 || bound > BOUND)
            throw new IllegalArgumentException("Complete selected field responses and models required");
        var fields = new TreeSet<>(selected); var models = immutable(modelVersions);
        Map<String,DocumentEvidence> documents = validateDocuments(pool,fields);
        Map<String,Mutable> union = new HashMap<>();
        for (String field : fields) {
            List<FieldScore> scores = fieldResults.get(field);
            if (scores == null || scores.size() > BOUND)
                throw new IllegalArgumentException("Successful bounded field response required");
            Set<String> seen = new HashSet<>();
            for (FieldScore score : scores) {
                if (score == null || !field.equals(score.field()) || !Double.isFinite(score.cosine())
                        || Math.abs(score.cosine()) > 1.000001 || !models.get(field).equals(score.modelVersion()))
                    throw new IllegalArgumentException("Finite model-bound field cosine required");
                String key = key(score.sourceId(),score.documentId(),score.documentVersion());
                DocumentEvidence original = documents.get(key);
                if (!seen.add(key) || original == null || !original.document().sourceVersion().equals(score.sourceVersion())
                        || !original.document().rawSha().equals(score.rawSha())
                        || !original.fieldLocators().get(field).equals(score.locatorRef()))
                    throw new IllegalStateException("Field semantic evidence differs from admitted original");
            }
            List<FieldScore> ranked = new ArrayList<>(scores);
            ranked.sort(Comparator.comparingDouble(FieldScore::cosine).reversed().thenComparing(FieldScore::sourceId)
                    .thenComparing(FieldScore::documentId).thenComparing(FieldScore::documentVersion));
            for (int i = 0; i < ranked.size(); i++) {
                FieldScore score = ranked.get(i); String key = key(score.sourceId(),score.documentId(),score.documentVersion());
                Mutable item = union.computeIfAbsent(key,ignored -> new Mutable(documents.get(key)));
                item.scores.put(field,score.cosine()); item.ranks.put(field,i+1); item.locators.put(field,score.locatorRef());
            }
        }
        List<Candidate> candidates = union.values().stream().map(item -> item.freeze(fields.size())).sorted(
                Comparator.comparingDouble((Candidate value) -> value.match().semanticScore()).reversed()
                        .thenComparing(value -> value.match().sourceId()).thenComparing(value -> value.match().documentId())
                        .thenComparing(value -> value.match().documentVersion())).toList();
        return new Result(METHOD_VERSION,fields,models,candidates.subList(0,Math.min(bound,candidates.size())),
                candidates.size(),candidates.size()>bound);
    }

    public static Map<String,DocumentEvidence> validateDocuments(List<DocumentEvidence> pool, Set<String> fields) {
        if (pool == null || fields == null || fields.isEmpty()
                || fields.stream().anyMatch(field -> field == null || !FIELDS.contains(field)))
            throw new IllegalArgumentException("Complete selected original pool required");
        Map<String,DocumentEvidence> result = new HashMap<>();
        for (DocumentEvidence evidence : pool) {
            if (evidence == null || evidence.document() == null) throw new IllegalArgumentException("Original document required");
            var doc = evidence.document(); String key = key(doc.sourceId(),doc.documentId(),doc.documentVersion());
            if (blank(doc.sourceVersion()) || doc.rawSha() == null || !doc.rawSha().matches("[0-9a-fA-F]{64}")
                    || blank(doc.locatorRef()) || doc.fields() == null || !doc.fields().keySet().equals(fields)
                    || doc.fields().values().stream().anyMatch(PatentFieldSemanticFusion::blank)
                    || !evidence.fieldLocators().keySet().equals(fields)
                    || evidence.fieldLocators().values().stream().anyMatch(PatentFieldSemanticFusion::blank)
                    || !evidence.fieldLocators().containsValue(doc.locatorRef()) || result.put(key,evidence) != null)
                throw new IllegalArgumentException("Exact selected document fields and original locations required");
            int marker = doc.locatorRef().indexOf('#');
            if (marker < 1 || evidence.fieldLocators().values().stream().anyMatch(
                    locator -> !locator.startsWith(doc.locatorRef().substring(0,marker) + "#")))
                throw new IllegalArgumentException("Field locations must share the original file");
        }
        return Map.copyOf(result);
    }
    private static final class Mutable {
        private final DocumentEvidence original;
        private final Map<String,Double> scores = new TreeMap<>();
        private final Map<String,Integer> ranks = new TreeMap<>();
        private final Map<String,String> locators = new TreeMap<>();
        Mutable(DocumentEvidence original) { this.original = original; }
        Candidate freeze(int fieldCount) {
            var doc = original.document();
            double score = ranks.values().stream().mapToDouble(rank -> 1.0/(60+rank)).sum()/fieldCount;
            return new Candidate(new PatentHybridQuery.SemanticMatch(doc.sourceId(),doc.documentId(),doc.documentVersion(),
                    doc.sourceVersion(),doc.rawSha(),doc.locatorRef(),score,scores.keySet()),scores,ranks,locators);
        }
    }
    private static String key(String source, String id, String version) {
        if (blank(source) || blank(id) || blank(version) || source.indexOf('\0') >= 0
                || id.indexOf('\0') >= 0 || version.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Exact versioned document identity required");
        return source+'\0'+id+'\0'+version;
    }
    private static <V> Map<String,V> immutable(Map<String,V> values) {
        if (values == null) throw new IllegalArgumentException("Field evidence map required");
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
