package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Persisted H2 target race; controlled model/source ports, not real calculation approval. */
class ProductComparisonMatrixReadBoundaryTest {
    ProductComparisonMatrixExecutionTest fixture;
    @BeforeEach void setup() throws Exception { fixture=new ProductComparisonMatrixExecutionTest();fixture.setup(); }

    @Test void changedTargetWithValidNewShaCannotReturnPreviouslyValidatedReport() {
        requireChangedTargetRejected("Changed target with a valid new digest");
    }
    @Test void targetChangeOutsideConfirmedFeatureStillCannotReturnOldReport() {
        var input=fixture.f.source.fixture.input;
        String original=input.db.queryForObject("SELECT raw_text FROM con3_message WHERE message_id=?",String.class,input.goal.messageId());
        requireChangedTargetRejected(original+" Additional target context after all confirmed feature spans.");
    }
    private void requireChangedTargetRejected(String changed) {
        var f=fixture.f;var input=f.source.fixture.input;
        var receipt=fixture.execution.execute(f.source.request,f.scope,f.acquire("target-read-race"));
        String reportSha=fixture.store.read(f.scope.task(),f.scope.comparison(),receipt.reportId()).reportSha();
        fixture.store=spy(fixture.store);var reads=new AtomicInteger();
        doAnswer(invocation->{
            String raw=(String)invocation.callRealMethod();reads.incrementAndGet();
            assertThat(input.db.update("UPDATE con3_message SET raw_text=?,content_sha=? WHERE message_id=?",changed,
                    ProductComparisonJson.hash(changed),input.goal.messageId())).isEqualTo(1);
            return raw;
        }).when(fixture.store).rawResponse(anyString());
        var reader=fixture.reader();
        assertThatThrownBy(()->reader.report(f.scope.task(),f.scope.comparison(),receipt.reportId(),f.source.fixture.human))
                .hasMessage("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");
        assertThat(reads).hasValue(1);
        assertThat(fixture.store.read(f.scope.task(),f.scope.comparison(),receipt.reportId()).reportSha()).isEqualTo(reportSha);
        assertThat(fixture.calls).hasValue(1);assertThat(f.count("con5_matrix_report")).isEqualTo(1);
    }
    @Test void unchangedTargetReadDoesNotRequireAnotherModelCallOrRewriteReport() {
        var f=fixture.f;var receipt=fixture.execution.execute(f.source.request,f.scope,f.acquire("target-read-stable"));
        var reader=fixture.reader();
        assertThat(reader.report(f.scope.task(),f.scope.comparison(),receipt.reportId(),f.source.fixture.human).receipt()).isEqualTo(receipt);
        assertThat(reader.report(f.scope.task(),f.scope.comparison(),receipt.reportId(),f.source.fixture.human).receipt()).isEqualTo(receipt);
        assertThat(reader.results(f.scope.task(),f.scope.comparison(),f.source.fixture.human).state()).isEqualTo("COMMITTED");
        assertThat(fixture.calls).hasValue(1);assertThat(f.count("con5_matrix_report")).isEqualTo(1);
    }
}
