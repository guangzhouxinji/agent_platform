package cn.xinji.agentplatform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.xinji.agentplatform.adjudication.adapter.out.memory.InMemoryAdjudicationStore;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8AdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import cn.xinji.agentplatform.config.AdjudicationApplicationConfiguration;
import cn.xinji.agentplatform.config.EnforcementApplicationConfiguration;
import cn.xinji.agentplatform.config.PublicEnforcementReferenceCatalogConfiguration;
import cn.xinji.agentplatform.config.PublicAdjudicationReferenceCatalogConfiguration;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8PublicAdjudicationReferenceCatalogAdapter;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort;
import cn.xinji.agentplatform.enforcement.adapter.out.memory.InMemoryEnforcementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8PublicEnforcementReferenceCatalogAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.V0EnforcementAuthorizationAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.application.PublicEnforcementReferenceCatalogPort;
import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class DevelopmentOperationalSourceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    EnforcementApplicationConfiguration.class,
                    PublicEnforcementReferenceCatalogConfiguration.class,
                    PublicAdjudicationReferenceCatalogConfiguration.class,
                    AdjudicationApplicationConfiguration.class,
                    DevelopmentOperationalSourceController.class)
            .withBean(EnforcementStore.class, InMemoryEnforcementStore::new)
            .withBean(AdjudicationStore.class, InMemoryAdjudicationStore::new);

    @Test
    void exposesDeterministicReadOnlyCatalogsForTheInternalWorkbench() {
        runner.withPropertyValues(
                "internal-workbench.development-sources-enabled=true",
                "enforcement.source=synthetic",
                "adjudication.source=synthetic").run(context -> {
            assertThat(context).hasSingleBean(SyntheticEnforcementSourceAdapter.class);
            assertThat(context).hasSingleBean(SyntheticAdjudicationSourceAdapter.class);
            DevelopmentOperationalSourceController controller = context.getBean(DevelopmentOperationalSourceController.class);
            assertThat(controller.clues(null, new MockHttpServletRequest()).dataMode()).isEqualTo("SYNTHETIC_V0");
            assertThat(controller.clues(null, new MockHttpServletRequest()).items()).hasSize(30);
            assertThat(controller.evidence(null, new MockHttpServletRequest()).items()).hasSize(119);
            assertThat(controller.publicReferences("v0-enforcement-user").items()).isEmpty();
            assertThat(controller.publicReferences("v0-enforcement-user").limitation()).contains("未配置");
            assertThat(controller.cases().items()).hasSize(20);
            assertThat(controller.materials().items()).hasSize(80);
            assertThat(controller.adjudicationPublicReferences("v0-adjudication-user").items()).isEmpty();
            assertThat(controller.adjudicationPublicReferences("v0-adjudication-user").limitation()).contains("未配置");
            assertThat(controller.clues(null, new MockHttpServletRequest()).limitation()).contains("不得作为真实");
        });
    }

    @Test
    void selectsTheDatabaseSourceWhenADataSourceExists() {
        runner.withPropertyValues("enforcement.source=database", "adjudication.source=database")
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:invalid:test"))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(EnforcementSourcePort.class);
                    assertThat(context).hasSingleBean(Dm8EnforcementSourceAdapter.class);
                    assertThat(context).hasSingleBean(PublicEnforcementReferenceCatalogPort.class);
                    assertThat(context).hasSingleBean(Dm8PublicEnforcementReferenceCatalogAdapter.class);
                    assertThat(context).hasSingleBean(SourcePort.class);
                    assertThat(context).hasSingleBean(Dm8AdjudicationSourceAdapter.class);
                    assertThat(context).hasSingleBean(PublicAdjudicationReferenceCatalogPort.class);
                    assertThat(context).hasSingleBean(Dm8PublicAdjudicationReferenceCatalogAdapter.class);
                    assertThat(context).doesNotHaveBean(SyntheticEnforcementSourceAdapter.class);
                    assertThat(context).doesNotHaveBean(SyntheticAdjudicationSourceAdapter.class);
                });
    }

    @Test
    void removesTheCatalogAndUsesUnavailablePortsWhenTheSwitchIsOff() {
        runner.withPropertyValues(
                "internal-workbench.development-sources-enabled=false",
                "enforcement.source=unavailable",
                "adjudication.source=unavailable").run(context -> {
            assertThat(context).doesNotHaveBean(DevelopmentOperationalSourceController.class);
            assertThat(context).doesNotHaveBean(SyntheticEnforcementSourceAdapter.class);
            assertThat(context).doesNotHaveBean(SyntheticAdjudicationSourceAdapter.class);
        });
    }

    @Test
    void authorizedCatalogRequiresAuthenticationFilterTrustMarker() {
        EnforcementSourcePort enforcement = mock(EnforcementSourcePort.class);
        when(enforcement.dataMode()).thenReturn("AUTHORIZED_LOCAL");
        when(enforcement.catalogVersion()).thenReturn("v1");
        when(enforcement.limitation()).thenReturn("authorized test");
        var controller = new DevelopmentOperationalSourceController(
                enforcement, mock(PublicEnforcementReferenceCatalogPort.class), new V0EnforcementAuthorizationAdapter(),
                mock(SourcePort.class), mock(PublicAdjudicationReferenceCatalogPort.class), mock(cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AuthorizationPort.class));
        MockHttpServletRequest untrusted = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.clues("v0-enforcement-user", untrusted))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(401));

        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setAttribute("agentPlatform.trustedClientId", "client-1");
        assertThat(controller.clues("v0-enforcement-user", trusted).dataMode()).isEqualTo("AUTHORIZED_LOCAL");
    }
}
