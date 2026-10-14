package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ProductModelInvocationFenceTest {
    final Instant now=Instant.parse("2026-10-06T00:00:00Z");
    final String id=UUID.randomUUID().toString();
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final ProductModelInvocationFence fence;
    final ProductTaskDirectory.Task task=new ProductTaskDirectory.Task(id,"org","issuer","actor","ACTIVE",3,1,"scope",1,now.plusSeconds(900));
    ProductModelInvocationFenceTest() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY)");db.update("INSERT INTO con3_task VALUES(?)",id);
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V089__product_consultation_model_turn.sql"),
                new FileSystemResource("db/dm8/V097__consultation_model_invocation.sql")).execute(source);
        fence=new ProductModelInvocationFence(db,tx,new ProductRetentionPolicy("policy.json",1,"b".repeat(64),Duration.ofHours(1)),Clock.fixed(now,ZoneOffset.UTC));
    }
    ProductModelInvocationFence.Claim reserve(Runnable check) { return fence.reserve(task,"actor","request","a".repeat(64),"PROPOSAL",null,null,check); }
    @Test void claimIsCommittedBoundedAndContainsNoPromptOrOutput() {
        var claim=reserve(()->{});assertTrue(claim.dispatch());assertEquals(task.expiresAt(),claim.expiresAt());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
        var error=assertThrows(ResponseStatusException.class,()->reserve(()->{}));
        assertEquals(409,error.getStatusCode().value());assertEquals("MODEL_INVOCATION_UNRESOLVED",error.getReason());
    }
    @Test void failedLastAuthorityCheckRollsBackClaimAndAllowsFirstRealDispatchLater() {
        var calls=new AtomicInteger();assertThrows(SecurityException.class,()->reserve(()->{if(calls.incrementAndGet()==2)throw new SecurityException();}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));
        assertTrue(reserve(()->{}).dispatch());
    }
    @Test void outerTransactionCannotLeaveUncommittedOwnershipDuringModelCall() {
        assertThrows(IllegalStateException.class,()->tx.execute(status->reserve(()->{})));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));
    }
    @Test void missingFenceSchemaDoesNotFallBackToUnprotectedDispatch() {
        db.execute("DROP TABLE con3_model_invocation");
        assertThrows(org.springframework.dao.DataAccessException.class,()->reserve(()->{}));
    }
    @Test void rollbackMigrationRefusesRetainedClaims() {
        reserve(()->{});
        assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->
                new ResourceDatabasePopulator(new FileSystemResource("db/dm8/U097__consultation_model_invocation.sql")).execute(db.getDataSource()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));
    }
}
