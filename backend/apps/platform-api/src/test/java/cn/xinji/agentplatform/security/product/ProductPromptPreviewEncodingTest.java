package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.prompt.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptPreviewEncodingTest {
    static final String ID="00000000-0000-0000-0000-000000000001",SHA="a".repeat(64);
    static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    final ObjectMapper json=new ObjectMapper();
    @Test void largeIntegerAndPreciseDecimalKeepStringWireAndExactJavaValue()throws Exception{
        var command=decode("[{\"name\":\"i\",\"type\":\"INTEGER\",\"value\":\"9007199254740993\"},"
                +"{\"name\":\"d\",\"type\":\"DECIMAL\",\"value\":\"1.23000000000000000000000001\"}]");
        assertThat(command.values().get(0).value()).isEqualTo(new BigInteger("9007199254740993"));
        assertThat(command.values().get(1).value()).isEqualTo(new BigDecimal("1.23000000000000000000000001"));
        var defs=List.of(def("i",VariableDefinition.Type.INTEGER,null),def("d",VariableDefinition.Type.DECIMAL,null));
        var revision=revision("{{i}}/{{d}}",defs);var values=command.supplied(revision,Map.of("i","integer-evidence","d","decimal-evidence"));
        var body=json.readTree(output(revision,values));
        assertThat(body.path("segments").get(0).path("value").isTextual()).isTrue();
        assertThat(body.path("segments").get(0).path("value").asText()).isEqualTo("9007199254740993");
        assertThat(body.path("segments").get(2).path("value").asText()).isEqualTo("1.23000000000000000000000001");
        assertThat(body.path("revisionNumber").asText()).isEqualTo("1");assertThat(body.has("issues")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"+1","01","1.",".5","NaN","Infinity"," 1","0x1","1e"})
    void rejectsLooseNumericLexical(String value){assertThatThrownBy(()->ProductPromptPreviewValues.number(VariableDefinition.Type.DECIMAL,value)).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"1e4097","1e-4097","1e2147483647","1e-2147483647"})
    void rejectsExponentsBeforeAnyExpansion(String value){assertThatThrownBy(()->ProductPromptPreviewValues.number(VariableDefinition.Type.DECIMAL,value)).isInstanceOf(ProductPromptPreviewLimit.class);}
    @Test void defaultExtremeScaleAndLongLexicalAreBounded(){
        assertThatThrownBy(()->ProductPromptPreviewValues.wire(VariableDefinition.Type.DECIMAL,new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE))).isInstanceOf(ProductPromptPreviewLimit.class);
        assertThatThrownBy(()->ProductPromptPreviewValues.number(VariableDefinition.Type.INTEGER,"1".repeat(1025))).isInstanceOf(ProductPromptPreviewLimit.class);
        assertThat(ProductPromptPreviewValues.wire(VariableDefinition.Type.DECIMAL,new BigDecimal("1e4096"))).isEqualTo("1E+4096");
    }
    @ParameterizedTest @ValueSource(strings={
            "[{\"name\":\"i\",\"type\":\"INTEGER\",\"value\":9007199254740993}]",
            "[{\"name\":\"i\",\"type\":\"BOOLEAN\",\"value\":\"true\"}]",
            "[{\"name\":\"i\",\"type\":\"STRING_LIST\",\"value\":[1]}]",
            "[{\"name\":\"i\",\"type\":\"STRING\",\"value\":\"x\",\"evidenceId\":\"forged\"}]",
            "[{\"name\":\"i\",\"type\":\"STRING\",\"value\":\"x\",\"value\":\"y\"}]",
            "[{\"name\":\"i\",\"type\":\"STRING\",\"value\":\"x\"},{\"name\":\"i\",\"type\":\"STRING\",\"value\":\"y\"}]"})
    void strictShapeAndTypesRejectForgedInput(String rows){assertThatThrownBy(()->decode(rows)).isInstanceOf(IllegalArgumentException.class);}
    @Test void invalidUtf8AndUnpairedEscapesFail(){
        assertThatThrownBy(()->ProductPromptPreviewCommand.decode(new byte[]{(byte)0xc3,0x28})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decode("[{\"name\":\"x\",\"type\":\"STRING\",\"value\":\"\\ud800\"}]")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nullUsesApprovedDefaultButEmptyRemainsAnEmptyValue()throws Exception{
        var revision=revision("{{x}}",List.of(def("x",VariableDefinition.Type.STRING,"default")));
        var command=decode("[{\"name\":\"x\",\"type\":\"STRING\",\"value\":null}]");
        var value=json.readTree(output(revision,command.supplied(revision,Map.of("x","sample-evidence")))) .path("segments").get(0);
        assertThat(value.path("value").asText()).isEqualTo("default");assertThat(value.path("sourceEvidenceId").asText()).isEqualTo("logical-policy");
        var empty=decode("[{\"name\":\"x\",\"type\":\"STRING\",\"value\":\"\"}]");
        assertThatThrownBy(()->output(revision,empty.supplied(revision,Map.of("x","e")))).isInstanceOf(PromptFailure.class);
    }
    @Test void schemaBindsOrderDefaultsAndEmptyRulesWithoutChangingContent(){
        var a=def("a",VariableDefinition.Type.DECIMAL,new BigDecimal("1.2300"));var b=def("b",VariableDefinition.Type.INTEGER,5);
        String hash=ProductPromptPreviewValues.schemaSha(List.of(a,b));
        assertThat(hash).isNotEqualTo(ProductPromptPreviewValues.schemaSha(List.of(b,a)));
        assertThat(hash).isNotEqualTo(ProductPromptPreviewValues.schemaSha(List.of(def("a",VariableDefinition.Type.DECIMAL,new BigDecimal("1.23")),b)));
        assertThat(hash).isNotEqualTo(ProductPromptPreviewValues.schemaSha(List.of(new VariableDefinition("a",a.type(),true,"source",true,a.defaultValue(),true),b)));
        assertThat(hash).isEqualTo(ProductPromptPreviewValues.schemaSha(List.of(a,b)));
    }
    @Test void repeatedLargeDataAndEscapingRejectBeforeResponseAllocationCompletes(){
        var revision=revision("{{x}}".repeat(20),List.of(def("x",VariableDefinition.Type.STRING,null)));
        assertThatThrownBy(()->output(revision,Map.of("x",new TemplateRenderer.Value("v".repeat(65536),"source","e")))).isInstanceOf(ProductPromptPreviewLimit.class);
        var escaped=revision("{{x}}".repeat(6),revision.variables());
        assertThatThrownBy(()->output(escaped,Map.of("x",new TemplateRenderer.Value("\u0000".repeat(65536),"source","e")))).isInstanceOf(ProductPromptPreviewLimit.class);
    }
    @Test void repeatedDefaultListIsAlsoBounded(){
        var revision=revision("{{x}}".repeat(16),List.of(def("x",VariableDefinition.Type.STRING_LIST,List.of("v".repeat(65536)))));
        assertThatThrownBy(()->output(revision,Map.of())).isInstanceOf(ProductPromptPreviewLimit.class);
    }
    @Test void diagnosticsExcludeValuesAndRejectPolicyErrors()throws Exception{
        var body=json.readTree(ProductPromptPreviewOutput.diagnostics(List.of(new PromptFailure.Issue("MISSING_VARIABLE","x")),NOW.plusSeconds(60)));
        assertThat(body.has("segments")).isFalse();assertThat(body.path("expiresAt").asText()).isEqualTo(NOW.plusSeconds(60).toString());
        assertThatThrownBy(()->ProductPromptPreviewOutput.diagnostics(List.of(new PromptFailure.Issue("VARIABLE_POLICY_UNAVAILABLE","x")),NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->ProductPromptPreviewOutput.diagnostics(List.of(new PromptFailure.Issue("UNDEFINED_VARIABLE","secret path")),NOW)).isInstanceOf(IllegalStateException.class);
    }
    private ProductPromptPreviewCommand decode(String rows){
        return ProductPromptPreviewCommand.decode(("{\"contentSha\":\""+SHA+"\",\"sourceId\":\""+ID+"\",\"values\":"+rows+"}").getBytes(StandardCharsets.UTF_8));
    }
    private static VariableDefinition def(String name,VariableDefinition.Type type,Object defaultValue){return new VariableDefinition(name,type,true,"source",defaultValue!=null,defaultValue,false);}
    private static PromptRevision revision(String body,List<VariableDefinition> defs){
        var ref=PromptRevision.referenceFor(ID,ID,1,PromptRevision.Kind.GUIDE,"Preview",body,Set.of(),Set.of(),"internal","zh",0,defs);
        return new PromptRevision(ref,PromptRevision.Kind.GUIDE,"Preview",body,Set.of(),Set.of(),"internal","zh",0,defs,
                new PromptRevision.Provenance("source","member",NOW,"member",NOW,"CREATE",List.of(),"approval","retention"));
    }
    private byte[] output(PromptRevision revision,Map<String,TemplateRenderer.Value> supplied){
        var defs=new LinkedHashMap<String,VariableDefinition>();revision.variables().forEach(v->defs.put(v.name(),v));
        var policy=new TemplateRenderer.Policy("logical-policy",SHA,NOW.plusSeconds(60),defs);
        var result=new TemplateRenderer().render(revision,supplied,policy,TemplateRenderer.Mode.TEST,NOW,1024);
        var header=new ProductPromptRecords.Header(ID,ID,1,revision.reference().contentSha256(),SHA,NOW,NOW.plusSeconds(90),NOW.plusSeconds(180),"PRESENT");
        return ProductPromptPreviewOutput.success(ID,revision,header,ProductPromptPreviewValues.schemaSha(revision.variables()),SHA,NOW.plusSeconds(60),result);
    }
}
