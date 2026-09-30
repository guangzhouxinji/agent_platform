package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.*;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test-synthetic")
@ConditionalOnProperty(name = "patent.fixture.enabled", havingValue = "true")
public class PatentFixtureFlowConfiguration {
    @Bean
    PatentConsultationFixtureFlow patentConsultationFixtureFlow() {
        TaskAuthorizationCheck fixtureAuthorization = action -> TaskAuthorizationDecision.allow("fixture-local-only");
        PatentTaskCommandService tasks = new PatentTaskCommandService(
                new FixtureInMemoryPatentTaskCommandRepository(), fixtureAuthorization);
        return new PatentConsultationFixtureFlow(tasks, fixtureAuthorization, new FixturePatentSource());
    }
}
