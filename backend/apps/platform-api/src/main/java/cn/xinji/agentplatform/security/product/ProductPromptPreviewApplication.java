package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptFailure;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Request-memory TEST output; bytes and diagnostics are finalized under one current dual-right lease. */
final class ProductPromptPreviewApplication {
    private static final Set<String> DIAGNOSTICS=Set.of("MISSING_VARIABLE","UNDEFINED_VARIABLE",
            "VARIABLE_TYPE_MISMATCH","VARIABLE_EMPTY_NOT_ALLOWED");
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final ProductPromptWorkspaceAuthorization authorization;
    private final ProductPromptPreviewPermission permissions;
    private final ProductPromptBindingRegistry registry;
    private final Clock clock;
    private final TemplateRenderer renderer=new TemplateRenderer();

    ProductPromptPreviewApplication(ProductPromptStore store,ProductPromptAuthorityStore authority,
            ProductPromptWorkspaceAuthorization authorization,ProductPromptPreviewPermission permissions,
            ProductPromptBindingRegistry registry,Clock clock){
        this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);
        this.authorization=Objects.requireNonNull(authorization);this.permissions=Objects.requireNonNull(permissions);
        this.registry=Objects.requireNonNull(registry);this.clock=Objects.requireNonNull(clock);
        if(!(store.jdbc.db instanceof ProductPromptStatementJdbcTemplate)
                ||!(authority.jdbc.db instanceof ProductPromptStatementJdbcTemplate))throw invalid();
        store.jdbc.requireSeparate(authority.jdbc);
    }
    Response preview(String token,String workspaceId,String promptId,String revisionId,ProductPromptPreviewCommand command){
        uuid(workspaceId);uuid(promptId);uuid(revisionId);Objects.requireNonNull(command);
        return authorization.execute(token,workspaceId,Action.PREVIEW,lease->{
            registry.requireCurrent();lease.requireCurrent();Workspace workspace=lease.workspace(workspaceId);
            Head head=store.head(promptId,true);
            if(head==null||!head.workspaceId().equals(workspaceId))throw denied();
            var selected=store.jdbc.db.query("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                    +"FROM conp_revision WHERE prompt_id=? AND revision_id=?",ProductPromptStore.HEADER,promptId,revisionId);
            if(selected.isEmpty())throw denied();if(selected.size()!=1)throw unavailable();
            Header header=selected.getFirst();Instant accessUntil=min(header.bodyUntil(),header.auditUntil(),lease.cutoff());
            access(accessUntil);
            if(!"PRESENT".equals(header.bodyState())||!header.contentSha().equals(command.contentSha()))throw denied();
            source(workspace,head,header,accessUntil);
            var revision=store.readRevision(promptId,revisionId,authority);
            if(revision.variables().size()>64||revision.variables().stream().anyMatch(v->v.name().length()>128))
                throw new ProductPromptPreviewLimit();
            var permission=permissions.admit(lease,workspaceId,header,revision,command.sourceId());
            Instant policyUntil=permission.expiresAt(),expiresAt=min(accessUntil,policyUntil);
            requireCurrent(accessUntil,policyUntil);beforeCommit(accessUntil,policyUntil);
            int status=200;byte[] body;
            try{
                var result=renderer.render(revision,command.supplied(revision,permission.evidenceIds()),permission.policy(),
                        TemplateRenderer.Mode.TEST,clock.instant(),1024);
                body=ProductPromptPreviewOutput.success(workspaceId,revision,header,permission.schemaSha(),permission.policySha(),expiresAt,result);
            }catch(PromptFailure invalidValues){
                if(invalidValues.issues().stream().anyMatch(issue->"PREVIEW_RESOURCE_LIMIT".equals(issue.code())))
                    throw new ProductPromptPreviewLimit();
                if(invalidValues.issues().stream().anyMatch(issue->!DIAGNOSTICS.contains(issue.code())))throw unavailable();
                status=400;body=ProductPromptPreviewOutput.diagnostics(invalidValues.issues(),expiresAt);
            }
            source(workspace,head,header,accessUntil);permission.requireCurrent();registry.requireCurrent();lease.requireCurrent();
            requireCurrent(accessUntil,policyUntil);
            return new Response(status,body,accessUntil,policyUntil);
        });
    }
    void requireCurrent(Response response){requireCurrent(response.accessUntil(),response.policyUntil());}
    record Response(int status,byte[] body,Instant accessUntil,Instant policyUntil){
        Response{
            if(status!=200&&status!=400||body==null||body.length==0||body.length>1024*1024)throw invalid();
            Objects.requireNonNull(accessUntil);Objects.requireNonNull(policyUntil);body=body.clone();
        }
        @Override public byte[] body(){return body.clone();}
    }
    private void source(Workspace workspace,Head head,Header header,Instant accessUntil){
        access(accessUntil);
        try{
            if(!head.equals(store.head(head.promptId(),false))||!header.equals(store.header(head.promptId(),header.revisionId())))throw unavailable();
            store.requireClosure(head);authority.requireReady(workspace,head,header);
        }catch(RuntimeException failure){access(accessUntil);throw failure;}
        access(accessUntil);
    }
    private void beforeCommit(Instant accessUntil,Instant policyUntil){
        store.jdbc.requireTransaction();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){requireCurrent(accessUntil,policyUntil);}
        });
    }
    private void requireCurrent(Instant accessUntil,Instant policyUntil){
        Instant now=clock.instant();if(!now.isBefore(accessUntil))throw denied();if(!now.isBefore(policyUntil))throw unavailable();
    }
    private void access(Instant accessUntil){if(!clock.instant().isBefore(accessUntil))throw denied();}
    private static Instant min(Instant...times){return java.util.Arrays.stream(times).min(Instant::compareTo).orElseThrow();}
}
