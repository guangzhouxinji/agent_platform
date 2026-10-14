package cn.xinji.agentplatform.security.product;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ProductModelInvocationRecoveryVerifierTest {
    @TempDir Path root;
    final Instant now=Instant.parse("2026-10-06T00:00:00Z");
    final String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
    JdbcTemplate db; String policySha;
    ProductModelInvocationRecoveryVerifier setup() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY,version BIGINT,expires_at TIMESTAMP)");
        db.update("INSERT INTO con3_task VALUES(?,?,?)",task,3,Timestamp.from(now.plusSeconds(7200)));
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V089__product_consultation_model_turn.sql"),
                new FileSystemResource("db/dm8/V097__consultation_model_invocation.sql")).execute(source);
        Files.writeString(root.resolve("policy.json"),"engineering-only retention evidence");
        policySha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("policy.json"))));
        db.update("INSERT INTO con3_model_invocation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,task,3,"actor","PROPOSAL","request","a".repeat(64),null,null,
                Timestamp.from(now),Timestamp.from(now.plusSeconds(3600)),"policy.json",1,policySha);
        return new ProductModelInvocationRecoveryVerifier(db,root.toAbsolutePath(),10000);
    }
    void complete() {
        db.update("INSERT INTO con3_model_turn VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,task,3,"actor","PROPOSAL","request","a".repeat(64),null,null,
                "component","RETRIEVAL_DIALOGUE/v3","{}","component envelope","{}",Timestamp.from(now.plusSeconds(1)),Timestamp.from(now.plusSeconds(3600)),"policy.json",1,policySha);
    }
    @Test void pendingAndCompletedClaimsVerifyWithoutCreatingOrChangingAnyTurn() throws Exception {
        var verifier=setup();assertEquals(1,verifier.verify());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
        complete();assertEquals(1,verifier.verify());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
    }
    @Test void claimAndCompletedTurnMustBindExactRequestAndExpiry() throws Exception {
        var verifier=setup();complete();db.update("UPDATE con3_model_turn SET request_sha=?","c".repeat(64));
        assertTrue(assertThrows(IllegalStateException.class,verifier::verify).getMessage().contains("TURN_BINDING"));
        db.update("UPDATE con3_model_turn SET request_sha=?,expires_at=?","a".repeat(64),Timestamp.from(now.plusSeconds(4000)));
        assertThrows(IllegalStateException.class,verifier::verify);
    }
    @Test void futureVersionAndExpiryBeyondTaskFailEvenIfHistoricalRecordExpired() throws Exception {
        var verifier=setup();db.update("UPDATE con3_model_invocation SET task_version=4");assertThrows(IllegalStateException.class,verifier::verify);
        db.update("UPDATE con3_model_invocation SET task_version=3,expires_at=?",Timestamp.from(now.plusSeconds(8000)));
        assertThrows(IllegalStateException.class,verifier::verify);
    }
    @Test void pendingClaimRetentionOriginalIsIncludedAndTamperingFails() throws Exception {
        var verifier=setup();var refs=new HashMap<String,String>();ProductModelTurnRetentionReferences.collect(db,refs);
        assertEquals(Map.of("policy.json",policySha),refs);Files.writeString(root.resolve("policy.json"),"tampered");
        assertTrue(assertThrows(IllegalStateException.class,verifier::verify).getMessage().contains("RETENTION_ORIGINAL"));
    }
    @Test void changedTurnKeyCannotHideCompletedTurnAsPending() throws Exception {
        var verifier=setup();complete();db.update("UPDATE con3_model_turn SET request_id='changed'");
        assertThrows(IllegalStateException.class,verifier::verify);
    }
    @Test void explanationClaimMustPointToSameTaskVersionAndResultSha() throws Exception {
        var verifier=setup();String result=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();
        db.execute("CREATE TABLE con4_search_attempt(attempt_id VARCHAR(36),task_id VARCHAR(36),task_version BIGINT)");
        db.execute("CREATE TABLE con4_result_set(result_set_id VARCHAR(36),attempt_id VARCHAR(36),result_sha VARCHAR(64))");
        db.update("INSERT INTO con4_search_attempt VALUES(?,?,?)",attempt,task,3);
        db.update("INSERT INTO con4_result_set VALUES(?,?,?)",result,attempt,"b".repeat(64));
        db.update("UPDATE con3_model_invocation SET kind='EXPLANATION',result_set_id=?,result_sha=?",result,"b".repeat(64));
        assertEquals(1,verifier.verify());db.update("UPDATE con4_search_attempt SET task_version=2");assertThrows(IllegalStateException.class,verifier::verify);
    }
    @Test void preFenceHistoricalSchemaIsReadOnlyCompatible() throws Exception {
        var verifier=setup();db.execute("DROP TABLE con3_model_invocation");assertEquals(0,verifier.verify());
    }
}
