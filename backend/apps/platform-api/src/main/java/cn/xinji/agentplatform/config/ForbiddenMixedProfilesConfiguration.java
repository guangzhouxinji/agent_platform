package cn.xinji.agentplatform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("prod & (test-synthetic | public-local-test | dm8-integration)")
public class ForbiddenMixedProfilesConfiguration {
    public ForbiddenMixedProfilesConfiguration() {
        throw new IllegalStateException("Production cannot start with historical test profiles");
    }
}
