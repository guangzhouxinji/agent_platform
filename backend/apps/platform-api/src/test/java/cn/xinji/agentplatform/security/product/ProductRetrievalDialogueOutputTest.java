package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Mechanical output counterexamples; not real model/identity/source business evidence. */
class ProductRetrievalDialogueOutputTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void proposalDoesNotImplyConfirmation() {
        var value=ProductRetrievalDialogueOutput.parse(mapper,"{\"query\":\"brazing rotor blades\",\"questions\":[]}","PROPOSAL",Set.of());
        assertThat(value.path("query").asText()).isEqualTo("brazing rotor blades");
        assertThatThrownBy(()->ProductRetrievalDialogueOutput.parse(mapper,
                "{\"query\":\"brazing\",\"questions\":[],\"confirmed\":true}","PROPOSAL",Set.of()));
    }
    @Test void citationsMustBelongToActualResults() {
        assertThat(ProductRetrievalDialogueOutput.parse(mapper,"{\"answer\":\"检索候选\",\"citations\":[\"member1\"]}",
                "EXPLANATION",Set.of("member1")).path("citations").size()).isEqualTo(1);
        for(String citations:new String[]{"[\"invented\"]","[]","[\"member1\",\"member1\"]"})
            assertThatThrownBy(()->ProductRetrievalDialogueOutput.parse(mapper,
                    "{\"answer\":\"候选\",\"citations\":"+citations+"}","EXPLANATION",Set.of("member1")));
    }
    @Test void actualEmptyResultCanOnlyHaveEmptyCitations() {
        assertThat(ProductRetrievalDialogueOutput.parse(mapper,"{\"answer\":\"未检索到候选\",\"citations\":[]}",
                "EXPLANATION",Set.of()).path("citations").isEmpty()).isTrue();
        assertThatThrownBy(()->ProductRetrievalDialogueOutput.parse(mapper,"{\"answer\":\"找到候选\",\"citations\":[\"fake\"]}",
                "EXPLANATION",Set.of()));
    }
    @Test void duplicateKeysAndLegalConclusionsReject() {
        for(String raw:new String[]{"{\"query\":\"a\",\"query\":\"b\",\"questions\":[]}",
                "{\"query\":\"具有新颖性\",\"questions\":[]}","[]","{\"query\":5,\"questions\":[]}"})
            assertThatThrownBy(()->ProductRetrievalDialogueOutput.parse(mapper,raw,"PROPOSAL",Set.of()));
    }
    @Test void exactProviderFormatTagIsRemovedWithoutAllowingBusinessExtras() {
        var output=ProductRetrievalDialogueOutput.parse(mapper,
                "{\"type\":\"json_object\",\"answer\":\"候选参考\",\"citations\":[\"member1\"]}",
                "EXPLANATION",Set.of("member1"));
        assertThat(output.has("type")).isFalse();
        for(String tag:new String[]{"unknown","MODEL_APPROVED","confirmed"})
            assertThatThrownBy(()->ProductRetrievalDialogueOutput.parse(mapper,
                    "{\"type\":\""+tag+"\",\"query\":\"brazing\",\"questions\":[]}","PROPOSAL",Set.of()));
    }
}
