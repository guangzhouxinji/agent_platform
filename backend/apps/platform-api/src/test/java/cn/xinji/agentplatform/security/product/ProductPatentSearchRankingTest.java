package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Controlled signal ordering only; this does not verify model quality or source admission. */
class ProductPatentSearchRankingTest {
    @Test void globalSemanticRanksDoNotDependOnRequestedSourceEnumerationOrder() {
        var a=new PatentLexicalQuery.Document("a","CN104900909A","v1","capture-v1",
                "a".repeat(64),"title-a",Map.of("TITLE","Lithium ion battery"));
        var b=new PatentLexicalQuery.Document("b","EP3044822A2","v1","capture-v1",
                "b".repeat(64),"title-b",Map.of("TITLE","Water-based cathode slurry"));
        var documents=List.of(a,b);
        var prepared=new PatentQueryRetrieval().prepare("quantum telemetry",documents,1000);
        var index=mock(ProductSemanticIndex.class);
        when(index.search("a","quantum telemetry",1000)).thenReturn(List.of(new ProductSemanticIndex.Match(
                a.sourceId(),a.documentId(),a.documentVersion(),a.sourceVersion(),a.rawSha(),a.locatorRef(),0.2)));
        when(index.search("b","quantum telemetry",1000)).thenReturn(List.of(new ProductSemanticIndex.Match(
                b.sourceId(),b.documentId(),b.documentVersion(),b.sourceVersion(),b.rawSha(),b.locatorRef(),0.9)));
        var ranking=new ProductPatentSearchRanking(new ProductPatentSearchEvidenceCodec(new ObjectMapper()),index,null,100);
        var first=ranking.rank(List.of("a","b"),prepared.expansion(),documents,prepared.lexical());
        var reverse=ranking.rank(List.of("b","a"),prepared.expansion(),documents,prepared.lexical());
        assertThat(first.members()).extracting(ProductPatentSearchExecution.Member::sourceId).containsExactly("b","a");
        assertThat(reverse.members()).extracting(ProductPatentSearchExecution.Member::sourceId).containsExactly("b","a");
        assertThat(first.members()).extracting(ProductPatentSearchExecution.Member::semanticRank).containsExactly(1,2);
        assertThat(reverse.members()).extracting(ProductPatentSearchExecution.Member::fusionScore)
                .containsExactlyElementsOf(first.members().stream().map(ProductPatentSearchExecution.Member::fusionScore).toList());
    }
}
