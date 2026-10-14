package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real migration/transaction code with mechanical originals C only; not Source admission or patent retrieval I. */
final class ProductPatentFieldRecoveryAssertions {
    static void verify(JdbcDataSource datasource,JdbcTemplate db,ObjectMapper mapper,String attempt,String result) {
        var codec=new ProductPatentSearchEvidenceCodec(mapper); Set<String> fields=Set.of("ABSTRACT","CLAIMS");
        String query="US-20250149543-A1",sha="a".repeat(64); var filters=ProductPatentSearchFilters.none();
        String filterJson=codec.json(filters.evidence());
        var scope=ProductPatentSearchFieldScope.from(List.of("ABSTRACT","CLAIMS"),filters);
        String scopeSha=scope.scopeSha(codec,List.of("source-1"),filters.version(),filterJson);
        var doc=new PatentLexicalQuery.Document("source-1",query,"version-1","capture-1",sha,"raw/doc#abstract",
                Map.of("ABSTRACT","mechanical original projection","CLAIMS","mechanical original claims"));
        var original=new PatentFieldSemanticFusion.DocumentEvidence(doc,Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims"));
        var prepared=new PatentQueryRetrieval().prepareFields(query,List.of(doc),fields,1000); var plan=prepared.expansion();
        db.execute("CREATE TABLE con3_search_input(input_id VARCHAR(36),requested_fields_json CLOB,source_scope_json CLOB,filters_json CLOB)");
        db.update("INSERT INTO con3_search_input VALUES('revision-1',?,?,?)",codec.json(scope.requestedFields()),"[\"source-1\"]",filterJson);
        db.update("INSERT INTO con4_source_verification VALUES(?,'source-1','contract-1','capture-1',2,"
                + "'manifest.json',?,?,?,1,1,'mechanical-C',CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),sha,sha,sha);
        db.update("UPDATE con4_search_source SET coverage_sha=? WHERE attempt_id=?",codec.digest(sha,sha),attempt);
        db.update("UPDATE con4_search_attempt SET original_query=?,actual_query=?,scope_sha=?,method_version='EXACT_PUBLICATION/v1',"
                + "rerank_model_version=NULL,rerank_status=NULL,query_plan_version=?,expansions_json=?,expansion_sha=?,"
                + "filter_version=?,filters_json=?,filter_sha=? WHERE attempt_id=?",query,prepared.lexical().normalizedQuery(),scopeSha,
                plan.version(),codec.json(plan.expansions()),codec.digest(plan.version(),codec.json(plan.expansions())),
                filters.version(),filterJson,codec.digest(filters.version(),filterJson),attempt);
        db.update("UPDATE con4_result_member SET document_id=?,match_fields='DOCUMENT_ID',matched_terms_json=?,"
                + "field_scores_json=?,semantic_score=NULL,semantic_rank=NULL,fusion_score=NULL,rerank_score=NULL,rerank_rank=NULL "
                + "WHERE result_set_id=?",query,codec.json(List.of(query)),codec.json(Map.of("DOCUMENT_ID",1.0)),result);
        db.update("UPDATE con4_result_set SET ordering_version='EXACT_PUBLICATION_DOCUMENT_KEY/v1',candidate_count=1 WHERE result_set_id=?",result);
        db.update("DELETE FROM con4_result_field_evidence WHERE result_set_id=?",result);
        db.update("DELETE FROM con4_search_field_scope WHERE result_set_id=?",result);
        var members=ProductPatentSearchPages.members(db,codec,result);
        var bundle=new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,List.copyOf(fields),Map.of(),
                "EXACT_PUBLICATION/v1","EXACT_PUBLICATION_DOCUMENT_KEY/v1",null,null,
                Map.of(members.getFirst().memberId(),new ProductPatentFieldResultEvidence.Fields(original.fieldLocators(),Map.of())));
        db.update("UPDATE con4_result_set SET result_sha=? WHERE result_set_id=?",ProductPatentFieldResultEvidence.digest(codec,bundle,members,false),result);
        new TransactionTemplate(new DataSourceTransactionManager(datasource)).execute(status -> {
            codec.fieldStore(db).save(attempt,result,scopeSha,bundle,members,false); return null;
        });
        var snapshot=new ProductCollectedPatentTitles.CaptureSnapshot("source-1","capture-1","manifest.json",sha,sha);
        var titles=mock(ProductCollectedPatentTitles.class);
        when(titles.loadFieldEvidence(eq(snapshot),any(ProductPatentSearchFilters.class),eq(fields))).thenReturn(List.of(original));
        var verifier=new ProductPatentFieldRecoveryEvidence(db,codec,titles);
        var receipt=new ProductPatentSearchReceipts(db).read(result);
        verifier.verify(result,receipt,members);
        var ordered=new TreeMap<String,Object>(Comparator.reverseOrder()); ordered.putAll(filters.evidence());
        String historicalFilters=codec.json(ordered);
        if(historicalFilters.equals(filterJson)) historicalFilters=codec.json(new TreeMap<>(filters.evidence()));
        String historicalScope=scope.scopeSha(codec,List.of("source-1"),filters.version(),historicalFilters);
        db.update("UPDATE con4_search_attempt SET filters_json=?,filter_sha=?,scope_sha=? WHERE attempt_id=?",
                historicalFilters,codec.digest(filters.version(),historicalFilters),historicalScope,attempt);
        db.update("UPDATE con4_search_field_scope SET scope_sha=? WHERE attempt_id=?",historicalScope,attempt);
        verifier.verify(result,receipt,members);
        db.update("UPDATE con3_search_input SET filters_json='{\"publicationFrom\":\"2000-01-01\"}'");
        assertThatThrownBy(() -> verifier.verify(result,receipt,members))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Search fields must exactly match selected text and actual filters");
        db.update("UPDATE con3_search_input SET filters_json=?",filterJson);
        db.update("UPDATE con4_search_attempt SET filter_sha=? WHERE attempt_id=?","f".repeat(64),attempt);
        assertThatThrownBy(() -> verifier.verify(result,receipt,members)).hasMessage("Restored field search evidence inconsistent");
        db.update("UPDATE con4_search_attempt SET filter_sha=? WHERE attempt_id=?",
                codec.digest(filters.version(),historicalFilters),attempt);
        verifier.verify(result,receipt,members);
        db.update("UPDATE con3_search_input SET requested_fields_json='[\"TITLE\"]'");
        assertThatThrownBy(() -> verifier.verify(result,receipt,members)).hasMessage("Restored field search evidence inconsistent");
        db.update("UPDATE con3_search_input SET requested_fields_json=?",codec.json(scope.requestedFields()));
        db.update("UPDATE con4_search_attempt SET expansions_json='[{}]' WHERE attempt_id=?",attempt);
        assertThatThrownBy(() -> verifier.verify(result,receipt,members)).hasMessage("Restored field search evidence inconsistent");
        db.update("UPDATE con4_search_attempt SET expansions_json=? WHERE attempt_id=?",codec.json(plan.expansions()),attempt);
        db.update("UPDATE con4_search_source SET coverage_sha=? WHERE attempt_id=?","b".repeat(64),attempt);
        assertThatThrownBy(() -> verifier.verify(result,receipt,members)).hasMessage("Restored field search evidence inconsistent");
        db.update("UPDATE con4_search_source SET coverage_sha=? WHERE attempt_id=?",codec.digest(sha,sha),attempt);
        var changed=new PatentFieldSemanticFusion.DocumentEvidence(new PatentLexicalQuery.Document(doc.sourceId(),doc.documentId(),
                doc.documentVersion(),doc.sourceVersion(),"b".repeat(64),doc.locatorRef(),doc.fields()),original.fieldLocators());
        when(titles.loadFieldEvidence(eq(snapshot),any(ProductPatentSearchFilters.class),eq(fields))).thenReturn(List.of(changed));
        assertThatThrownBy(() -> verifier.verify(result,receipt,members)).hasMessage("Restored field search evidence inconsistent");
        when(titles.loadFieldEvidence(eq(snapshot),any(ProductPatentSearchFilters.class),eq(fields))).thenReturn(List.of(original));
        verifier.verify(result,receipt,members);
    }
    private ProductPatentFieldRecoveryAssertions() { }
}
