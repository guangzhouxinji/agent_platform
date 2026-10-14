package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Configuration factory/schema checks with real JDBC bindings. Unconsumed comparison dependencies are mocks;
 * this does not claim a full production comparison context, actual maintenance admission or DM8 startup. */
class ProductHumanRequestConfigurationTest {
    @TempDir Path root;

    @Test void defaultOffRegistersNeitherApplicationNorController() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(ProductHumanRequestConfiguration.class, ProductHumanRequestController.class);
            context.refresh();
            assertThat(context.getBeansOfType(ProductHumanRequestApplication.class)).isEmpty();
            assertThat(context.getBeansOfType(ProductHumanRequestController.class)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"product.identity.enabled", "product.source.engine.enabled",
            "product.consultation.comparison.enabled"})
    void missingPrerequisiteCannotSilentlyAssemblePartialService(String property) {
        var settings = settings(); settings.remove(property);
        assertThatThrownBy(() -> new ProductHumanRequestConfiguration(environment(settings)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(property);
    }

    @ParameterizedTest @ValueSource(strings = {"product.identity.issuer", "product.consultation.retention.ref",
            "product.consultation.retention.version", "product.consultation.retention.evidence-sha256",
            "product.consultation.retention.max-age", "product.consultation.goal-retention.ref",
            "product.consultation.goal-retention.version", "product.consultation.goal-retention.evidence-sha256",
            "product.consultation.goal-retention.max-age", "product.consultation.human-request.retention.ref",
            "product.consultation.human-request.retention.version", "product.consultation.human-request.retention.evidence-sha256"})
    void missingPolicyOrIssuerHasNoPlaceholderFallback(String property) {
        var settings = settings(); settings.remove(property);
        assertThatThrownBy(() -> new ProductHumanRequestConfiguration(environment(settings)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(property);
    }

    @ParameterizedTest @ValueSource(strings = {"con6_human_package", "con6_human_handling", "con6_human_event",
            "con6_human_receipt", "con6_human_cleanup_scope", "con6_human_cleanup_event", "id6_human_cleanup_scope", "body_access_until"})
    void missingSchemaPreventsFactoryAssemblyWithoutAutomaticMigration(String missing) throws Exception {
        var fixture = fixture();
        if (missing.equals("body_access_until")) fixture.base.db.execute(
                "ALTER TABLE con6_human_package RENAME COLUMN body_access_until TO absent_body_access_until");
        else (missing.startsWith("id6") ? fixture.base.authority : fixture.base.db).execute(
                "ALTER TABLE " + missing + " RENAME TO absent_human_table");
        assertThatThrownBy(() -> assemble(fixture)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void legitimateSettingsConstructSharedClockApplicationWithoutStartupWritesOrMaintenanceAuthority() throws Exception {
        var fixture = fixture();
        var before = counts(fixture);
        var application = assemble(fixture);
        assertThat(application).isNotNull();
        assertThat(counts(fixture)).isEqualTo(before);
        var cleanup = (ProductHumanCleanupScope) ReflectionTestUtils.getField(application, "cleanup");
        assertThat(cleanup).isNotNull();
        assertThatThrownBy(() -> cleanup.initialize(fixture.base.task.id(),
                new ProductHumanCleanupScope.Policy("human-v3", 3, "e".repeat(64)), "operator",
                fixture.base.task.expiresAt(), fixture.base.clock.instant(), fixture.base.task.expiresAt()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Request entry cannot initialize human cleanup scope");
        assertThat(counts(fixture)).isEqualTo(before);
    }

    private ProductHumanRequestApplicationFixture fixture() throws Exception {
        var fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true);
        return fixture;
    }

    private ProductHumanRequestApplication assemble(ProductHumanRequestApplicationFixture fixture) {
        var base = fixture.base;
        var json = new ProductComparisonJson(fixture.mapper);
        var inputs = mock(ProductComparisonInputApplication.class);
        var inputStore = new ProductComparisonInputStore(base.db, json);
        var matrixStore = mock(ProductComparisonMatrixStore.class);
        var calculation = new ProductComparisonMatrixCalculation(json);
        var sources = new ProductSourceAvailability(base.db,
                new ProductSourceWatermark(base.db, base.authority, new TransactionTemplate(base.manager)), base.clock);
        // Real reader objects retain real DB/transaction requireBinding; only unused business dependencies are replaced.
        var matrices = new ProductComparisonMatrixReader(new JdbcProductTaskDirectory(base.db, base.clock), base.access,
                inputStore, mock(ProductComparisonDocuments.class), mock(ProductComparisonTargetReader.class), sources,
                matrixStore, calculation, json, base.db, base.clock);
        var parents = new ProductComparisonParentEvidenceReader(mock(ProductComparisonPreparationLoader.class), inputs,
                inputStore, matrixStore, mock(ProductComparisonLineageStore.class), sources, base.db,
                new TransactionTemplate(base.manager), json, calculation, mock(ProductComparisonLineageFacts.class), base.clock);
        return new ProductHumanRequestConfiguration(environment(settings())).productHumanRequestApplication(base.db,
                base.manager, base.watermark, base.access, base.decoder, new ProductCollectedPatentTitles(fixture.files, fixture.mapper),
                inputs, matrices, parents, json, fixture.mapper);
    }

    private Map<String, Integer> counts(ProductHumanRequestApplicationFixture fixture) {
        var counts = new LinkedHashMap<String, Integer>();
        for (String table : new String[]{"con6_human_package", "con6_human_handling", "con6_human_event", "con6_human_receipt",
                "con6_human_cleanup_scope", "con6_human_cleanup_event"})
            counts.put(table, fixture.base.db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        counts.put("id6_human_cleanup_scope", fixture.base.authority.queryForObject("SELECT COUNT(*) FROM id6_human_cleanup_scope", Integer.class));
        return counts;
    }

    private static MockEnvironment environment(Map<String, Object> settings) {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("human-configuration-test", settings));
        return environment;
    }
    private static Map<String, Object> settings() {
        var settings = new LinkedHashMap<String, Object>();
        for (String flag : new String[]{"product.identity.enabled", "product.source.engine.enabled",
                "product.consultation.comparison.enabled", "product.consultation.human-request.enabled"}) settings.put(flag, "true");
        settings.put("product.identity.issuer", "https://identity.example.org");
        settings.put("product.consultation.retention.ref", "approved-policy");
        settings.put("product.consultation.goal-retention.ref", "goal-policy");
        for (String prefix : new String[]{"product.consultation.retention", "product.consultation.goal-retention"}) {
            settings.put(prefix + ".version", "1"); settings.put(prefix + ".evidence-sha256", "a".repeat(64));
            settings.put(prefix + ".max-age", "PT1H");
        }
        settings.put("product.consultation.human-request.retention.ref", "human-v3");
        settings.put("product.consultation.human-request.retention.version", "3");
        settings.put("product.consultation.human-request.retention.evidence-sha256", "e".repeat(64));
        return settings;
    }
}
