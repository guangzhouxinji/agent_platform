package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProductPromptRegisterPinsTest {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static Map<String,Object> pin(int index) {
        return Map.of("workspaceId",new UUID(0,index+1).toString(),"approvalRef","register-"+index+".json",
                "approvalSha",String.format("%064x",index+1));
    }
    private static String json(Object value)throws Exception{return MAPPER.writeValueAsString(value);}
    @Test void acceptsOneThrough128UniqueImmutableCanonicalPins()throws Exception {
        var values=new ArrayList<Map<String,Object>>();
        for(int index=0;index<128;index++)values.add(pin(index));
        var result=ProductPromptRegisterConfiguration.pins(json(values));assertThat(result).hasSize(128);
        assertThat(result.getFirst().workspaceId()).isEqualTo(pin(0).get("workspaceId"));
        assertThatThrownBy(()->result.clear()).isInstanceOf(UnsupportedOperationException.class);
        values.add(pin(128));assertThatThrownBy(()->ProductPromptRegisterConfiguration.pins(json(values))).isInstanceOf(RuntimeException.class);
    }
    @Test void honorsUtf8ByteLimitIncludingValidJsonWhitespace()throws Exception {
        String raw=json(List.of(pin(0)));String exact=raw+" ".repeat(65536-raw.getBytes(StandardCharsets.UTF_8).length);
        assertThat(ProductPromptRegisterConfiguration.pins(exact)).hasSize(1);
        assertThatThrownBy(()->ProductPromptRegisterConfiguration.pins(exact+" ")).isInstanceOf(RuntimeException.class);
        var unicode=new HashMap<>(pin(0));unicode.put("approvalRef","批准.json");String utf8=json(List.of(unicode));
        String over=utf8+" ".repeat(65536-utf8.length());
        assertThat(over.length()).isEqualTo(65536);assertThat(over.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65536);
        assertThatThrownBy(()->ProductPromptRegisterConfiguration.pins(over)).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @MethodSource("invalidPins")
    void rejectsAmbiguousNonCanonicalAndUnboundedDeploymentInput(String raw) {
        assertThatThrownBy(()->ProductPromptRegisterConfiguration.pins(raw)).isInstanceOf(RuntimeException.class);
    }
    static Stream<String> invalidPins()throws Exception {
        var invalid=new ArrayList<String>();String one=json(List.of(pin(0)));
        invalid.add(null);invalid.add("");invalid.add("null");invalid.add("{}");invalid.add("[]");invalid.add("[null]");invalid.add("[1]");
        invalid.add(one+" {}");invalid.add(one+" true");invalid.add(one.replace("\"workspaceId\":","\"workspaceId\":\"ignored\",\"workspaceId\":"));
        for(String field:List.of("workspaceId","approvalRef","approvalSha")) {
            var missing=new HashMap<>(pin(0));missing.remove(field);invalid.add(json(List.of(missing)));
            for(Object value:List.of(1,true,List.of("text"),Map.of("text","value"))) {
                var wrong=new HashMap<>(pin(0));wrong.put(field,value);invalid.add(json(List.of(wrong)));
            }
            var nulled=new HashMap<>(pin(0));nulled.put(field,null);invalid.add(json(List.of(nulled)));
            var repeated=new HashMap<>(pin(1));repeated.put(field,pin(0).get(field));invalid.add(json(List.of(pin(0),repeated)));
        }
        var unknown=new HashMap<>(pin(0));unknown.put("approved",true);invalid.add(json(List.of(unknown)));
        for(String[] pair:new String[][]{{"workspaceId","1-1-1-1-1"},{"workspaceId","ABCDEF01-0000-0000-0000-000000000001"},
                {"approvalRef","../register.json"},{"approvalSha","A".repeat(64)},{"approvalSha","f".repeat(63)}}) {
            var value=new HashMap<>(pin(0));value.put(pair[0],pair[1]);invalid.add(json(List.of(value)));
        }
        return invalid.stream();
    }
}
