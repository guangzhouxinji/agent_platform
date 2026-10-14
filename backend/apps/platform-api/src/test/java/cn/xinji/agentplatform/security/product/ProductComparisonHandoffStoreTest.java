package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual file-H2 atomic receipt and persistent lookup; identity/source ports are explicit fixtures. */
class ProductComparisonHandoffStoreTest extends ProductComparisonStartHandoffRaceFixture {
    ProductComparisonHandoffStore handoffs;
    @BeforeEach void handoffSchema() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!Files.isDirectory(root.resolve("db/dm8")))root=root.getParent();
        String sql=Files.readString(root.resolve("db/dm8/V105__comparison_start_handoff_receipt.sql")).replaceAll("(?m)^\\s*--[^\\r\\n]*","");
        for(String statement:sql.split(";"))if(!statement.isBlank())db.execute(statement);
        handoffs=openStore();
    }
    ProductComparisonHandoffStore openStore(){var ds=new DriverManagerDataSource(databaseUrl,"sa","");return new ProductComparisonHandoffStore(new org.springframework.jdbc.core.JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),openCommands(),new ProductComparisonJson(mapper),clock);}
    ProductComparisonStartCommands.Command prepared(){
        var c=commands.reserveExact(scope,actor,KEY,DIGEST,new ProductComparisonStartCommands.ManagedBinding("worker","HANDOFF_ONLY:managed-v1"),UNTIL).command();
        commands.dispatchOnce(c.commandId());insertDelegation();return commands.bindDelegation(c.commandId(),DELEGATION);
    }
    @Test void receiptAndDefinitiveNotClaimedAreAtomicAndSurviveReconnect(){
        var c=prepared();var proof=handoffs.accept(c,()->{});assertEquals("ADMISSION_REQUIRED",proof.status());
        var saved=openCommands().metadata(c.commandId());assertEquals("FAILED",saved.status());assertEquals(ProductComparisonHandoffStore.ERROR,saved.errorCode());
        assertEquals(proof,openStore().read(saved).orElseThrow());assertEquals(proof,handoffs.accept(saved,()->{}));
        assertEquals("QUEUED",db.queryForObject("SELECT status FROM con5_comparison_candidate",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Integer.class));noClosure();
    }
    @Test void lastAuthorizationFailureRollsBackReceiptAndCommand(){
        var c=prepared();AtomicInteger calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->handoffs.accept(c,()->{if(calls.incrementAndGet()==2)throw new IllegalStateException("source withdrawn");}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_start_handoff_receipt",Integer.class));assertEquals("DISPATCHING",commands.metadata(c.commandId()).status());
    }
    @Test void cancellationEpochBeforeReceiveClosesReceipt(){var c=prepared();db.update("UPDATE con3_task SET cancel_epoch=cancel_epoch+1");assertThrows(IllegalStateException.class,()->handoffs.accept(c,()->{}));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_start_handoff_receipt",Integer.class));}
    @Test void expiryDuringReceiveRollsBack(){var c=prepared();AtomicInteger calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->handoffs.accept(c,()->{if(calls.incrementAndGet()==2)clock.now=UNTIL;}));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_start_handoff_receipt",Integer.class));}
    @Test void currentInputDriftAndWrongBindingCloseReceipt(){var c=prepared();db.update("DELETE FROM con5_comparison_current");assertThrows(IllegalStateException.class,()->handoffs.accept(c,()->{}));}
    @Test void existingAttemptCannotBecomeAnAdmissionOnlyReceipt(){var c=prepared();insertAttempt(ATTEMPT,1,"start:"+c.commandId(),UNTIL);assertThrows(IllegalStateException.class,()->handoffs.accept(c,()->{}));}
    @Test void bodyOrCoordinateTamperNeverProvesReception(){var c=prepared();handoffs.accept(c,()->{});db.update("UPDATE con5_start_handoff_receipt SET request_sha=?","d".repeat(64));assertThrows(IllegalStateException.class,()->handoffs.read(commands.metadata(c.commandId())));}
    @Test void sameOriginalRequestConflictsOnAnotherPayload(){var c=prepared();handoffs.accept(c,()->{});assertThrows(IllegalStateException.class,()->commands.reserveExact(scope,actor,KEY,"d".repeat(64),c.managed(),UNTIL));}
    @Test void missingReceiptIsUnknownNotProvenReception(){var c=prepared();commands.markReconcile(c.commandId(),"lost");assertTrue(handoffs.read(commands.metadata(c.commandId())).isEmpty());}
    @Test void missingTableCannotHalfCommit(){var c=prepared();db.execute("DROP TABLE con5_start_handoff_receipt");assertThrows(org.springframework.dao.DataAccessException.class,()->handoffs.accept(c,()->{}));assertEquals("DISPATCHING",commands.metadata(c.commandId()).status());}
    @Test void actualSpringStartAssemblyRequiresV105AndManagedTlsButNoModelConfiguration() throws Exception {
        try(var tls=new ProductComparisonStartMtlsEngineeringSupport.Tls()){
            var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ProductComparisonStartConfiguration.class)
                .withPropertyValues("product.identity.enabled=true","product.source.engine.enabled=true","product.consultation.comparison.enabled=true","product.consultation.comparison.start.enabled=true",
                    "spring.datasource.hikari.schema=PUBLIC","product.consultation.comparison.start.configuration-version=handoff-assembly-v1","product.consultation.comparison.start.lease-millis=60000",
                    "product.consultation.comparison.start.product-origin=https://localhost:9443/","product.consultation.comparison.start.worker-subject=worker",
                    "product.consultation.comparison.start.worker-keystore="+tls.root.resolve("worker.p12"),"product.consultation.comparison.start.worker-password="+tls.password,
                    "product.consultation.comparison.start.truststore="+tls.root.resolve("trust.p12"),"product.consultation.comparison.start.truststore-password="+tls.password)
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->db)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(new DriverManagerDataSource(databaseUrl,"sa","")))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->mapper)
                .withBean(ProductComparisonJson.class,()->new ProductComparisonJson(mapper))
                .withBean(ProductComparisonInputApplication.class,()->inputs).withBean(ProductComparisonWorkerDelegations.class,()->delegations)
                .withBean(ProductWorkerDelegations.class,()->authority).withBean(ProductComparisonMatrixReader.class,()->results)
                .withBean(cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory.class,()->tasks)
                .withBean(ProductConsultationAccess.class,()->access);
            runner.run(c->org.assertj.core.api.Assertions.assertThat(c).hasNotFailed().hasSingleBean(ProductComparisonStartApplication.class).hasSingleBean(ProductComparisonHandoffStore.class).doesNotHaveBean(ProductComparisonModelAdmission.class));
            db.execute("DROP TABLE con5_start_handoff_receipt");
            runner.run(c->org.assertj.core.api.Assertions.assertThat(c).hasFailed());
        }
    }
    @Test void forgedFailedCodeWithoutReceiptCannotReleaseNewCommand(){
        var c=prepared();db.update("UPDATE con5_start_command SET status='FAILED',error_code=?",ProductComparisonHandoffStore.ERROR);
        assertThrows(IllegalStateException.class,()->commands.lookupOriginalKey(TASK,actor,KEY));
        assertThrows(IllegalStateException.class,()->commands.reserveExact(scope,actor,"new-key",DIGEST,c.managed(),UNTIL));
    }
    @Test void provenReceiptReleasesOnlyNewExplicitRequestAndReplayNeverChangesOriginal(){
        var c=prepared();var proof=handoffs.accept(c,()->{});
        assertEquals(ProductComparisonHandoffStore.ERROR,commands.lookupOriginalKey(TASK,actor,KEY).orElseThrow().errorCode());
        var next=commands.reserveExact(scope,actor,"explicit-new-key",DIGEST,c.managed(),UNTIL);
        assertTrue(next.newlyReserved());assertNotEquals(c.commandId(),next.command().commandId());
        assertEquals(proof,handoffs.read(commands.metadata(c.commandId())).orElseThrow());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt",Integer.class));
    }

}
