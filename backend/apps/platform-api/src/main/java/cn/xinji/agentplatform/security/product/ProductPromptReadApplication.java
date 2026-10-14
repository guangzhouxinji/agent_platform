package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authenticated exact revision/history reads only. Owns no writes or workspace registration. */
final class ProductPromptReadApplication {
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final ProductPromptWorkspaceAuthorization authorization;
    private final ProductPromptBindingRegistry registry;
    private final TransactionTemplate locate;
    private final Clock clock;
    ProductPromptReadApplication(ProductPromptStore store,ProductPromptAuthorityStore authority,
            ProductPromptWorkspaceAuthorization authorization,ProductPromptBindingRegistry registry,
            DataSourceTransactionManager manager,Clock clock){
        if(manager.getDataSource()!=store.jdbc.source)throw invalid();
        this.store=store;this.authority=authority;this.authorization=authorization;this.registry=registry;this.clock=clock;
        locate=new TransactionTemplate(manager);locate.setReadOnly(true);
        locate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    ProductPromptReadView.Revision revision(String token,String promptId,String revisionId){
        uuid(promptId);uuid(revisionId);String workspace=workspace(promptId);
        return authorization.execute(token,workspace,Action.READ,lease->{
            registry.requireCurrent();owned(promptId,workspace);lease.requireCurrent();
            var selected=store.jdbc.db.query("SELECT body_until,body_state FROM conp_revision WHERE prompt_id=? AND revision_id=?",
                    (rs,n)->new Body(rs.getTimestamp(1).toInstant(),rs.getString(2)),promptId,revisionId);
            if(selected.isEmpty())throw denied();
            if(selected.size()!=1)throw unavailable();
            if(!"PRESENT".equals(selected.getFirst().state())||!clock.instant().isBefore(selected.getFirst().until()))throw denied();
            var value=store.readRevision(promptId,revisionId,authority);
            var header=store.header(promptId,revisionId);
            registry.requireCurrent();lease.requireCurrent();
            if(!clock.instant().isBefore(header.bodyUntil()))throw denied();
            return ProductPromptReadView.Revision.from(workspace,value,header);
        });
    }
    ProductPromptReadView.History history(String token,String promptId,int page,int size){
        uuid(promptId);if(page<1||!List.of(10,20,50).contains(size))throw invalid();
        String workspace=workspace(promptId);
        return authorization.execute(token,workspace,Action.READ,lease->{
            registry.requireCurrent();owned(promptId,workspace);lease.requireCurrent();
            Page result=store.history(promptId,page,size,authority);
            registry.requireCurrent();lease.requireCurrent();
            Instant now=clock.instant();if(result.revisions().stream().anyMatch(row->!now.isBefore(row.bodyUntil())))throw denied();
            return new ProductPromptReadView.History(promptId,page,size,result.total(),result.revisions().stream().map(ProductPromptReadView.Summary::from).toList());
        });
    }
    private String workspace(String promptId){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(store.jdbc.source)||TransactionSynchronizationManager.hasResource(authority.jdbc.source))throw unavailable();
        registry.requireCurrent();
        return locate.execute(status->{
            store.jdbc.requireTransaction();
            var rows=store.jdbc.db.query("SELECT workspace_id FROM conp_prompt WHERE prompt_id=?",(rs,n)->rs.getString(1),promptId);
            if(rows.isEmpty())throw denied();if(rows.size()!=1)throw unavailable();return rows.getFirst();
        });
    }
    private void owned(String promptId,String workspace){
        Head head=store.head(promptId,true);
        if(head==null||!head.workspaceId().equals(workspace))throw denied();
        authority.requireReady(store.workspace(workspace,false),head);
    }
    private record Body(Instant until,String state) { }
}
