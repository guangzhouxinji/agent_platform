package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Collected originals and isolated H2/authority ports only; no current formal object is accessed. */
class ProductComparisonWorkbenchTest {
    ProductComparisonInputTest f;
    @BeforeEach void setup() throws Exception {f=new ProductComparisonInputTest();f.inputSetup();ProductSourceScopeGrantTest.script(f.main,"db/dm8/V101__product_comparison_prepare_request.sql");}
    @Test void keyedPreparationReplaysExactIdAndReadWithoutSecondSideEffect() {
        var a=f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt);
        var b=f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt);
        assertThat(a).isEqualTo(b);assertThat(a.taskId()).isEqualTo(f.goal.taskId());assertThat(a.requestId()).isEqualTo("prepare-ui");
        assertThat(f.base.application.readPreparation(f.goal.taskId(),a.preparationId(),f.jwt)).isEqualTo(a);
        assertThat(f.count("con5_preparation_request")).isEqualTo(1);assertThat(f.count("con5_preparation")).isEqualTo(2);
    }
    @Test void sameKeyOtherPayloadAndBlankKeyCannotCreateAnotherPreparation() {
        f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt);
        var c=f.base.command;var other=new Prepare(c.expectedTaskVersion(),c.goalId(),c.goalRevision(),c.inputId(),"CLAIM_ALIGNMENT",c.target(),c.candidates());
        assertThatThrownBy(()->f.base.application.prepare(f.goal.taskId(),other,"prepare-ui",f.jwt)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->f.base.application.prepare(f.goal.taskId(),c,"",f.jwt)).isInstanceOf(ResponseStatusException.class);
        assertThat(f.count("con5_preparation_request")).isEqualTo(1);assertThat(f.count("con5_preparation")).isEqualTo(2);
    }
    @Test void withdrawnOriginalRejectsReplayAndReadRatherThanReturningHistory() {
        var a=f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt);
        when(f.documents.read(anyString(),any(),any(),anyBoolean())).thenThrow(new IllegalStateException("source unavailable"));
        assertThatThrownBy(()->f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt)).hasMessage("source unavailable");
        assertThatThrownBy(()->f.base.application.readPreparation(f.goal.taskId(),a.preparationId(),f.jwt)).hasMessage("source unavailable");
        assertThat(f.count("con5_preparation_request")).isEqualTo(1);
    }
    @Test void missingRequestTableFailsBeforeCreatingPreparation() {
        f.db.execute("DROP TABLE con5_preparation_request");long before=f.count("con5_preparation");
        assertThatThrownBy(()->f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.count("con5_preparation")).isEqualTo(before);
    }
    @Test void reverseIsAllowedOnlyWithoutRetainedRequests() throws Exception {
        ProductSourceScopeGrantTest.script(f.main,"db/dm8/U101__product_comparison_prepare_request.sql");
        ProductSourceScopeGrantTest.script(f.main,"db/dm8/V101__product_comparison_prepare_request.sql");
        f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt);
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(f.main,"db/dm8/U101__product_comparison_prepare_request.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(f.count("con5_preparation_request")).isEqualTo(1);
    }
    ProductComparisonWorkbenchContextController context() {return context(f.clock);}
    ProductComparisonWorkbenchContextController context(java.time.Clock clock) {
        var directory=new JdbcProductTaskDirectory(f.db,clock);
        var retention=new ProductRetentionPolicy("mechanical-C-retention",1,ProductComparisonPreparationTest.HASH,java.time.Duration.ofHours(2));
        return new ProductComparisonWorkbenchContextController(new ProductTaskGoalReader(f.db,directory,f.access,retention,f.mapper,clock),
                new ProductComparisonTargetReader(f.db,directory,f.access,clock,65536),f.db);
    }
    @Test void contextExposesSavedUserTextNotQueryAndUsesActualCurrentPointers() {
        var c=context().current(f.goal.taskId(),f.jwt);assertThat(c.schemeMessageId()).isEqualTo(f.goal.messageId());
        assertThat(c.schemeText()).isEqualTo("A valve with a pressure sensor.");assertThat(c.previousFeatureRevision()).isEqualTo(1);assertThat(c.previousComparisonId()).isNull();
        var input=f.comparisons.create(f.goal.taskId(),f.create,"input-ui",f.jwt);
        assertThat(context().current(f.goal.taskId(),f.jwt).previousComparisonId()).isEqualTo(input.input().comparisonId());
    }
    @Test void contextAndPreparationsDenyLossOfCurrentReadAndExpiredContext() {
        var a=f.base.application.prepare(f.goal.taskId(),f.base.command,"prepare-ui",f.jwt);
        when(f.access.permits(any(),any(),any())).thenReturn(false);
        assertThatThrownBy(()->context().current(f.goal.taskId(),f.jwt)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->f.base.application.readPreparation(f.goal.taskId(),a.preparationId(),f.jwt)).isInstanceOf(ResponseStatusException.class);
        when(f.access.permits(any(),any(),any())).thenReturn(true);
        // Advance only the isolated reader clock past fixture deadlines; no production clock or old authorization changes.
        assertThatThrownBy(()->context(java.time.Clock.fixed(f.until.plusSeconds(1),java.time.ZoneOffset.UTC)).current(f.goal.taskId(),f.jwt)).isInstanceOf(ResponseStatusException.class);
    }
}
