package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.denied;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.security.product.ProductPromptCreateRequest.CopySource;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorization.Lease;
import java.time.Clock;
import java.util.Objects;
import java.util.TreeSet;

/** Live exact source for a new write; never needed to settle an already committed COPY. */
final class ProductPromptCopySourceSnapshot {
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final Clock clock;
    ProductPromptCopySourceSnapshot(ProductPromptStore store,ProductPromptAuthorityStore authority,Clock clock){
        this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
        store.jdbc.requireSeparate(authority.jdbc);
    }
    Snapshot load(Lease lease,String sourceWorkspaceId,String sourcePromptId,String sourceRevisionId,String sourceContentSha,String targetPromptId){
        Objects.requireNonNull(lease);uuid(sourceWorkspaceId);uuid(sourcePromptId);uuid(sourceRevisionId);sha(sourceContentSha);uuid(targetPromptId);
        lease.requireCurrent();lease.requireCopySource(sourceWorkspaceId);
        if(sourcePromptId.equals(targetPromptId))throw invalid();
        Workspace workspace=lease.workspace(sourceWorkspaceId);
        var ids=new TreeSet<String>();ids.add(sourcePromptId);ids.add(targetPromptId);
        Head source=null;
        for(String id:ids){
            Head selected=store.head(id,true);
            if(id.equals(sourcePromptId))source=selected;
            else if(selected!=null)throw unavailable();
        }
        if(source==null||!source.workspaceId().equals(sourceWorkspaceId))throw denied();
        Header header=store.header(sourcePromptId,sourceRevisionId);
        if(!"PRESENT".equals(header.bodyState())||!clock.instant().isBefore(header.bodyUntil()))throw denied();
        if(!sourceContentSha.equals(header.contentSha()))throw denied();
        PromptRevision revision=store.readRevision(sourcePromptId,sourceRevisionId,authority);
        var result=new Snapshot(lease,workspace,source,header,revision);
        result.requireCurrent();return result;
    }
    final class Snapshot {
        private final Lease lease;
        private final Workspace workspace;
        private final Head head;
        private final Header header;
        private final PromptRevision revision;
        private final CopySource source;
        private Snapshot(Lease lease,Workspace workspace,Head head,Header header,PromptRevision revision){
            this.lease=lease;this.workspace=workspace;this.head=head;this.header=header;this.revision=revision;
            source=new CopySource(workspace.id(),head.promptId(),header.revisionId(),header.number(),header.recordSha(),header.bodyUntil(),header.auditUntil());
        }
        CopySource source(){bound();return source;}
        PromptRevision revision(){requireCurrent();return revision;}
        void requireCurrent(){
            bound();lease.requireCurrent();
            if(!clock.instant().isBefore(header.bodyUntil())||!clock.instant().isBefore(header.auditUntil()))throw denied();
            if(!workspace.equals(store.workspace(workspace.id(),false))||!head.equals(store.head(head.promptId(),false))
                    ||!header.equals(store.header(head.promptId(),header.revisionId())))throw unavailable();
            store.requireClosure(head);authority.requireReady(workspace,head,header);
            lease.requireCurrent();
            if(!clock.instant().isBefore(header.bodyUntil())||!clock.instant().isBefore(header.auditUntil()))throw denied();
        }
        private void bound(){
            lease.requireCopySource(workspace.id());
            if(!workspace.equals(lease.workspace(workspace.id())))throw unavailable();
            store.jdbc.requireTransaction();
        }
    }
}
