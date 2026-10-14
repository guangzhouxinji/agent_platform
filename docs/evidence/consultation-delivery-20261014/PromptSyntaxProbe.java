package cn.xinji.agentplatform.security.product;
import java.nio.charset.StandardCharsets;
class PromptSyntaxProbe {
    public static void main(String[] args) {
        byte[] bytes=("{\"expectedGeneration\":0,\"kind\":\"GUIDE\",\"name\":\"draft\",\"body\":\"broken {{\","
                +"\"scenarioIds\":[],\"intentIds\":[],\"audienceRef\":\"internal\",\"language\":\"zh-CN\","
                +"\"displayOrder\":0,\"variables\":[],\"sourceId\":\"10000000-0000-0000-0000-000000000001\"}")
                .getBytes(StandardCharsets.UTF_8);
        try {ProductPromptCreateCommand.decode(bytes);System.out.println("MALFORMED_TEMPLATE_ACCEPTED");}
        catch(IllegalArgumentException expected){System.out.println("MALFORMED_TEMPLATE_REJECTED");}
    }
}
