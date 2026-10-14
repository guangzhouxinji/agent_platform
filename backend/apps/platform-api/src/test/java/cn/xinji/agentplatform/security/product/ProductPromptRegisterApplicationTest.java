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
class ProductPromptRegisterApplicationTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void eraseCertificates()throws Exception{if(tls!=null)tls.close();}
    ProductPromptRegisterFixture fixture()throws Exception{return new ProductPromptRegisterFixture(root,tls);}

    @Test void firstRegistrationCreatesOnlyExactWorkspaceAndOriginalReceipts()throws Exception{
        try(var f=fixture()){
            assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.count(AUTHORITY,"idp_workspace")).isZero();
            var result=f.app.register(f.request,f.workspaceId,"first");
            assertThat(result.key().workspaceId()).isEqualTo(f.workspaceId);
            assertThat(result.key().subject()).isEqualTo(f.subject);
            assertThat(result.key().certificateSha()).isEqualTo(f.certificateSha);
            assertThat(result.preparedAt()).isEqualTo(f.now);
            assertThat(result.receiptUntil()).isEqualTo(f.now.plusSeconds(3000));
            assertThat(f.count(MAIN,"conp_workspace")).isEqualTo(1);
            assertThat(f.count(MAIN,"conp_workspace_register_receipt")).isEqualTo(1);
            assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isEqualTo(1);
            assertThat(f.state()).isEqualTo("READY");
            assertThat(f.count(MAIN,"conp_prompt")).isZero();assertThat(f.count(MAIN,"conp_revision")).isZero();
            assertThat(f.count(AUTHORITY,"id2_maintenance_audit")).isZero();
            assertThat(f.mainFault.commits).contains("COMMITTED");assertThat(f.authorityFault.commits).contains("COMMITTED");
        }
    }

    @Test void readyLookupAfterOriginalIntentExpiredPreservesAllScalarsWithoutWrites()throws Exception{
        try(var f=fixture()){
            var first=f.app.register(f.request,f.workspaceId,"stable");
            var request=f.snapshot(AUTHORITY,"idp_workspace_register_request");var receipt=f.snapshot(MAIN,"conp_workspace_register_receipt");
            f.advance(Duration.ofMinutes(6));f.mainFault.clear();f.authorityFault.clear();
            assertThat(f.app.lookup(f.request,f.workspaceId,"stable")).isEqualTo(first);
            assertThat(f.app.register(f.request,f.workspaceId,"stable")).isEqualTo(first);
            assertThat(f.snapshot(AUTHORITY,"idp_workspace_register_request")).isEqualTo(request);
            assertThat(f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(receipt);
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void lookupMissingNeverCreatesWorkspaceOrPreparedRequest()throws Exception{
        try(var f=fixture()){
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"missing")).isInstanceOf(RuntimeException.class);
            assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isZero();
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void differentKeyForSameWorkspaceCannotInstallAgain()throws Exception{
        try(var f=fixture()){
            var first=f.app.register(f.request,f.workspaceId,"original");f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"different")).isInstanceOf(RuntimeException.class);
            assertThat(f.app.lookup(f.request,f.workspaceId,"original")).isEqualTo(first);
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void mainRealCommitFailureRequiresExplicitLookupAndRetainsOriginalCause(boolean after)throws Exception{
        try(var f=fixture()){
            f.failCommitForSql(MAIN,"INSERT INTO conp_workspace(",after);
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"main-unknown"))
                    .hasRootCauseMessage("Owned component commit "+(after?"after":"before")+" failure");
            assertThat(f.state()).isEqualTo("PENDING");
            assertThat(f.count(MAIN,"conp_workspace")).isEqualTo(after?1:0);
            assertThat(f.count(MAIN,"conp_workspace_register_receipt")).isEqualTo(after?1:0);
            assertThat(f.mainFault.commits).contains(after?"AFTER_FAILURE":"BEFORE_FAILURE");
            var original=f.snapshot(AUTHORITY,"idp_workspace_register_request");
            f.mainFault.clear();f.authorityFault.clear();
            var restarted=f.reopenApplication();
            if(after){
                var restored=restarted.lookup(f.request,f.workspaceId,"main-unknown");
                assertThat(restored.preparedAt()).isEqualTo(f.now);assertThat(f.state()).isEqualTo("READY");
            }else{
                assertThatThrownBy(()->restarted.lookup(f.request,f.workspaceId,"main-unknown")).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(()->restarted.register(f.request,f.workspaceId,"main-unknown")).isInstanceOf(RuntimeException.class);
                assertThat(f.snapshot(AUTHORITY,"idp_workspace_register_request")).isEqualTo(original);
                assertNoWrites(f.authorityFault.sql);
            }
            assertNoWrites(f.mainFault.sql);
        }
    }

    @Test void failedReadyStatementLeavesExactMainFactsAndLookupOnlyCompletesOriginal()throws Exception{
        try(var f=fixture()){
            f.failSql(AUTHORITY,sql->sql.startsWith("UPDATE idp_workspace SET"));
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"complete-later")).isInstanceOf(RuntimeException.class);
            assertThat(f.state()).isEqualTo("PENDING");
            var mainWorkspace=f.snapshot(MAIN,"conp_workspace");var mainReceipt=f.snapshot(MAIN,"conp_workspace_register_receipt");
            f.advance(Duration.ofSeconds(5));f.mainFault.clear();f.authorityFault.clear();
            var recovered=f.app.lookup(f.request,f.workspaceId,"complete-later");
            assertThat(recovered.preparedAt()).isEqualTo(f.now.minusSeconds(5));assertThat(f.state()).isEqualTo("READY");
            assertThat(f.snapshot(MAIN,"conp_workspace")).isEqualTo(mainWorkspace);
            assertThat(f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(mainReceipt);assertNoWrites(f.mainFault.sql);
            assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("INSERT"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void authorityReserveCommitFailureDoesNotStartMainInsert(boolean after)throws Exception{
        try(var f=fixture()){
            f.failCommitForSql(AUTHORITY,"INSERT INTO idp_workspace_register_request",after);
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"reserve-unknown")).isInstanceOf(RuntimeException.class);
            assertThat(f.count(MAIN,"conp_workspace")).isZero();
            assertThat(f.count(MAIN,"conp_workspace_register_receipt")).isZero();
            assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isEqualTo(after?1:0);
            f.mainFault.clear();f.authorityFault.clear();
            var restarted=f.reopenApplication();
            assertThatThrownBy(()->restarted.lookup(f.request,f.workspaceId,"reserve-unknown")).isInstanceOf(RuntimeException.class);
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void authorityCompleteCommitFailureOnlyReconcilesOriginalMainReceipt(boolean after)throws Exception{
        try(var f=fixture()){
            f.failCommitForSql(AUTHORITY,"UPDATE idp_workspace SET",after);
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"ready-unknown")).isInstanceOf(RuntimeException.class);
            assertThat(f.state()).isEqualTo(after?"READY":"PENDING");
            var mainReceipt=f.snapshot(MAIN,"conp_workspace_register_receipt");f.mainFault.clear();f.authorityFault.clear();
            var recovered=f.reopenApplication().lookup(f.request,f.workspaceId,"ready-unknown");
            assertThat(recovered.preparedAt()).isEqualTo(f.now);assertThat(f.state()).isEqualTo("READY");
            assertThat(f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(mainReceipt);assertNoWrites(f.mainFault.sql);
            if(after)assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void expiredPendingIntentCannotCompleteEvenWhenBothMainRowsExist()throws Exception{
        try(var f=fixture()){
            f.failSql(AUTHORITY,sql->sql.startsWith("UPDATE idp_workspace SET"));
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"expired-pending")).isInstanceOf(RuntimeException.class);
            f.advance(Duration.ofSeconds(300));f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"expired-pending")).isInstanceOf(RuntimeException.class);
            assertThat(f.state()).isEqualTo("PENDING");assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @ParameterizedTest @ValueSource(strings={"missingReceipt","mainDrift","mainClosed","authorityClosed","authorityDrift"})
    void readyNeverRebuildsMissingDriftingOrClosedFacts(String failure)throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"no-rebuild");
            switch(failure){
                case "missingReceipt"->f.db.update("DELETE FROM conp_workspace_register_receipt WHERE workspace_id=?",f.workspaceId);
                case "mainDrift"->f.db.update("UPDATE conp_workspace SET scope_version=2 WHERE workspace_id=?",f.workspaceId);
                case "mainClosed"->f.db.update("UPDATE conp_workspace SET state='CLOSED' WHERE workspace_id=?",f.workspaceId);
                case "authorityClosed"->f.independent.update("UPDATE idp_workspace SET state='CLOSED' WHERE workspace_id=?",f.workspaceId);
                case "authorityDrift"->f.independent.update("UPDATE idp_workspace SET scope_version=2 WHERE workspace_id=?",f.workspaceId);
            }
            f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"no-rebuild")).isInstanceOf(RuntimeException.class);
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void receiptExpiryIsReadBeforeFullOriginalRequest()throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"expired-receipt");
            var expired=java.sql.Timestamp.from(f.now.plusSeconds(1));
            f.independent.update("UPDATE idp_workspace_register_request SET receipt_until=?,intent_until=? WHERE workspace_id=?",
                    expired,expired,f.workspaceId);
            f.advance(Duration.ofSeconds(1));
            f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"expired-receipt")).isInstanceOf(RuntimeException.class);
            assertThat(f.authorityFault.sql).anyMatch(sql->sql.startsWith("SELECT receipt_until FROM idp_workspace_register_request"));
            assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("SELECT request_id,"));
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void orphanMainWorkspaceWithoutOriginalRequestIsNeverRegisteredAgain()throws Exception{
        try(var f=fixture()){
            var target=f.target;
            f.db.update("INSERT INTO conp_workspace VALUES(?,?,?,?,?,?,?,?)",target.id(),target.organization(),target.scopeVersion(),
                    target.state(),target.policyRef(),target.policySha(),target.registrySha(),java.sql.Timestamp.from(target.validUntil()));
            f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"orphan")).isInstanceOf(RuntimeException.class);
            assertThat(f.count(MAIN,"conp_workspace")).isEqualTo(1);assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isZero();
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @ParameterizedTest @ValueSource(strings={"","space key","\u4e2d\u6587"})
    void invalidOriginalKeyNeverReservesOrCreatesAnything(String key)throws Exception{
        try(var f=fixture()){
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,key)).isInstanceOf(IllegalArgumentException.class);
            assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isZero();
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void currentMaintenanceIdentityCannotBeReplacedByOldOriginalKey()throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"identity");f.independent.update("UPDATE id2_maintainer SET version=2");
            f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"identity")).isInstanceOf(RuntimeException.class);
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    @Test void approvalBytesChangedBetweenMainInsertAndCommitRollbackMainAndKeepPending()throws Exception{
        try(var f=fixture()){
            f.mainFault.afterSql=sql->{if(sql.startsWith("INSERT INTO conp_workspace_register_receipt")){
                try{var changed=f.read(f.registerRef);changed.put("decisionVersion","drifted");f.write(f.registerRef,changed);}
                catch(Exception failure){throw new IllegalStateException(failure);}
            }};
            assertThatThrownBy(()->f.app.register(f.request,f.workspaceId,"precommit-drift")).isInstanceOf(RuntimeException.class);
            assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.state()).isEqualTo("PENDING");
        }
    }

    @Test void changedOriginalApprovalRejectsReadyReplayWithoutNewWrites()throws Exception{
        try(var f=fixture()){
            f.app.register(f.request,f.workspaceId,"approval");var changed=f.read(f.registerRef);changed.put("decisionVersion","drifted");f.write(f.registerRef,changed);
            f.mainFault.clear();f.authorityFault.clear();
            assertThatThrownBy(()->f.app.lookup(f.request,f.workspaceId,"approval")).isInstanceOf(RuntimeException.class);
            assertNoWrites(f.mainFault.sql);assertNoWrites(f.authorityFault.sql);
        }
    }

    private static void assertNoWrites(List<String> sql){assertThat(sql).noneMatch(value->value.startsWith("INSERT")||value.startsWith("UPDATE")||value.startsWith("DELETE"));}
}
