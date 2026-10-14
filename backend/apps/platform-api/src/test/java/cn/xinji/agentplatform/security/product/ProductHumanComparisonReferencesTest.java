package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Actual Input/report/native closure with captured bytes; controlled Source/model ports and facts boundary input. */
class ProductHumanComparisonReferencesTest {
    ProductComparisonMatrixExecutionTest f;
    ProductComparisonInputTest input;
    ProductHumanComparisonReferences references;
    ProductHumanTaskTransaction.Context context;
    ProductHumanTaskFacts.Snapshot facts;
    ProductComparisonMatrixResults.Receipt receipt;
    String task, comparison;

    @BeforeEach void setup() throws Exception {
        f = new ProductComparisonMatrixExecutionTest(); f.setup();
        input = f.f.source.fixture.input;
        task = f.f.scope.task(); comparison = f.f.scope.comparison();
        receipt = f.execution.execute(f.f.source.request, f.f.scope, f.f.acquire("human-references"));
        var directory = new JdbcProductTaskDirectory(input.db, input.clock);
        context = new ProductHumanTaskTransaction.Context(directory.find(task).orElseThrow(),
                new OrdinaryHumanHandling.Actor(input.jwt.getIssuer().toString(), input.jwt.getSubject(), OrdinaryHumanHandling.ActorKind.HUMAN));
        var command = input.base.command;
        facts = new ProductHumanTaskFacts.Snapshot(task, command.expectedTaskVersion(), command.purpose(),
                new ProductHumanTaskFacts.Reference("GOAL", command.goalId(), command.goalRevision(), "a".repeat(64)),
                new ProductHumanTaskFacts.Reference("MESSAGE", command.target().messageId(), 1, "b".repeat(64)),
                new ProductHumanTaskFacts.Reference("INPUT", command.inputId(), 1, "c".repeat(64)),
                new ProductRetentionPolicy("mechanical-C-retention", 1, "a".repeat(64), Duration.ofHours(2)),
                input.clock.instant(), input.until);
        var sources = mock(ProductSourceAvailability.class);
        when(sources.require(anyString(), eq("CLAIMS"), eq("SAVE"))).thenReturn(input.candidate.authority().source());
        when(sources.useUntil(anyString(), eq("CLAIMS"), eq("SAVE"))).thenReturn(input.until);
        var targets = new ProductComparisonTargetReader(input.db, directory, input.access, input.clock, 65536);
        var loader = new ProductComparisonPreparationLoader(input.db, directory, input.access, targets, input.documents, input.json, input.clock);
        var parents = new ProductComparisonParentEvidenceReader(loader, input.comparisons, input.inputStore, f.store,
                new ProductComparisonLineageStore(input.db, input.json), sources, input.db, f.f.transactions, input.json,
                f.calculation, new ProductComparisonLineageFacts(input.json, f.calculation), input.clock);
        references = new ProductHumanComparisonReferences(input.db, input.comparisons, f.reader(), parents, input.json);
        references.requireBinding(input.main);
    }

    @Test void selectedReportLoadsActualInputDigestAndNativeReceiptWithoutInvokingModelAgain() {
        var actual = load(comparison, facts, false);
        var stored = f.store.read(task, comparison, receipt.reportId());
        assertThat(actual.id()).isEqualTo(comparison);
        assertThat(actual.sha()).isEqualTo(input.inputStore.read(task, comparison).inputSha());
        assertThat(actual.reports()).singleElement().satisfies(report -> {
            assertThat(report.sha()).isEqualTo(stored.reportSha());
            assertThat(report.comparisonId()).isEqualTo(comparison);
            assertThat(report.ancestry()).isEmpty();
        });
        assertThat(f.calls).hasValue(1);
    }

