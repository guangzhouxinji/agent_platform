package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.V0EnforcementAuthorizationAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementAuthorizationPort;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnforcementApplicationConfiguration {
    @Bean
    @ConditionalOnMissingBean(EnforcementAuthorizationPort.class)
    EnforcementAuthorizationPort enforcementAuthorizationPort() {
        return new V0EnforcementAuthorizationAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(EnforcementSourcePort.class)
    SyntheticEnforcementSourceAdapter enforcementSourcePort() {
        return new SyntheticEnforcementSourceAdapter();
    }

    @Bean
    EnforcementApplicationService enforcementApplicationService(
            EnforcementStore store,
            EnforcementAuthorizationPort authorization,
            EnforcementSourcePort sources) {
        return new EnforcementApplicationService(store, authorization, sources);
    }
}
