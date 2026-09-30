package cn.xinji.agentplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ForbiddenMixedProfilesConfigurationTest {
    @Test
    void refusesEveryProductionAndHistoricalProfileCombinationBeforeRunnersStart() {
        for (String historical : new String[] {"test-synthetic", "public-local-test", "dm8-integration"}) {
            new ApplicationContextRunner().withUserConfiguration(ForbiddenMixedProfilesConfiguration.class)
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", historical))
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production cannot start with historical test profiles"));
        }
    }

    @Test
    void ordinaryProductionProfileDoesNotRegisterTheGuard() {
        new ApplicationContextRunner().withUserConfiguration(ForbiddenMixedProfilesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).doesNotHaveBean(ForbiddenMixedProfilesConfiguration.class));
    }
}
