package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real owned file-JDBC persistence; identity/source/transport ports remain doubles, no model dispatch. */
class ProductComparisonStartFailureClosureTest extends ProductComparisonStartHandoffRaceFixture {
    @Override String boundCommand() {
        String id=super.boundCommand();
        db.update("UPDATE con5_comparison_candidate SET status='RUNNING' WHERE candidate_id=?", CANDIDATE);
        return id;
    }
    void failAttempt(String error) {
        db.update("UPDATE con5_candidate_attempt SET status='FAILED',error_code=? WHERE attempt_id=?", error, ATTEMPT);
        db.update("UPDATE con5_candidate_lease SET status='FAILED' WHERE attempt_id=?", ATTEMPT);
        db.update("UPDATE con5_comparison_candidate SET status='FAILED' WHERE candidate_id=?", CANDIDATE);
    }
    void invocation(String state,String raw,String sha) {
        db.update("INSERT INTO con5_matrix_invocation VALUES(?,?,?,?,?,?,?,?)",ATTEMPT,DIGEST,DIGEST,
                managed.configurationVersion(),state,sha,raw,ts(NOW));
    }
    void cannotRetry() {
        assertThrows(IllegalStateException.class,()->commands.reserveExact(scope,actor,"new-blind-key",DIGEST,managed,UNTIL));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con5_start_command",Integer.class));
    }
    @ParameterizedTest @ValueSource(strings={"RESERVED","RECEIVED","COMMITTED"})
    void failedAttemptCannotEraseUnknownOrUnclosedNativeInvocation(String state) {
        boundCommand(); invocation(state,null,null); failAttempt("MODEL_UNAVAILABLE");
        commands.markReconcile(original().commandId(),"HANDOFF_RESULT_REQUIRES_RECONCILIATION");
        assertEquals("RECONCILE_REQUIRED",app.read(TASK,INPUT,CANDIDATE,KEY,human).status());
        cannotRetry(); assertEquals(0,claims.get()); assertEquals(0,executes.get());
    }
    @Test void knownNotDispatchedFailureHasAccurateReadOnlyReceiptAndPersistsAcrossReopen() throws Exception {
        boundCommand();failAttempt("SOURCE_CHANGED");
        var value=app.read(TASK,INPUT,CANDIDATE,KEY,human);
        assertEquals("FAILED",value.status());assertEquals("CANDIDATE_FAILED_BEFORE_MODEL_DISPATCH",value.errorCode());
        ownedAnchor.close();ownedAnchor=null;commands=openCommands();
        var reopened=commands.lookupOriginalKey(TASK,actor,KEY).orElseThrow();
        assertEquals("FAILED",reopened.status());assertEquals(value.errorCode(),reopened.errorCode());
        assertTrue(commands.reserveExact(scope,actor,"explicit-new-key",DIGEST,managed,UNTIL).newlyReserved());
        assertEquals("DISPATCHING",db.queryForObject("SELECT status FROM con5_start_command WHERE command_id=?",String.class,value.commandId()));
        assertEquals(0,claims.get());assertEquals(0,executes.get());
    }
    @Test void exactWindowRejectionIsKnownButGenericRejectedStatusIsNotProof() {
        boundCommand();invocation("REJECTED",null,null);failAttempt("MODEL_UNAVAILABLE");cannotRetry();
        failAttempt("EXECUTION_WINDOW_INSUFFICIENT");
        assertEquals("EXECUTION_WINDOW_INSUFFICIENT",app.read(TASK,INPUT,CANDIDATE,KEY,human).errorCode());
        assertTrue(commands.reserveExact(scope,actor,"explicit-window-retry",DIGEST,managed,UNTIL).newlyReserved());
    }
    @Test void responseRejectionMustHaveExactConfigurationAndIntactRetainedBytes() {
        boundCommand();String raw="owned-invalid-response";invocation("REJECTED",raw,ProductComparisonJson.hash(raw));failAttempt("MATRIX_RESPONSE_INVALID");
        assertEquals("MATRIX_RESPONSE_INVALID",app.read(TASK,INPUT,CANDIDATE,KEY,human).errorCode());
        db.update("UPDATE con5_matrix_invocation SET response_sha=?",SHA);cannotRetry();
        db.update("UPDATE con5_matrix_invocation SET response_sha=?,configuration_version='other'",ProductComparisonJson.hash(raw));cannotRetry();
        db.update("UPDATE con5_matrix_invocation SET configuration_version=?",managed.configurationVersion());
        db.update("UPDATE con5_candidate_attempt SET request_id='another-command'");cannotRetry();
    }
    @Test void initialCoordinateLookupDoesNotConsumeNativeResponseBeforeSourceAdmission() {
        boundCommand();failAttempt("MATRIX_RESPONSE_INVALID");String raw="owned response body";
        invocation("REJECTED",raw,ProductComparisonJson.hash(raw));
        var observed=spy(db);var guarded=new ProductComparisonStartCommands(observed,
                new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),clock);
        clearInvocations(observed);
        assertEquals("DISPATCHING",guarded.lookupOriginalKeyMetadata(TASK,actor,KEY).orElseThrow().status());
        verify(observed,never()).query(eq("SELECT status,configuration_version,response_json,response_sha FROM con5_matrix_invocation WHERE attempt_id=?"),
                any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
        assertEquals("FAILED",guarded.lookupOriginalKey(TASK,actor,KEY).orElseThrow().status());
    }
    @Test void currentReadAndSourcesRemainRequiredForConfirmedFailure() {
        boundCommand();failAttempt("SOURCE_CHANGED");readAllowed=false;
        assertThrows(RuntimeException.class,()->app.read(TASK,INPUT,CANDIDATE,KEY,human));
        readAllowed=true;sourceAllowed=false;
        assertThrows(RuntimeException.class,()->app.read(TASK,INPUT,CANDIDATE,KEY,human));
        assertEquals(0,claims.get());assertEquals(0,executes.get());
    }
    ProductComparisonMatrixStore matrixStore() {
        return new ProductComparisonMatrixStore(db,new ProductComparisonJson(mapper),
                new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),clock);
    }
    @Test void invalidResponseRejectionAndFailureAreAtomicAndMidFailurePreservesScene() {
        boundCommand();String raw="owned-invalid-response";invocation("RECEIVED",raw,ProductComparisonJson.hash(raw));
        assertThrows(SecurityException.class,()->matrixStore().reject(TASK,ATTEMPT,()->{},()->{
            failAttempt("MATRIX_RESPONSE_INVALID");throw new SecurityException("owned final permission withdrawal");
        }));
        assertEquals("RECEIVED",db.queryForObject("SELECT status FROM con5_matrix_invocation",String.class));
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM con5_candidate_attempt",String.class));
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM con5_candidate_lease",String.class));
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM con5_comparison_candidate",String.class));
        matrixStore().reject(TASK,ATTEMPT,()->{},()->failAttempt("MATRIX_RESPONSE_INVALID"));
        assertEquals("REJECTED",db.queryForObject("SELECT status FROM con5_matrix_invocation",String.class));
        assertEquals("FAILED",original().status());
        assertEquals(raw,db.queryForObject("SELECT response_json FROM con5_matrix_invocation",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con5_matrix_report",Integer.class));
        verify(results,never()).report(anyString(),anyString(),anyString(),any());
    }
}