    @Test void wrongGoalSearchInputAndTaskVersionAreRejectedBeforeReportConsumption() {
        for (var wrong : List.of(
                changedFacts(facts.taskVersion(), new ProductHumanTaskFacts.Reference("GOAL", "foreign-goal", 1, "a".repeat(64)), facts.input()),
                changedFacts(facts.taskVersion(), facts.goal(), new ProductHumanTaskFacts.Reference("INPUT", "foreign-input", 1, "a".repeat(64))),
                changedFacts(facts.taskVersion() + 1, facts.goal(), facts.input()),
                changedFacts(facts.taskVersion(), new ProductHumanTaskFacts.Reference("GOAL", facts.goal().id(), facts.goal().revision() + 1, facts.goal().sha()), facts.input())))
            assertThatThrownBy(() -> load(comparison, wrong, false)).hasMessage("Human comparison references unavailable");
    }

    @Test void historicalSelectionRemainsOriginalAfterNativeCurrentPointerMoves() {
        var before = load(comparison, facts, false);
        String successor = successor();
        assertThat(input.inputStore.current(task)).isEqualTo(successor);
        assertThatThrownBy(() -> load(comparison, facts, false)).hasMessage("Human comparison references unavailable");
        assertThat(load(comparison, facts, true)).isEqualTo(before);
    }

    @Test void selectedReportRejectsBrokenNativeCitationClosure() {
        input.db.update("DELETE FROM con5_matrix_citation WHERE report_id=?", receipt.reportId());
        assertThatThrownBy(() -> load(comparison, facts, false)).hasMessageContaining("CLOSURE_INVALID");
    }

    @Test void discoverIncludesCurrentAndAncestorSourceUsesForSelectedOriginalReport() {
        String successor = successor();
        var uses = f.f.transactions.execute(status -> references.discover(task, selection(successor)));
        assertThat(uses).hasSize(2).allSatisfy(use -> {
            assertThat(use.sourceId()).isEqualTo(input.candidate.reference().sourceId());
            assertThat(use.fields()).isEqualTo(Set.of("CLAIMS"));
            assertThat(use.comparisonFields()).isEqualTo(Set.of("CLAIMS"));
        });
        var actual = load(successor, facts, false);
        assertThat(actual.id()).isEqualTo(successor);
        assertThat(actual.reports()).singleElement().satisfies(report -> {
            assertThat(report.comparisonId()).isEqualTo(comparison);
            assertThat(report.ancestry()).extracting(ProductComparisonParentEvidenceReader.Ancestor::comparisonId)
                    .containsExactly(comparison);
        });
    }

    @Test void ancestorDiscoveryCannotHideMissingInputEvent() {
        String successor = successor();
        input.db.update("DELETE FROM con5_comparison_event WHERE comparison_id=?", comparison);
        assertThatThrownBy(() -> f.f.transactions.execute(status -> references.discover(task, selection(successor))))
                .hasMessageContaining("closure or digest invalid");
    }

    @Test void referenceConsumptionRequiresOwningTransaction() {
        assertThatThrownBy(() -> references.load(context, facts, selection(comparison), input.jwt, false))
                .hasMessage("Human comparison references unavailable");
        assertThatThrownBy(() -> references.discover(task, selection(comparison)))
                .hasMessage("Human comparison references unavailable");
    }

    private ProductHumanRequestReferences.Comparison load(String selected, ProductHumanTaskFacts.Snapshot selectedFacts, boolean replay) {
        return f.f.transactions.execute(status -> references.load(context, selectedFacts, selection(selected), input.jwt, replay));
    }
    private ProductHumanComparisonReferences.Selection selection(String selected) {
        return new ProductHumanComparisonReferences.Selection(selected, List.of(receipt.reportId()));
    }
    private String successor() {
        return input.comparisons.create(task, input.commandFor(input.confirmed, comparison), "human-reference-successor", input.jwt)
                .input().comparisonId();
    }
    private ProductHumanTaskFacts.Snapshot changedFacts(long version, ProductHumanTaskFacts.Reference goal,
            ProductHumanTaskFacts.Reference searchInput) {
        return new ProductHumanTaskFacts.Snapshot(task, version, facts.purpose(), goal, facts.message(), searchInput,
                facts.policy(), facts.confirmedAt(), facts.expiresAt());
    }
}
