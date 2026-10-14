package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptCreateCommandTest {
    private final ObjectMapper mapper=new ObjectMapper().setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    private ProductPromptCreateCommand command(){return new ProductPromptCreateCommand(0,PromptRevision.Kind.SYSTEM," Draft "," Value {{ratio}} ",
            List.of("b","a","b"),List.of("second","first"),"internal","zh",0,
            List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"owned",true,new BigDecimal("1.00"),false)),UUID.randomUUID().toString());}
    @Test void exactDecimalContentAndSortedIdsSurviveStrictDecodeWithoutTrimming()throws Exception{
        var original=command();var decoded=ProductPromptCreateCommand.decode(mapper.writeValueAsBytes(original));
        assertThat(decoded).isEqualTo(original);assertThat(decoded.scenarioIds()).containsExactly("a","b");
        assertThat(decoded.body()).isEqualTo(" Value {{ratio}} ");assertThat(decoded.name()).isEqualTo(" Draft ");
        assertThat(decoded.variables().getFirst().defaultValue()).isEqualTo(new BigDecimal("1.00"));
        String workspace=UUID.randomUUID().toString();assertThat(decoded.requestSha(workspace)).isEqualTo(original.requestSha(workspace));
        var reference=PromptRevision.referenceFor("domain-prompt","domain-revision",1,original.kind(),original.name(),original.body(),
                Set.copyOf(original.scenarioIds()),Set.copyOf(original.intentIds()),original.audienceRef(),original.language(),original.displayOrder(),original.variables());
        assertThat(decoded.contentSha()).isEqualTo(reference.contentSha256());
        assertThatThrownBy(()->decoded.scenarioIds().add("mutation")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void requestHashBindsWorkspaceAndSourceWhileContentHashExcludesBoth(){
        var a=command();var b=new ProductPromptCreateCommand(0,a.kind(),a.name(),a.body(),List.of("b","a"),a.intentIds(),a.audienceRef(),a.language(),0,a.variables(),UUID.randomUUID().toString());
        String workspace=UUID.randomUUID().toString();
        assertThat(a.contentSha()).isEqualTo(b.contentSha());assertThat(a.requestSha(workspace)).isNotEqualTo(b.requestSha(workspace));
        assertThat(a.requestSha(workspace)).isNotEqualTo(a.requestSha(UUID.randomUUID().toString()));
    }
    @ParameterizedTest @ValueSource(strings={"unclosed {{", "stray }}", "{{bad-name}}", "{{outer {{inner}}"})
    void malformedTemplateIsAnInputErrorBeforeAnyPermissionOrPersistence(String body)throws Exception{
        ObjectNode value=mapper.valueToTree(command());value.put("body",body);
        assertThatThrownBy(()->ProductPromptCreateCommand.decode(mapper.writeValueAsBytes(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"server-coordinate","missing-generation","missing-variable-field","string-generation","float-generation","numeric-kind","null-display-order","wrong-default","unknown-variable-field","trailing","duplicate-key","bad-utf8"})
    void malformedOrServerSuppliedCoordinatesCannotBecomeCommands(String fault)throws Exception{
        ObjectNode value=mapper.valueToTree(command());
        switch(fault){
            case "server-coordinate"->value.put("subject","administrator");
            case "missing-generation"->value.remove("expectedGeneration");
            case "string-generation"->value.put("expectedGeneration","0");
            case "float-generation"->value.put("expectedGeneration",0.0);
            case "numeric-kind"->value.put("kind",1);
            case "null-display-order"->value.putNull("displayOrder");
            case "missing-variable-field"->((ObjectNode)value.path("variables").get(0)).remove("allowEmpty");
            case "wrong-default"->((ObjectNode)value.path("variables").get(0)).put("defaultValue","1.00");
            case "unknown-variable-field"->((ObjectNode)value.path("variables").get(0)).put("permission",true);
            default->{ }
        }
        String raw=value.toString();if(fault.equals("trailing"))raw+=" {}";
        if(fault.equals("duplicate-key"))raw=raw.replace("\"expectedGeneration\":0","\"expectedGeneration\":0,\"expectedGeneration\":0");
        byte[] bytes=fault.equals("bad-utf8")?new byte[]{(byte)0xc3,0x28}:raw.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->ProductPromptCreateCommand.decode(bytes)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bodyDefaultAndCollectionLimitsRejectWithoutTruncation()throws Exception{
        ObjectNode value=mapper.valueToTree(command());value.put("body","字".repeat(90000));
        assertThatThrownBy(()->ProductPromptCreateCommand.decode(mapper.writeValueAsBytes(value))).isInstanceOf(IllegalArgumentException.class);
        value.put("body","safe");var variable=(ObjectNode)value.path("variables").get(0);variable.put("type","STRING");variable.put("defaultValue","x".repeat(65537));
        assertThatThrownBy(()->ProductPromptCreateCommand.decode(mapper.writeValueAsBytes(value))).isInstanceOf(IllegalArgumentException.class);
        variable.put("defaultValue","safe");value.putArray("scenarioIds");for(int i=0;i<65;i++)value.withArray("scenarioIds").add("same");
        assertThatThrownBy(()->ProductPromptCreateCommand.decode(mapper.writeValueAsBytes(value))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductPromptCreateCommand.decode(new byte[1024*1024+1])).isInstanceOf(IllegalArgumentException.class);
    }
}
