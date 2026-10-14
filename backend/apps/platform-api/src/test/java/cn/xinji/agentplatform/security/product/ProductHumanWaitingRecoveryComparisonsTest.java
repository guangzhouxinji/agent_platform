package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real native comparison/report stores; existing controlled model/source ports are not runtime evidence. */
class ProductHumanWaitingRecoveryComparisonsTest {
    @TempDir Path root;
    ProductHumanComparisonReferencesTest f;
    ProductHumanRequestStore.Manifest template;
    ProductHumanWaitingRecoveryComparisons reader;
    java.time.Instant current;
    final java.time.Clock clock=new java.time.Clock() {
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId zone){return this;}
        public java.time.Instant instant(){return current;}
    };
    @BeforeEach void setup() throws Exception {
        f=new ProductHumanComparisonReferencesTest();f.setup();
        var waiting=new ProductHumanWaitingRecoveryFixture();waiting.setup(root);template=waiting.packet(false).body().manifest();
        var input=f.input;String preparation=input.confirmed.revision().preparationId();
        ProductSourceScopeGrantTest.script(input.main,"db/dm8/V101__product_comparison_prepare_request.sql");
        // The reused older fixture invokes the legacy unkeyed prepare overload. Add its native
        // keyed receipt using the production Store, with the exact original command/actor hash.
        String key="recovery-prepare";
        input.store.savePrepareRequest(f.task,input.jwt.getIssuer().toString(),input.jwt.getSubject(),key,
                input.json.sha(Map.of("taskId",f.task,"issuer",input.jwt.getIssuer().toString(),"subject",input.jwt.getSubject(),"requestId",key,"command",input.base.command)),preparation);
        current=input.clock.instant();reader=new ProductHumanWaitingRecoveryComparisons(input.db,input.json,clock);
    }
    @Test void currentComparisonWithoutReportChecksNativeInputAndRealOriginalSpans() {
        var manifest=manifest(f.comparison,List.of());
        var closure=reader.discover(manifest);
        assertThat(closure.nodes()).hasSize(1);assertThat(closure.documents()).hasSize(1);
        assertThat(closure.referencedSaveUntil()).isEqualTo(closure.expiresAt());
        verifyBytes(closure);
    }
    @Test void inheritedReportChecksNearestNativeAncestorAndOriginalReportReceipt() {
        String successor=f.input.comparisons.create(f.task,f.input.commandFor(f.input.confirmed,f.comparison),"recovery-successor",f.input.jwt).input().comparisonId();
        var manifest=manifest(successor,List.of(f.receipt.reportId()));
        var closure=reader.discover(manifest);
        assertThat(closure.nodes()).hasSize(2);
        assertThat(manifest.references().comparison().reports().getFirst().ancestry()).extracting(ProductComparisonParentEvidenceReader.Ancestor::comparisonId).containsExactly(f.comparison);
        verifyBytes(closure);
        f.input.db.update("DELETE FROM con5_matrix_citation WHERE report_id=?",f.receipt.reportId());
        assertThatThrownBy(()->reader.discover(manifest)).isInstanceOf(RuntimeException.class);
    }
    @Test void expiredNativeInputIsRejectedBeforeMalformedBodyCanBeDecoded() {
        var manifest=manifest(f.comparison,List.of());
        current=current.plusSeconds(1);
        f.input.db.update("UPDATE con5_comparison_input SET expires_at=?,input_json='invalid' WHERE comparison_id=?",Timestamp.from(current),f.comparison);
        assertThatThrownBy(()->reader.discover(manifest)).isInstanceOf(IllegalStateException.class).hasMessage("Recovered comparison reference closure unavailable");
    }
    @ParameterizedTest @ValueSource(strings={"con5_feature_revision","con5_preparation","con5_prepared_document","con3_message"})
    void inputCannotOutliveAnyNativeSupportingObject(String table) {
        var manifest=manifest(f.comparison,List.of());
        f.input.db.update("UPDATE "+table+" SET expires_at=?",Timestamp.from(current.plusSeconds(30)));
        assertThatThrownBy(()->reader.discover(manifest)).isInstanceOf(IllegalStateException.class).hasMessage("Recovered comparison reference closure unavailable");
    }
    @Test void finalOriginalReadCannotCrossTheReferencedSaveDeadline() {
        var closure=reader.discover(manifest(f.comparison,List.of()));
        assertThatThrownBy(()->reader.verifyMaterials(closure,(ref,material)->{
            current=closure.referencedSaveUntil();return f.input.candidate.original();
        })).isInstanceOf(IllegalStateException.class).hasMessage("Recovered comparison reference closure unavailable");
    }
    private void verifyBytes(ProductHumanWaitingRecoveryComparisons.Closure closure) {
        reader.verifyMaterials(closure,(ref,material)->{
            assertThat(ref).isEqualTo(f.input.candidate.reference());
            assertThat(material).isEqualTo(f.input.candidate.material());
            return f.input.candidate.original();
        });
    }
    private ProductHumanRequestStore.Manifest manifest(String comparison,List<String> reports) {
        var selected=f.f.f.transactions.execute(status->f.references.load(f.context,f.facts,new ProductHumanComparisonReferences.Selection(comparison,reports),f.input.jwt,false));
        var old=template.references().cleanup();
        var cleanup=new ProductHumanCleanupScope.Snapshot(old.scopeId(),f.context.task().organization(),f.task,old.generation(),old.state(),old.intent(),old.policy(),old.historySha(),f.input.clock.instant(),f.input.until);
        var references=new ProductHumanRequestReferences(f.facts,List.of(),List.of(),selected,cleanup,reports.isEmpty()?List.of(ProductHumanRequestReferences.Gap.NO_REPORT_SELECTED):List.of());
        return new ProductHumanRequestStore.Manifest(f.task,f.context.task().organization(),f.context.task().dataScope(),f.facts.taskVersion(),template.packageId(),1,f.context.actor(),references.binding(),references,"Check exact report","ORDINARY_ASSISTANCE",template.policy(),template.bounds(),f.input.clock.instant());
    }
}
