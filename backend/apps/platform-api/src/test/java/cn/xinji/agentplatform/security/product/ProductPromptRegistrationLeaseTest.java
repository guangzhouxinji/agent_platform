package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.Duration;
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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual Hikari/H2 locks and commits; component evidence does not activate a DM8 deployment. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptRegistrationLeaseTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{tls.close();}
    ProductPromptRegisterFixture fixture()throws Exception{return new ProductPromptRegisterFixture(root.resolve(UUID.randomUUID().toString()),tls);}

    @Test void ownsOnlyTheSelectedWorkerTransactionAndCommitsBeforeReturning()throws Exception {
        try(var f=fixture()) {
            f.db.execute("CREATE TABLE lease_probe(marker INTEGER)");f.independent.execute("CREATE TABLE lease_probe(marker INTEGER)");
            var escaped=new AtomicReference<ProductPromptRegistrationLease.Lease>();
            f.lease.withLease(f.request,f.witness(),lease->{
                escaped.set(lease);assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                assertEquals(f.subject,lease.subject());assertEquals(f.organization,lease.organization());
                assertEquals(f.certificateSha,lease.certificateSha());assertEquals(1,lease.maintainerVersion());
                assertEquals(f.witness().evidence().primaryDatabaseIdentitySha(),lease.primaryDatabaseIdentitySha());
                assertEquals(f.witness().evidence().authorityDatabaseIdentitySha(),lease.authorityDatabaseIdentitySha());
                lease.main(lease.expiresAt(),()->{
                    lease.requireMainTransaction(f.mainSource);
                    assertThrows(IllegalStateException.class,()->lease.requireAuthorityTransaction(f.authoritySource));
                    assertEquals(1,TransactionSynchronizationManager.getResourceMap().size());
                    assertThrows(IllegalStateException.class,()->lease.authority(lease.expiresAt(),()->null));
                    f.db.update("INSERT INTO lease_probe VALUES(1)");return null;
                });
                assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM lease_probe",Integer.class));
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                lease.authority(lease.expiresAt(),()->{
                    lease.requireAuthorityTransaction(f.authoritySource);
                    assertThrows(IllegalStateException.class,()->lease.requireMainTransaction(f.mainSource));
                    f.independent.update("INSERT INTO lease_probe VALUES(2)");return null;
                });
                assertEquals(1,f.independent.queryForObject("SELECT COUNT(*) FROM lease_probe",Integer.class));return null;
            });
            assertThrows(IllegalStateException.class,()->escaped.get().requireCurrent());
            assertThrows(IllegalStateException.class,()->escaped.get().approval());
            assertEquals(0,f.count(AUTHORITY,"idp_workspace_register_request"));
            assertTrue(f.authorityFault.sql.stream().anyMatch(sql->sql.contains("FOR UPDATE NOWAIT")));
        }
    }
    @Test void sameInstanceRejectsConcurrentAdmissionWithoutBorrowingAnotherConnection()throws Exception {
        try(var f=fixture()) {
            f.lease.withLease(f.request,f.witness(),lease->{
                int active=f.authoritySource.getHikariPoolMXBean().getActiveConnections();
                assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,f.witness(),other->null));
                assertEquals(active,f.authoritySource.getHikariPoolMXBean().getActiveConnections());return null;
            });
        }
    }
    @Test void rejectsOtherThreadAndForeignTransactionResource()throws Exception {
        try(var f=fixture();var worker=Executors.newSingleThreadExecutor()) {
            f.lease.withLease(f.request,f.witness(),lease->{
                try{worker.submit(()->assertThrows(IllegalStateException.class,lease::requireCurrent)).get(5,TimeUnit.SECONDS);}
                catch(Exception failure){throw new AssertionError(failure);}
                Object foreign=new Object();TransactionSynchronizationManager.bindResource(foreign,new Object());
                try{assertThrows(IllegalStateException.class,lease::requireCurrent);}
                finally{TransactionSynchronizationManager.unbindResource(foreign);}
                lease.main(lease.expiresAt(),()->{
                    Object nested=new Object();TransactionSynchronizationManager.bindResource(nested,new Object());
                    try{assertThrows(IllegalStateException.class,lease::requireCurrent);}
                    finally{TransactionSynchronizationManager.unbindResource(nested);}return null;
                });return null;
            });
        }
    }
    @Test void rejectsAmbientTransactionsAndMismatchedManagers()throws Exception {
        try(var f=fixture()) {
            new TransactionTemplate(f.mainManager).executeWithoutResult(status->
                    assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,f.witness(),lease->null)));
            assertThrows(IllegalStateException.class,()->new ProductPromptRegistrationLease(f.mainSource,f.authoritySource,
                    new DataSourceTransactionManager(f.authoritySource),f.authorityManager,f.clock));
            assertThrows(IllegalStateException.class,()->new ProductPromptRegistrationLease(f.mainSource,f.mainSource,
                    f.mainManager,f.mainManager,f.clock));
        }
    }
    @ParameterizedTest @ValueSource(strings={"subject","organization","certificate","version","roles","status"})
    void rejectsCurrentMaintainerDrift(String field)throws Exception {
        try(var f=fixture()) {
            switch(field) {
                case "subject"->f.independent.update("UPDATE id2_maintainer SET subject_ref='other'");
                case "organization"->f.independent.update("UPDATE id2_maintainer SET organization=?",UUID.randomUUID().toString());
                case "certificate"->f.independent.update("UPDATE id2_maintainer SET certificate_sha=?","0".repeat(64));
                case "version"->f.independent.update("UPDATE id2_maintainer SET version=2");
                case "roles"->f.independent.update("UPDATE id2_maintainer SET allowed_roles='SUBJECTS,HUMAN_RETENTION'");
                case "status"->f.independent.update("UPDATE id2_maintainer SET status='REVOKED'");
            }
            var called=new AtomicBoolean();
            assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->{called.set(true);return null;}));
            assertFalse(called.get());assertEquals(0,f.count(AUTHORITY,"idp_workspace"));
        }
    }
    @Test void missingTransportCertificateCannotReachTheCallback()throws Exception {
        try(var f=fixture()) {
            f.request.setSecure(false);f.request.setScheme("http");
            assertFalse(f.request.isSecure());
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,f.witness(),lease->fail("must reject")));
            f.request.setSecure(true);f.request.setScheme("https");f.request.removeAttribute("jakarta.servlet.request.X509Certificate");
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,f.witness(),lease->fail("must reject")));
        }
    }
    @Test void externalMaintainerLockIsNowaitAndDoesNotRunTheOperation()throws Exception {
        try(var f=fixture();Connection blocker=f.authoritySource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.createStatement();var row=statement.executeQuery("SELECT singleton_key FROM id2_maintainer FOR UPDATE")) {
                assertTrue(row.next());var called=new AtomicBoolean();
                assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->{called.set(true);return null;}));
                assertFalse(called.get());
            }finally{blocker.rollback();}
        }
    }
    @Test void maintainerRevocationWaitsAcrossTheRealMainCommitThenTakesEffect()throws Exception {
        try(var f=fixture();var worker=Executors.newSingleThreadExecutor()) {
            f.db.execute("CREATE TABLE lease_probe(marker INTEGER)");
            var entered=new CountDownLatch(1);var update=new AtomicReference<java.util.concurrent.Future<Integer>>();
            f.lease.withLease(f.request,f.witness(),lease->{
                update.set(worker.submit(()->{entered.countDown();return f.independent.update("UPDATE id2_maintainer SET status='REVOKED',version=2");}));
                try{assertTrue(entered.await(2,TimeUnit.SECONDS));}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
                lease.main(lease.expiresAt(),()->{f.db.update("INSERT INTO lease_probe VALUES(1)");return null;});
                assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM lease_probe",Integer.class));
                assertThrows(TimeoutException.class,()->update.get().get(100,TimeUnit.MILLISECONDS));return null;
            });
            assertEquals(1,update.get().get(5,TimeUnit.SECONDS));
            assertThrows(ProductPromptAccessDenied.class,()->f.lease.withLease(f.request,f.witness(),lease->null));
        }
    }
    @Test void workerPoolExhaustionFailsAndReleasesTheIdentityLock()throws Exception {
        try(var f=fixture()) {
            assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->{
                try(Connection a=f.authoritySource.getConnection();Connection b=f.authoritySource.getConnection()) {
                    assertThrows(RuntimeException.class,()->lease.authority(lease.expiresAt(),()->fail("no worker connection")));
                }catch(java.sql.SQLException failure){throw new AssertionError(failure);}
                return null;
            }));
            assertEquals(1,f.independent.update("UPDATE id2_maintainer SET version=2"));
        }
    }
    @Test void insufficientBorrowBudgetDoesNotAcquireTheMaintenanceLock()throws Exception {
        try(var f=fixture()) {
            f.advance(Duration.ofMillis(2_994_500));
            assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,f.witness(),lease->fail("no budget")));
            assertTrue(f.authorityFault.sql.stream().noneMatch(sql->sql.contains("FOR UPDATE NOWAIT")));
            assertEquals(0,f.authoritySource.getHikariPoolMXBean().getActiveConnections());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void allTransactionGuardsRecheckApprovalBeforeCommit(boolean authority)throws Exception {
        try(var f=fixture()) {
            var db=authority?f.independent:f.db;db.execute("CREATE TABLE lease_probe(marker INTEGER)");
            assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->{
                java.util.function.Supplier<Void> operation=()->{
                    db.update("INSERT INTO lease_probe VALUES(1)");
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                        @Override public int getOrder(){return Integer.MIN_VALUE;}
                        @Override public void beforeCommit(boolean readOnly){
                            try{Files.writeString(f.root.resolve(f.registerRef),"{}");}
                            catch(java.io.IOException failure){throw new AssertionError(failure);}
                        }
                    });return null;
                };
                return authority?lease.authority(lease.expiresAt(),operation):lease.main(lease.expiresAt(),operation);
            }));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM lease_probe",Integer.class));
        }
    }
    @Test void expiryAfterBeforeCommitCannotBeConvertedIntoOneMillisecondPermission()throws Exception {
        try(var f=fixture()) {
            f.db.execute("CREATE TABLE lease_probe(marker INTEGER)");
            assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->
                    lease.main(f.now.plusSeconds(60),()->{
                        f.db.update("INSERT INTO lease_probe VALUES(1)");
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                            @Override public void beforeCommit(boolean readOnly){f.advance(Duration.ofSeconds(60));}
                        });return null;
                    })));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM lease_probe",Integer.class));
            assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,f.witness(),lease->null));
        }
    }
    @Test void preservesUnknownCommitCauseAndTheCommittedMainFact()throws Exception {
        try(var f=fixture()) {
            f.db.execute("CREATE TABLE lease_probe(marker INTEGER)");f.failCommitForSql(MAIN,"INSERT INTO lease_probe",true);
            RuntimeException error=assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->
                    lease.main(lease.expiresAt(),()->{f.db.update("INSERT INTO lease_probe VALUES(1)");return null;})));
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            assertTrue(cause instanceof java.sql.SQLException);assertTrue(cause.getMessage().contains("after failure"));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM lease_probe",Integer.class));
            assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,f.witness(),lease->null));
        }
    }
    @Test void workerAndMaintenanceConnectionsReceiveFiniteNetworkBudgets()throws Exception {
        try(var f=fixture()) {
            f.lease.withLease(f.request,f.witness(),lease->{
                lease.main(lease.expiresAt(),()->{
                    var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(f.mainSource);
                    try{assertEquals(Connection.TRANSACTION_READ_COMMITTED,holder.getConnection().getTransactionIsolation());}
                    catch(java.sql.SQLException failure){throw new AssertionError(failure);}return null;
                });
                lease.authority(lease.expiresAt(),()->null);return null;
            });
            assertTrue(f.mainFault.networkTimeouts.contains(5000));assertTrue(f.authorityFault.networkTimeouts.contains(5000));
            assertTrue(f.authorityFault.queryTimeouts.stream().allMatch(value->value>=1&&value<=5));
            assertEquals(0,f.mainSource.getHikariPoolMXBean().getActiveConnections());
            assertEquals(0,f.authoritySource.getHikariPoolMXBean().getActiveConnections());
        }
    }
    @ParameterizedTest @ValueSource(strings={"rollback","setAutoCommit","setNetworkTimeout"})
    void maintenanceReleaseFailuresArePreservedAndCloseFurtherAdmission(String method)throws Exception {
        try(var f=fixture()) {
            RuntimeException error=assertThrows(RuntimeException.class,()->f.lease.withLease(f.request,f.witness(),lease->{
                f.authorityFault.connectionCalls.clear();
                // The final current-identity read is the first network setter after the
                // callback; the second is the cleanup budget immediately before rollback.
                f.authorityFault.armConnection(method,method.equals("setNetworkTimeout")?2:1);return null;
            }));
            assertNull(f.authorityFault.failConnectionMethod,"the intended physical fault must actually fire");
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            assertTrue(cause instanceof java.sql.SQLException);assertTrue(cause.getMessage().contains(method));
            assertThrows(IllegalStateException.class,()->f.lease.withLease(f.request,f.witness(),lease->null));
            if(method.equals("setAutoCommit")) {
                var calls=f.authorityFault.connectionCalls;
                assertTrue(calls.indexOf("rollback")>=0);
                assertTrue(calls.indexOf("rollback")<calls.indexOf("setAutoCommit"));
            }
        }
    }
    @Test void normalReleaseRollsBackBeforeRestoringAutocommitAndReturnsCleanConnection()throws Exception {
        try(var f=fixture()) {
            f.lease.withLease(f.request,f.witness(),lease->{f.authorityFault.connectionCalls.clear();return null;});
            var calls=f.authorityFault.connectionCalls;
            assertTrue(calls.indexOf("rollback")>=0);
            assertTrue(calls.indexOf("rollback")<calls.indexOf("setAutoCommit"));
            try(var connection=f.authoritySource.getConnection()) {
                assertTrue(connection.getAutoCommit());assertFalse(connection.isReadOnly());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED,connection.getTransactionIsolation());
            }
        }
    }
}
