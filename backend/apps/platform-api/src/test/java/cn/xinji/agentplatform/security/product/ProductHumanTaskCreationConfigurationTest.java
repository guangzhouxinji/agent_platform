package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class ProductHumanTaskCreationConfigurationTest {
    @Test void enabledControllerCannotSilentlyFallBackWhenNativeAdapterIsAbsent() throws Exception {
        var f = new ProductHumanTaskCreationFixture();
        var empty = new DefaultListableBeanFactory().getBeanProvider(ProductHumanTaskCreation.class);
        assertThatThrownBy(() -> new ProductConsultationTaskController(f.base.directory, f.base.access, null, null,
                null, null, f.base.service, empty, true)).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("con3_task")).isZero();
    }

    @Test void disabledHumanFeatureUsesOriginalCreationEvenIfAdapterExists() throws Exception {
        var f = new ProductHumanTaskCreationFixture();
        var beans = new DefaultListableBeanFactory();
        var unused = mock(ProductHumanTaskCreation.class); beans.registerSingleton("human", unused);
        var controller = new ProductConsultationTaskController(f.base.directory, f.base.access, null, null, null, null,
                f.base.service, beans.getBeanProvider(ProductHumanTaskCreation.class), false);
        assertThat(controller.create(f.jwt, "plain", f.base.input).taskId()).isNotBlank();
        assertThat(f.count("con6_human_cleanup_scope")).isZero(); verifyNoInteractions(unused);
    }

    @Test void factoryNormalizesPolicyOnceForCreationAndRequestAndDoesNotWriteAtStartup() throws Exception {
        var f = new ProductHumanTaskCreationFixture();
        var configuration = new ProductHumanRequestConfiguration(settings());
        var human = configuration.productHumanTaskCreation(f.base.db, f.manager, f.base.watermark, f.base.access,
                f.decoder, f.base.using(f.base.directory, f.original.clock), f.mapper);
        assertThat(f.count("con3_task")).isZero(); assertThat(f.count("id6_human_cleanup_scope")).isZero();
        human.create(f.jwt, "configured", f.base.input);
        assertThat(f.base.db.queryForObject("SELECT policy_sha FROM con6_human_cleanup_scope", String.class)).isEqualTo("e".repeat(64));
        var policy = (cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention.Policy)
                org.springframework.test.util.ReflectionTestUtils.getField(configuration, "humanPolicy");
        assertThat(policy.evidenceSha256()).isEqualTo("e".repeat(64));
    }

    @ParameterizedTest @ValueSource(strings = {"subject_kind", "id2_client_current"})
    void requiredIdentitySchemaFailsClosedWithoutMigration(String absent) throws Exception {
        var f = new ProductHumanTaskCreationFixture();
        if (absent.equals("subject_kind")) f.base.db.execute("ALTER TABLE id2_subject RENAME COLUMN subject_kind TO absent_kind");
        else f.base.db.execute("ALTER TABLE id2_client_current RENAME TO absent_client_current");
        assertThatThrownBy(() -> new ProductHumanRequestConfiguration(settings()).productHumanTaskCreation(f.base.db,
                f.manager, f.base.watermark, f.base.access, f.decoder, f.base.service, f.mapper))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.count("con3_task")).isZero();
    }

    private MockEnvironment settings() {
        var env = new MockEnvironment();
        for (String flag : new String[]{"product.identity.enabled", "product.source.engine.enabled", "product.consultation.comparison.enabled"})
            env.withProperty(flag, "true");
        env.withProperty("product.identity.issuer", ProductTaskCreationFixture.ISSUER);
        for (String prefix : new String[]{"product.consultation.retention", "product.consultation.goal-retention"}) {
            env.withProperty(prefix + ".ref", "approved").withProperty(prefix + ".version", "1")
                    .withProperty(prefix + ".evidence-sha256", "a".repeat(64)).withProperty(prefix + ".max-age", "PT2H");
        }
        return env.withProperty("product.consultation.human-request.retention.ref", "human-v3")
                .withProperty("product.consultation.human-request.retention.version", "3")
                .withProperty("product.consultation.human-request.retention.evidence-sha256", "E".repeat(64));
    }
}
