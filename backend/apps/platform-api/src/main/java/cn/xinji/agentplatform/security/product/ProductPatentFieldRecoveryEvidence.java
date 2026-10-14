package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Historical field closure only: no current Source grant, mutation, or model-generated replacement evidence. */
final class ProductPatentFieldRecoveryEvidence {
    private record Input(String fields,String sources,String filters,String query,String scope,String actual,
                         String plan,String expansions,String expansionSha,String filterVersion,String filterSha,String storedFilters) { }
    private final JdbcTemplate db;
    private final ProductPatentSearchEvidenceCodec codec;
    private final ProductCollectedPatentTitles titles;
    ProductPatentFieldRecoveryEvidence(JdbcTemplate db,ProductPatentSearchEvidenceCodec codec,ProductCollectedPatentTitles titles) {
        this.db=db; this.codec=codec; this.titles=titles;
    }
    void verify(String resultId,ProductPatentSearchExecution.Receipt receipt,List<ProductPatentSearchExecution.Member> members) {
        var bundle=codec.fieldStore(db).read(resultId);
        var rows=db.query("SELECT i.requested_fields_json,i.source_scope_json,i.filters_json,a.original_query,a.scope_sha,"
                + "a.actual_query,a.query_plan_version,a.expansions_json,a.expansion_sha,a.filter_version,a.filter_sha,a.filters_json "
                + "FROM con4_search_attempt a JOIN con3_search_input i ON i.input_id=a.input_revision WHERE a.attempt_id=?",
                (rs,n)->new Input(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12)),receipt.attemptId());
        if(rows.size()!=1) invalid(); var input=rows.getFirst();
        var filters=ProductPatentSearchFilters.parse(new ObjectMapper(),input.filters());
        var scope=ProductPatentSearchFieldScope.from(codec.nonEmptyStringList(input.fields()),filters);
        var sources=codec.nonEmptyStringList(input.sources()); String filtersJson=input.storedFilters();
        if(!scope.textFields().equals(Set.copyOf(bundle.fields())) || scope.legacyTitle()
                || !input.scope().equals(scope.scopeSha(codec,sources,filters.version(),filtersJson))
                || !filters.version().equals(input.filterVersion())
                || !filters.equals(ProductPatentSearchFilters.parse(new ObjectMapper(),filtersJson))
                || !codec.digest(filters.version(),filtersJson).equals(input.filterSha())
                || !new HashSet<>(sources).equals(receipt.sourceOutcomes().stream().map(
                        ProductPatentSearchSourceBatch.Outcome::sourceId).collect(java.util.stream.Collectors.toSet()))) invalid();
        Map<String,PatentFieldSemanticFusion.DocumentEvidence> all=new TreeMap<>(), admitted=new TreeMap<>();
        for(var source:receipt.sourceOutcomes()) if("SUCCEEDED".equals(source.status())) {
            var snapshots=db.query("SELECT v.source_version,v.manifest_ref,v.manifest_sha,v.records_sha,s.coverage_sha "
                    + "FROM con4_source_verification v JOIN con4_search_source s ON s.source_id=v.source_id "
                    + "AND s.contract_version=v.contract_version WHERE s.attempt_id=? AND s.source_id=?",
                    (rs,n)-> {
                        if(!rs.getString(5).equals(codec.digest(rs.getString(3),rs.getString(4)))) invalid();
                        return new ProductCollectedPatentTitles.CaptureSnapshot(source.sourceId(),rs.getString(1),
                                rs.getString(2),rs.getString(3),rs.getString(4));
                    },receipt.attemptId(),source.sourceId());
            if(snapshots.size()!=1) invalid();
            for(var document:titles.loadFieldEvidence(snapshots.getFirst(),ProductPatentSearchFilters.none(),scope.textFields()))
                if(all.put(key(document.document()),document)!=null) invalid();
            for(var document:titles.loadFieldEvidence(snapshots.getFirst(),filters,scope.textFields()))
                if(admitted.put(key(document.document()),document)!=null) invalid();
        }
        var prepared=new PatentQueryRetrieval().prepareFields(input.query(),admitted.values().stream().map(
                PatentFieldSemanticFusion.DocumentEvidence::document).toList(),scope.textFields(),1000);
        var expansion=prepared.expansion(); String json=codec.json(expansion.expansions());
        if(!input.actual().equals(prepared.lexical().normalizedQuery()) || !input.plan().equals(expansion.version())
                || !input.expansions().equals(json) || !input.expansionSha().equals(codec.digest(expansion.version(),json))) invalid();
        Map<String,PatentFieldSemanticFusion.DocumentEvidence> pool=new TreeMap<>();
        for(var doc:prepared.documents()) pool.put(key(doc),admitted.get(key(doc)));
        verifyOriginals(bundle,members,pool);
        verifyLexical(bundle,members,prepared.lexical());
        new ProductPatentFieldRecoveryModels(db,codec).verify(receipt,bundle,all);
    }
    static void verifyOriginals(ProductPatentFieldResultEvidence.Bundle bundle,
            List<ProductPatentSearchExecution.Member> members,Map<String,PatentFieldSemanticFusion.DocumentEvidence> originals) {
        ProductPatentFieldResultEvidence.validate(bundle,members);
        PatentFieldSemanticFusion.validateDocuments(new ArrayList<>(originals.values()),Set.copyOf(bundle.fields()));
        for(int n=0;n<members.size();n++) {
            var member=members.get(n); var original=originals.get(ProductLegacySearchDigest.key(member));
            if(member.ordinal()!=n || original==null) invalid(); var doc=original.document();
            if(!member.sourceVersion().equals(doc.sourceVersion()) || !member.rawSha().equals(doc.rawSha())
                    || !member.locatorRef().equals(doc.locatorRef())
                    || !bundle.members().get(member.memberId()).locators().equals(original.fieldLocators())) invalid();
        }
    }
    private static void verifyLexical(ProductPatentFieldResultEvidence.Bundle bundle,
            List<ProductPatentSearchExecution.Member> members,PatentBm25fQuery.Result lexical) {
        Map<String,PatentBm25fQuery.Match> matches=new HashMap<>();
        Map<String,Integer> ranks=new HashMap<>();
        for(int n=0;n<lexical.matches().size();n++) {
            var match=lexical.matches().get(n); String key=ProductLegacySearchDigest.key(match);
            matches.put(key,match); ranks.put(key,n+1);
        }
        for(var member:members) {
            var match=matches.get(ProductLegacySearchDigest.key(member));
            Set<String> expected=new HashSet<>(bundle.members().get(member.memberId()).semantic().keySet());
            if(match!=null) expected.addAll(match.matchFields());
            if(!expected.equals(member.matchFields())) invalid();
            if(member.lexicalRank()==null) {
                if(match!=null || !member.fieldScores().isEmpty() || !member.matchedTerms().isEmpty()
                        || (member.lexicalScore()!=null && member.lexicalScore()!=0)) invalid();
            } else if(match==null || !same(match.lexicalScore(),member.lexicalScore())
                    || !match.fieldScores().equals(member.fieldScores()) || !match.matchedTerms().equals(member.matchedTerms())
                    || !ranks.get(ProductLegacySearchDigest.key(member)).equals(member.lexicalRank())) invalid();
            if(!"EXACT_PUBLICATION/v1".equals(bundle.method())) {
                double fusion=(member.lexicalRank()==null?0:1.0/(60+member.lexicalRank()))
                        +(member.semanticRank()==null?0:1.0/(60+member.semanticRank()));
                if(!same(fusion,member.fusionScore())) invalid();
            }
        }
    }
    private static boolean same(Double actual,Double stored) {
        return actual!=null && stored!=null && Double.isFinite(actual) && Double.isFinite(stored)
                && BigDecimal.valueOf(actual).setScale(12,RoundingMode.HALF_UP)
                    .compareTo(BigDecimal.valueOf(stored).setScale(12,RoundingMode.HALF_UP))==0;
    }
    private static String key(PatentLexicalQuery.Document document) {
        return document.sourceId()+'\0'+document.documentId()+'\0'+document.documentVersion();
    }
    private static void invalid() { throw new IllegalStateException("Restored field search evidence inconsistent"); }
}
