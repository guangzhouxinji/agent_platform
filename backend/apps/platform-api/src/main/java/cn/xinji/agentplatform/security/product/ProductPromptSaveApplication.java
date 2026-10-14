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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Full new draft revisions under SAVE only; recovery never reads an old revision body. */
final class ProductPromptSaveApplication {
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final ProductPromptCreateRequestStore requests;
    private final ProductPromptWorkspaceAuthorization authorization;
    private final ProductPromptInputPermission inputs;
    private final ProductPromptBindingRegistry registry;
    private final ProductPromptCreateReconciliation reconciliation;
    private final ProductPromptAuthorityTransactions independent;
    private final ProductPromptCreationOrigin origins;
    private final TransactionTemplate locate;
    private final Clock clock;

    ProductPromptSaveApplication(ProductPromptStore store,ProductPromptAuthorityStore authority,
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
        origins=new ProductPromptCreationOrigin(store,authority,clock);
        locate=new TransactionTemplate(mainManager);locate.setReadOnly(true);locate.setTimeout(5);
        locate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    Receipt request(String token,String promptId,String key,ProductPromptSaveCommand command){
        return requestWithinExpectedWorkspace(token,null,promptId,key,command);
    }

    Receipt request(String token,String expectedWorkspaceId,String promptId,String key,ProductPromptSaveCommand command){
        uuid(expectedWorkspaceId);
        return requestWithinExpectedWorkspace(token,expectedWorkspaceId,promptId,key,command);
    }

    private Receipt requestWithinExpectedWorkspace(String token,String expectedWorkspaceId,String promptId,String key,ProductPromptSaveCommand command){
        outside();uuid(promptId);Objects.requireNonNull(command);String keySha=keySha(key);
        String workspaceId=workspace(promptId);
        if(expectedWorkspaceId!=null&&!workspaceId.equals(expectedWorkspaceId))throw denied();
        authorization.execute(token,workspaceId,Action.SAVE,lease->{
            registry.requireCurrent();
            var coordinates=new ProductPromptCreateRequest.Key(workspaceId,lease.issuer(),lease.subject(),Operation.SAVE,keySha);
            var original=independent.execute(lease,()->requests.find(coordinates));
            if(original==null){
                if(store.receipt(workspaceId,lease.issuer(),lease.subject(),Operation.SAVE,keySha)!=null)throw unavailable();
                Head before=owned(promptId,workspaceId);
                store.requireClosure(before);authority.requireReady(lease.workspace(workspaceId),before);
                requireBase(before,command);
                current(before.containerUntil());
                var origin=origins.requireCurrent(lease,before,null);
                var permission=inputs.forSaveWrite(lease,workspaceId,command);
                Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
                Instant body=min(at.plus(Duration.ofDays(90)),permission.bodyLimit());
                Instant audit=min(at.plus(Duration.ofDays(180)),permission.auditLimit());
                var prepared=prepare(lease,workspaceId,promptId,command,keySha,id(),id(),at,body,audit,permission.evidence(),origin);
                var p=new Pending(promptId,workspaceId,command.kind(),command.expectedGeneration(),command.expectedRevisionId(),before.scalarSha(),
                        prepared.after().scalarSha(),prepared.header().revisionId(),id(),Operation.SAVE,keySha,
                        command.requestSha(workspaceId,promptId),min(at.plusSeconds(300),body,audit),
                        prepared.after().containerUntil(),prepared.after().controlUntil());
                var request=requests.prepared(p,prepared,permission.evidence());
                if(reconciliation.observe(lease.workspace(workspaceId),request).state()!=ProductPromptCreateReconciliation.State.ALL_BEFORE)
                    throw unavailable();
                permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                Instant cutoff=min(before.containerUntil(),p.intentUntil(),permission.bodyLimit(),permission.auditLimit());
                beforeCommit(cutoff);
                independent.execute(lease,cutoff,()->requests.reserve(request));
                permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                current(cutoff);store.append(prepared);permission.requireCurrent();current(cutoff);
            }else{
                // Match the complete original request before looking at today's head/CAS.
                if(!original.receipt().requestSha().equals(command.requestSha(workspaceId,promptId)))throw new ProductPromptCreateConflict();
                var trace=inputs.forTrace(lease,workspaceId,original);
                var observed=reconciliation.observe(lease.workspace(workspaceId),original);
                if(observed.state()==ProductPromptCreateReconciliation.State.UNKNOWN)throw unavailable();
                if(observed.state()==ProductPromptCreateReconciliation.State.ALL_BEFORE){
                    if(!"PENDING".equals(original.state()))throw unavailable();
                    current(original.pending().intentUntil());current(original.header().bodyUntil());current(original.header().auditUntil());
                    Head before=owned(promptId,workspaceId);
                    requireBase(before,command);current(before.containerUntil());
                    var origin=origins.requireCurrent(lease,before,original.pending());
                    var permission=inputs.forSaveWrite(lease,workspaceId,command);
                    if(!permission.evidence().equals(original.evidence())||!inputs.sourceIdFor(original.evidence()).equals(command.sourceId())
                            ||original.header().bodyUntil().isAfter(permission.bodyLimit())
                            ||original.header().auditUntil().isAfter(permission.auditLimit()))throw unavailable();
                    var prepared=prepare(lease,workspaceId,promptId,command,keySha,original.pending().targetRevisionId(),
                            original.receipt().id(),original.header().submittedAt(),original.header().bodyUntil(),original.header().auditUntil(),
                            original.evidence(),origin);
                    requests.requirePrepared(original,prepared);
                    permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                    Instant cutoff=min(before.containerUntil(),original.pending().intentUntil(),permission.bodyLimit(),permission.auditLimit());
                    beforeCommit(cutoff);
                    independent.execute(lease,cutoff,()->requests.reserve(original));
                    permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
                    current(cutoff);store.append(prepared);permission.requireCurrent();current(cutoff);
                }
                trace.requireCurrent();
            }
            registry.requireCurrent();lease.requireCurrent();return null;
        });
        // The main append must have actually committed before its proof can settle authority.
        return settle(token,workspaceId,keySha);
    }

    Receipt lookup(String token,String workspaceId,String key){
        outside();uuid(workspaceId);return settle(token,workspaceId,keySha(key));
    }

    private Receipt settle(String token,String workspaceId,String keySha){
        registry.requireCurrent();
        return authorization.execute(token,workspaceId,Action.SAVE,lease->{
            registry.requireCurrent();
            var coordinates=new ProductPromptCreateRequest.Key(workspaceId,lease.issuer(),lease.subject(),Operation.SAVE,keySha);
            var request=independent.execute(lease,()->requests.find(coordinates));
            if(request==null){
                if(store.receipt(workspaceId,lease.issuer(),lease.subject(),Operation.SAVE,keySha)!=null)throw unavailable();
                throw denied();
            }
            var permission=inputs.forTrace(lease,workspaceId,request);
            var observation=reconciliation.observe(lease.workspace(workspaceId),request);
            if(observation.state()!=ProductPromptCreateReconciliation.State.EXACT_AFTER)throw unavailable();
            var proof=observation.exactAfter();
            permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
            Receipt result=independent.execute(lease,min(permission.auditLimit(),request.header().auditUntil(),request.pending().controlUntil()),
                    ()->requests.complete(proof));
            permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();return result;
        });
    }

    private PreparedWrite prepare(ProductPromptWorkspaceAuthorization.Lease lease,String workspace,String promptId,ProductPromptSaveCommand command,
            String keySha,String revisionId,String receiptId,Instant at,Instant body,Instant audit,
            ProductPromptCreateRequest.Evidence evidence,ProductPromptCreationOrigin.Origin origin){
        var provenance=new PromptRevision.Provenance(evidence.generationSourceRef(),origin.createdBy(),origin.createdAt(),lease.subject(),at,
                "SAVE_DRAFT",List.of(),evidence.inputPermissionRef(),evidence.policyRef());
        var write=new Write(workspace,command.revision(promptId,revisionId,provenance),command.expectedGeneration(),Operation.SAVE,
                lease.issuer(),lease.subject(),keySha,command.requestSha(workspace,promptId),body,audit);
        return store.prepare(write,receiptId);
    }
    private String workspace(String promptId){
        registry.requireCurrent();
        return locate.execute(status->{
            store.jdbc.requireTransaction();
            var rows=store.jdbc.db.query("SELECT workspace_id FROM conp_prompt WHERE prompt_id=?",(rs,n)->rs.getString(1),promptId);
            if(rows.isEmpty())throw denied();if(rows.size()!=1)throw unavailable();return rows.getFirst();
        });
    }
    private Head owned(String promptId,String workspaceId){
        Head head=store.head(promptId,true);
        if(head==null||!head.workspaceId().equals(workspaceId))throw denied();return head;
    }
    private static void requireBase(Head before,ProductPromptSaveCommand command){
        if(before.generation()!=command.expectedGeneration()||!before.revisionId().equals(command.expectedRevisionId())
                ||before.kind()!=command.kind())throw new ProductPromptSaveBaseConflict();
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
