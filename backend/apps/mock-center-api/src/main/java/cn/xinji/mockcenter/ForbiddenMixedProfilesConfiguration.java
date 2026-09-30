package cn.xinji.mockcenter;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("prod & test-synthetic")
public class ForbiddenMixedProfilesConfiguration {
    public ForbiddenMixedProfilesConfiguration() {
        throw new IllegalStateException("Production cannot start with the mock-center test profile");
    }
}
