package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;

import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Observes main scalar closure under its workspace lock; never reconstructs or returns draft text. */
final class ProductPromptCreateReconciliation {
    enum State { ALL_BEFORE, EXACT_AFTER, UNKNOWN }
    static final class Observation {
        private final State state;private final ExactAfter proof;
        private Observation(State state,ExactAfter proof){this.state=state;this.proof=proof;}
        State state(){return state;}
        ExactAfter exactAfter(){if(state!=State.EXACT_AFTER||proof==null)throw unavailable();return proof;}
    }
    static final class ExactAfter {
        private final ProductPromptCreateReconciliation owner;
        private final Thread thread=Thread.currentThread();
        private final ConnectionHolder holder;
        private final Verified value;
        private boolean active=true,consumed,suspended;
        private ExactAfter(ProductPromptCreateReconciliation owner,ConnectionHolder holder,Workspace workspace,
                           ProductPromptCreateRequest request,Head head){
            this.owner=owner;this.holder=holder;this.value=new Verified(workspace,request,head);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void suspend(){suspended=true;}
                @Override public void resume(){suspended=false;}
                @Override public void afterCompletion(int status){active=false;}
            });
        }
        Verified consume(ProductPromptAuthorityStore authority){
            if(!active||consumed||!suspended||thread!=Thread.currentThread()||owner.authority!=authority
                    ||TransactionSynchronizationManager.hasResource(owner.main.jdbc.source))throw unavailable();
            authority.jdbc.requireTransaction();
            try{
                if(!holder.isSynchronizedWithTransaction()||holder.getConnection().isClosed()
                        ||holder.getConnection().getAutoCommit())throw unavailable();
            }catch(java.sql.SQLException failure){throw unavailable();}
            owner.current(value.request.header().auditUntil());owner.current(value.head.controlUntil());
            consumed=true;return value;
        }
    }
    record Verified(Workspace workspace,ProductPromptCreateRequest request,Head head) { }
    private final ProductPromptStore main;
    private final ProductPromptAuthorityStore authority;
    private final Clock clock;
    ProductPromptCreateReconciliation(ProductPromptStore main,ProductPromptAuthorityStore authority,Clock clock){
        this.main=Objects.requireNonNull(main);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
        main.jdbc.requireSeparate(authority.jdbc);
    }
    Observation observe(Workspace expected,ProductPromptCreateRequest request){
        main.jdbc.requireTransaction();Objects.requireNonNull(expected);Objects.requireNonNull(request);
        authority.jdbc.requireNoManagedTransaction();
        current(request.header().auditUntil());current(request.pending().controlUntil());
        Workspace workspace=main.workspace(expected.id(),true);
        if(!workspace.equals(expected)||!workspace.id().equals(request.pending().workspaceId())
                ||workspace.scopeVersion()!=request.evidence().scopeVersion()||!workspace.registrySha().equals(request.evidence().registrySha())
                ||!workspace.policyRef().equals(request.evidence().policyRef())||!workspace.policySha().equals(request.evidence().policySha()))return unknown();
        authority.requireWorkspace(workspace);
        try{
            if(allBefore(workspace,request))return "PENDING".equals(request.state())?new Observation(State.ALL_BEFORE,null):unknown();
            Head head=main.head(request.pending().promptId(),true);
            if(head==null||!head.workspaceId().equals(workspace.id())||head.kind()!=request.pending().kind())return unknown();
            if("PENDING".equals(request.state())){if(!head.equals(request.after())||!request.matchesPending(authority.control(head.promptId())))return unknown();}
            else if("READY".equals(request.state())){
                if(head.generation()<request.receipt().generation())return unknown();
                authority.requireReady(workspace,head);
            }
            else return unknown();
            main.requireClosure(head);
            if("READY".equals(request.state()))new ProductPromptBodyClearHistory(authority,clock).requireReadyMain(main,workspace,request,head);
            else {
                if(!Objects.equals(main.header(head.promptId(),request.header().revisionId()),request.header()))return unknown();
                if(count("SELECT COUNT(*) FROM conp_revision WHERE revision_id=? AND prompt_id=? AND body_state='PRESENT' AND revision_json IS NOT NULL",
                        request.header().revisionId(),head.promptId())!=1)return unknown();
            }
            if(!exactOriginalEventAndReceipt(request))return unknown();
            if(!head.equals(main.head(head.promptId(),false))||!workspace.equals(main.workspace(workspace.id(),false)))return unknown();
            current(request.header().auditUntil());current(head.controlUntil());authority.requireWorkspace(workspace);
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(main.jdbc.source);
            return new Observation(State.EXACT_AFTER,new ExactAfter(this,holder,workspace,request,head));
        }catch(IllegalStateException mixed){return unknown();}
    }
    private boolean allBefore(Workspace workspace,ProductPromptCreateRequest request){
        var p=request.pending();var r=request.receipt();
        if(!"PENDING".equals(request.state()))return false;
        if(p.operation()==Operation.SAVE){
            Head before=main.head(p.promptId(),true);
            if(!request.matchesBefore(before))return false;
            main.requireClosure(before);
            if(!beforeControl(workspace,request,before))return false;
            return count("SELECT COUNT(*) FROM conp_revision WHERE revision_id=? OR (prompt_id=? AND revision_number=?)",p.targetRevisionId(),p.promptId(),r.generation())==0
                    &&count("SELECT COUNT(*) FROM conp_event WHERE revision_id=? OR event_id=? OR (prompt_id=? AND generation=?)",p.targetRevisionId(),r.id(),p.promptId(),r.generation())==0
                    &&count("SELECT COUNT(*) FROM conp_receipt WHERE revision_id=? OR receipt_id=? OR (prompt_id=? AND generation=?) OR "
                            +"(workspace_id=? AND issuer=? AND subject_id=? AND operation=? AND key_sha=?)",
                            p.targetRevisionId(),r.id(),p.promptId(),r.generation(),r.workspaceId(),r.issuer(),r.subject(),r.operation().name(),r.keySha())==0;
        }
        return beforeControl(workspace,request,null)&&count("SELECT COUNT(*) FROM conp_prompt WHERE prompt_id=?",p.promptId())==0
                &&count("SELECT COUNT(*) FROM conp_revision WHERE prompt_id=? OR revision_id=?",p.promptId(),p.targetRevisionId())==0
                &&count("SELECT COUNT(*) FROM conp_event WHERE prompt_id=? OR revision_id=? OR event_id=?",p.promptId(),p.targetRevisionId(),r.id())==0
                &&count("SELECT COUNT(*) FROM conp_receipt WHERE prompt_id=? OR revision_id=? OR receipt_id=? OR "
                        +"(workspace_id=? AND issuer=? AND subject_id=? AND operation=? AND key_sha=?)",
                        p.promptId(),p.targetRevisionId(),r.id(),r.workspaceId(),r.issuer(),r.subject(),r.operation().name(),r.keySha())==0;
    }
    private boolean beforeControl(Workspace workspace,ProductPromptCreateRequest request,Head before){
        var p=request.pending();var r=request.receipt();var control=authority.control(p.promptId());
        long stored=authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_prompt_request WHERE request_id=? OR "
                        +"(workspace_id=? AND issuer=? AND subject_id=? AND operation=? AND key_sha=?)",Long.class,
                p.intentId(),r.workspaceId(),r.issuer(),r.subject(),r.operation().name(),r.keySha());
        if(control==null)return (p.operation()==Operation.CREATE||p.operation()==Operation.COPY)&&stored==0;
        if("PENDING".equals(control.state())){
            if(stored!=1||!request.matchesPending(control))return false;
            return authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_prompt_request WHERE request_id=? AND prepared_sha=? "
                            +"AND state='PENDING' AND audit_until>?",Long.class,p.intentId(),request.preparedSha(),ProductPromptJdbc.ts(clock.instant()))==1;
        }
        if(p.operation()!=Operation.SAVE||stored!=0)return false;
        authority.requireReady(workspace,before);return true;
    }
    private boolean exactOriginalEventAndReceipt(ProductPromptCreateRequest request){
        var r=request.receipt();
        if(count("SELECT COUNT(*) FROM conp_event WHERE event_id=? OR revision_id=? OR (prompt_id=? AND generation=?)",r.id(),r.revisionId(),r.promptId(),r.generation())!=1
                ||count("SELECT COUNT(*) FROM conp_receipt WHERE receipt_id=? OR revision_id=? OR (prompt_id=? AND generation=?) OR "
                        +"(workspace_id=? AND issuer=? AND subject_id=? AND operation=? AND key_sha=?)",
                        r.id(),r.revisionId(),r.promptId(),r.generation(),r.workspaceId(),r.issuer(),r.subject(),r.operation().name(),r.keySha())!=1)return false;
        if(count("SELECT COUNT(*) FROM conp_event WHERE event_id=? AND prompt_id=? AND revision_id=? AND generation=? AND operation=? "
                        +"AND issuer=? AND subject_id=? AND submitted_at=? AND request_sha=? AND result_sha=? AND audit_until=?",
                        r.id(),r.promptId(),r.revisionId(),r.generation(),r.operation().name(),r.issuer(),r.subject(),ts(r.submittedAt()),r.requestSha(),r.resultSha(),ts(r.auditUntil()))!=1)return false;
        Receipt receipt=main.receipt(r.workspaceId(),r.issuer(),r.subject(),r.operation(),r.keySha());return r.equals(receipt);
    }
    private long count(String sql,Object...args){return main.jdbc.db.queryForObject(sql,Long.class,args);}
    private Observation unknown(){return new Observation(State.UNKNOWN,null);}
    private void current(java.time.Instant until){if(!clock.instant().isBefore(until))throw unavailable();}
}
