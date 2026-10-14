package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptSaveCommandTest {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=ProductPromptCreateCommand.commandMapper();
    private String id(){return UUID.randomUUID().toString();}
    private ProductPromptSaveCommand command(){return new ProductPromptSaveCommand(2,id(),PromptRevision.Kind.SYSTEM," Draft "," {{ratio}} ",
            List.of("b","a","b"),List.of("second","first"),"internal","zh",0,
            List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"owned",true,new BigDecimal("1.00"),false)),id());}
    @Test void exactNewRevisionPreservesContentAndBindsEveryCoordinate()throws Exception{
        var value=command();var decoded=ProductPromptSaveCommand.decode(mapper.writeValueAsBytes(value));
        assertThat(decoded).isEqualTo(value);assertThat(decoded.scenarioIds()).containsExactly("a","b");
        assertThat(decoded.variables().getFirst().defaultValue()).isEqualTo(new BigDecimal("1.00"));
        assertThat(decoded.body()).isEqualTo(" {{ratio}} ");
        String workspace=id(),prompt=id(),revision=id();String hash=value.requestSha(workspace,prompt);
        assertThat(decoded.requestSha(workspace,prompt)).isEqualTo(hash);
        assertThat(value.requestSha(id(),prompt)).isNotEqualTo(hash);assertThat(value.requestSha(workspace,id())).isNotEqualTo(hash);
        for(String field:List.of("expectedGeneration","expectedRevisionId","sourceId")){
            ObjectNode tree=mapper.valueToTree(value);
            if(field.equals("expectedGeneration"))tree.put(field,3);else tree.put(field,id());
            var changed=ProductPromptSaveCommand.decode(mapper.writeValueAsBytes(tree));
            assertThat(changed.requestSha(workspace,prompt)).isNotEqualTo(hash);assertThat(changed.contentSha()).isEqualTo(value.contentSha());
        }
        Instant created=Instant.parse("2026-10-14T00:00:00Z"),modified=created.plusSeconds(20);
        var saved=value.revision(prompt,revision,new PromptRevision.Provenance("owned","creator",created,"modifier",modified,"SAVE_DRAFT",List.of(),null,"policy"));
        assertThat(saved.reference().version()).isEqualTo(3);assertThat(saved.reference().contentSha256()).isEqualTo(value.contentSha());
        assertThat(saved.provenance().createdBy()).isEqualTo("creator");assertThat(saved.provenance().modifiedBy()).isEqualTo("modifier");
    }
    @ParameterizedTest @ValueSource(strings={"zero","negative","overflow","fractional","string","missing-revision","invalid-revision","unknown","server-provenance","duplicate","trailing","utf8","oversized","unclosed-template","illegal-variable","nested-template"})
    void malformedSaveCannotBypassCreateContentOrCasValidation(String fault)throws Exception{
        ObjectNode value=mapper.valueToTree(command());
        switch(fault){
            case "zero"->value.put("expectedGeneration",0);
            case "negative"->value.put("expectedGeneration",-1);
            case "overflow"->value.put("expectedGeneration",Long.MAX_VALUE);
            case "fractional"->value.put("expectedGeneration",2.5);
            case "string"->value.put("expectedGeneration","2");
            case "missing-revision"->value.remove("expectedRevisionId");
            case "invalid-revision"->value.put("expectedRevisionId","invalid");
            case "unknown"->value.put("unknown",true);
            case "server-provenance"->value.put("createdBy","administrator");
            case "oversized"->value.put("body","x".repeat(256*1024+1));
            case "unclosed-template"->value.put("body","{{ratio");
            case "illegal-variable"->value.put("body","{{not.valid}}");
            case "nested-template"->value.put("body","{{ratio{{other}}}}");
            default->{ }
        }
        String raw=value.toString();if(fault.equals("duplicate"))raw=raw.replace("\"expectedGeneration\":2","\"expectedGeneration\":2,\"expectedGeneration\":2");
        if(fault.equals("trailing"))raw+=" {}";
        byte[] bytes=fault.equals("utf8")?new byte[]{(byte)0xc3,0x28}:raw.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->ProductPromptSaveCommand.decode(bytes)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void finalAllowedGenerationCannotOverflowTarget()throws Exception{
        ObjectNode value=mapper.valueToTree(command());value.put("expectedGeneration",Long.MAX_VALUE-1);
        var command=ProductPromptSaveCommand.decode(mapper.writeValueAsBytes(value));
        var now=Instant.parse("2026-10-14T00:00:00Z");
        assertThat(command.revision(id(),id(),new PromptRevision.Provenance("owned","member",now,"member",now,"SAVE_DRAFT",List.of(),null,"policy"))
                .reference().version()).isEqualTo(Long.MAX_VALUE);
    }
}
