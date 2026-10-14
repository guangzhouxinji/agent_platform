package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

/** Exact source copies; committed requests settle from target scalars without reopening source text. */
final class ProductPromptCopyApplication {
    private static final String ZERO="0".repeat(64);
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final ProductPromptCreateRequestStore requests;
    private final ProductPromptWorkspaceAuthorization authorization;
    private final ProductPromptCopyInputPermission inputs;
    private final ProductPromptBindingRegistry registry;
    private final ProductPromptCopySourceSnapshot sources;
    private final ProductPromptCreateReconciliation reconciliation;
    private final ProductPromptAuthorityTransactions independent;
    private final Clock clock;

    ProductPromptCopyApplication(ProductPromptStore store,ProductPromptAuthorityStore authority,
            ProductPromptCreateRequestStore requests,ProductPromptWorkspaceAuthorization authorization,
            ProductPromptCopyInputPermission inputs,ProductPromptBindingRegistry registry,
            DataSourceTransactionManager mainManager,DataSourceTransactionManager authorityManager,Clock clock){
        this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);
        this.requests=Objects.requireNonNull(requests);this.authorization=Objects.requireNonNull(authorization);
        this.inputs=Objects.requireNonNull(inputs);this.registry=Objects.requireNonNull(registry);this.clock=Objects.requireNonNull(clock);
        if(!(store.jdbc.db instanceof ProductPromptStatementJdbcTemplate)
                ||!(authority.jdbc.db instanceof ProductPromptStatementJdbcTemplate))throw invalid();
        independent=new ProductPromptAuthorityTransactions(store,authority,mainManager,authorityManager,clock);
        reconciliation=new ProductPromptCreateReconciliation(store,authority,clock);
        sources=new ProductPromptCopySourceSnapshot(store,authority,clock);
    }

    Receipt request(String token,String sourceWorkspaceId,String sourcePromptId,String key,ProductPromptCopyCommand command){
        outside();uuid(sourceWorkspaceId);uuid(sourcePromptId);Objects.requireNonNull(command);String keySha=keySha(key);
        String target=command.targetWorkspaceId(),requestSha=command.requestSha(sourceWorkspaceId,sourcePromptId);
        registry.requireCurrent();
        authorization.executeCopy(token,sourceWorkspaceId,target,lease->{
            registry.requireCurrent();
            var coordinates=new ProductPromptCreateRequest.Key(target,lease.issuer(),lease.subject(),Operation.COPY,keySha);
            var original=independent.execute(lease,()->requests.find(coordinates));
            if(original==null){
                if(store.receipt(target,lease.issuer(),lease.subject(),Operation.COPY,keySha)!=null)throw unavailable();
                String promptId=id();
                var snapshot=sources.load(lease,sourceWorkspaceId,sourcePromptId,command.sourceRevisionId(),command.sourceContentSha(),promptId);
                var permission=inputs.forWrite(lease,command,snapshot);
                Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
                Instant body=min(at.plus(Duration.ofDays(90)),snapshot.source().sourceBodyUntil(),permission.bodyLimit());
                Instant audit=min(at.plus(Duration.ofDays(180)),snapshot.source().sourceAuditUntil(),permission.auditLimit());
                var prepared=prepare(lease,target,snapshot.revision(),requestSha,keySha,promptId,id(),id(),at,body,audit,permission.evidence());
                var pending=new Pending(promptId,target,prepared.after().kind(),0,null,ZERO,prepared.after().scalarSha(),
                        prepared.header().revisionId(),id(),Operation.COPY,keySha,requestSha,min(at.plusSeconds(300),body,audit),body,audit);
                var request=requests.prepared(pending,prepared,permission.evidence(),snapshot.source());
                if(reconciliation.observe(lease.workspace(target),request).state()!=ProductPromptCreateReconciliation.State.ALL_BEFORE)
                    throw unavailable();
                snapshot.requireCurrent();permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                Instant cutoff=min(pending.intentUntil(),permission.bodyLimit(),permission.auditLimit(),body,audit);
                beforeCommit(cutoff);
                independent.execute(lease,cutoff,()->requests.reserve(request));
                snapshot.requireCurrent();permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                current(pending.intentUntil());store.append(prepared);
                snapshot.requireCurrent();permission.requireCurrent();current(pending.intentUntil());
            }else{
                // Compare the original command before consulting today's source head or text.
                if(!original.receipt().requestSha().equals(requestSha))throw new ProductPromptCreateConflict();
                requireSource(original,sourceWorkspaceId);
                if(!original.copySource().sourcePromptId().equals(sourcePromptId)
                        ||!original.copySource().sourceRevisionId().equals(command.sourceRevisionId())
                        ||!original.header().contentSha().equals(command.sourceContentSha()))throw unavailable();
                var trace=inputs.forTrace(lease,original);
                var observed=reconciliation.observe(lease.workspace(target),original);
                if(observed.state()==ProductPromptCreateReconciliation.State.UNKNOWN)throw unavailable();
                if(observed.state()==ProductPromptCreateReconciliation.State.ALL_BEFORE){
                    if(!"PENDING".equals(original.state()))throw unavailable();
                    current(original.pending().intentUntil());current(original.header().bodyUntil());current(original.header().auditUntil());
                    var snapshot=sources.load(lease,sourceWorkspaceId,sourcePromptId,command.sourceRevisionId(),command.sourceContentSha(),original.pending().promptId());
                    if(!snapshot.source().equals(original.copySource()))throw unavailable();
                    var permission=inputs.forWrite(lease,command,snapshot);
                    if(!permission.evidence().equals(original.evidence())||!inputs.sourceIdFor(original.evidence()).equals(command.sourceId())
                            ||original.header().bodyUntil().isAfter(permission.bodyLimit())
                            ||original.header().auditUntil().isAfter(permission.auditLimit()))throw unavailable();
                    var prepared=prepare(lease,target,snapshot.revision(),requestSha,keySha,original.pending().promptId(),
                            original.pending().targetRevisionId(),original.receipt().id(),original.header().submittedAt(),
                            original.header().bodyUntil(),original.header().auditUntil(),original.evidence());
                    requests.requirePrepared(original,prepared);
                    snapshot.requireCurrent();permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                    Instant cutoff=min(original.pending().intentUntil(),permission.bodyLimit(),permission.auditLimit(),
                            original.header().bodyUntil(),original.header().auditUntil());
                    beforeCommit(cutoff);
                    independent.execute(lease,cutoff,()->requests.reserve(original));
                    snapshot.requireCurrent();permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                    current(original.pending().intentUntil());store.append(prepared);
                    snapshot.requireCurrent();permission.requireCurrent();current(original.pending().intentUntil());
                }
                trace.requireCurrent();
            }
            registry.requireCurrent();lease.requireCurrent();return null;
        });
        // Returning from executeCopy establishes the main commit before independent completion.
        return settle(token,sourceWorkspaceId,target,keySha);
    }

    Receipt lookup(String token,String sourceWorkspaceId,String targetWorkspaceId,String key){
        outside();uuid(sourceWorkspaceId);uuid(targetWorkspaceId);return settle(token,sourceWorkspaceId,targetWorkspaceId,keySha(key));
    }

    private Receipt settle(String token,String sourceWorkspaceId,String target,String keySha){
        registry.requireCurrent();
        return authorization.executeCopy(token,sourceWorkspaceId,target,lease->{
            registry.requireCurrent();
            var coordinates=new ProductPromptCreateRequest.Key(target,lease.issuer(),lease.subject(),Operation.COPY,keySha);
            var request=independent.execute(lease,()->requests.find(coordinates));
            if(request==null){
                if(store.receipt(target,lease.issuer(),lease.subject(),Operation.COPY,keySha)!=null)throw unavailable();
                throw denied();
            }
            requireSource(request,sourceWorkspaceId);
            var permission=inputs.forTrace(lease,request);
            var observation=reconciliation.observe(lease.workspace(target),request);
            if(observation.state()!=ProductPromptCreateReconciliation.State.EXACT_AFTER)throw unavailable();
            var proof=observation.exactAfter();
            permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
            Instant cutoff=min(permission.auditLimit(),request.header().auditUntil(),request.pending().controlUntil());
            Receipt result=independent.execute(lease,cutoff,()->requests.complete(proof));
            permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();return result;
        });
    }

    private PreparedWrite prepare(ProductPromptWorkspaceAuthorization.Lease lease,String workspace,PromptRevision source,
            String requestSha,String keySha,String promptId,String revisionId,String receiptId,Instant at,Instant body,Instant audit,
            ProductPromptCreateRequest.Evidence evidence){
        var provenance=new PromptRevision.Provenance(evidence.generationSourceRef(),lease.subject(),at,lease.subject(),at,
                "COPY_DRAFT",List.of(),evidence.inputPermissionRef(),evidence.policyRef());
        var revision=new PromptRevision(new PromptRevision.Reference(promptId,revisionId,1,source.reference().contentSha256()),
                source.kind(),source.name(),source.body(),source.scenarioIds(),source.intentIds(),source.audienceRef(),source.language(),
                source.displayOrder(),source.variables(),provenance);
        return store.prepare(new Write(workspace,revision,0,Operation.COPY,lease.issuer(),lease.subject(),keySha,requestSha,body,audit),receiptId);
    }
    private static void requireSource(ProductPromptCreateRequest request,String sourceWorkspaceId){
        if(request.copySource()==null||!request.copySource().sourceWorkspaceId().equals(sourceWorkspaceId))throw denied();
    }
    private void outside(){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(store.jdbc.source)||TransactionSynchronizationManager.hasResource(authority.jdbc.source))throw unavailable();
    }
    private void current(Instant until){if(!clock.instant().isBefore(until))throw unavailable();}
    private void beforeCommit(Instant cutoff){
        store.jdbc.requireTransaction();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){current(cutoff);}
        });
    }
    private static String keySha(String key){
        if(key==null||key.length()<1||key.length()>64||key.chars().anyMatch(c->c<33||c>126))throw invalid();return ProductComparisonJson.hash(key);
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static Instant min(Instant...times){return java.util.Arrays.stream(times).min(Instant::compareTo).orElseThrow();}
}
