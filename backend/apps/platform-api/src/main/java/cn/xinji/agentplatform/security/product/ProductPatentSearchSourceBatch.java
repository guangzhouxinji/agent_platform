package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRetrievalConfidence;
import java.util.*;

/** Only content errors are source-local; approval and current-authority errors propagate. */
final class ProductPatentSearchSourceBatch {
    record Outcome(String sourceId, String contractVersion, String decisionId, long sourceEpoch,
                   String status, String errorCode) { }
    record Batch(List<ProductSourceAvailability.Active> snapshots,
                 List<PatentLexicalQuery.Document> documents, Map<String,String> failures,
                 List<cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence> fieldEvidence) {
        Batch {
            snapshots = List.copyOf(snapshots); documents = List.copyOf(documents);
            failures = Map.copyOf(failures); fieldEvidence = List.copyOf(fieldEvidence);
        }
        Batch(List<ProductSourceAvailability.Active> snapshots,List<PatentLexicalQuery.Document> documents,Map<String,String> failures) {
            this(snapshots,documents,failures,List.of());
        }
        List<String> successfulSources() {
            return snapshots.stream().map(ProductSourceAvailability.Active::sourceId)
                    .filter(source -> !failures.containsKey(source)).toList();
        }
        PatentRetrievalConfidence.Assessment confidence(PatentRetrievalConfidence.Assessment result) {
            return failures.isEmpty() ? result : new PatentRetrievalConfidence.Assessment(
                    PatentRetrievalConfidence.Status.LOW_CONFIDENCE,
                    "PARTIAL_SOURCE_CONFIDENCE/v1", "PARTIAL_SOURCE_COVERAGE");
        }
    }
    Batch validateIndexes(Batch batch, ProductSemanticIndex index) {
        return validateIndexesFields(batch,index,Set.of("TITLE"));
    }
    Batch validateIndexesFields(Batch batch, ProductSemanticIndex index, Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        var fields = new TreeSet<>(selected);
        Map<String,String> failures = new LinkedHashMap<>(batch.failures());
        for (var active : batch.snapshots()) {
            if (failures.containsKey(active.sourceId())) continue;
            try {
                for (String field : fields) {
                    if (fields.equals(Set.of("TITLE"))) index.validate(active);
                    else index.validateField(active,field);
                }
            }
            catch (ProductSemanticIndex.Unavailable invalid) {
                failures.put(active.sourceId(), "SOURCE_INDEX_UNAVAILABLE");
            } catch (ProductCollectedPatentTitles.Unavailable content) {
                failures.put(active.sourceId(), "SOURCE_CONTENT_UNAVAILABLE");
            }
        }
        if (failures.size() == batch.snapshots().size())
            throw new IllegalStateException("All requested source indexes are unavailable");
        return new Batch(batch.snapshots(), batch.documents().stream()
                .filter(document -> !failures.containsKey(document.sourceId())).toList(), Map.copyOf(failures),
                batch.fieldEvidence().stream().filter(item -> !failures.containsKey(item.document().sourceId())).toList());
    }
    Batch load(List<String> sources, ProductPatentSearchFilters filters,
            ProductSourceAvailability availability, ProductCollectedPatentTitles titles) {
        return loadFields(sources,filters,availability,titles,Set.of("TITLE"));
    }
    Batch loadFields(List<String> sources, ProductPatentSearchFilters filters,
            ProductSourceAvailability availability, ProductCollectedPatentTitles titles, Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        var fields = Set.copyOf(selected);
        if(sources==null || sources.isEmpty() || new HashSet<>(sources).size()!=sources.size())
            throw new IllegalArgumentException("Unique requested sources required");
        List<ProductSourceAvailability.Active> snapshots = new ArrayList<>();
        List<PatentLexicalQuery.Document> documents = new ArrayList<>();
        List<cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence> originalEvidence = new ArrayList<>();
        Map<String,String> failures = new LinkedHashMap<>();
        for(String source : sources) {
            ProductSourceAvailability.Active active = null;
            for (String field : new TreeSet<>(fields)) {
                var current = availability.require(source,field,"QUERY");
                if (current == null || !source.equals(current.sourceId())
                        || (active != null && !active.equals(current)))
                    throw new IllegalStateException("Current source field snapshots differ");
                active = current;
            }
            filters.requireAuthorized(availability,active);
            snapshots.add(active);
            try {
                var originals = fields.equals(Set.of("TITLE")) ? titles.load(active,filters).stream().map(document ->
                        new cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence(
                                document,Map.of("TITLE",document.locatorRef()))).toList()
                        : titles.loadFieldEvidence(active,filters,fields);
                if (originals == null) throw new ProductCollectedPatentTitles.Unavailable(
                        new IllegalStateException("Source field evidence missing"));
                var loaded = originals.stream().map(
                        cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence::document).toList();
                if (loaded == null || loaded.stream().anyMatch(document -> document == null
                        || !source.equals(document.sourceId()) || !document.fields().keySet().equals(fields)))
                    throw new ProductCollectedPatentTitles.Unavailable(new IllegalStateException("Selected source projection differs"));
                documents.addAll(loaded);
                originalEvidence.addAll(originals);
            }
            catch(ProductCollectedPatentTitles.Unavailable content) {
                failures.put(source,"SOURCE_CONTENT_UNAVAILABLE");
            }
        }
        if(snapshots.size()==failures.size())
            throw new IllegalStateException("All requested source contents are unavailable");
        return new Batch(List.copyOf(snapshots),List.copyOf(documents),Map.copyOf(failures),List.copyOf(originalEvidence));
    }
}
