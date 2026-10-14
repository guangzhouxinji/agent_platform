package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptRegisterStoreTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{if(tls!=null)tls.close();}
    ProductPromptRegisterFixture fixture()throws Exception{return new ProductPromptRegisterFixture(root,tls);}

    @Test void preparedIdsAreDistinctAndRawKeyNeverEntersFiniteColumns()throws Exception{
        try(var f=fixture()){
            f.lease.withLease(f.request,f.witness(),lease->{
                var first=f.requests.prepare(lease,"secret-original-key");var second=f.requests.prepare(lease,"secret-original-key");
                assertThat(first.requestId()).isNotEqualTo(first.receiptId()).isNotEqualTo(second.requestId());
                assertThat(first.receiptId()).isNotEqualTo(second.receiptId());
                assertThat(first.requestSha()).isEqualTo(second.requestSha()).isEqualTo(f.requests.requestSha(lease));
                assertThat(first.key()).isEqualTo(second.key());assertThat(first.columns().values()).doesNotContain("secret-original-key");
                assertThat(first.intentUntil()).isEqualTo(f.now.plusSeconds(300));
                assertThat(first.receiptUntil()).isEqualTo(f.now.plusSeconds(3000));
                return null;
            });
        }
    }

    @Test void authorityRequestInsertFailureRollsBackItsWorkspaceAsOneTransaction()throws Exception{
        try(var f=fixture()){
            f.failSql(AUTHORITY,sql->sql.startsWith("INSERT INTO idp_workspace_register_request"));
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"reserve-atomic");
                return lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
            })).isInstanceOf(RuntimeException.class);
            assertThat(f.count(AUTHORITY,"idp_workspace")).isZero();assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isZero();
            assertThat(f.count(MAIN,"conp_workspace")).isZero();
        }
    }

    @Test void mainReceiptInsertFailureRollsBackWorkspaceAndRetainsOriginalPendingRequest()throws Exception{
        try(var f=fixture()){
            f.failSql(MAIN,sql->sql.startsWith("INSERT INTO conp_workspace_register_receipt"));
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"main-atomic");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                return lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
            })).isInstanceOf(RuntimeException.class);
            assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.count(MAIN,"conp_workspace_register_receipt")).isZero();
            assertThat(f.count(AUTHORITY,"idp_workspace")).isEqualTo(1);assertThat(f.state()).isEqualTo("PENDING");
        }
    }

    @ParameterizedTest @ValueSource(strings={"prepared_sha","request_sha","workspace_sha","result_sha","approval_sha"})
    void requestDigestTamperingClosesWithoutReturningOrRewritingOriginal(String field)throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"authenticated");
            f.independent.update("UPDATE idp_workspace_register_request SET "+field+"=?","a".repeat(64));
            f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"authenticated")).isInstanceOf(IllegalStateException.class);
            assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("UPDATE")||sql.startsWith("INSERT")||sql.startsWith("DELETE"));
            assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"scope_version=2","policy_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'",
            "expected_registry_sha='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'",
            "control_state='PENDING',registry_intent_id='50000000-0000-0000-0000-000000000005'"})
    void readyFindAuthenticatesIndependentTargetAndControl(String assignment)throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"independent-target");
            f.independent.update("UPDATE idp_workspace SET "+assignment);f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"independent-target")).isInstanceOf(IllegalStateException.class);
            assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("UPDATE")||sql.startsWith("INSERT"));
        }
    }

    @Test void receiptExpiresDuringFullQueryAndRemainsUnavailableInsteadOfDifferentKeyConflict()throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"deadline-crossing");
            var cutoff=f.now.plusSeconds(1);
            f.independent.update("UPDATE idp_workspace_register_request SET receipt_until=?,intent_until=?",
                    java.sql.Timestamp.from(cutoff),java.sql.Timestamp.from(cutoff));
            f.authorityFault.afterSql=sql->{if(sql.startsWith("SELECT request_id,"))f.advance(Duration.ofSeconds(1));};
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"different-key"))
                    .isInstanceOf(IllegalStateException.class).isNotInstanceOf(ProductPromptRegisterConflict.class);
            f.authorityFault.afterSql=null;
        }
    }

    @Test void readyCanBeReadAfterIntentAndNeverRenewsReceipt()throws Exception{
        try(var f=fixture()){
            var original=f.app.register(f.request,f.workspaceId,"ready-read");f.advance(Duration.ofSeconds(301));
            f.lease.withLease(f.request,f.witness(),lease->{
                var found=lease.authority(original.receiptUntil(),()->f.requests.find(lease,original.key()));
                assertThat(found.state()).isEqualTo("READY");assertThat(found.receipt()).isEqualTo(original);
                assertThat(found.intentUntil()).isBefore(f.now);assertThat(found.receiptUntil()).isEqualTo(original.receiptUntil());return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(strings={"operation='CLOSE'","maintainer_version=0","state='CLOSED'","scope_version=0",
            "operator_organization='50000000-0000-0000-0000-000000000005'","primary_database_sha=authority_database_sha",
            "request_id=receipt_id","intent_until=receipt_until+INTERVAL '1' SECOND","receipt_until=valid_until+INTERVAL '1' SECOND",
            "approved_receipt_until=prepared_at"})
    void authoritySchemaRejectsInvalidFiniteRegistration(String assignment)throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"schema-guard");var before=f.snapshot(AUTHORITY,"idp_workspace_register_request");
            assertThatThrownBy(()->f.independent.update("UPDATE idp_workspace_register_request SET "+assignment))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(f.snapshot(AUTHORITY,"idp_workspace_register_request")).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings={"operation='CLOSE'","maintainer_version=0","request_id=receipt_id","receipt_until=prepared_at"})
    void mainSchemaRejectsInvalidRegistrationReceipt(String assignment)throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"receipt-schema");var before=f.snapshot(MAIN,"conp_workspace_register_receipt");
            assertThatThrownBy(()->f.db.update("UPDATE conp_workspace_register_receipt SET "+assignment))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(before);
        }
    }

    @Test void requestUniquenessAndForeignKeyRejectIndependentOrphanAndSecondWorkspaceRow()throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"unique");
            var columns=f.snapshot(AUTHORITY,"idp_workspace_register_request").getFirst();
            for(String assignment:List.of("workspace_id='50000000-0000-0000-0000-000000000005'")){
                assertThatThrownBy(()->f.independent.update("UPDATE idp_workspace_register_request SET "+assignment))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
            assertThat(f.snapshot(AUTHORITY,"idp_workspace_register_request").getFirst()).isEqualTo(columns);
            assertThatThrownBy(()->f.independent.update("INSERT INTO idp_workspace_register_request SELECT * FROM idp_workspace_register_request"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isEqualTo(1);
        }
    }
}
