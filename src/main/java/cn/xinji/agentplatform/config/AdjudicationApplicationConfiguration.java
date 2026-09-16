package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.V0AdjudicationAuthorizationAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AuthorizationPort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdjudicationApplicationConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthorizationPort.class)
    AuthorizationPort adjudicationAuthorizationPort() {
        return new V0AdjudicationAuthorizationAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SourcePort.class)
    SyntheticAdjudicationSourceAdapter adjudicationSourcePort() {
        return new SyntheticAdjudicationSourceAdapter();
    }

    @Bean
    AdjudicationApplicationService adjudicationApplicationService(
            AdjudicationStore store, AuthorizationPort authorization, SourcePort sources) {
        return new AdjudicationApplicationService(store, authorization, sources);
    }
}
