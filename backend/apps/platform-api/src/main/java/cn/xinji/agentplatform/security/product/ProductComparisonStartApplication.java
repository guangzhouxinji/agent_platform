package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** One explicit human request, one candidate. No scheduler, polling or synthetic service identity. */
final class ProductComparisonStartApplication {
    record Start(long expectedTaskVersion, String inputSha) { }
    record Receipt(String commandId,String taskId,String comparisonId,String candidateId,String inputSha,
                   String requestId,String attemptId,String status,String errorCode,Instant expiresAt) { }
    private final ProductComparisonStartCommands commands;
    private final ProductComparisonInputApplication inputs;
    private final ProductComparisonWorkerDelegations delegations;
    private final ProductWorkerDelegations authority;
    private final ProductComparisonMatrixReader results;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess access;
    private final JdbcTemplate db;
    private final ProductRetrievalMtls.Transport transport;
    private final ProductComparisonStartCommands.ManagedBinding managed;
    private final Duration lease;
    private final Clock clock;
    private final ObjectMapper mapper;
    private ProductComparisonHandoffStore handoffs;
    ProductComparisonStartApplication(ProductComparisonStartCommands commands,ProductComparisonInputApplication inputs,
            ProductComparisonWorkerDelegations delegations,ProductWorkerDelegations authority,ProductComparisonMatrixReader results,
            ProductTaskDirectory tasks,ProductConsultationAccess access,JdbcTemplate db,ProductRetrievalMtls.Transport transport,
            ProductComparisonStartCommands.ManagedBinding managed,Duration lease,Clock clock,ObjectMapper mapper) {
        if(lease==null||lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofSeconds(90))>0)
            throw new IllegalArgumentException("Managed finite comparison lease required");
        this.commands=commands;this.inputs=inputs;this.delegations=delegations;this.authority=authority;this.results=results;
        this.tasks=tasks;this.access=access;this.db=db;this.transport=transport;this.managed=managed;this.lease=lease;this.clock=clock;this.mapper=mapper;
    }
    ProductComparisonStartApplication(ProductComparisonStartCommands commands,ProductComparisonInputApplication inputs,
            ProductComparisonWorkerDelegations delegations,ProductWorkerDelegations authority,ProductComparisonMatrixReader results,
            ProductTaskDirectory tasks,ProductConsultationAccess access,JdbcTemplate db,ProductRetrievalMtls.Transport transport,
            ProductComparisonStartCommands.ManagedBinding managed,Duration lease,Clock clock,ObjectMapper mapper,ProductComparisonHandoffStore handoffs) {
        this(commands,inputs,delegations,authority,results,tasks,access,db,transport,managed,lease,clock,mapper);
        this.handoffs=Objects.requireNonNull(handoffs);
    }
    Receipt start(String task,String comparison,String candidate,Start request,String key,Jwt human) {
        coordinate(task,comparison,candidate);
        if(request==null||request.expectedTaskVersion()<1||request.inputSha()==null||!request.inputSha().matches("[a-f0-9]{64}"))throw invalid();
        authority.requireComparisonActor(task,human);
        var actor=actor(human);
        // A lost POST response is reconciled before new reservation; replay never dispatches.
        var original=commands.lookupOriginalKeyMetadata(task,actor,key);
        if(original.isPresent()) {
            var old=original.get();
            if(!old.scope().comparison().equals(comparison)||!old.scope().candidate().equals(candidate)
                    ||!old.scope().inputSha().equals(request.inputSha())||old.scope().taskVersion()!=request.expectedTaskVersion())throw conflict();
            return read(task,comparison,candidate,key,human);
        }
        var input=inputs.consume(task,comparison,human);
        var current=tasks.find(task).orElseThrow(ProductComparisonStartApplication::conflict);
        if(current.version()!=request.expectedTaskVersion()||!input.inputSha().equals(request.inputSha())
                ||input.input().candidates().stream().noneMatch(c->candidate.equals(c.candidateId())))throw conflict();
        var until=clock.instant().plus(lease).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(!until.isBefore(current.expiresAt())||!until.isBefore(input.input().expiresAt()))throw conflict();
        Long epoch=db.queryForObject("SELECT cancel_epoch FROM con3_task WHERE task_id=?",Long.class,task);
        var scope=new ProductComparisonStartCommands.Scope(task,comparison,candidate,input.inputSha(),current.version(),epoch);
        String digest=new ProductComparisonJson(mapper).sha(Map.of("scope",scope,"actor",actor,"request",request,"key",key));
        var reserved=commands.reserveExact(scope,actor,key,digest,managed,until);
        if(!reserved.newlyReserved())return read(task,comparison,candidate,key,human);
        var dispatched=commands.dispatchOnce(reserved.command().commandId());
        if(dispatched.isEmpty())return read(task,comparison,candidate,key,human);
        var command=dispatched.get();
        try {
            authority.requireComparisonActor(task,human);
            var intent=delegations.issue(task,comparison,new ProductComparisonWorkerDelegations.Issue(managed.serviceSubject(),candidate,input.inputSha(),until),human,"start:"+command.commandId());
            command=commands.bindDelegation(command.commandId(),intent.delegationId());
            requireHandoff(command,input,human);
            if(managed.configurationVersion().startsWith("HANDOFF_ONLY:")) {
                if(handoffs==null)throw conflict();
                var output=transport.post(root(task,comparison,candidate)+"/start-commands/"+command.commandId()+"/handoff-receipts",
                        new ProductComparisonHandoffController.Request(input.inputSha(),intent.delegationId(),command.requestSha()),"start:"+command.commandId());
                var proof=mapper.treeToValue(output,ProductComparisonHandoffStore.Proof.class);
                if(!proof.commandId().equals(command.commandId())||!proof.requestSha().equals(command.requestSha()))throw conflict();
                // Never trust the transport response as proof. GET consumes the atomic persisted closure.
                return read(task,comparison,candidate,key,human);
            }
            var response=transport.post(root(task,comparison,candidate)+"/executions",
                    new ProductComparisonMatrixController.Claim(input.inputSha(),intent.delegationId(),lease.toMillis()),"start:"+command.commandId());
            var acquired=mapper.treeToValue(response,ProductComparisonCandidateExecution.Lease.class);
            command=commands.bindAttempt(command.commandId(),new ProductComparisonStartCommands.AttemptBinding(acquired.attemptId(),acquired.token(),acquired.until()));
            requireHandoff(command,input,human);
            var body=new ProductComparisonMatrixController.Execute(comparison,candidate,input.inputSha(),intent.delegationId(),acquired.token(),acquired.until());
            var output=transport.post("/v2/consultation/tasks/"+task+"/executions/"+acquired.attemptId()+"/patent-comparison-result",body,"start:"+command.commandId());
            var receipt=mapper.treeToValue(output,ProductComparisonMatrixResults.Receipt.class);
            exact(command,receipt);
            authority.requireComparisonActor(task,human);
            if(!input.equals(inputs.consume(task,comparison,human)))throw conflict();
            command=commands.commitFromExactReceipt(command.commandId(),receipt.reportId(),receipt.reportSha());
        } catch(Exception unknown) {
            commands.markReconcile(command.commandId(),"HANDOFF_RESULT_REQUIRES_RECONCILIATION");
        }
        return read(task,comparison,candidate,key,human);
    }
    /** Reopening the workbench discovers only this human's exact original request; never creates a replacement key. */
    Receipt latest(String task,String comparison,String candidate,String inputSha,Jwt human) {
        coordinate(task,comparison,candidate);requireRead(task,human);
        if(inputSha==null||!inputSha.matches("[a-f0-9]{64}"))throw invalid();
        var actor=actor(human);
        var keys=db.queryForList("SELECT request_id FROM con5_start_command WHERE task_id=? AND comparison_id=? AND candidate_id=? AND input_sha=? AND actor_issuer=? AND actor_subject=? ORDER BY created_at DESC,command_id DESC",String.class,
                task,comparison,candidate,inputSha,actor.issuer(),actor.subject());
        if(keys.isEmpty()){var input=inputs.read(task,comparison,human);if(!input.inputSha().equals(inputSha))throw conflict();return null;}
        var result=read(task,comparison,candidate,keys.getFirst(),human);
        if(!result.inputSha().equals(inputSha))throw conflict();return result;
    }    Receipt read(String task,String comparison,String candidate,String key,Jwt human) {
        coordinate(task,comparison,candidate);
        requireRead(task,human);

        var command=commands.lookupOriginalKeyMetadata(task,actor(human),key).orElseThrow(ProductComparisonStartApplication::conflict);
        if(!command.scope().comparison().equals(comparison)||!command.scope().candidate().equals(candidate))throw conflict();
        // Cancellation receipt contains coordinates only; never read expired/invalidated Goal or originals to show it.
        if("CANCELLED".equals(command.status())){requireRead(task,human);return receipt(command);}
        var input=inputs.read(task,comparison,human);
        if(!command.scope().inputSha().equals(input.inputSha()))throw conflict();
        if(!Set.of("CANCELLED","COMMITTED","FAILED").contains(command.status())&&command.delegationId()!=null) {
            // Only original attempt coordinates. No claim/execute retry, even when a claim receipt was lost.
            var attempts=db.query("SELECT attempt_id,fencing_token,lease_until FROM con5_candidate_attempt WHERE task_id=? AND comparison_id=? AND candidate_id=? AND input_sha=? AND delegation_id=? AND worker_subject=? AND request_id=?",
                    (rs,n)->new ProductComparisonStartCommands.AttemptBinding(rs.getString(1),rs.getLong(2),rs.getTimestamp(3).toInstant()),
                    task,comparison,candidate,command.scope().inputSha(),command.delegationId(),command.managed().serviceSubject(),"start:"+command.commandId());
            if(attempts.size()>1)throw conflict();
            if(attempts.size()==1){requireRead(task,human);if(!input.equals(inputs.read(task,comparison,human)))throw conflict();}
            if(attempts.size()==1)command=commands.bindAttempt(command.commandId(),attempts.getFirst());
            if(command.attemptId()!=null) {
                var summary=results.results(task,comparison,human);
                var expected=command;
                for(var value:summary.candidates())if(value.candidateId().equals(candidate)&&Objects.equals(value.attemptId(),expected.attemptId()))
                    for(String reportId:value.reportIds()) {
                        var report=results.report(task,comparison,reportId,human);
                        if(!report.result().attemptId().equals(expected.attemptId()))continue;
                        exact(expected,report.receipt());
                        requireRead(task,human);
                        if(!input.equals(inputs.read(task,comparison,human)))throw conflict();
                        command=commands.commitFromExactReceipt(command.commandId(),reportId,report.reportSha());
                    }
            }
        }
        var reconciled=commands.lookupOriginalKey(task,actor(human),key).orElseThrow(ProductComparisonStartApplication::conflict);
        requireRead(task,human);
        if(!"CANCELLED".equals(reconciled.status())&&!input.equals(inputs.read(task,comparison,human)))throw conflict();
        if(handoffs!=null&&!"CANCELLED".equals(reconciled.status())&&handoffs.read(reconciled).isPresent()) {
            requireRead(task,human);
            if(!input.equals(inputs.read(task,comparison,human)))throw conflict();
            var s=reconciled.scope();
            return new Receipt(reconciled.commandId(),s.task(),s.comparison(),s.candidate(),s.inputSha(),reconciled.requestId(),null,"ADMISSION_REQUIRED",ProductComparisonHandoffStore.ERROR,reconciled.expiresAt());
        }
        return receipt(reconciled);
    }
    private void requireHandoff(ProductComparisonStartCommands.Command command,ProductComparisonInputs.View expected,Jwt human) {
        authority.requireComparisonActor(command.scope().task(),human);
        if(!expected.equals(inputs.consume(command.scope().task(),command.scope().comparison(),human)))throw conflict();
        commands.requireLiveHandoff(command.commandId());
    }
    private void requireRead(String task,Jwt human) {
        var current=tasks.find(task).orElseThrow(ProductComparisonStartApplication::conflict);
        if(!current.expiresAt().isAfter(clock.instant())||!access.permits(human,new ProductConsultationAccess.ResourceFacts(current.organization(),task,current.dataScope(),"TASK",task,current.authorizationVersion()),cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy.Action.READ))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }    private static void exact(ProductComparisonStartCommands.Command c,ProductComparisonMatrixResults.Receipt r) {
        if(r==null||!c.scope().task().equals(r.taskId())||!c.scope().comparison().equals(r.comparisonId())||!c.scope().candidate().equals(r.candidateId())
                ||!c.scope().inputSha().equals(r.inputSha())||!Objects.equals(c.attemptId(),r.attemptId())||c.fencingToken()==null||c.fencingToken()!=r.token()||!"COMMITTED".equals(r.status()))throw conflict();
    }
    private static Receipt receipt(ProductComparisonStartCommands.Command c){var s=c.scope();return new Receipt(c.commandId(),s.task(),s.comparison(),s.candidate(),s.inputSha(),c.requestId(),c.attemptId(),c.status(),c.errorCode(),c.expiresAt());}
    private static ProductComparisonStartCommands.Actor actor(Jwt jwt){if(jwt==null||jwt.getIssuer()==null||jwt.getSubject()==null)throw conflict();return new ProductComparisonStartCommands.Actor(jwt.getIssuer().toString(),jwt.getSubject());}
    private static void coordinate(String task,String comparison,String candidate){if(task==null||comparison==null||candidate==null||!task.matches("[a-f0-9-]{36}")||!comparison.matches("[a-f0-9-]{36}")||!candidate.matches("[a-f0-9]{64}"))throw invalid();}
    private static String root(String task,String comparison,String candidate){return "/v2/consultation/tasks/"+task+"/patent-comparisons/"+comparison+"/candidates/"+candidate;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact comparison start required");}
    private static IllegalStateException conflict(){return new IllegalStateException("Comparison start binding unavailable");}
}


