package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.model.gateway.*;
import cn.xinji.agentplatform.runtime.ModelGateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local transport boundary tests, not vendor approval or domain quality acceptance. */
class AgentModelGatewayTest {
    HttpServer server;final AtomicInteger requests=new AtomicInteger();String received;String auth;
    Configuration config;OpenAiModelGateway gateway;
    final String body="{\"model\":\"configured-model\",\"messages\":[{\"role\":\"user\",\"content\":\"data only\"}]}";
    final String reply="{\"model\":\"configured-model\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/model",exchange->{requests.incrementAndGet();received=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);auth=exchange.getRequestHeaders().getFirst("Authorization");
            byte[] raw=reply.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);exchange.close();});server.start();
        config=configuration(65536,Duration.ofSeconds(2));
        gateway=new OpenAiModelGateway(id->new OpenAiModelGateway.Connection(config,"private-test-key"),new ObjectMapper());
    }
    @AfterEach void stop(){server.stop(0);}
    Configuration configuration(int maxResponse,Duration timeout){return new Configuration("configured","version-1","approved-provider","configured-model",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/model"),"OPENAI_CHAT_COMPLETIONS",timeout,65536,maxResponse,Set.of("candidate-analysis"),Set.of("approved-data"));}
    Call call(){return new Call("configured","candidate-analysis","approved-data","prompt-2","quality-3","trace-1",body);}
    @Test void sendsExactRequestWithConfiguredModelAndReturnsMeasuredUsage(){var result=gateway.invoke(call());assertEquals(body,received);assertEquals("Bearer private-test-key",auth);assertEquals(10L,result.usage().inputTokens());assertEquals(2L,result.usage().outputTokens());assertEquals(reply,result.rawResponse());assertTrue(result.elapsedMillis()>=0);}
    @Test void purposeOrDataClassMismatchNeverReachesServer(){var wrong=new Call("configured","foreign-purpose","approved-data","p","q","trace-1",body);assertEquals("MODEL_PURPOSE_NOT_APPROVED",assertThrows(Failure.class,()->gateway.invoke(wrong)).code());assertEquals(0,requests.get());}
    @Test void requestForDifferentModelIsNotSent(){var wrong=new Call("configured","candidate-analysis","approved-data","p","q","trace-1",body.replace("configured-model","foreign-model"));assertEquals("MODEL_REQUEST_NOT_APPROVED",assertThrows(Failure.class,()->gateway.invoke(wrong)).code());assertEquals(0,requests.get());}
    @Test void streamedOversizeResponseIsRejectedAtBound(){config=configuration(8,Duration.ofSeconds(2));assertEquals("OUTPUT_SIZE_LIMIT",assertThrows(Failure.class,()->gateway.invoke(call())).code());}
    @Test void configurationChangeDuringExternalCallIsRejected(){var lookups=new AtomicInteger();gateway=new OpenAiModelGateway(id->{if(lookups.incrementAndGet()>1)config=new Configuration(config.id(),"version-2",config.provider(),config.model(),config.endpoint(),config.protocol(),config.timeout(),config.maxInputBytes(),config.maxResponseBytes(),config.purposes(),config.dataClasses());return new OpenAiModelGateway.Connection(config,"private-test-key");},new ObjectMapper());assertEquals("MODEL_CONFIGURATION_CHANGED",assertThrows(Failure.class,()->gateway.invoke(call())).code());}
    @Test void redirectsAreNotFollowed(){server.removeContext("/model");server.createContext("/model",exchange->{requests.incrementAndGet();exchange.getResponseHeaders().add("Location","/foreign");exchange.sendResponseHeaders(302,-1);exchange.close();});assertEquals("UPSTREAM_REJECTED_302",assertThrows(Failure.class,()->gateway.invoke(call())).code());assertEquals(1,requests.get());}
    @Test void entireResponseIncludingBodyHasTimeout(){server.removeContext("/model");server.createContext("/model",exchange->{try{exchange.sendResponseHeaders(200,0);Thread.sleep(200);exchange.getResponseBody().write(reply.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}finally{exchange.close();}});config=configuration(65536,Duration.ofMillis(40));assertEquals("TIMEOUT",assertThrows(Failure.class,()->gateway.invoke(call())).code());}
    @Test void unknownUsageIsNotFabricatedAsZero(){server.removeContext("/model");server.createContext("/model",exchange->{byte[] raw="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);exchange.close();});var result=gateway.invoke(call());assertNull(result.usage().inputTokens());assertNull(result.usage().outputTokens());}
    @Test void supplierEndpointCannotBeChangedOutsideExistingMaterialApproval(){assertThrows(IllegalArgumentException.class,()->ModelProviderDefaults.approvedDeepSeekEndpoint(URI.create("https://unapproved.example/chat/completions")));assertThrows(IllegalArgumentException.class,()->ModelProviderDefaults.approvedDeepSeekEndpoint(URI.create("https://api.deepseek.com/chat/completions?redirect=foreign")));}
    @Test void credentialsAreRedactedAndControlCharactersReject(){assertFalse(new OpenAiModelGateway.Connection(config,"private-test-key").toString().contains("private-test-key"));assertThrows(Failure.class,()->new OpenAiModelGateway.Connection(config,"key\r\nheader"));}
}
