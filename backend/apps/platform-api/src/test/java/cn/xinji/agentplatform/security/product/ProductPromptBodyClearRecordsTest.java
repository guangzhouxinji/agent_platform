package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecordsFixture.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptBodyClearRecordsTest {
    @Test void readyPreservesMaterialAndHistoricalLifetimeOutlastsAction(){
        var request=fixed();var ready=request.ready();var manifest=request.manifest();
        assertThat(ready.manifest()).isSameAs(manifest);assertThat(request.sameMaterial(ready)).isTrue();
        assertThat(ready.receipt().manifest()).isSameAs(manifest);assertThat(ready.state()).isEqualTo("READY");
        assertThat(request.intentUntil()).isEqualTo(AT.plusSeconds(30));
        assertThat(request.receiptUntil()).isEqualTo(request.target().auditUntil());
        assertThat(request.receiptUntil()).isAfter(request.intentUntil());
        assertThat(request.afterHead().revisionId()).isEqualTo(request.before().revisionId());
        assertThat(request.afterHead().generation()).isEqualTo(request.before().generation());
        assertThat(request.afterHead().scalarSha()).isEqualTo(request.afterSha());
        assertThat(request.afterHeader().bodyState()).isEqualTo("CLEARED");
        assertThat(request.afterHeader().contentSha()).isEqualTo(request.target().contentSha());
        request.requireDigest(json());ready.requireDigest(json());
        assertThat(codec().decode(codec().encode(manifest))).isEqualTo(manifest);
    }
    @ParameterizedTest @ValueSource(strings={"requestSha","resultSha","preparedSha","afterSha"})
    void allAuthenticatedDigestsRejectScalarChanges(String field)throws Exception{
        ObjectNode tree=tree();tree.put(field,"f".repeat(64));
        assertThatThrownBy(()->codec().decode(tree.toString())).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"unknown","nestedUnknown","duplicate","trailing","null","numberText","numberFraction",
            "numberOverflow","numberZero","nonCanonicalTime","futureBody","wrongTarget","wrongOriginal","wrongEvidence","wrongAfter",
            "lateIntent","extendedReceipt","lateScope","sameIds","clearedBefore","oversize","oversizeUtf8"})
    void strictDecodeRejectsMalformedOrUnboundMaterial(String failure)throws Exception{
        ObjectNode tree=tree();String raw;
        switch(failure){
            case "unknown" -> tree.put("body","secret");
            case "nestedUnknown" -> object(tree,"target").put("body","secret");
            case "null" -> tree.set("evidence",null);
            case "numberText" -> object(tree,"before").put("generation","2");
            case "numberFraction" -> object(tree,"before").put("generation",2.0);
            case "numberOverflow" -> object(tree,"before").put("generation",new java.math.BigInteger("9223372036854775808"));
            case "numberZero" -> object(tree,"before").put("generation",0);
            case "nonCanonicalTime" -> tree.put("firstAttempt","2026-10-14T00:00:00Z");
            case "futureBody" -> object(tree,"target").put("bodyUntil",AT.plusSeconds(1).toString());
            case "wrongTarget" -> object(tree,"target").put("promptId",WORKSPACE);
            case "wrongOriginal" -> object(tree,"originalReceipt").put("contentSha","f".repeat(64));
            case "wrongEvidence" -> object(tree,"evidence").put("operatorSubject","other");
            case "wrongAfter" -> tree.put("afterSha",tree.get("before").get("scalarSha").asText());
            case "lateIntent" -> tree.put("intentUntil",AT.plusSeconds(301).toString());
            case "extendedReceipt" -> tree.put("receiptUntil",AT.plusSeconds(1201).toString());
            case "lateScope" -> tree.put("closureScopeUntil",AT.plusSeconds(1201).toString());
            case "sameIds" -> tree.put("requestId",tree.get("receiptId").asText());
            case "clearedBefore" -> object(tree,"target").put("bodyState","CLEARED");
            case "oversize" -> tree.put("unused","x".repeat(16385));
            case "oversizeUtf8" -> tree.put("unused","\u4e00".repeat(16385));
            default -> { }
        }
        raw=tree.toString();
        if(failure.equals("duplicate"))raw=raw.replace("\"requestId\":","\"requestId\":\""+tree.get("requestId").asText()+"\",\"requestId\":");
        if(failure.equals("trailing"))raw+=" {}";
        final String malformed=raw;
        assertThatThrownBy(()->codec().decode(malformed)).as(failure).isInstanceOf(IllegalStateException.class);
    }
    @Test void exactLongBeyondDoublePrecisionRoundTrips()throws Exception{
        var r=fixed();var w=r.workspace();
        var workspace=new ProductPromptRecords.Workspace(w.id(),w.organization(),9007199254740993L,w.state(),w.policyRef(),w.policySha(),w.registrySha(),w.validUntil());
        var prepared=codec().prepare(workspace,r.evidence(),r.before(),r.target(),r.originalReceipt(),r.key(),r.afterSha(),r.firstAttempt(),r.closureScopeUntil(),r.intentUntil());
        assertThat(codec().decode(codec().encode(prepared.manifest())).workspace().scopeVersion()).isEqualTo(9007199254740993L);
    }
    @Test void everyMappedScalarColumnMustMatchManifest()throws Exception{
        var r=fixed();var values=codec().requestColumns(r);
        assertThat(codec().readRequest(row(values))).isEqualTo(r);
        assertThat(codec().readReceipt(row(codec().receiptColumns(r.receipt())))).isEqualTo(r.receipt());
        for(var field:values.keySet()){
            if(field.equals("manifest_json"))continue;
            var changed=new java.util.LinkedHashMap<>(values);Object old=changed.get(field);
            changed.put(field,old instanceof Instant at?at.plusMillis(1):old instanceof Long?3L:field.equals("state")?"OTHER":"wrong");
            assertThatThrownBy(()->codec().readRequest(row(changed))).as(field).isInstanceOf(RuntimeException.class);
            changed.put(field,null);
            assertThatThrownBy(()->codec().readRequest(row(changed))).as(field+" null").isInstanceOf(RuntimeException.class);
        }
    }
    @Test void visibleOriginalKeyContractIsStrict(){
        var r=fixed();
        for(String key:List.of(""," ","line\n","x".repeat(65),"\u4e00"))
            assertThatThrownBy(()->ProductPromptBodyClearRecords.key(r.workspace(),r.evidence(),PROMPT,REVISION,key)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProductPromptBodyClearRecords.key(r.workspace(),r.evidence(),PROMPT,REVISION,"!".repeat(64)).keySha()).hasSize(64);
    }
    @Test void independentJvmsAuthenticateSameFixedMaterial()throws Exception{
        String raw=codec().encode(fixed().manifest());var results=new HashSet<String>();var pids=new HashSet<Long>();
        String transport=java.util.Base64.getEncoder().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int run=0;run<3;run++){
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                    "-Xmx128m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),getClass().getName(),transport).redirectErrorStream(true).start();
            pids.add(child.pid());
            try{
                assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(child.exitValue()).as(output).isZero();var lines=output.lines().filter(line->line.startsWith("BODY_CLEAR_PROBE ")).toList();
                assertThat(lines).hasSize(1);results.add(lines.getFirst());
            }finally{if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
        }
        assertThat(results).hasSize(1);assertThat(pids).hasSize(3);
    }
    public static void main(String[] args){
        if(args.length!=1)throw new IllegalArgumentException("fixed manifest argument");
        String raw=new String(java.util.Base64.getDecoder().decode(args[0]),java.nio.charset.StandardCharsets.UTF_8);var m=codec().decode(raw);
        System.out.println("BODY_CLEAR_PROBE "+m.requestSha()+" "+m.resultSha()+" "+m.preparedSha()+" "+ProductComparisonJson.hash(codec().encode(m)));
    }
    private static ObjectNode tree()throws Exception{return (ObjectNode)new ObjectMapper().readTree(codec().encode(fixed().manifest()));}
    private static ObjectNode object(JsonNode n,String field){return (ObjectNode)n.get(field);}
    private static ResultSet row(Map<String,Object> values){
        boolean[] wasNull={false};return (ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)->{
            if(method.getName().equals("wasNull"))return wasNull[0];
            Object value=values.get((String)args[0]);wasNull[0]=value==null;
            return switch(method.getName()){
                case "getString" -> value==null?null:value.toString();
                case "getLong" -> value==null?0L:((Number)value).longValue();
                case "getTimestamp" -> value==null?null:Timestamp.from((Instant)value);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
