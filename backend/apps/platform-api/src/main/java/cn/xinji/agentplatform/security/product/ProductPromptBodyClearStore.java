package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Key;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Receipt;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearReconciliation.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Prompt-specific main primitives and exact authority operations; no route or scheduler. */
final class ProductPromptBodyClearStore {
    final ProductPromptStore main;
    final ProductPromptAuthorityStore authority;
    final ProductPromptBodyClearCodec codec;
    final ProductPromptBodyClearRows rows;
    final Clock clock;
    private final ProductComparisonJson json;
    private final ProductPromptBodyClearAuthority independent;
    ProductPromptBodyClearStore(ProductPromptStore main,ProductPromptAuthorityStore authority,ObjectMapper mapper,Clock clock){
        this.main=Objects.requireNonNull(main);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
        main.jdbc.requireSeparate(authority.jdbc);
        if(!(main.jdbc.db instanceof ProductPromptStatementJdbcTemplate)||!(authority.jdbc.db instanceof ProductPromptStatementJdbcTemplate))throw invalid();
        json=new ProductComparisonJson(mapper.copy().registerModule(new JavaTimeModule()));codec=new ProductPromptBodyClearCodec(json);
        rows=new ProductPromptBodyClearRows(main,authority,codec,clock);independent=new ProductPromptBodyClearAuthority(this);
    }
    Key key(Lease lease,String rawKey){
        lease.requireCurrent();var approved=lease.bodyClearApproval();var target=approved.target();
        return ProductPromptBodyClearRecords.key(approved.workspace(),approved.evidence(),target.promptId(),target.revisionId(),rawKey);
    }
    Request prepare(Lease lease,Key key){
        requireMainTransaction(lease);bound(lease,key);var approved=lease.bodyClearApproval();
        Workspace workspace=main.workspace(key.workspaceId(),true);Head before=main.head(key.promptId(),true);
        if(!workspace.equals(approved.workspace())||before==null||!before.workspaceId().equals(workspace.id()))throw unavailable();
        Header target=lockedHeader(key.promptId(),key.revisionId());main.requireClosure(before);
        requireTarget(lease,target);requireNative(target,"PRESENT");
        if(rows.mainReceipt(key.promptId(),key.revisionId())!=null)throw unavailable();
        var snapshot=main.scalarSnapshot(before);var original=originalReceipt(before,target);
        var request=codec.prepare(workspace,approved.evidence(),before,target,original,key,snapshot.clear(target).sha(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS),snapshot.closureScopeUntil(workspace),approved.expiresAt());
        requireApproved(lease,request);codec.encode(request.manifest());return request;
    }
    Request find(Lease lease,Key key){return independent.find(lease,key);}
    Request reserve(Lease lease,Request request){
        requireAuthorityTransaction(lease);commitGuard(lease,mutationUntil(lease,request));
        try{return independent.reserve(lease,request);}catch(RuntimeException|Error failed){rollback(authority.jdbc,failed);throw failed;}
    }
    void clear(Lease lease,Request request){
        requireMainTransaction(lease);requireApproved(lease,request);commitGuard(lease,mutationUntil(lease,request));
        try {
            if(!"PENDING".equals(request.state())||observeMain(lease,request).state()!=State.ALL_BEFORE)throw unavailable();
            Header h=request.target();Head b=request.before();
            if(main.jdbc.db.update("UPDATE conp_revision SET revision_json=NULL,body_state='CLEARED' WHERE revision_id=? AND prompt_id=? "
                            +"AND revision_number=? AND content_sha=? AND record_sha=? AND submitted_at=? AND body_until=? AND audit_until=? "
                            +"AND body_state='PRESENT' AND revision_json IS NOT NULL",
                    h.revisionId(),h.promptId(),h.number(),h.contentSha(),h.recordSha(),ts(h.submittedAt()),ts(h.bodyUntil()),ts(h.auditUntil()))!=1)throw unavailable();
            if(main.jdbc.db.update("UPDATE conp_prompt SET scalar_sha=? WHERE prompt_id=? AND workspace_id=? AND kind=? "
                            +"AND generation=? AND head_revision_id=? AND scalar_sha=? AND container_until=? AND control_until=?",
                    request.afterSha(),b.promptId(),b.workspaceId(),b.kind().name(),b.generation(),b.revisionId(),b.scalarSha(),ts(b.containerUntil()),ts(b.controlUntil()))!=1)throw unavailable();
            rows.insert(main.jdbc.db,"conp_prompt_cleanup_receipt",codec.receiptColumns(request.receipt()));
            if(observeMain(lease,request).state()!=State.EXACT_AFTER)throw unavailable();
            current(mutationUntil(lease,request));
        }catch(RuntimeException|Error failed){rollback(main.jdbc,failed);throw failed;}
    }
    Receipt complete(Lease lease,Request request,ProductPromptBodyClearReconciliation.ExactAfter proof){
        requireAuthorityTransaction(lease);commitGuard(lease,Objects.requireNonNull(proof).cutoff());
        try{return independent.complete(lease,request,proof);}catch(RuntimeException|Error failed){rollback(authority.jdbc,failed);throw failed;}
    }
    Instant readCurrentScope(Lease lease,Request request){
        requireMainTransaction(lease);requireApproved(lease,request);
        Workspace workspace=main.workspace(request.workspace().id(),true);Head head=main.head(request.before().promptId(),true);
        if(!workspace.equals(request.workspace())||head==null)throw unavailable();
        Instant scope=main.scalarSnapshot(head).closureScopeUntil(workspace);
        if(head.generation()==request.before().generation()&&!scope.equals(request.closureScopeUntil()))throw unavailable();
        return scope;
    }
    MainObservation observeMain(Lease lease,Request request){
        requireMainTransaction(lease);requireApproved(lease,request);
        Workspace workspace=main.workspace(request.workspace().id(),true);Head head=main.head(request.before().promptId(),true);
        if(!workspace.equals(request.workspace())||head==null||head.kind()!=request.before().kind()
                ||!head.workspaceId().equals(workspace.id())||head.generation()<request.before().generation())return unknown();
        var snapshot=main.scalarSnapshot(head);Instant scope=snapshot.closureScopeUntil(workspace);
        if(head.generation()==request.before().generation()&&!scope.equals(request.closureScopeUntil()))return unknown();
        Header target=lockedHeader(head.promptId(),request.target().revisionId());
        if(!originalReceipt(head,target).equals(request.originalReceipt()))return unknown();
        Receipt receipt=rows.mainReceipt(head.promptId(),target.revisionId());
        if(head.equals(request.before())&&target.equals(request.target())&&receipt==null&&"PENDING".equals(request.state())){
            requireNative(target,"PRESENT");return new MainObservation(State.ALL_BEFORE,head,null,List.of(),scope);
        }
        if(!target.equals(request.afterHeader())||receipt==null||!receipt.manifest().equals(request.manifest()))return unknown();
        requireNative(target,"CLEARED");
        if("PENDING".equals(request.state()))return head.equals(request.afterHead())
                ?new MainObservation(State.EXACT_AFTER,head,receipt,List.of(),scope):unknown();
        List<Receipt> candidates=List.of();
        if(head.generation()==request.before().generation()){
            if(!sameCoordinates(head,request.afterHead()))return unknown();
            candidates=chain(head,request.afterSha());
            for(int i=candidates.size()-1;i>=0;i--){
                var candidate=candidates.get(i);if(!snapshot.sha().equals(candidate.afterSha()))return unknown();
                Header actual=main.header(head.promptId(),candidate.target().revisionId());
                if(!actual.equals(candidate.afterHeader())||!originalReceipt(head,actual).equals(candidate.originalReceipt()))return unknown();
                requireNative(actual,"CLEARED");snapshot=snapshot.restorePresent(candidate.target());
                if(!snapshot.sha().equals(candidate.before().scalarSha()))return unknown();
            }
            if(!snapshot.sha().equals(request.afterSha()))return unknown();
        }
        return new MainObservation(State.EXACT_AFTER,head,receipt,candidates,scope);
    }
    private List<Receipt> chain(Head head,String start){
        var all=rows.mainGeneration(head.promptId(),head.generation());var result=new ArrayList<Receipt>();
        var seen=new HashSet<String>();String next=start;
        while(!next.equals(head.scalarSha())){
            String selected=next;var matches=all.stream().filter(r->r.before().scalarSha().equals(selected)).toList();
            if(matches.size()!=1||!seen.add(next))throw unavailable();var value=matches.getFirst();
            if(!sameCoordinates(head,value.before())||!value.workspace().equals(main.workspace(head.workspaceId(),false)))throw unavailable();
            result.add(value);next=value.afterSha();if(result.size()>all.size())throw unavailable();
        }
        return List.copyOf(result);
    }
    void requireApproved(Lease lease,Request request){
        lease.requireCurrent();request.requireDigest(json);var approved=lease.bodyClearApproval();approved.requireCurrent();
        if(!approved.workspace().equals(request.workspace())||!approved.evidence().equals(request.evidence())
                ||request.firstAttempt().isAfter(clock.instant())||request.receiptUntil().isAfter(approved.evidence().approvedReceiptUntil()))throw unavailable();
        requireTarget(lease,request.target());bound(lease,request.key());current(request.receiptUntil());current(request.closureScopeUntil());
    }
    void bound(Lease lease,Key key){
        var approved=lease.bodyClearApproval();var target=approved.target();var e=approved.evidence();
        if(!key.workspaceId().equals(approved.workspace().id())||!key.promptId().equals(target.promptId())||!key.revisionId().equals(target.revisionId())
                ||!key.subject().equals(e.operatorSubject())||!key.certificateSha().equals(e.certificateSha()))throw unavailable();
    }
    private void requireTarget(Lease lease,Header h){
        var target=lease.bodyClearApproval().target();
        if(!target.promptId().equals(h.promptId())||!target.revisionId().equals(h.revisionId())||target.revisionNumber()!=h.number()
                ||!target.recordSha().equals(h.recordSha())||!target.bodyUntil().equals(h.bodyUntil())||!target.auditUntil().equals(h.auditUntil())
                ||!"PRESENT".equals(h.bodyState())||clock.instant().isBefore(h.bodyUntil()))throw unavailable();current(h.auditUntil());
    }
    private Header lockedHeader(String prompt,String revision){
        var headers=main.jdbc.db.query("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                +"FROM conp_revision WHERE prompt_id=? AND revision_id=? FOR UPDATE",ProductPromptStore.HEADER,prompt,revision);
        if(headers.size()!=1)throw unavailable();return headers.getFirst();
    }
    private ProductPromptRecords.Receipt originalReceipt(Head head,Header target){
        var values=main.jdbc.db.query("SELECT receipt_id,workspace_id,issuer,subject_id,operation,key_sha,request_sha,prompt_id,revision_id,generation,"
                +"content_sha,result_sha,submitted_at,audit_until FROM conp_receipt WHERE prompt_id=? AND revision_id=?",ProductPromptStore.RECEIPT,head.promptId(),target.revisionId());
        if(values.size()!=1)throw unavailable();var r=values.getFirst();current(r.auditUntil());
        if(!r.workspaceId().equals(head.workspaceId())||r.generation()!=target.number()||!r.contentSha().equals(target.contentSha())
                ||!r.submittedAt().equals(target.submittedAt())||!r.auditUntil().equals(target.auditUntil()))throw unavailable();return r;
    }
    private void requireNative(Header target,String state){
        String predicate="CLEARED".equals(state)?"revision_json IS NULL":"revision_json IS NOT NULL";
        if(!state.equals(target.bodyState())||main.jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_revision WHERE prompt_id=? AND revision_id=? "
                +"AND body_state=? AND "+predicate,Long.class,target.promptId(),target.revisionId(),state)!=1)throw unavailable();
    }
    void requireAuthorityTransaction(Lease lease){lease.requireAuthorityTransaction(authority.jdbc.source);authority.jdbc.requireTransaction();}
    private void requireMainTransaction(Lease lease){lease.requireMainTransaction(main.jdbc.source);main.jdbc.requireTransaction();}
    Instant mutationUntil(Lease lease,Request request){return min(request.intentUntil(),request.receiptUntil(),request.closureScopeUntil(),lease.expiresAt(),lease.bodyClearApproval().expiresAt());}
    Instant settlementUntil(Lease lease,Request request,Instant scope){return min(request.receiptUntil(),request.closureScopeUntil(),scope,lease.expiresAt(),lease.bodyClearApproval().expiresAt());}
    private void commitGuard(Lease lease,Instant cutoff){
        current(cutoff);if(lease.phase==null||lease.phase.cutoff.isAfter(cutoff))throw unavailable();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){lease.requireCurrent();current(cutoff);lease.deadline.remaining(cutoff);}
        });
    }
    void current(Instant cutoff){if(cutoff==null||!clock.instant().isBefore(cutoff))throw unavailable();}
    private static void rollback(ProductPromptJdbc jdbc,Throwable original){try{jdbc.markRollbackOnly();}catch(RuntimeException|Error marking){if(marking!=original)original.addSuppressed(marking);}}
    static Instant min(Instant...values){return java.util.Arrays.stream(values).min(Instant::compareTo).orElseThrow();}
    private static boolean sameCoordinates(Head a,Head b){return a.promptId().equals(b.promptId())&&a.workspaceId().equals(b.workspaceId())&&a.kind()==b.kind()
            &&a.generation()==b.generation()&&a.revisionId().equals(b.revisionId())&&a.containerUntil().equals(b.containerUntil())&&a.controlUntil().equals(b.controlUntil());}
    private MainObservation unknown(){return new MainObservation(State.UNKNOWN,null,null,List.of(),null);}
    static final class MainObservation {
        private final State state;private final Head head;private final Receipt receipt;private final List<Receipt> candidates;private final Instant scope;
        private MainObservation(State state,Head head,Receipt receipt,List<Receipt> candidates,Instant scope){this.state=state;this.head=head;this.receipt=receipt;this.candidates=List.copyOf(candidates);this.scope=scope;}
        State state(){return state;}Head head(){return head;}Receipt receipt(){return receipt;}List<Receipt> candidates(){return candidates;}Instant scope(){return scope;}
    }
}
