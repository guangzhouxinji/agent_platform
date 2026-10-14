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

/** Authenticated CREATE and original-key recovery. No HTTP, registration or cleanup side effects. */
final class ProductPromptCreateApplication {
    private static final String ZERO="0".repeat(64);
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final ProductPromptCreateRequestStore requests;
    private final ProductPromptWorkspaceAuthorization authorization;
    private final ProductPromptInputPermission inputs;
    private final ProductPromptBindingRegistry registry;
    private final ProductPromptCreateReconciliation reconciliation;
    private final ProductPromptAuthorityTransactions independent;
    private final Clock clock;

    ProductPromptCreateApplication(ProductPromptStore store,ProductPromptAuthorityStore authority,
            ProductPromptCreateRequestStore requests,ProductPromptWorkspaceAuthorization authorization,
            ProductPromptInputPermission inputs,ProductPromptBindingRegistry registry,
            DataSourceTransactionManager mainManager,DataSourceTransactionManager authorityManager,Clock clock){
        this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);
        this.requests=Objects.requireNonNull(requests);this.authorization=Objects.requireNonNull(authorization);
        this.inputs=Objects.requireNonNull(inputs);this.registry=Objects.requireNonNull(registry);this.clock=Objects.requireNonNull(clock);
        if(!(store.jdbc.db instanceof ProductPromptStatementJdbcTemplate)
                ||!(authority.jdbc.db instanceof ProductPromptStatementJdbcTemplate))throw invalid();
        independent=new ProductPromptAuthorityTransactions(store,authority,mainManager,authorityManager,clock);
        reconciliation=new ProductPromptCreateReconciliation(store,authority,clock);
    }

    Receipt request(String token,String workspaceId,String key,ProductPromptCreateCommand command){
        outside();uuid(workspaceId);Objects.requireNonNull(command);String keySha=keySha(key);
        registry.requireCurrent();
        authorization.execute(token,workspaceId,Action.CREATE,lease->{
            registry.requireCurrent();
            var coordinates=new ProductPromptCreateRequest.Key(workspaceId,lease.issuer(),lease.subject(),keySha);
            var original=independent.execute(lease,()->requests.find(coordinates));
            if(original==null){
                if(store.receipt(workspaceId,lease.issuer(),lease.subject(),Operation.CREATE,keySha)!=null)throw unavailable();
                var permission=inputs.forWrite(lease,workspaceId,command);
                Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
                Instant body=min(at.plus(Duration.ofDays(90)),permission.bodyLimit());
                Instant audit=min(at.plus(Duration.ofDays(180)),permission.auditLimit());
                var evidence=permission.evidence();
                var prepared=prepare(lease,workspaceId,command,keySha,id(),id(),id(),at,body,audit,evidence);
                var p=new Pending(prepared.after().promptId(),workspaceId,command.kind(),0,null,ZERO,
                        prepared.after().scalarSha(),prepared.header().revisionId(),id(),Operation.CREATE,keySha,
                        command.requestSha(workspaceId),min(at.plusSeconds(300),body,audit),body,audit);
                var request=requests.prepared(p,prepared,evidence);
                if(reconciliation.observe(lease.workspace(workspaceId),request).state()!=ProductPromptCreateReconciliation.State.ALL_BEFORE)
                    throw unavailable();
                permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                independent.execute(lease,min(p.intentUntil(),permission.bodyLimit(),permission.auditLimit()),()->requests.reserve(request));
                permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                if(!clock.instant().isBefore(p.intentUntil()))throw unavailable();
                store.append(prepared);
                permission.requireCurrent();
                if(!clock.instant().isBefore(p.intentUntil()))throw unavailable();
            }else{
                if(!original.receipt().requestSha().equals(command.requestSha(workspaceId)))throw new ProductPromptCreateConflict();
                var trace=inputs.forTrace(lease,workspaceId,original);
                var observed=reconciliation.observe(lease.workspace(workspaceId),original);
                if(observed.state()==ProductPromptCreateReconciliation.State.UNKNOWN)throw unavailable();
                if(observed.state()==ProductPromptCreateReconciliation.State.ALL_BEFORE){
                    if(!"PENDING".equals(original.state())||!clock.instant().isBefore(original.pending().intentUntil())
                            ||!clock.instant().isBefore(original.header().bodyUntil())||!clock.instant().isBefore(original.header().auditUntil()))
                        throw unavailable();
                    var permission=inputs.forWrite(lease,workspaceId,command);
                    if(!permission.evidence().equals(original.evidence())||!inputs.sourceIdFor(original.evidence()).equals(command.sourceId())
                            ||original.header().bodyUntil().isAfter(permission.bodyLimit())
                            ||original.header().auditUntil().isAfter(permission.auditLimit()))throw unavailable();
                    var prepared=prepare(lease,workspaceId,command,keySha,original.pending().promptId(),original.pending().targetRevisionId(),
                            original.receipt().id(),original.header().submittedAt(),original.header().bodyUntil(),original.header().auditUntil(),original.evidence());
                    requests.requirePrepared(original,prepared);
                    permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                    independent.execute(lease,min(original.pending().intentUntil(),permission.bodyLimit(),permission.auditLimit()),()->requests.reserve(original));
                    permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                    if(!clock.instant().isBefore(original.pending().intentUntil()))throw unavailable();
                    store.append(prepared);permission.requireCurrent();
                    if(!clock.instant().isBefore(original.pending().intentUntil()))throw unavailable();
                }
                trace.requireCurrent();
            }
            registry.requireCurrent();lease.requireCurrent();return null;
        });
        // The first execute has returned only after its main transaction settled. Never
        // complete authority while a first-phase append is still merely uncommitted.
        return settle(token,workspaceId,keySha);
    }

    Receipt lookup(String token,String workspaceId,String key){
        outside();uuid(workspaceId);return settle(token,workspaceId,keySha(key));
    }

    private Receipt settle(String token,String workspaceId,String keySha){
        registry.requireCurrent();
        return authorization.execute(token,workspaceId,Action.CREATE,lease->{
            registry.requireCurrent();
            var coordinates=new ProductPromptCreateRequest.Key(workspaceId,lease.issuer(),lease.subject(),keySha);
            var request=independent.execute(lease,()->requests.find(coordinates));
            if(request==null){
                if(store.receipt(workspaceId,lease.issuer(),lease.subject(),Operation.CREATE,keySha)!=null)throw unavailable();
                throw denied();
            }
            var permission=inputs.forTrace(lease,workspaceId,request);
            var observation=reconciliation.observe(lease.workspace(workspaceId),request);
            if(observation.state()!=ProductPromptCreateReconciliation.State.EXACT_AFTER)throw unavailable();
            var proof=observation.exactAfter();
            permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
            Receipt result=independent.execute(lease,min(permission.auditLimit(),request.header().auditUntil(),request.pending().controlUntil()),()->requests.complete(proof));
            permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();return result;
        });
    }

    private PreparedWrite prepare(ProductPromptWorkspaceAuthorization.Lease lease,String workspace,ProductPromptCreateCommand command,
            String keySha,String promptId,String revisionId,String receiptId,Instant at,Instant body,Instant audit,
            ProductPromptCreateRequest.Evidence evidence){
        var provenance=new PromptRevision.Provenance(evidence.generationSourceRef(),lease.subject(),at,lease.subject(),at,
                "CREATE_DRAFT",List.of(),evidence.inputPermissionRef(),evidence.policyRef());
        var write=new Write(workspace,command.revision(promptId,revisionId,provenance),0,Operation.CREATE,
                lease.issuer(),lease.subject(),keySha,command.requestSha(workspace),body,audit);
        return store.prepare(write,receiptId);
    }
    private void outside(){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(store.jdbc.source)||TransactionSynchronizationManager.hasResource(authority.jdbc.source))
            throw unavailable();
    }
    private static String keySha(String key){
        if(key==null||key.length()<1||key.length()>64||key.chars().anyMatch(c->c<33||c>126))throw invalid();
        return ProductComparisonJson.hash(key);
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static Instant min(Instant...times){return java.util.Arrays.stream(times).min(Instant::compareTo).orElseThrow();}
}
