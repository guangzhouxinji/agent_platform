package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Mechanical handoff tests with isolated ports, not formal TLS/model or professional acceptance. */
class ProductComparisonStartApplicationTest {
    final String task=UUID.randomUUID().toString(),comparison=UUID.randomUUID().toString(),candidate="a".repeat(64),sha="b".repeat(64),key="human-key";
    final Instant now=Instant.parse("2026-10-07T07:00:00Z");
    final ProductComparisonStartCommands store=mock(ProductComparisonStartCommands.class);
    final ProductComparisonInputApplication inputs=mock(ProductComparisonInputApplication.class);
    final ProductComparisonWorkerDelegations delegations=mock(ProductComparisonWorkerDelegations.class);
    final ProductWorkerDelegations authority=mock(ProductWorkerDelegations.class);
    final ProductComparisonMatrixReader results=mock(ProductComparisonMatrixReader.class);
    final ProductTaskDirectory tasks=mock(ProductTaskDirectory.class);
    final ProductConsultationAccess access=mock(ProductConsultationAccess.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final AtomicInteger sends=new AtomicInteger();
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final Jwt jwt=Jwt.withTokenValue("engineering-only").header("alg","RS256").issuer("https://identity.invalid").subject("human").build();
    ProductComparisonStartCommands.Command command;
    ProductComparisonInputs.View view;
    ProductComparisonStartApplication app;
    @BeforeEach void setup() {
        var envelope=mock(ProductComparisonInputs.Envelope.class);
        view=mock(ProductComparisonInputs.View.class);
        when(view.input()).thenReturn(envelope);when(view.inputSha()).thenReturn(sha);
        when(envelope.expiresAt()).thenReturn(now.plusSeconds(600));when(envelope.candidates()).thenReturn(List.of(new ProductComparisonInputs.Candidate(candidate,0)));
        when(access.permits(eq(jwt),any(),any())).thenReturn(true);
        when(inputs.consume(task,comparison,jwt)).thenReturn(view);when(inputs.read(task,comparison,jwt)).thenReturn(view);
        when(tasks.find(task)).thenReturn(Optional.of(new ProductTaskDirectory.Task(task,"org","https://identity.invalid","human","IN_PROGRESS",2,7,"scope",1,now.plusSeconds(600))));
        when(db.queryForObject("SELECT cancel_epoch FROM con3_task WHERE task_id=?",Long.class,task)).thenReturn(0L);
        command=new ProductComparisonStartCommands.Command(UUID.randomUUID().toString(),new ProductComparisonStartCommands.Scope(task,comparison,candidate,sha,2,0),
                new ProductComparisonStartCommands.Actor("https://identity.invalid","human"),key,"d".repeat(64),new ProductComparisonStartCommands.ManagedBinding("worker","version"),null,null,null,null,"ACCEPTED",null,now,now.plusSeconds(60));
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.empty(),Optional.of(command));
        // The coordinate-only boundary uses the same scripted commands, not a new fixture authority.
        when(store.lookupOriginalKeyMetadata(anyString(),any(),anyString())).thenAnswer(call ->
                store.lookupOriginalKey(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        when(store.reserveExact(any(),any(),anyString(),anyString(),any(),any())).thenReturn(new ProductComparisonStartCommands.Reservation(command,true));
        when(store.dispatchOnce(anyString())).thenReturn(Optional.of(command));
        app=application((path,body,k)->{sends.incrementAndGet();throw new IllegalStateException("UNKNOWN");});
    }
    ProductComparisonStartApplication application(ProductRetrievalMtls.Transport transport){return new ProductComparisonStartApplication(store,inputs,delegations,authority,results,tasks,access,db,transport,command.managed(),Duration.ofSeconds(60),Clock.fixed(now,ZoneOffset.UTC),mapper);}
    @Test void positiveSingleCandidateUsesClaimThenExecuteAndExactCommittedReceipt() {
        String delegation=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString(),report=UUID.randomUUID().toString();
        var intent=new ProductComparisonWorkerDelegations.Receipt(delegation,1,"ACTIVE",comparison,sha,candidate,now.plusSeconds(60));
        when(delegations.issue(anyString(),anyString(),any(),eq(jwt),anyString())).thenReturn(intent);
        var bound=new ProductComparisonStartCommands.Command(command.commandId(),command.scope(),command.actor(),key,command.requestSha(),command.managed(),delegation,null,null,null,"DISPATCHING",null,now,command.expiresAt());
        var active=new ProductComparisonStartCommands.Command(command.commandId(),command.scope(),command.actor(),key,command.requestSha(),command.managed(),delegation,attempt,1L,command.expiresAt(),"DISPATCHING",null,now,command.expiresAt());
        var completed=new ProductComparisonStartCommands.Command(command.commandId(),command.scope(),command.actor(),key,command.requestSha(),command.managed(),delegation,attempt,1L,command.expiresAt(),"COMMITTED",null,now,command.expiresAt());
        when(store.bindDelegation(anyString(),anyString())).thenReturn(bound);
        when(store.bindAttempt(anyString(),any())).thenReturn(active);
        when(store.commitFromExactReceipt(command.commandId(),report,"f".repeat(64))).thenReturn(completed);
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.empty(),Optional.of(completed));
        app=application((url,body,requestKey)->{
            sends.incrementAndGet();assertThat(requestKey).isEqualTo("start:"+command.commandId());
            if(body instanceof ProductComparisonMatrixController.Claim claim){assertThat(claim.inputSha()).isEqualTo(sha);assertThat(url).contains(candidate);return mapper.valueToTree(new ProductComparisonCandidateExecution.Lease(attempt,1,command.expiresAt()));}
            var execute=(ProductComparisonMatrixController.Execute)body;assertThat(execute.candidateId()).isEqualTo(candidate);assertThat(execute.delegationId()).isEqualTo(delegation);
            return mapper.valueToTree(new ProductComparisonMatrixResults.Receipt(task,comparison,candidate,sha,attempt,1,report,"f".repeat(64),1,"COMMITTED"));
        });
        var receipt=app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(2,sha),key,jwt);
        assertThat(receipt.status()).isEqualTo("COMMITTED");assertThat(receipt.attemptId()).isEqualTo(attempt);assertThat(sends.get()).isEqualTo(2);
        verify(store).commitFromExactReceipt(command.commandId(),report,"f".repeat(64));verify(store,never()).markReconcile(anyString(),anyString());
    }
    @Test void insufficientCurrentTaskWindowCannotIssueDelegation() {
        when(tasks.find(task)).thenReturn(Optional.of(new ProductTaskDirectory.Task(task,"org","https://identity.invalid","human","IN_PROGRESS",2,7,"scope",1,now.plusSeconds(30))));
        assertThatThrownBy(()->app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(2,sha),key,jwt)).isInstanceOf(IllegalStateException.class);
        verify(store,never()).reserveExact(any(),any(),anyString(),anyString(),any(),any());verify(delegations,never()).issue(anyString(),anyString(),any(),any(),anyString());assertThat(sends.get()).isZero();
    }    @Test void unknownClaimUsesDurableOriginalCommandAndNeverBlindRedispatches() {
        var intent=new ProductComparisonWorkerDelegations.Receipt(UUID.randomUUID().toString(),1,"ACTIVE",comparison,sha,candidate,now.plusSeconds(60));
        when(delegations.issue(anyString(),anyString(),any(),eq(jwt),anyString())).thenReturn(intent);
        var bound=new ProductComparisonStartCommands.Command(command.commandId(),command.scope(),command.actor(),key,command.requestSha(),command.managed(),intent.delegationId(),null,null,null,"DISPATCHING",null,now,command.expiresAt());
        when(store.bindDelegation(anyString(),anyString())).thenReturn(bound);
        // Read sees the persisted coordinates; claim failed with unknown outcome, no fabricated attempt.
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.empty(),Optional.of(bound));
        when(db.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var receipt=app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(2,sha),key,jwt);
        assertThat(sends.get()).isEqualTo(1);assertThat(receipt.requestId()).isEqualTo(key);
        verify(store).markReconcile(bound.commandId(),"HANDOFF_RESULT_REQUIRES_RECONCILIATION");
        app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(2,sha),key,jwt);
        assertThat(sends.get()).isEqualTo(1);verify(store,times(1)).dispatchOnce(anyString());
    }
    @Test void replayOnlyReadsAndChangedPayloadCannotDispatch() {
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.of(command));
        app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(2,sha),key,jwt);
        verify(store,never()).reserveExact(any(),any(),anyString(),anyString(),any(),any());assertThat(sends.get()).isZero();
        assertThatThrownBy(()->app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(3,sha),key,jwt)).isInstanceOf(IllegalStateException.class);
    }
    @Test void wrongCurrentInputAndInsufficientWindowNeverReserveOrSend() {
        when(inputs.consume(task,comparison,jwt)).thenReturn(view);
        assertThatThrownBy(()->app.start(task,comparison,candidate,new ProductComparisonStartApplication.Start(2,"e".repeat(64)),key,jwt)).isInstanceOf(IllegalStateException.class);
        verify(store,never()).dispatchOnce(anyString());assertThat(sends.get()).isZero();
    }
    @Test void readRequiresCurrentSourceReadAndNeverDispatches() {
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.of(command));
        when(inputs.read(task,comparison,jwt)).thenThrow(new IllegalStateException("source withdrawn"));
        assertThatThrownBy(()->app.read(task,comparison,candidate,key,jwt)).hasMessageContaining("withdrawn");
        verify(store,never()).dispatchOnce(anyString());assertThat(sends.get()).isZero();
    }
    @Test void cancelledReceiptRequiresCurrentReadButDoesNotReadInvalidatedGoalOrOriginals() {
        var cancelled=new ProductComparisonStartCommands.Command(command.commandId(),command.scope(),command.actor(),key,command.requestSha(),command.managed(),null,null,null,null,"CANCELLED",null,now,command.expiresAt());
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.of(cancelled));
        assertThat(app.read(task,comparison,candidate,key,jwt).status()).isEqualTo("CANCELLED");
        verify(inputs,never()).read(anyString(),anyString(),any());
        when(access.permits(eq(jwt),any(),any())).thenReturn(false);
        assertThatThrownBy(()->app.read(task,comparison,candidate,key,jwt)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(sends.get()).isZero();
    }    @Test void reopenedWorkspaceFindsOnlyExactHumanInputOriginalKeyWithoutDispatch() {
        when(db.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of(key));
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.of(command));
        assertThat(app.latest(task,comparison,candidate,sha,jwt).requestId()).isEqualTo(key);
        verify(store,never()).dispatchOnce(anyString());assertThat(sends.get()).isZero();
        when(access.permits(eq(jwt),any(),any())).thenReturn(false);
        assertThatThrownBy(()->app.latest(task,comparison,candidate,sha,jwt)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }    @Test void foreignCandidateAndBrowserServiceFieldsCannotChooseTransport() {
        var json=new ProductComparisonJson(mapper);
        assertThatThrownBy(()->json.request("{\"expectedTaskVersion\":2,\"inputSha\":\""+sha+"\",\"serviceSubject\":\"attacker\"}",ProductComparisonStartApplication.Start.class)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->app.start(task,comparison,"../../../",new ProductComparisonStartApplication.Start(2,sha),key,jwt)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sends.get()).isZero();
    }
    @Test void readRevokedAfterInputLoadCannotReturnOriginalCommandCoordinates() {
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenReturn(Optional.of(command));
        when(inputs.read(task,comparison,jwt)).thenAnswer(invocation->{
            when(access.permits(eq(jwt),any(),any())).thenReturn(false);return view;
        });
        assertThatThrownBy(()->app.read(task,comparison,candidate,key,jwt)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(store,never()).dispatchOnce(anyString());assertThat(sends.get()).isZero();
    }
    @Test void cancellationCoordinatesAlsoRecheckReadAfterOriginalKeyLookupWithoutLoadingGoal() {
        var cancelled=new ProductComparisonStartCommands.Command(command.commandId(),command.scope(),command.actor(),key,
                command.requestSha(),command.managed(),null,null,null,null,"CANCELLED",null,now,command.expiresAt());
        when(store.lookupOriginalKey(anyString(),any(),anyString())).thenAnswer(invocation->{
            when(access.permits(eq(jwt),any(),any())).thenReturn(false);return Optional.of(cancelled);
        });
        assertThatThrownBy(()->app.read(task,comparison,candidate,key,jwt)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(inputs,never()).read(anyString(),anyString(),any());assertThat(sends.get()).isZero();
    }

}




