package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Receipt;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import java.util.Objects;

/** Authority-only cleanup gate and witness transitions; never queries the main database. */
final class ProductPromptBodyClearAuthority {
    private static final String EMPTY="intent_id IS NULL AND operation IS NULL AND key_sha IS NULL AND request_sha IS NULL "
            +"AND before_sha IS NULL AND expected_after_sha IS NULL AND target_generation IS NULL AND target_revision_id IS NULL AND intent_until IS NULL";
    private static final String HEAD="prompt_id=? AND workspace_id=? AND kind=? AND generation=? AND head_revision_id=? "
            +"AND scalar_sha=? AND container_until=? AND control_until=?";
    private final ProductPromptBodyClearStore store;
    ProductPromptBodyClearAuthority(ProductPromptBodyClearStore store){this.store=store;}

    Request find(Lease lease,ProductPromptBodyClearRecords.Key key){
        store.requireAuthorityTransaction(lease);store.bound(lease,key);
        Request value=store.rows.authorityRequest(key.promptId(),key.revisionId(),true);
        Control control=store.authority.control(key.promptId());
        if(value==null){
            if(control!=null&&("CLEANUP_PENDING".equals(control.state())||control.cleanupRequestId()!=null))throw unavailable();
            Long cleared=store.authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_revision_witness "
                    +"WHERE prompt_id=? AND revision_id=? AND body_state<>'PRESENT'",Long.class,key.promptId(),key.revisionId());
            if(cleared!=0)throw unavailable();return null;
        }
        store.requireApproved(lease,value);
        store.authority.requireWorkspace(value.workspace());
        if("PENDING".equals(value.state())){requireControl(value.before(),"CLEANUP_PENDING",value.requestId());requireWitness(value,"PRESENT");}
        else {requireReadyContext(control,value);requireWitness(value,"CLEARED");}
        if(!value.key().equals(key))throw new ProductPromptBodyClearConflict();
        return value;
    }
    Request reserve(Lease lease,Request request){
        store.requireAuthorityTransaction(lease);store.requireApproved(lease,request);store.current(store.mutationUntil(lease,request));
        if(!"PENDING".equals(request.state()))throw invalid();
        store.authority.requireWorkspace(request.workspace());
        if(store.rows.authorityRequest(request.before().promptId(),request.target().revisionId(),true)!=null){
            find(lease,request.key());
            throw unavailable();
        }
        requireControl(request.before(),"READY",null);requireWitness(request,"PRESENT");
        store.rows.insert(store.authority.jdbc.db,"idp_prompt_cleanup_request",store.codec.requestColumns(request));
        Head before=request.before();
        if(store.authority.jdbc.db.update("UPDATE idp_prompt_control SET state='CLEANUP_PENDING',cleanup_request_id=? WHERE "+HEAD
                        +" AND state='READY' AND cleanup_request_id IS NULL AND "+EMPTY,
                request.requestId(),before.promptId(),before.workspaceId(),before.kind().name(),before.generation(),before.revisionId(),
                before.scalarSha(),ts(before.containerUntil()),ts(before.controlUntil()))!=1)throw new ProductPromptBodyClearConflict();
        if(!request.equals(find(lease,request.key())))throw unavailable();
        store.current(store.mutationUntil(lease,request));return request;
    }
    Receipt complete(Lease lease,Request request,ProductPromptBodyClearReconciliation.ExactAfter proof){
        store.requireAuthorityTransaction(lease);store.requireApproved(lease,request);
        var observed=Objects.requireNonNull(proof).consume(store,lease,request);
        Request stored=store.rows.authorityRequest(request.before().promptId(),request.target().revisionId(),true);
        if(!request.equals(stored))throw unavailable();
        store.authority.requireWorkspace(request.workspace());
        if("READY".equals(stored.state())){
            requireControl(observed.head(),"READY",null);requireWitness(stored,"CLEARED");
            for(var candidate:observed.candidates()){
                Request independent=store.rows.authorityRequest(candidate.before().promptId(),candidate.target().revisionId(),true);
                if(independent==null||!"READY".equals(independent.state())||!candidate.manifest().equals(independent.manifest()))throw unavailable();
                requireWitness(independent,"CLEARED");
            }
            return stored.receipt();
        }
        if(!observed.head().equals(request.afterHead())||!observed.candidates().isEmpty())throw unavailable();
        requireControl(request.before(),"CLEANUP_PENDING",request.requestId());requireWitness(request,"PRESENT");
        Header target=request.target();
        if(store.authority.jdbc.db.update("UPDATE idp_revision_witness SET body_state='CLEARED' WHERE revision_id=? AND prompt_id=? "
                        +"AND revision_number=? AND record_sha=? AND content_sha=? AND submitted_at=? AND body_until=? AND audit_until=? AND body_state='PRESENT'",
                target.revisionId(),target.promptId(),target.number(),target.recordSha(),target.contentSha(),ts(target.submittedAt()),ts(target.bodyUntil()),ts(target.auditUntil()))!=1)throw unavailable();
        Head before=request.before();
        if(store.authority.jdbc.db.update("UPDATE idp_prompt_control SET state='READY',scalar_sha=?,cleanup_request_id=NULL WHERE "+HEAD
                        +" AND state='CLEANUP_PENDING' AND cleanup_request_id=? AND "+EMPTY,
                request.afterSha(),before.promptId(),before.workspaceId(),before.kind().name(),before.generation(),before.revisionId(),before.scalarSha(),
                ts(before.containerUntil()),ts(before.controlUntil()),request.requestId())!=1)throw unavailable();
        if(store.authority.jdbc.db.update("UPDATE idp_prompt_cleanup_request SET state='READY' WHERE request_id=? AND receipt_id=? "
                        +"AND prepared_sha=? AND state='PENDING' AND receipt_until>? AND closure_scope_until>?",
                request.requestId(),request.receiptId(),request.preparedSha(),ts(store.clock.instant()),ts(store.clock.instant()))!=1)throw unavailable();
        if(!request.ready().equals(find(lease,request.key())))throw unavailable();return request.receipt();
    }
    void requireWitness(Request request,String state){
        Header h=request.target();var r=request.originalReceipt();store.current(h.auditUntil());
        Long count=store.authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_revision_witness WHERE revision_id=? AND prompt_id=? "
                        +"AND revision_number=? AND record_sha=? AND content_sha=? AND submitted_at=? AND body_until=? AND audit_until=? AND body_state=? "
                        +"AND result_sha=? AND key_sha=? AND request_sha=? AND issuer=? AND subject_id=?",
                Long.class,h.revisionId(),h.promptId(),h.number(),h.recordSha(),h.contentSha(),ts(h.submittedAt()),ts(h.bodyUntil()),ts(h.auditUntil()),state,
                r.resultSha(),r.keySha(),r.requestSha(),r.issuer(),r.subject());
        if(count!=1)throw unavailable();
    }
    private void requireReadyContext(Control control,Request request){
        if(control==null||control.generation()<request.before().generation()||!control.workspaceId().equals(request.workspace().id())
                ||control.kind()!=request.before().kind())throw unavailable();
        requireControl(new Head(control.promptId(),control.workspaceId(),control.kind(),control.revisionId(),control.generation(),
                control.scalarSha(),control.containerUntil(),control.controlUntil()),"READY",null);
    }
    private void requireControl(Head head,String state,String cleanup){
        store.current(head.controlUntil());
        Control c=store.authority.control(head.promptId());
        if(c==null||!c.promptId().equals(head.promptId())||!c.workspaceId().equals(head.workspaceId())||c.kind()!=head.kind()
                ||c.generation()!=head.generation()||!Objects.equals(c.revisionId(),head.revisionId())||!c.scalarSha().equals(head.scalarSha())
                ||!c.containerUntil().equals(head.containerUntil())||!c.controlUntil().equals(head.controlUntil())||!state.equals(c.state())
                ||!Objects.equals(c.cleanupRequestId(),cleanup)||c.intentId()!=null||c.operation()!=null||c.keySha()!=null||c.requestSha()!=null
                ||c.beforeSha()!=null||c.afterSha()!=null||c.targetGeneration()!=null||c.targetRevisionId()!=null||c.intentUntil()!=null)throw unavailable();
    }
}
