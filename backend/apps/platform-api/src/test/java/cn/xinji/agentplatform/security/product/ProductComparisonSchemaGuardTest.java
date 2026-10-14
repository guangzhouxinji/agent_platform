package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Physical H2 migrations/zero-row startup probes only; no formal DM8 history or business admission. */
class ProductComparisonSchemaGuardTest {
    ProductComparisonCandidateExecutionTest f;
    JdbcTemplate db;
    @BeforeEach void setup() throws Exception {
        f=new ProductComparisonCandidateExecutionTest();f.setup();db=f.source.fixture.db;
        var source=f.source.fixture.input.main;
        ProductSourceScopeGrantTest.script(source,"db/dm8/V100__product_comparison_matrix.sql");
        ProductSourceScopeGrantTest.script(source,"db/dm8/V101__product_comparison_prepare_request.sql");
    }
    static Stream<ProductComparisonSchemaGuard.Shape> shapes() {
        return Stream.concat(ProductComparisonSchemaGuard.INPUT.stream(),ProductComparisonSchemaGuard.MATRIX.stream());
    }
    @Test void actualMigrationsPermitInputAndMatrixStartupWithoutReadingRowsOrWriting() {
        var observed=spy(db);long before=f.count("con5_comparison_input");
        new ProductComparisonSchemaGuard(observed,true);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(observed,times(19)).query(sql.capture(),any(ResultSetExtractor.class));
        assertThat(sql.getAllValues()).allMatch(x->x.startsWith("SELECT ")&&x.endsWith(" WHERE 1=0"));
        assertThat(f.count("con5_comparison_input")).isEqualTo(before);
    }
    @ParameterizedTest @MethodSource("shapes") void eachMissingTableRejectsBeforeReady(ProductComparisonSchemaGuard.Shape shape) {
        db.execute("ALTER TABLE "+shape.table()+" RENAME TO old_"+shape.table());
        assertThatThrownBy(()->new ProductComparisonSchemaGuard(db,true))
                .hasMessage("COMPARISON_SCHEMA_REQUIRED:"+shape.migration()+":"+shape.table());
    }
    @ParameterizedTest @MethodSource("shapes") void eachMalformedProjectionRejectsBeforeReady(ProductComparisonSchemaGuard.Shape shape) {
        db.execute("ALTER TABLE "+shape.table()+" RENAME COLUMN "+shape.columns().split(",")[0]+" TO unavailable_column");
        assertThatThrownBy(()->new ProductComparisonSchemaGuard(db,true))
                .hasMessage("COMPARISON_SCHEMA_REQUIRED:"+shape.migration()+":"+shape.table());
    }
    @Test void inputOnlyDoesNotRequireUnopenedMatrixTables() {
        for(var shape:ProductComparisonSchemaGuard.MATRIX)db.execute("ALTER TABLE "+shape.table()+" RENAME TO old_"+shape.table());
        assertThatCode(()->new ProductComparisonSchemaGuard(db,false)).doesNotThrowAnyException();
        assertThatThrownBy(()->new ProductComparisonSchemaGuard(db,true)).hasMessageContaining("V100");
    }
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(ProductComparisonSchemaConfiguration.class)
                .withBean(JdbcTemplate.class,()->db);
    }
    @Test void contextFailsClosedWhenV101IsMissing() {
        db.execute("ALTER TABLE con5_preparation_request RENAME TO old_request");
        context().withPropertyValues("product.identity.enabled=true","product.source.engine.enabled=true")
                .run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasRootCauseMessage("COMPARISON_SCHEMA_REQUIRED:V101:con5_preparation_request");});
    }
    @Test void enabledContextHasGuardAndDefaultLeavesCalculationGated() {
        db.execute("ALTER TABLE con5_matrix_invocation RENAME TO old_invocation");
        context().withPropertyValues("product.identity.enabled=true","product.source.engine.enabled=true")
                .run(c->assertThat(c).hasSingleBean(ProductComparisonSchemaGuard.class));
        context().withPropertyValues("product.identity.enabled=true","product.source.engine.enabled=true","product.consultation.comparison.enabled=true")
                .run(c->assertThat(c).hasFailed());
    }
    @Test void disabledProductDoesNotProbeOrImplicitlyEnableComparison() {
        var untouched=mock(JdbcTemplate.class);
        new ApplicationContextRunner().withUserConfiguration(ProductComparisonSchemaConfiguration.class)
                .withBean(JdbcTemplate.class,()->untouched).withPropertyValues("product.identity.enabled=true","product.source.engine.enabled=false")
                .run(c->assertThat(c).doesNotHaveBean(ProductComparisonSchemaGuard.class));
        verify(untouched,never()).query(anyString(),any(ResultSetExtractor.class));
    }
}
