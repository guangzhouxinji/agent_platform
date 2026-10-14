package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owned TLS and real two-H2 locks verify admission mechanics, never actual deletion approval. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptBodyClearLeaseTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{tls.close();}
    ProductPromptRegisterFixture fixture()throws Exception{return new ProductPromptRegisterFixture(root.resolve(UUID.randomUUID().toString()),tls);}

    ProductPromptBodyClearApproval.Witness witness(ProductPromptRegisterFixture f)throws Exception{return witness(f,Map.of());}
    ProductPromptBodyClearApproval.Witness witness(ProductPromptRegisterFixture f,Map<String,Object> changes)throws Exception {
        var value=f.read(f.registerRef);
        String prompt=UUID.randomUUID().toString(),revision=UUID.randomUUID().toString();
        value.put("format","PROMPT_BODY_CLEAR_APPROVAL/v1");value.put("operation","BODY_CLEAR");
        value.put("traceCoverage","MINIMAL_BODY_CLEAR_SCALARS_AND_ORIGINAL_KEY");
        value.put("promptId",prompt);value.put("revisionId",revision);value.put("revisionNumber",1L);
        value.put("recordSha","a".repeat(64));value.put("bodyUntil",f.now.minusSeconds(1));
        value.put("auditUntil",f.now.plusSeconds(1000));value.putAll(changes);
        String ref="body-clear.json",sha=f.write(ref,value);
        var files=new ProductSourceEvidenceFiles(f.root,1048576);
        var registry=new ProductPromptBindingRegistry(files,f.bindingsRef,ProductCapturedArchive.digest(Files.readAllBytes(f.root.resolve(f.bindingsRef))));
        return new ProductPromptBodyClearApproval(files,registry,List.of(
                new ProductPromptBodyClearApproval.Pin(f.workspaceId,prompt,revision,ref,sha)),f.clock)
                .admit(f.workspaceId,prompt,revision);
    }

    @ParameterizedTest @ValueSource(strings={"SUBJECTS","HUMAN_RETENTION","GRANTS"})
    void exactBodyClearApprovalWorksWithoutTreatingAnyRoleAsItsPermission(String roles)throws Exception {
        try(var f=fixture()) {
            f.independent.update("UPDATE id2_maintainer SET allowed_roles=?",roles);
            var approval=witness(f);var escaped=new AtomicReference<ProductPromptRegistrationLease.Lease>();
            f.lease.withLease(f.request,approval,lease->{
                escaped.set(lease);assertSame(approval,lease.bodyClearApproval());
                assertThrows(ProductPromptAccessDenied.class,lease::approval);
                assertEquals(f.subject,lease.subject());assertEquals(f.organization,lease.organization());
                assertEquals(f.certificateSha,lease.certificateSha());assertEquals(1,lease.maintainerVersion());
                assertEquals(approval.evidence().primaryDatabaseIdentitySha(),lease.primaryDatabaseIdentitySha());
                assertEquals(approval.evidence().authorityDatabaseIdentitySha(),lease.authorityDatabaseIdentitySha());
                return null;
            });
            assertThrows(IllegalStateException.class,()->escaped.get().bodyClearApproval());
            if(!roles.equals("GRANTS"))assertThrows(ProductPromptAccessDenied.class,
                    ()->f.lease.withLease(f.request,f.witness(),lease->fail("REGISTER still needs GRANTS")));
            else f.lease.withLease(f.request,f.witness(),lease->{
                assertThrows(ProductPromptAccessDenied.class,lease::bodyClearApproval);return null;
            });
            assertEquals(0,f.count(MAIN,"conp_revision"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"subject","organization","certificate","version","status"})
    void rejectsWrongCurrentMaintainerBeforeCallback(String field)throws Exception {
        try(var f=fixture()) {
            var approval=witness(f);
            switch(field) {
                case "subject"->f.independent.update("UPDATE id2_maintainer SET subject_ref='other'");
                case "organization"->f.independent.update("UPDATE id2_maintainer SET organization=?",UUID.randomUUID().toString());
                case "certificate"->f.independent.update("UPDATE id2_maintainer SET certificate_sha=?","0".repeat(64));
                case "version"->f.independent.update("UPDATE id2_maintainer SET version=2");
                case "status"->f.independent.update("UPDATE id2_maintainer SET status='REVOKED'");
            }
            var called=new AtomicBoolean();
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,approval,lease->{called.set(true);return null;}));
            assertFalse(called.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"primaryDatabaseIdentitySha","authorityDatabaseIdentitySha"})
    void rejectsWrongExactDatabaseIdentities(String field)throws Exception {
        try(var f=fixture()) {
            var approval=witness(f,Map.of(field,"0".repeat(64)));
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,approval,lease->fail("wrong database")));
        }
    }
    @Test void roleSnapshotDriftInsideTheHeldLockCannotBeIgnored()throws Exception {
        try(var f=fixture()) {
            var approval=witness(f);
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,approval,lease->{
                try(var statement=lease.maintenance.createStatement()) {
                    statement.executeUpdate("UPDATE id2_maintainer SET allowed_roles='SUBJECTS'");
                }catch(java.sql.SQLException failed){throw new AssertionError(failed);}
                lease.requireCurrent();return null;
            }));
            assertEquals("GRANTS",f.independent.queryForObject("SELECT allowed_roles FROM id2_maintainer",String.class));
        }
    }
    @Test void transportFailuresCannotReachBodyClearCallback()throws Exception {
        try(var f=fixture()) {
            var approval=witness(f);f.request.setSecure(false);f.request.setScheme("http");
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,approval,lease->fail("insecure")));
            f.request.setSecure(true);f.request.setScheme("https");f.request.removeAttribute("jakarta.servlet.request.X509Certificate");
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,approval,lease->fail("no certificate")));
        }
    }
    @Test void rejectsAmbientCrossThreadAndConcurrentAdmissions()throws Exception {
        try(var f=fixture();var worker=Executors.newSingleThreadExecutor()) {
            var approval=witness(f);
            new TransactionTemplate(f.mainManager).executeWithoutResult(status->assertThrows(IllegalStateException.class,
                    ()->f.lease.withLease(f.request,approval,lease->fail("ambient"))));
            f.lease.withLease(f.request,approval,lease->{
                try{worker.submit(()->assertThrows(IllegalStateException.class,lease::bodyClearApproval)).get(5,TimeUnit.SECONDS);}
                catch(Exception failed){throw new AssertionError(failed);}
                int borrowed=f.authoritySource.getHikariPoolMXBean().getActiveConnections();
                assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,approval,other->null));
                assertEquals(borrowed,f.authoritySource.getHikariPoolMXBean().getActiveConnections());return null;
            });
        }
    }
    @ParameterizedTest @ValueSource(strings={"approval","expiry"})
    void currentApprovalGuardRollsBackAtActualMainBeforeCommit(String change)throws Exception {
        try(var f=fixture()) {
            f.db.execute("CREATE TABLE body_clear_lease_probe(marker INTEGER)");
            var approval=witness(f,Map.of("validUntil",f.now.plusSeconds(60)));
            assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,approval,lease->lease.main(lease.expiresAt(),()->{
                f.db.update("INSERT INTO body_clear_lease_probe VALUES(1)");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public int getOrder(){return Integer.MIN_VALUE;}
                    @Override public void beforeCommit(boolean readOnly) {
                        if(change.equals("expiry"))f.advance(Duration.ofSeconds(60));
                        else try{Files.writeString(f.root.resolve("body-clear.json"),"{}");}
                        catch(java.io.IOException failed){throw new AssertionError(failed);}
                    }
                });return null;
            })));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM body_clear_lease_probe",Integer.class));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            assertEquals(0,f.mainSource.getHikariPoolMXBean().getActiveConnections());
            assertEquals(0,f.authoritySource.getHikariPoolMXBean().getActiveConnections());
        }
    }
    @Test void auditCutoffAndBorrowBudgetAreStillFinite()throws Exception {
        try(var f=fixture()) {
            var approval=witness(f);f.advance(Duration.ofSeconds(995));
            assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,approval,lease->fail("insufficient budget")));
            assertTrue(f.authorityFault.sql.stream().noneMatch(sql->sql.contains("FOR UPDATE NOWAIT")));
            f.advance(Duration.ofSeconds(5));
            assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,approval,lease->fail("audit expired")));
        }
    }
    @Test void externalLockIsNowaitAndDoesNotRunBodyClearCallback()throws Exception {
        try(var f=fixture();Connection blocker=f.authoritySource.getConnection()) {
            var approval=witness(f);var called=new AtomicBoolean();blocker.setAutoCommit(false);
            try(var statement=blocker.createStatement();var row=statement.executeQuery("SELECT singleton_key FROM id2_maintainer FOR UPDATE")) {
                assertTrue(row.next());
                assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,approval,lease->{called.set(true);return null;}));
                assertFalse(called.get());
                assertTrue(f.authorityFault.sql.stream().anyMatch(sql->sql.contains("FOR UPDATE NOWAIT")));
            }finally{blocker.rollback();}
        }
    }
    @Test void maintainerLockSpansRealMainCommit()throws Exception {
        try(var f=fixture();var worker=Executors.newSingleThreadExecutor()) {
            var approval=witness(f);
            f.db.execute("CREATE TABLE body_clear_lease_probe(marker INTEGER)");
            var started=new CountDownLatch(1);var revoke=new AtomicReference<java.util.concurrent.Future<Integer>>();
            f.lease.withLease(f.request,approval,lease->{
                revoke.set(worker.submit(()->{started.countDown();return f.independent.update("UPDATE id2_maintainer SET status='REVOKED',version=2");}));
                try{assertTrue(started.await(2,TimeUnit.SECONDS));}
                catch(InterruptedException failed){Thread.currentThread().interrupt();throw new AssertionError(failed);}
                lease.main(lease.expiresAt(),()->{lease.requireMainTransaction(f.mainSource);
                    assertThrows(IllegalStateException.class,()->lease.requireAuthorityTransaction(f.authoritySource));
                    f.db.update("INSERT INTO body_clear_lease_probe VALUES(1)");return null;});
                assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM body_clear_lease_probe",Integer.class));
                assertThrows(TimeoutException.class,()->revoke.get().get(100,TimeUnit.MILLISECONDS));return null;
            });
            assertEquals(1,revoke.get().get(5,TimeUnit.SECONDS));
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,approval,lease->null));
        }
    }
    @Test void bodyClearUnknownCommitPreservesCommittedFactAndCause()throws Exception {
        try(var f=fixture()) {
            f.db.execute("CREATE TABLE body_clear_lease_probe(marker INTEGER)");var approval=witness(f);
            f.failCommitForSql(MAIN,"INSERT INTO body_clear_lease_probe",true);
            RuntimeException error=assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,approval,lease->
                    lease.main(lease.expiresAt(),()->{f.db.update("INSERT INTO body_clear_lease_probe VALUES(1)");return null;})));
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            assertTrue(cause instanceof java.sql.SQLException);assertTrue(cause.getMessage().contains("after failure"));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM body_clear_lease_probe",Integer.class));
            assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,approval,lease->null));
        }
    }
}
