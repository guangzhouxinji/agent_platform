package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.persistence.AgentCheckpointCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Final-product feature switch; no development identity, implicit migration or default encryption key. */
@Configuration
@ConditionalOnProperty(name="agent-platform.runtime.enabled",havingValue="true")
public class AgentRuntimeConfiguration {
    @Bean AuthorizedAgentExecution authorizedAgentExecution(org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.jwt.JwtDecoder> decoders) {
        return new AuthorizedAgentExecution(decoders);
    }
    @Bean AgentCheckpointCodec agentCheckpointCodec(ObjectMapper json,@Value("${agent-platform.runtime.checkpoint-key:}")String key) {
        return new AgentCheckpointCodec(json,key);
    }
}
