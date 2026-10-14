package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTrademarkReadTransactionTest {
    @TempDir Path root;
    @Test void actualSignedHumanReadsCompletePagingAndExactDetailWithoutMaintenanceTlsOrWriteGrant() throws Exception {
        var f=new ProductTrademarkReadFixture(root);
        var first=f.boundary.response(()->f.service.query(f.request(Operation.QUERY,null),f.query(1)));
        assertThat(first.data().pagination().reliableTotal()).isEqualTo(21);assertThat(first.data().items()).hasSize(20);
        assertThat(first.data().items().getFirst().registrationNumber()).isEqualTo("00012A");
        var second=f.service.query(f.request(Operation.QUERY,null),f.query(2));assertThat(second.items()).hasSize(1);
        var item=first.data().items().getFirst();var detail=f.service.detail(f.request(Operation.DETAIL,item.ref()),true);
        assertThat(detail.item().applicants()).hasSize(2);assertThat(detail.item().classes()).hasSize(2);
        assertThat(detail.capabilities().values()).containsOnly(Capability.UNKNOWN);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE action_code='WRITE'",Integer.class)).isZero();
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit",Integer.class)).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM con1tm_capture_request",Integer.class)).isEqualTo(1);
    }
    @Test void queryUsesOnlyItsActualConditionsAndSortFields() throws Exception {
        var f=new ProductTrademarkReadFixture(root);f.db.update("DELETE FROM con4_source_field_use WHERE use_code='QUERY' AND field_code<>'DATES'");f.sourceHistory();
        assertThat(f.service.query(f.request(Operation.QUERY,null),f.query(1)).items()).hasSize(20);
        var named=new Query(null,"Brand",null,Set.of(),Set.of(),null,null,null,null,new PageRequest(1,20,null),false);
        assertThatThrownBy(()->f.service.query(f.request(Operation.QUERY,null),named)).isInstanceOf(RuntimeException.class);
        var ref=f.origin.verified.records().getFirst().observation().ref();
        f.db.update("DELETE FROM con4_source_field_use WHERE use_code='QUERY'");f.sourceHistory();
        assertThat(f.service.detail(f.request(Operation.DETAIL,ref),false).item().registrationNumber()).isEqualTo("00012A");
        assertThatThrownBy(()->f.service.query(f.request(Operation.QUERY,null),f.query(1))).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"page-outside-index","off-page-original","expired","task-version","wrong-receipt","source-pending","grant-pending","display-field"})
    void brokenCurrentClosureNeverReturnsAnEmptySuccess(String fault) throws Exception {
        var f=new ProductTrademarkReadFixture(root);
        switch(fault){
            case "page-outside-index"->f.db.update("UPDATE kb_trademark SET mark_name='tampered' WHERE record_version_id=(SELECT record_version_id FROM kb_record_version WHERE stable_record_key='r20')");
            case "off-page-original"->f.origin.file(f.origin.input.originals().getLast().ref(),new byte[]{1,2});
            case "expired"->f.origin.capture.clock.now=f.until;
            case "task-version"->f.db.update("UPDATE con3_task SET authorization_version=8");
            case "wrong-receipt"->f.db.update("UPDATE con1tm_capture_request SET task_id='other'");
            case "source-pending"->f.authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id='pending'");
            case "grant-pending"->f.authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='pending'");
            case "display-field"->{f.db.update("DELETE FROM con4_source_field_use WHERE use_code='DISPLAY' AND field_code='APPLICANTS'");f.sourceHistory();}
            default->throw new AssertionError(fault);
        }
        assertThatThrownBy(()->f.boundary.response(()->f.service.query(f.request(Operation.QUERY,null),f.query(1)))).isInstanceOf(RuntimeException.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM con1tm_capture_request",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"source","task","original","session"})
    void changesDuringReadAreRejectedAtTheFinalBoundary(String fault) throws Exception {
        var f=new ProductTrademarkReadFixture(root);
        assertThatThrownBy(()->f.boundary.read(f.request(Operation.QUERY,null),scope->{
            switch(fault){
                case "source"->f.authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id='pending'");
                case "task"->f.db.update("UPDATE con3_task SET authorization_version=8");
                case "session"->f.db.update("UPDATE id2_session SET status='REVOKED'");
                case "original"->{try{f.origin.file(f.origin.input.originals().getLast().ref(),new byte[]{1});}catch(Exception failure){throw new IllegalStateException(failure);}}
                default->throw new AssertionError(fault);
            }return "must not escape";
        })).isInstanceOf(RuntimeException.class);
    }
    @Test void publicScopeCannotEscapeItsTransactionOrCrossThreadAndOuterTransactionFails() throws Exception {
        var f=new ProductTrademarkReadFixture(root);var seen=new AtomicReference<AuthorizedScope>();
        f.boundary.read(f.request(Operation.QUERY,null),scope->{seen.set(scope);var thrown=new AtomicReference<Throwable>();
            var thread=new Thread(()->{try{f.boundary.context(scope);}catch(Throwable failure){thrown.set(failure);}});thread.start();
            try{thread.join();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            assertThat(thrown.get()).isInstanceOf(RuntimeException.class);return null;
        });
        assertThatThrownBy(()->f.boundary.context(seen.get())).isInstanceOf(RuntimeException.class);
        f.transactions.execute(status->{assertThatThrownBy(()->f.service.query(f.request(Operation.QUERY,null),f.query(1))).isInstanceOf(RuntimeException.class);return null;});
    }
    @Test void wrongExactVersionIsNotSilentlyReplacedWithCurrentVersion() throws Exception {
        var f=new ProductTrademarkReadFixture(root);
        assertThatThrownBy(()->f.service.detail(f.request(Operation.DETAIL,new RecordRef("source","r00","other")),false))
                .isInstanceOfSatisfying(Failure.class,failure->assertThat(failure.code()).isEqualTo(Code.RECORD_NOT_FOUND));
    }
    @Test void selectedLongerReceiptCannotExtendOriginalBindingExpiryInHttpEnvelope() throws Exception {
        var f=new ProductTrademarkReadFixture(root,true);var originalNow=f.origin.capture.clock.instant();
        // A small observed clock correction puts the newer receipt first in the specified stable SQL order.
        f.origin.capture.clock.now=originalNow.minusSeconds(1);
        var longer=new ProductTrademarkProjectionStore.Context("organization","task",7,"maintainer",f.origin.certSHA,"decision",1,1,"retention.json",1,
                f.origin.hash("retention.json"),f.origin.pin.approvedScopeSHA(),f.origin.pin.approvalEvidenceSHA(),f.until);
        var current=f.transactions.execute(status->f.store.project(f.origin.input,longer,"later-longer"));
        f.origin.capture.clock.now=originalNow;
        assertThat(f.db.queryForList("SELECT receipt_id FROM con1tm_capture_request ORDER BY committed_at,receipt_id",String.class).getFirst()).isEqualTo(current.id());
        var response=f.boundary.response(()->f.service.query(f.request(Operation.QUERY,null),f.query(1)));
        assertThat(response.expiresAt()).isEqualTo(f.originalUntil);
        f.origin.capture.clock.now=f.originalUntil;
        assertThatThrownBy(()->f.boundary.response(()->f.service.query(f.request(Operation.QUERY,null),f.query(1)))).isInstanceOf(RuntimeException.class);
    }
}
