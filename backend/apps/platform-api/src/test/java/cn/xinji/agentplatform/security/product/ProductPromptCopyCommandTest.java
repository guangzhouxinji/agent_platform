package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptCopyCommandTest {
    final String sourceWorkspace=id(),sourcePrompt=id(),sourceRevision=id(),targetWorkspace=id(),sourceId=id(),sha="a".repeat(64);
    final ProductPromptCopyCommand command=new ProductPromptCopyCommand(0,sourceRevision,sha,targetWorkspace,sourceId);
    final com.fasterxml.jackson.databind.ObjectMapper mapper=ProductPromptCreateCommand.commandMapper();
    @Test void exactFiveFieldsRoundTripAndCanonicalIgnoresJsonOrdering()throws Exception{
        byte[] encoded=mapper.writeValueAsBytes(command);assertThat(ProductPromptCopyCommand.decode(encoded)).isEqualTo(command);
        var reversed=Map.of("sourceId",sourceId,"targetWorkspaceId",targetWorkspace,"sourceContentSha",sha,"sourceRevisionId",sourceRevision,"expectedGeneration",0);
        var decoded=ProductPromptCopyCommand.decode(mapper.writeValueAsBytes(reversed));
        assertThat(decoded.requestSha(sourceWorkspace,sourcePrompt)).isEqualTo(command.requestSha(sourceWorkspace,sourcePrompt)).matches("[a-f0-9]{64}");
        assertThat(command.requestSha(id(),sourcePrompt)).isNotEqualTo(command.requestSha(sourceWorkspace,sourcePrompt));
        assertThat(command.requestSha(sourceWorkspace,id())).isNotEqualTo(command.requestSha(sourceWorkspace,sourcePrompt));
        for(var changed:java.util.List.of(new ProductPromptCopyCommand(0,id(),sha,targetWorkspace,sourceId),
                new ProductPromptCopyCommand(0,sourceRevision,"b".repeat(64),targetWorkspace,sourceId),
                new ProductPromptCopyCommand(0,sourceRevision,sha,id(),sourceId),new ProductPromptCopyCommand(0,sourceRevision,sha,targetWorkspace,id())))
            assertThat(changed.requestSha(sourceWorkspace,sourcePrompt)).isNotEqualTo(command.requestSha(sourceWorkspace,sourcePrompt));
    }
    @ParameterizedTest @ValueSource(strings={"1","-1","0.0","\"0\"","null","false","9223372036854775808"})
    void generationIsStrictlyAnIntegralZero(String generation)throws Exception{
        String json=mapper.writeValueAsString(command).replace("\"expectedGeneration\":0","\"expectedGeneration\":"+generation);
        assertThatThrownBy(()->ProductPromptCopyCommand.decode(json.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","unknown","missing","trailing","array","uppercase-sha","bad-id","null-id","unicode"})
    void sourceSelectionCannotSmuggleContentProvenanceOrAmbiguousTokens(String fault)throws Exception{
        String raw=mapper.writeValueAsString(command);
        raw=switch(fault){
            case "duplicate"->raw.substring(0,raw.length()-1)+",\"expectedGeneration\":0}";
            case "unknown"->raw.substring(0,raw.length()-1)+",\"body\":\"unapproved\"}";
            case "missing"->raw.replace("\"sourceId\":\""+sourceId+"\"", "\"differentId\":\""+sourceId+"\"");
            case "trailing"->raw+" {}";case "array"->"["+raw+"]";
            case "uppercase-sha"->raw.replace(sha,sha.toUpperCase());case "bad-id"->raw.replace(sourceRevision,"1-1-1-1-1");
            case "null-id"->raw.replace("\""+sourceId+"\"","null");default->raw.replace(sourceRevision,"\\uD800");
        };
        byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->ProductPromptCopyCommand.decode(bytes)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bodyBoundAndUtf8ApplyBeforeParsing()throws Exception{
        String raw=mapper.writeValueAsString(command);byte[] limit=(raw+" ".repeat(1024*1024-raw.length())).getBytes(StandardCharsets.UTF_8);
        assertThat(ProductPromptCopyCommand.decode(limit)).isEqualTo(command);
        assertThatThrownBy(()->ProductPromptCopyCommand.decode(java.util.Arrays.copyOf(limit,limit.length+1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductPromptCopyCommand.decode(new byte[]{(byte)0xc3,0x28})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductPromptCopyCommand.decode(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
