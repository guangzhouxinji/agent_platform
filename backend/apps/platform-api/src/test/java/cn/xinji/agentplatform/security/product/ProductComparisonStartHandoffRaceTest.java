package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FIX-CON-START-HANDOFF-RACE-01: owned, independent file-H2 persistence negatives.
 * Commands (including every CAS/binding/read) and Application are real current workspace code.
 * Commands is spied only to observe closure/dispatch calls; no Commands method is stubbed.
 * Input, task-directory, human access/authority, delegation issuance and matrix-reader ports are
 * explicit Mockito doubles. Delegation issuance creates owned ACTIVE database rows. Transport is
 * an in-process double which creates owned ACTIVE attempts/slots and returns engineering responses;
 * it does NOT execute a worker, model, Issuer, Source, OAuth or mTLS chain. A late receipt double is
 * deliberately untrusted: no report/receipt/invocation or COMMITTED row is ever inserted here.
 * This proves storage/application handoff mechanics, not business success or end-to-end security.
 * No shared target, Docker, external model, existing receipt helper or concurrent-CAS duplication.
 */
class ProductComparisonStartHandoffRaceTest extends ProductComparisonStartHandoffRaceFixture {
    @Test void liveGuardIsPureReadAndReopenedFileDatabaseRetainsOriginalBinding() throws Exception {
        String id = boundCommand();
        var before = snapshot();
        assertDoesNotThrow(() -> commands.requireLiveHandoff(id));
        assertEquals(before, snapshot());
        // Close the final connection to force real file-backed database reopen, not an in-memory anchor.
        ownedAnchor.close();ownedAnchor=null;
        var reopened = openCommands();
        assertEquals(original(), reopened.lookupOriginalKey(TASK, actor, KEY).orElseThrow());
        assertDoesNotThrow(() -> reopened.requireLiveHandoff(id));
        assertEquals(before, snapshot());
        noClosure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelEpoch", "taskVersion", "taskState", "taskExpiry", "inputExpiry", "inputSha", "currentInput",
            "delegationWithdrawn", "authorizationVersion", "delegationWindow", "bindingWindow", "attemptUnknown",
            "slotUnknown", "slotReplacement", "slotLeaseMismatch", "attemptWorker", "attemptInput", "commandUnknown", "commandDeadline"})
    void liveGuardRejectsStalePersistentCoordinatesWithoutWriting(String change) {
        String id = boundCommand();
        invalidate(change);
        var before = snapshot();
        assertThrows(IllegalStateException.class, () -> commands.requireLiveHandoff(id), change);
        assertEquals(before, snapshot(), "Guard must not repair or rewrite history: " + change);
        assertThrows(IllegalStateException.class, () -> openCommands().requireLiveHandoff(id), change);
        assertEquals(before, snapshot());
        noClosure();
    }

    @Test void claimCrossingOriginalDeadlineRetainsActiveAttemptButNeverExecutesOrRedispatches() {
        duringClaim = () -> clock.now = UNTIL.plusMillis(1);
        var receipt = start();
        assertEquals("RECONCILE_REQUIRED", receipt.status());
        assertEquals(KEY, receipt.requestId());
        assertEquals(ATTEMPT, receipt.attemptId());
        assertEquals(1, claims.get());
        assertEquals(0, executes.get());
        assertEquals("ACTIVE", db.queryForObject("SELECT status FROM con5_candidate_attempt WHERE attempt_id=?", String.class, ATTEMPT));
        assertEquals("ACTIVE", db.queryForObject("SELECT status FROM con5_candidate_lease WHERE attempt_id=?", String.class, ATTEMPT));
        assertEquals(UNTIL, original().leaseUntil());
        assertEquals(1L, original().fencingToken());
        assertEquals(original(), openCommands().lookupOriginalKey(TASK, actor, KEY).orElseThrow());
        assertEquals(receipt.commandId(), start().commandId());
        assertEquals(1, claims.get());
        assertEquals(0, executes.get());
        verify(commands, times(1)).dispatchOnce(receipt.commandId());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM con5_start_command", Integer.class));
        noClosure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelEpoch", "taskVersion", "inputExpiry", "currentInput", "delegationWithdrawn", "authorizationVersion", "slotReplacement", "slotLeaseMismatch"})
    void changeAfterAttemptBindingRejectsBeforeNextHttp(String change) {
        beforeExecute = () -> invalidate(change);
        if (Set.of("taskVersion", "currentInput", "slotReplacement", "slotLeaseMismatch").contains(change)) {
            // The final GET must also refuse a now-invalid binding; it is not a retry license.
            assertThrows(IllegalStateException.class, this::start);
        } else {
            var receipt = start();
            assertEquals(ATTEMPT, receipt.attemptId());
            assertEquals(KEY, receipt.requestId());
            assertEquals("cancelEpoch".equals(change) ? "CANCELLED" : "RECONCILE_REQUIRED", receipt.status());
        }
        assertEquals(1, claims.get());
        assertEquals(0, executes.get(), change);
        assertEquals(ATTEMPT, original().attemptId());
        assertEquals("RECONCILE_REQUIRED", db.queryForObject("SELECT status FROM con5_start_command", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM con5_start_command", Integer.class));
        noClosure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelEpoch", "taskVersion", "delegationWithdrawn", "authorizationVersion"})
    void changeAfterDelegationBindingRejectsEvenClaimHttp(String change) {
        beforeClaim = () -> invalidate(change);
        start();
        assertEquals(0, claims.get());
        assertEquals(0, executes.get());
        assertNull(original().attemptId());
        assertEquals(DELEGATION, original().delegationId());
        noClosure();
    }

    @Test void lostClaimResponseReconcilesSameOwnedFileKeyWithoutRedispatch() {
        claimUnknown = true;
        var receipt = start();
        assertEquals("RECONCILE_REQUIRED", receipt.status());
        assertEquals(ATTEMPT, receipt.attemptId(), "GET may recover the exact ACTIVE attempt after an unknown claim");
        assertEquals(original(), openCommands().lookupOriginalKey(TASK, actor, KEY).orElseThrow());
        assertEquals(receipt, start());
        assertEquals(1, claims.get());
        assertEquals(0, executes.get());
        verify(commands, times(1)).dispatchOnce(receipt.commandId());
        noClosure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "input"})
    void lateExecuteReceiptMustRecheckCurrentHumanAndInputBeforeAttemptingClosure(String revoked) {
        duringExecute = () -> {
            if ("actor".equals(revoked)) compareAllowed = false;
            else sourceAllowed = false;
        };
        if ("input".equals(revoked)) assertThrows(IllegalStateException.class, this::start);
        else assertEquals("RECONCILE_REQUIRED", start().status());
        assertEquals(1, claims.get());
        assertEquals(1, executes.get());
        assertEquals("RECONCILE_REQUIRED", db.queryForObject("SELECT status FROM con5_start_command", String.class));
        noClosure(); // A failed exact-receipt SQL join is NOT a substitute for checking before closure.
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "input"})
    void getRechecksCurrentReadAndInputAfterResultLookupBeforeReturning(String revoked) {
        boundCommand();
        compareAllowed = false; // GET requires READ, not COMPARE; it must not touch the human execution port.
        when(results.results(TASK, INPUT, human)).thenAnswer(call -> {
            if ("read".equals(revoked)) readAllowed = false;
            else sourceAllowed = false;
            return emptyResults();
        });
        assertThrows(RuntimeException.class, () -> app.read(TASK, INPUT, CANDIDATE, KEY, human));
        verifyNoInteractions(authority);
        assertEquals(0, claims.get());
        assertEquals(0, executes.get());
        assertEquals("DISPATCHING", db.queryForObject("SELECT status FROM con5_start_command", String.class));
        noClosure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "input"})
    void getRechecksBeforeClosingAnUntrustedReceiptWithoutPrefilledCommittedRows(String revoked) {
        boundCommand();
        when(results.results(TASK, INPUT, human)).thenReturn(new ProductComparisonMatrixReader.Results(TASK, INPUT, SHA,
                "IN_PROGRESS", List.of(), List.of(new ProductComparisonMatrixReader.Candidate(CANDIDATE, "QUEUED", ATTEMPT,
                        null, "ACTIVE", List.of(REPORT)))));
        var untrustedPackage = new ProductComparisonMatrixResults.Package(TASK, INPUT, CANDIDATE, SHA, ATTEMPT, 1, REPORT,
                1, "owned-method", "worker", managed.configurationVersion(), DIGEST, DIGEST, NOW, UNTIL, List.of());
        when(results.report(TASK, INPUT, REPORT, human)).thenAnswer(call -> {
            if ("read".equals(revoked)) readAllowed = false;
            else sourceAllowed = false;
            return new ProductComparisonMatrixResults.Report(untrustedPackage, DIGEST, untrustedReceipt());
        });
        assertThrows(RuntimeException.class, () -> app.read(TASK, INPUT, CANDIDATE, KEY, human));
        assertEquals(0, claims.get());
        assertEquals(0, executes.get());
        noClosure();
    }

    @Test void readOnlyRecoveryAtExpiredDeadlineDoesNotRequireCompareOrSend() {
        String id = boundCommand();
        commands.markReconcile(id, "OWNED_UNKNOWN");
        clock.now = UNTIL.plusSeconds(1);
        compareAllowed = false;
        var receipt = app.read(TASK, INPUT, CANDIDATE, KEY, human);
        assertEquals(id, receipt.commandId());
        assertEquals(ATTEMPT, receipt.attemptId());
        assertEquals("RECONCILE_REQUIRED", receipt.status());
        verifyNoInteractions(authority);
        assertEquals(0, claims.get());
        assertEquals(0, executes.get());
        noClosure();
    }

    @Test void cancelledReadReturnsCoordinatesWithoutReadingInvalidatedInput() {
        String id = boundCommand();
        invalidate("cancelEpoch");
        sourceAllowed = false;
        var receipt = app.read(TASK, INPUT, CANDIDATE, KEY, human);
        assertEquals(id, receipt.commandId());
        assertEquals("CANCELLED", receipt.status());
        verify(inputs, never()).read(anyString(), anyString(), any());
        readAllowed = false;
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> app.read(TASK, INPUT, CANDIDATE, KEY, human));
        assertEquals(0, claims.get());
        assertEquals(0, executes.get());
        noClosure();
    }

    @Test void acceptedOrUnboundCommandCannotAuthorizeHttpAndGuardDoesNotWrite() {
        String id = commands.reserveExact(scope, actor, KEY, DIGEST, managed, UNTIL).command().commandId();
        var before = snapshot();
        assertThrows(IllegalStateException.class, () -> commands.requireLiveHandoff(id));
        assertEquals(before, snapshot());
        commands.dispatchOnce(id);
        before = snapshot();
        assertThrows(IllegalStateException.class, () -> commands.requireLiveHandoff(id));
        assertEquals(before, snapshot());
        insertDelegation();
        commands.bindDelegation(id, DELEGATION);
        before = snapshot();
        assertDoesNotThrow(() -> commands.requireLiveHandoff(id), "Claim needs live delegation but no attempt yet");
        assertEquals(before, snapshot());
        noClosure();
    }

    @Test void shorterAttemptLeaseExpiringInsideClaimBlocksExecuteWhileCommandRemainsLive() {
        claimedUntil = NOW.plusSeconds(30);
        duringClaim = () -> clock.now = claimedUntil.plusMillis(1);
        var receipt = start();
        assertEquals("RECONCILE_REQUIRED", receipt.status());
        assertEquals(ATTEMPT, receipt.attemptId());
        assertTrue(original().expiresAt().isAfter(clock.instant()));
        assertEquals(claimedUntil, original().leaseUntil());
        assertEquals(1, claims.get());
        assertEquals(0, executes.get());
        assertEquals("ACTIVE", db.queryForObject("SELECT status FROM con5_candidate_attempt WHERE attempt_id=?", String.class, ATTEMPT));
        assertEquals("ACTIVE", db.queryForObject("SELECT status FROM con5_candidate_lease WHERE attempt_id=?", String.class, ATTEMPT));
        noClosure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "input"})
    void getRechecksBeforeRecoveringLostAttemptBinding(String revoked) {
        String id = commands.reserveExact(scope, actor, KEY, DIGEST, managed, UNTIL).command().commandId();
        commands.dispatchOnce(id);
        insertDelegation();
        commands.bindDelegation(id, DELEGATION);
        insertAttempt(ATTEMPT, 1, "start:" + id, UNTIL);
        db.update("INSERT INTO con5_candidate_lease VALUES(?,?,?,1,'ACTIVE',?)", INPUT, CANDIDATE, ATTEMPT, ts(UNTIL));
        commands.markReconcile(id, "OWNED_CLAIM_RESPONSE_LOST");
        var reads = new AtomicInteger();
        when(inputs.read(TASK, INPUT, human)).thenAnswer(call -> {
            if (reads.incrementAndGet() == 1) {
                if ("read".equals(revoked)) readAllowed = false;
                else sourceAllowed = false;
                return view;
            }
            if (!sourceAllowed) throw new IllegalStateException("owned source withdrawn before binding");
            return view;
        });
        assertThrows(RuntimeException.class, () -> app.read(TASK, INPUT, CANDIDATE, KEY, human));
        verify(commands, never()).bindAttempt(anyString(), any());
        assertNull(original().attemptId());
        assertEquals("RECONCILE_REQUIRED", original().status());
        assertEquals(0, claims.get());
        assertEquals(0, executes.get());
        noClosure();
    }

}
