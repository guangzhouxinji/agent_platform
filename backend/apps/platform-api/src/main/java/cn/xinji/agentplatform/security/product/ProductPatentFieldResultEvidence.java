package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion;
import java.math.*;
import java.util.*;

/** Immutable field-result facts. Their v3 digest never aliases a legacy TITLE-only digest. */
final class ProductPatentFieldResultEvidence {
    static final String VERSION = "FIELD_RESULT_EVIDENCE/v1";
    static final String DIGEST_VERSION = "RESULT_DIGEST_FIELDS_DECIMAL_12/v3";
    record Signal(double cosine, int rank, String locatorRef, String modelVersion) {
        Signal {
            if (!Double.isFinite(cosine) || Math.abs(cosine)>1.000001 || rank<1 || rank>1000
                    || blank(locatorRef) || blank(modelVersion)) throw new IllegalArgumentException("Exact field signal required");
        }
    }
    record Fields(Map<String,String> locators, Map<String,Signal> semantic) {
        Fields { locators=immutable(locators); semantic=immutable(semantic); }
    }
    record Bundle(String version, List<String> fields, Map<String,String> models,
                  String method, String ordering, String rerankModel, String rerankStatus,
                  Map<String,Fields> members) {
        Bundle {
            if (!VERSION.equals(version) || fields==null || new HashSet<>(fields).size()!=fields.size()
                    || blank(method) || blank(ordering)) throw new IllegalArgumentException("Versioned field result required");
            ProductCapturedPatentFields.validate(new HashSet<>(fields));
            if (Set.copyOf(fields).equals(Set.of("TITLE"))) throw new IllegalArgumentException("Legacy TITLE result is not field v3");
            fields=List.copyOf(new TreeSet<>(fields)); models=immutable(models); members=immutable(members);
            if ((!method.equals("EXACT_PUBLICATION/v1") && !models.keySet().equals(new HashSet<>(fields)))
                    || (method.equals("EXACT_PUBLICATION/v1") && !models.isEmpty())
                    || models.values().stream().anyMatch(ProductPatentFieldResultEvidence::blank))
                throw new IllegalArgumentException("Complete selected field model versions required");
            if (!Set.of("EXACT_PUBLICATION/v1","HYBRID_FIELD_RRF/v1","HYBRID_FIELD_RRF_COMPLETE_CE/v1").contains(method)
                    || (method.equals("HYBRID_FIELD_RRF_COMPLETE_CE/v1")
                        && (!"SUCCEEDED".equals(rerankStatus) || blank(rerankModel)))
                    || (method.equals("EXACT_PUBLICATION/v1") && rerankModel!=null)
                    || (method.equals("EXACT_PUBLICATION/v1") && rerankStatus!=null)
                    || (method.equals("HYBRID_FIELD_RRF/v1") && (rerankStatus!=null && !"RERANK_FAILED".equals(rerankStatus)))
                    || (method.equals("HYBRID_FIELD_RRF/v1") && (rerankStatus==null ? rerankModel!=null : blank(rerankModel)))
                    || (method.equals("HYBRID_FIELD_RRF/v1") && !members.isEmpty() && !"RERANK_FAILED".equals(rerankStatus)))
                throw new IllegalArgumentException("Exact field ranking method and outcome required");
            String expectedOrdering=method.equals("EXACT_PUBLICATION/v1") ? "EXACT_PUBLICATION_DOCUMENT_KEY/v1"
                    : method.equals("HYBRID_FIELD_RRF_COMPLETE_CE/v1") ? "COMPLETE_CE_SCORE_DESC_FIELD_RRF_DESC_DOCUMENT_KEY/v1"
                    : "FIELD_RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1";
            if(!expectedOrdering.equals(ordering)) throw new IllegalArgumentException("Field ordering method differs");
        }
    }
    static Bundle from(ProductPatentFieldSearchRanking.Result result, Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        Map<String,PatentFieldSemanticFusion.Candidate> signals = new HashMap<>();
        Map<String,String> models = Map.of();
        if (result.semanticEvidence()!=null) {
            if (!result.semanticEvidence().fields().equals(selected)) throw new IllegalStateException("Result field scope differs");
            models=result.semanticEvidence().modelVersions();
            for (var candidate : result.semanticEvidence().candidates()) signals.put(key(candidate.match().sourceId(),
                    candidate.match().documentId(),candidate.match().documentVersion()),candidate);
        }
        Map<String,Fields> members = new TreeMap<>();
        for (var member : result.ranking().members()) {
            String id=key(member.sourceId(),member.documentId(),member.documentVersion()); var original=result.originals().get(id);
            if (original==null || !original.document().sourceVersion().equals(member.sourceVersion())
                    || !original.document().rawSha().equals(member.rawSha())
                    || !original.document().locatorRef().equals(member.locatorRef()))
                throw new IllegalStateException("Result field original differs");
            Map<String,Signal> evidence=new TreeMap<>(); var candidate=signals.get(id);
            if(candidate!=null) for(String field:candidate.fieldScores().keySet()) evidence.put(field,
                    new Signal(candidate.fieldScores().get(field),candidate.fieldRanks().get(field),
                            candidate.fieldLocators().get(field),models.get(field)));
            if (members.put(member.memberId(),new Fields(original.fieldLocators(),evidence))!=null)
                throw new IllegalStateException("Duplicate result field member");
        }
        var rank=result.ranking();
        Bundle bundle=new Bundle(VERSION,new ArrayList<>(selected),models,rank.method(),rank.ordering(),
                rank.rerankModelVersion(),rank.rerankStatus(),members);
        validate(bundle,rank.members()); return bundle;
    }
    static void validate(Bundle bundle, List<ProductPatentSearchExecution.Member> members) {
        if(bundle==null || members==null || members.size()!=bundle.members().size())
            throw new IllegalStateException("Complete field member facts required");
        Set<String> fields=Set.copyOf(bundle.fields()), ids=new HashSet<>();
        for(int i=0;i<members.size();i++) {
            var member=members.get(i); Fields evidence=bundle.members().get(member.memberId());
            if(evidence==null || !ids.add(member.memberId()) || member.ordinal()!=i
                    || !evidence.locators().keySet().equals(fields) || !fields.containsAll(evidence.semantic().keySet())
                    || !evidence.locators().containsValue(member.locatorRef())
                    || !member.matchFields().containsAll(evidence.semantic().keySet())
                    || member.matchFields().stream().anyMatch(f->!fields.contains(f)&&!"DOCUMENT_ID".equals(f))) invalid();
            if ("SUCCEEDED".equals(bundle.rerankStatus())) {
                if (member.rerankScore()==null || !Double.isFinite(member.rerankScore())
                        || member.rerankRank()==null || member.rerankRank()<1) invalid();
            } else if (member.rerankScore()!=null || member.rerankRank()!=null) invalid();
            int marker=member.locatorRef().indexOf('#');
            if(marker<1 || evidence.locators().values().stream().anyMatch(location->blank(location)
                    || !location.startsWith(member.locatorRef().substring(0,marker)+"#"))) invalid();
            if(member.semanticRank()==null) {
                if(member.semanticScore()!=null || !evidence.semantic().isEmpty()) invalid();
            } else {
                if(member.semanticRank()<1 || member.semanticScore()==null || !Double.isFinite(member.semanticScore())
                        || evidence.semantic().isEmpty()) invalid();
                double combined=0;
                for(var entry:evidence.semantic().entrySet()) {
                    var signal=entry.getValue();
                    if(!signal.locatorRef().equals(evidence.locators().get(entry.getKey()))
                            || !signal.modelVersion().equals(bundle.models().get(entry.getKey()))) invalid();
                    combined+=1.0/(60+signal.rank());
                }
                if(Math.abs(combined/fields.size()-member.semanticScore())>1e-12) invalid();
            }
        }
    }
    static String digest(ProductPatentSearchEvidenceCodec codec, Bundle bundle,
            List<ProductPatentSearchExecution.Member> members, boolean truncated) {
        validate(bundle,members);
        Map<String,Object> header=new TreeMap<>(); header.put("version",bundle.version()); header.put("fields",bundle.fields());
        header.put("models",bundle.models()); header.put("method",bundle.method()); header.put("ordering",bundle.ordering());
        header.put("rerankModel",bundle.rerankModel()); header.put("rerankStatus",bundle.rerankStatus());
        List<Object> facts=new ArrayList<>();
        for(var member:members) facts.add(canonical(bundle.members().get(member.memberId())));
        // UUIDs identify storage rows, not deterministic retrieval content.
        return codec.digest(DIGEST_VERSION,codec.resultDigest(members,truncated),codec.json(header),codec.json(facts));
    }
    static Map<String,Object> canonical(Fields fields) {
        Map<String,Object> signals=new TreeMap<>();
        fields.semantic().forEach((field,signal)-> {
            Map<String,Object> value=new TreeMap<>();
            value.put("cosine",BigDecimal.valueOf(signal.cosine()).setScale(12,RoundingMode.HALF_UP).stripTrailingZeros());
            value.put("rank",signal.rank()); value.put("locatorRef",signal.locatorRef()); value.put("modelVersion",signal.modelVersion());
            signals.put(field,Collections.unmodifiableMap(value));
        });
        Map<String,Object> result=new TreeMap<>(); result.put("locators",fields.locators()); result.put("semantic",signals);
        return Collections.unmodifiableMap(result);
    }
    private static <V> Map<String,V> immutable(Map<String,V> map) {
        if(map==null) throw new IllegalArgumentException("Field fact map required");
        return Collections.unmodifiableMap(new TreeMap<>(map));
    }
    private static String key(String source,String document,String version) { return source+'\0'+document+'\0'+version; }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static void invalid() { throw new IllegalStateException("Result field evidence is inconsistent"); }
    private ProductPatentFieldResultEvidence() { }
}
