package cn.xinji.mockcenter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.mockcenter.adapter.in.web.MockCenterPatentCatalogController;
import cn.xinji.mockcenter.application.MockCenterPatentCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MockCenterPatentCatalogControllerTest {
    @Test void productionCannotStartWithTheMockCenterTestProfile() {
        new ApplicationContextRunner().withUserConfiguration(ForbiddenMixedProfilesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "test-synthetic"))
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Production cannot start with the mock-center test profile"));
    }

    @Test void explicitSwitchDoesNotExposeDevelopmentServiceInOrdinaryProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockCenterConfiguration.class, MockCenterPatentCatalogController.class)
                .withPropertyValues("mock-center.service.enabled=true", "mock-center.seed.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MockCenterConfiguration.class);
                    assertThat(context).doesNotHaveBean(MockCenterPatentCatalogController.class);
                });
    }

    @Test void exposesOnlyReadOnlyNonCenterResources() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new MockCenterPatentCatalogController(new FixedCatalog())).build();
        mvc.perform(get("/api/dev/mock-center/patents/metadata")).andExpect(status().isOk()).andExpect(jsonPath("$.dataMode").value("PUBLIC_PILOT")).andExpect(jsonPath("$.authorityStatus").value("NON_CENTER_PUBLIC_SOURCE"));
        mvc.perform(get("/api/dev/mock-center/patents").param("pageSize","51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/dev/mock-center/patents/missing")).andExpect(status().isNotFound());
        mvc.perform(post("/api/dev/mock-center/patents")).andExpect(status().isMethodNotAllowed());
    }
    private static final class FixedCatalog implements MockCenterPatentCatalog {
        public Metadata metadata(){return new Metadata(0,"mock-v1","batch-1");}
        public Page search(Query query){return new Page(List.of(),query.page(),query.pageSize(),0);}
        public Optional<PatentRecord> find(String id){return Optional.empty();}
    }
}
